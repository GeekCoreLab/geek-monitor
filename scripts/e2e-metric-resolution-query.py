#!/usr/bin/env python3
"""Verify metric history queries merge raw, five-minute, and hourly samples.

The script starts the packaged Quarkus application on loopback port 18094 and
expects a disposable, empty PostgreSQL database at 127.0.0.1:55439 named
``geek_monitor_metric_resolution``. It initializes the test administrator
``admin@biliwind.com`` / ``admin@biliwind.com``, seeds one resource with data
at each storage resolution, and checks three overlapping history windows.
Only the local application and disposable database are used.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class ApiClient:
    def __init__(self, base_url: str, email: str, password: str):
        self.base_url = base_url.rstrip("/")
        self.email = email
        self.password = password
        self.cookie: str | None = None

    def request(self, method: str, path: str, body: dict[str, object] | None = None) -> tuple[int, object]:
        encoded = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(
            f"{self.base_url}{path}", data=encoded, method=method,
            headers={"Accept": "application/json", "Content-Type": "application/json"},
        )
        if self.cookie:
            request.add_header("Cookie", self.cookie)
        try:
            response = urllib.request.urlopen(request, timeout=10)
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"HTTP {error.code} for {method} {path}: {detail}") from error
        with response:
            raw = response.read()
            payload = json.loads(raw) if raw else {}
            if method == "POST" and path == "/api/v1/install/setup":
                set_cookie = response.headers.get("Set-Cookie")
                if set_cookie:
                    self.cookie = set_cookie.split(";", 1)[0]
            return response.status, payload


def psql(args: list[str], database_env: dict[str, str], input_text: str | None = None) -> str:
    result = subprocess.run(
        ["psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", *args],
        env=database_env,
        check=False,
        capture_output=True,
        text=True,
        input=input_text,
        timeout=20,
    )
    if result.returncode != 0:
        raise RuntimeError(f"psql failed: {result.stderr.strip()}")
    return result.stdout.strip()


def wait_until(description: str, timeout: float, callback):
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            value = callback()
            if value:
                return value
        except Exception as error:
            last_error = error
        time.sleep(0.5)
    detail = f"; last error={last_error}" if last_error else ""
    raise TimeoutError(f"timed out waiting for {description}{detail}")


def stop_application(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def query_metrics(api: ApiClient, resource_id: str, start, end, step: int) -> dict[str, object]:
    query = urllib.parse.urlencode({
        "from": start.isoformat(),
        "to": end.isoformat(),
        "stepSeconds": str(step),
    })
    status, response = api.request("GET", f"/api/v1/resources/{resource_id}/metrics?{query}")
    if status != 200 or not isinstance(response, dict):
        raise RuntimeError(f"metric API returned HTTP {status}")
    return response


def summarize(response: dict[str, object]) -> dict[int, dict[str, float]]:
    series = response.get("series")
    if not isinstance(series, list) or len(series) != 1 or series[0].get("metricName") != "metric-resolution-e2e":
        raise RuntimeError(f"unexpected metric series: {series!r}")
    points = series[0].get("points")
    if not isinstance(points, list):
        raise RuntimeError("metric API omitted its points list")
    summary: dict[int, dict[str, float]] = {}
    for point in points:
        if not isinstance(point, dict):
            raise RuntimeError("metric API returned a malformed point")
        average = float(point["average"])
        summary[round(average)] = {
            "average": average,
            "minimum": float(point["minimum"]),
            "maximum": float(point["maximum"]),
            "sampleCount": float(point["sampleCount"]),
        }
    if int(response.get("pointCount", -1)) != len(points):
        raise RuntimeError("pointCount does not match the returned points")
    return summary


def assert_point(actual: dict[int, dict[str, float]], key: int, expected: tuple[float, float, float, float]) -> None:
    point = actual.get(key)
    if point is None:
        raise RuntimeError(f"missing point with average {key}: {actual}")
    values = (point["average"], point["minimum"], point["maximum"], point["sampleCount"])
    if any(not math.isclose(got, want, rel_tol=1e-9, abs_tol=1e-9) for got, want in zip(values, expected)):
        raise RuntimeError(f"point {key} differs: actual={values}, expected={expected}")


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--email", default="admin@biliwind.com")
    parser.add_argument("--password", default="admin@biliwind.com")
    parser.add_argument("--app-port", type=int, default=18094)
    parser.add_argument("--db-port", type=int, default=55439)
    parser.add_argument("--db-name", default="geek_monitor_metric_resolution")
    args = parser.parse_args()

    if args.app_port != 18094 or args.db_port != 55439 or args.db_name != "geek_monitor_metric_resolution":
        parser.error("only loopback app 18094 and disposable database geek_monitor_metric_resolution on 55439 are allowed")
    password = os.environ.get("GEEK_MONITOR_E2E_DB_PASSWORD")
    if not password:
        parser.error("set GEEK_MONITOR_E2E_DB_PASSWORD for the disposable PostgreSQL database")
    jar = root / "build/quarkus-app/quarkus-run.jar"
    if not jar.is_file():
        parser.error("build the application first with ./gradlew build")

    database_env = os.environ.copy()
    database_env.update({
        "PGHOST": "127.0.0.1",
        "PGPORT": str(args.db_port),
        "PGDATABASE": args.db_name,
        "PGUSER": "geek_monitor",
        "PGPASSWORD": password,
    })
    if psql(["-c", "SELECT 1"], database_env) != "1":
        parser.error("could not connect to the disposable database")
    if psql(["-c", "SELECT to_regclass('public.flyway_schema_history') IS NULL"], database_env) != "t":
        parser.error("database must be empty and not migrated")

    app_env = os.environ.copy()
    app_env.update({
        "DB_URL": f"jdbc:postgresql://127.0.0.1:{args.db_port}/{args.db_name}",
        "DB_USERNAME": "geek_monitor",
        "DB_PASSWORD": password,
        "QUARKUS_HTTP_HOST": "127.0.0.1",
        "QUARKUS_HTTP_PORT": str(args.app_port),
        "QUARKUS_MAILER_MOCK": "true",
        "GEEK_MONITOR_PARTITION_INTERVAL": "1s",
    })
    app_process: subprocess.Popen | None = None
    with tempfile.TemporaryDirectory(prefix="geek-monitor-metric-resolution-") as directory:
        log = (Path(directory) / "application.log").open("w", encoding="utf-8")
        try:
            app_process = subprocess.Popen(
                ["java", "-jar", str(jar)], cwd=root, env=app_env,
                stdout=log, stderr=subprocess.STDOUT,
            )
            base_url = f"http://127.0.0.1:{args.app_port}"

            def ready() -> bool:
                if app_process is not None and app_process.poll() is not None:
                    raise RuntimeError(f"application exited with code {app_process.returncode}")
                try:
                    with urllib.request.urlopen(f"{base_url}/q/health/ready", timeout=2) as response:
                        return response.status == 200 and json.loads(response.read()).get("status") == "UP"
                except (urllib.error.URLError, TimeoutError):
                    return False

            wait_until("application readiness", 120, ready)
            api = ApiClient(base_url, args.email, args.password)
            status, _ = api.request("POST", "/api/v1/install/setup", {
                "email": args.email,
                "password": args.password,
                "projectName": "指标分辨率查询验收",
            })
            if status != 201 or not api.cookie:
                raise RuntimeError("could not initialize the disposable test instance")
            status, projects = api.request("GET", "/api/v1/projects")
            if status != 200 or not isinstance(projects, list) or not projects:
                raise RuntimeError("initialization did not create its default project")
            status, resource = api.request("POST", "/api/v1/resources", {
                "projectId": str(projects[0]["id"]),
                "name": "多分辨率指标目标",
                "type": "HOST",
                "environment": "test",
            })
            if status != 201 or not isinstance(resource, dict):
                raise RuntimeError(f"could not create the test resource (HTTP {status})")
            resource_id = str(resource["id"])
            agent_id, batch_id = uuid.uuid4(), uuid.uuid4()
            seed_sql = """
                INSERT INTO agent(id, resource_id, credential_hash, hostname, operating_system, agent_version)
                VALUES (:'agent_id'::uuid, :'resource_id'::uuid, repeat('c', 64), 'metric-e2e', 'linux', 'e2e');
                INSERT INTO agent_batch(id, agent_id, boot_id, sequence, sampled_at, config_version,
                    receive_sample_delta_ms, client_clock_offset_ms, clock_synced, trusted, accepted_count)
                VALUES (:'batch_id'::uuid, :'agent_id'::uuid, gen_random_uuid(), 1, now() - interval '1 day', 1, 0, 0, TRUE, TRUE, 1);
                INSERT INTO metric_sample(batch_id, agent_id, resource_id, metric_name, labels, value, sampled_at, config_version, trusted)
                VALUES (:'batch_id'::uuid, :'agent_id'::uuid, :'resource_id'::uuid, 'metric-resolution-e2e', '{"source":"test"}'::jsonb, 8,
                    now() - interval '1 day', 1, TRUE);
                INSERT INTO metric_rollup(resource_id, metric_name, labels, resolution_seconds, bucket_at, sample_count, sum_value, min_value, max_value)
                VALUES (:'resource_id'::uuid, 'metric-resolution-e2e', '{"source":"test"}'::jsonb, 300,
                    date_bin(INTERVAL '5 minutes', now() - interval '10 days', TIMESTAMPTZ '1970-01-01 00:00:00+00'), 2, 40, 15, 25);
                INSERT INTO metric_rollup(resource_id, metric_name, labels, resolution_seconds, bucket_at, sample_count, sum_value, min_value, max_value)
                VALUES (:'resource_id'::uuid, 'metric-resolution-e2e', '{"source":"test"}'::jsonb, 3600,
                    date_bin(INTERVAL '1 hour', now() - interval '40 days', TIMESTAMPTZ '1970-01-01 00:00:00+00'), 3, 300, 100, 200);
            """
            psql([
                "-v", f"agent_id={agent_id}", "-v", f"batch_id={batch_id}",
                "-v", f"resource_id={resource_id}", "-f", "-",
            ], database_env, input_text=seed_sql)

            partitioned_tables = int(psql([
                "-c",
                """SELECT count(*) FROM pg_partitioned_table
                   WHERE partrelid IN ('check_execution'::regclass, 'metric_sample'::regclass,
                       'database_metric_sample'::regclass, 'metric_rollup'::regclass,
                       'availability_rollup'::regclass)""",
            ], database_env))
            if partitioned_tables != 5:
                raise RuntimeError(f"expected all five telemetry parents to be partitioned, found {partitioned_tables}")
            current_partition = psql([
                "-c",
                """SELECT count(*) FROM pg_inherits
                   WHERE inhparent = 'metric_sample'::regclass
                     AND inhrelid = to_regclass('public.metric_sample_' || to_char(now() AT TIME ZONE 'UTC', 'YYYYMM'))""",
            ], database_env)
            if current_partition != "1":
                raise RuntimeError("application startup did not ensure the current metric partition")
            now = dt.datetime.now(dt.timezone.utc)
            future_month_index = now.year * 12 + now.month - 1 + 2
            future_month = (future_month_index // 12, future_month_index % 12 + 1)
            future_partition = f"metric_sample_{future_month[0]:04d}{future_month[1]:02d}"
            future_attached = psql([
                "-c",
                f"SELECT count(*) FROM pg_inherits WHERE inhparent = 'metric_sample'::regclass "
                f"AND inhrelid = to_regclass('public.{future_partition}')",
            ], database_env)
            if future_attached != "1":
                raise RuntimeError(f"expected future partition {future_partition} to be attached")
            psql(["-c", f"DROP TABLE public.\"{future_partition}\""], database_env)
            wait_until(
                "future metric partition creation",
                15,
                lambda: psql([
                    "-c",
                    f"SELECT count(*) FROM pg_inherits WHERE inhparent = 'metric_sample'::regclass "
                    f"AND inhrelid = to_regclass('public.{future_partition}')",
                ], database_env) == "1",
            )
            metric_partitions = psql([
                "-c",
                f"""SELECT string_agg(DISTINCT tableoid::regclass::text, ',' ORDER BY tableoid::regclass::text)
                   FROM metric_rollup WHERE resource_id = '{resource_id}'::uuid""",
            ], database_env)
            if "_default" in metric_partitions or not metric_partitions:
                raise RuntimeError(f"seeded rollups did not route to month partitions: {metric_partitions!r}")
            psql([
                "-c",
                "CREATE TABLE metric_sample_202001 PARTITION OF metric_sample "
                "FOR VALUES FROM (TIMESTAMPTZ '2020-01-01 00:00:00+00') TO (TIMESTAMPTZ '2020-02-01 00:00:00+00')",
            ], database_env)
            wait_until(
                "empty expired partition cleanup",
                15,
                lambda: psql([
                    "-c",
                    "SELECT count(*) FROM pg_inherits WHERE inhparent = 'metric_sample'::regclass "
                    "AND inhrelid = to_regclass('public.metric_sample_202001')",
                ], database_env) == "0",
            )

            to = now + dt.timedelta(seconds=60)
            full = query_metrics(api, resource_id, now - dt.timedelta(days=41), to, 60)
            if full.get("stepSeconds") != 3600 or full.get("pointCount") != 3:
                raise RuntimeError(f"41-day metric query returned unexpected resolution/count: {full}")
            full_points = summarize(full)
            assert_point(full_points, 100, (100, 100, 200, 3))
            assert_point(full_points, 20, (20, 15, 25, 2))
            assert_point(full_points, 8, (8, 8, 8, 1))

            recent = query_metrics(api, resource_id, now - dt.timedelta(days=15), to, 60)
            if recent.get("stepSeconds") != 300 or recent.get("pointCount") != 2:
                raise RuntimeError(f"15-day metric query returned unexpected resolution/count: {recent}")
            recent_points = summarize(recent)
            assert_point(recent_points, 20, (20, 15, 25, 2))
            assert_point(recent_points, 8, (8, 8, 8, 1))

            raw = query_metrics(api, resource_id, now - dt.timedelta(days=2), to, 60)
            if raw.get("stepSeconds") != 60 or raw.get("pointCount") != 1:
                raise RuntimeError(f"2-day metric query returned unexpected resolution/count: {raw}")
            assert_point(summarize(raw), 8, (8, 8, 8, 1))

            print(json.dumps({
                "test_admin": args.email,
                "windows": {
                    "41_days": {"stepSeconds": full["stepSeconds"], "averages": [8, 20, 100]},
                    "15_days": {"stepSeconds": recent["stepSeconds"], "averages": [8, 20]},
                    "2_days": {"stepSeconds": raw["stepSeconds"], "averages": [8]},
                },
                "raw_five_minute_hourly_sources_merged": True,
                "time_partitioned_tables": partitioned_tables,
                "rollup_month_partitions": metric_partitions,
                "future_partition_recreated": True,
                "empty_expired_partitions_removed": True,
                "external_services_contacted": False,
            }, separators=(",", ":")))
            return 0
        except Exception:
            log.flush()
            raise
        finally:
            if app_process is not None:
                stop_application(app_process)
            log.close()


if __name__ == "__main__":
    raise SystemExit(main())
