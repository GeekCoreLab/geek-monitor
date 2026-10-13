#!/usr/bin/env python3
"""Verify trusted Agent CPU/disk threshold incidents and recovery end to end.

The script starts Quarkus on loopback port 18087 and requires an empty,
disposable PostgreSQL database named ``geek_monitor_metric_threshold`` on
127.0.0.1:55442. It initializes the local app, registers one synthetic Agent,
uploads trusted samples through the real Agent API, and checks scheduler state
transitions plus PERFORMANCE incident open/recovery. It never targets the
development or soak instances.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


APP_PORT = 18087
DB_PORT = 55442
DB_NAME = "geek_monitor_metric_threshold"
ADMIN_EMAIL = "admin@biliwind.com"
ADMIN_PASSWORD = "admin@biliwind.com"
WINDOW_SECONDS = 120


class ApiClient:
    def __init__(self, base_url: str, email: str, password: str):
        self.base_url = base_url.rstrip("/")
        self.email = email
        self.password = password
        self.cookie: str | None = None

    def request(self, method: str, path: str, body: dict[str, object] | None = None,
                bearer: str | None = None, allow_http_error: bool = False) -> tuple[int, object]:
        encoded = json.dumps(body, separators=(",", ":")).encode() if body is not None else None
        request = urllib.request.Request(
            f"{self.base_url}{path}", data=encoded, method=method,
            headers={"Accept": "application/json", "Content-Type": "application/json"},
        )
        if self.cookie:
            request.add_header("Cookie", self.cookie)
        if bearer:
            request.add_header("Authorization", f"Bearer {bearer}")
        try:
            response = urllib.request.urlopen(request, timeout=12)
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            if allow_http_error:
                return error.code, json.loads(detail) if detail else {}
            raise RuntimeError(f"HTTP {error.code} for {method} {path}: {detail}") from error
        with response:
            raw = response.read()
            payload = json.loads(raw) if raw else {}
            if method == "POST" and path == "/api/v1/install/setup":
                set_cookie = response.headers.get("Set-Cookie")
                if set_cookie:
                    self.cookie = set_cookie.split(";", 1)[0]
            return response.status, payload

    def initialize(self) -> str:
        status, payload = self.request("POST", "/api/v1/install/setup", {
            "email": self.email,
            "password": self.password,
            "projectName": "Agent 主机指标阈值验收",
        })
        if status != 201 or not self.cookie:
            raise RuntimeError(f"could not initialize disposable instance (HTTP {status})")
        status, projects = self.request("GET", "/api/v1/projects")
        if status != 200 or not isinstance(projects, list) or not projects:
            raise RuntimeError("initialization did not create its default project")
        if payload.get("user", {}).get("email") != self.email.lower():
            raise RuntimeError("setup response did not confirm the test administrator")
        return str(projects[0]["id"])


def psql(args: list[str], database_env: dict[str, str]) -> str:
    result = subprocess.run(
        ["psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", *args],
        env=database_env, check=False, capture_output=True, text=True, timeout=20,
    )
    if result.returncode != 0:
        raise RuntimeError(f"psql failed: {result.stderr.strip()}")
    return result.stdout.strip()


def wait_until(description: str, timeout_seconds: float, callback):
    deadline = time.monotonic() + timeout_seconds
    last_value = None
    while time.monotonic() < deadline:
        last_value = callback()
        if last_value:
            return last_value
        time.sleep(0.5)
    raise TimeoutError(f"timed out waiting for {description}; last observation={last_value!r}")


def stop_application(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def read_states(api: ApiClient, resource_id: str) -> dict[str, str]:
    query = urllib.parse.urlencode({"resourceId": resource_id})
    status, monitors = api.request("GET", f"/api/v1/monitors?{query}")
    if status != 200 or not isinstance(monitors, list):
        raise RuntimeError(f"could not read monitor state (HTTP {status})")
    return {str(monitor["id"]): str(monitor["state"]) for monitor in monitors}


def assert_history(api: ApiClient, monitor_id: str, expected_code: str) -> None:
    status, history = api.request("GET", f"/api/v1/monitors/{monitor_id}/results?limit=20")
    if status != 200 or not isinstance(history, list):
        raise RuntimeError(f"could not read check history for {monitor_id} (HTTP {status})")
    if not any(row.get("status") == "DEGRADED" and row.get("errorCode") == expected_code for row in history):
        raise RuntimeError(f"monitor {monitor_id} history omitted {expected_code}: {history!r}")


def submit_sample(api: ApiClient, agent_id: str, credential: str, boot_id: str,
                  sequence: int, sampled_at: dt.datetime, cpu: float, disk: float) -> None:
    status, result = api.request("POST", f"/api/v1/agents/{agent_id}/batches", {
        "bootId": boot_id,
        "sequence": sequence,
        "sampledAt": sampled_at.isoformat(),
        "configVersion": 1,
        "clientClockOffsetMs": 0,
        "clockSynced": True,
        "metrics": [
            {"name": "cpu_usage_percent", "value": cpu},
            {"name": "disk_used_percent", "value": disk, "labels": {"mountpoint": "/"}},
        ],
    }, bearer=credential)
    if status not in (200, 202) or not isinstance(result, dict) or result.get("trusted") is not True or result.get("accepted") != 2:
        raise RuntimeError(f"Agent API did not accept two trusted metrics (HTTP {status}): {result!r}")


def create_metric_monitor(api: ApiClient, resource_id: str, name: str,
                          metric_name: str, threshold: float, recovery: float) -> str:
    status, monitor = api.request("POST", "/api/v1/monitors", {
        "resourceId": resource_id,
        "name": name,
        "type": "METRIC_THRESHOLD",
        "intervalSeconds": 10,
        "timeoutSeconds": 1,
        "failureThreshold": 1,
        "recoveryThreshold": 1,
        "critical": True,
        "metricName": metric_name,
        "metricOperator": "GT",
        "metricThreshold": threshold,
        "metricRecoveryValue": recovery,
        "metricWindowSeconds": WINDOW_SECONDS,
        "metricSeverity": "HIGH",
    })
    if status != 201 or not isinstance(monitor, dict):
        raise RuntimeError(f"could not create {metric_name} threshold monitor (HTTP {status})")
    return str(monitor["id"])


def incident_counts(database_env: dict[str, str], monitor_ids: list[str]) -> tuple[int, int, int, int, int]:
    ids = ",".join(f"'{monitor_id}'::uuid" for monitor_id in monitor_ids)
    opened = psql(["-c", f"""SELECT
        count(*) FILTER (WHERE category='PERFORMANCE' AND status='OPEN'),
        count(*) FILTER (WHERE category='PERFORMANCE' AND status='RESOLVED'),
        count(*) FILTER (WHERE category='PERFORMANCE' AND severity='HIGH'),
        (SELECT count(*) FROM incident_event e WHERE e.incident_id IN
            (SELECT id FROM incident WHERE monitor_id IN ({ids})) AND e.event_type='OPENED'),
        (SELECT count(*) FROM incident_event e WHERE e.incident_id IN
            (SELECT id FROM incident WHERE monitor_id IN ({ids})) AND e.event_type='RESOLVED')
        FROM incident WHERE monitor_id IN ({ids})"""], database_env)
    values = tuple(int(value) for value in opened.split("|"))
    if len(values) != 5:
        raise RuntimeError(f"unexpected incident query result: {opened!r}")
    return values  # type: ignore[return-value]


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--email", default=ADMIN_EMAIL)
    parser.add_argument("--password", default=ADMIN_PASSWORD)
    parser.add_argument("--app-port", type=int, default=APP_PORT)
    parser.add_argument("--db-port", type=int, default=DB_PORT)
    parser.add_argument("--db-name", default=DB_NAME)
    args = parser.parse_args()
    if args.app_port != APP_PORT or args.db_port != DB_PORT or args.db_name != DB_NAME:
        parser.error(f"only loopback app {APP_PORT} and disposable database {DB_NAME} on {DB_PORT} are allowed")
    password = os.environ.get("GEEK_MONITOR_E2E_DB_PASSWORD")
    if not password:
        parser.error("set GEEK_MONITOR_E2E_DB_PASSWORD for the disposable PostgreSQL database")
    jar = root / "build/quarkus-app/quarkus-run.jar"
    if not jar.is_file():
        parser.error("build the application first with ./gradlew build")

    database_env = os.environ.copy()
    database_env.update({
        "PGHOST": "127.0.0.1", "PGPORT": str(args.db_port), "PGDATABASE": args.db_name,
        "PGUSER": "geek_monitor", "PGPASSWORD": password,
    })
    if psql(["-c", "SELECT 1"], database_env) != "1":
        parser.error("could not connect to the disposable database")
    if psql(["-c", "SELECT to_regclass('public.flyway_schema_history') IS NULL"], database_env) != "t":
        parser.error("database must be empty and not migrated")

    app_env = os.environ.copy()
    app_env.update({
        "DB_URL": f"jdbc:postgresql://127.0.0.1:{args.db_port}/{args.db_name}",
        "DB_USERNAME": "geek_monitor", "DB_PASSWORD": password,
        "QUARKUS_HTTP_HOST": "127.0.0.1", "QUARKUS_HTTP_PORT": str(args.app_port),
        "QUARKUS_MAILER_MOCK": "true", "GEEK_MONITOR_PARTITION_INTERVAL": "1s",
    })
    app_process: subprocess.Popen | None = None
    with tempfile.TemporaryDirectory(prefix="geek-monitor-agent-metric-threshold-") as directory:
        app_log_path = Path(directory) / "application.log"
        app_log = app_log_path.open("w", encoding="utf-8")
        try:
            app_process = subprocess.Popen(
                ["java", "-jar", str(jar)], cwd=root, env=app_env,
                stdout=app_log, stderr=subprocess.STDOUT,
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
            project_id = api.initialize()
            status, initial_system_status = api.request("GET", "/api/v1/system/status")
            if status != 200 or not isinstance(initial_system_status, dict) or "platformHealth" not in initial_system_status:
                raise RuntimeError(f"system status omitted platform health telemetry (HTTP {status})")
            status, resource = api.request("POST", "/api/v1/resources", {
                "projectId": project_id, "name": "CPU 与磁盘阈值验收主机", "type": "HOST", "environment": "e2e",
            })
            if status != 201:
                raise RuntimeError(f"HOST resource creation failed (HTTP {status})")
            resource_id = str(resource["id"])
            status, enrollment = api.request("POST", "/api/v1/agent-enrollments", {"resourceId": resource_id})
            if status != 201:
                raise RuntimeError(f"Agent enrollment failed (HTTP {status})")
            status, registration = api.request("POST", "/api/v1/agents/register", {
                "enrollmentToken": enrollment["token"],
                "hostname": "metric-threshold-e2e",
                "operatingSystem": "linux",
                "agentVersion": "e2e",
            })
            if status != 201:
                raise RuntimeError(f"Agent registration failed (HTTP {status})")
            agent_id = str(registration["agentId"])
            credential = str(registration["credential"])
            boot_id = str(uuid.uuid4())
            sequence = 0

            status, rejected = api.request("POST", f"/api/v1/agents/{agent_id}/batches", {
                "bootId": boot_id,
                "sequence": 999,
                "sampledAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                "configVersion": 1,
                "clientClockOffsetMs": 0,
                "clockSynced": True,
                "metrics": [{"name": "not_a_host_metric", "value": 1}],
            }, bearer=credential, allow_http_error=True)
            if status != 400 or not isinstance(rejected, dict) or rejected.get("code") != "INVALID_METRIC":
                raise RuntimeError(f"expected the Agent API to reject an invalid metric (HTTP {status}): {rejected!r}")
            status, system_status = api.request("GET", "/api/v1/system/status")
            health = system_status.get("platformHealth") if isinstance(system_status, dict) else None
            if status != 200 or not isinstance(health, dict) or health.get("rejectedAgentBatchesSinceStart") != 1:
                raise RuntimeError(f"system status did not count the rejected Agent batch: {health!r}")

            high_values = [
                (95.0, 92.0),
                (96.0, 93.0),
                (97.0, 94.0),
                (98.0, 95.0),
            ]
            high_last_sample_at: dt.datetime | None = None
            for offset, (cpu, disk) in zip((105, 75, 45, 15), high_values):
                sequence += 1
                high_last_sample_at = dt.datetime.now(dt.timezone.utc) - dt.timedelta(seconds=offset)
                submit_sample(api, agent_id, credential, boot_id, sequence, high_last_sample_at, cpu, disk)

            cpu_monitor = create_metric_monitor(api, resource_id, "CPU 持续高使用率", "cpu_usage_percent", 80, 70)
            disk_monitor = create_metric_monitor(api, resource_id, "根分区空间不足", "disk_used_percent", 85, 75)
            monitor_ids = [cpu_monitor, disk_monitor]

            def both_alerting() -> bool:
                states = read_states(api, resource_id)
                counts = incident_counts(database_env, monitor_ids)
                print(f"threshold_open states={states} active_performance={counts[0]} high_severity={counts[2]}", flush=True)
                return all(states.get(monitor_id) == "DEGRADED" for monitor_id in monitor_ids) and counts[0] == 2 and counts[2] == 2

            wait_until("CPU and disk PERFORMANCE incidents", 45, both_alerting)
            for monitor_id in monitor_ids:
                assert_history(api, monitor_id, "METRIC_THRESHOLD_EXCEEDED")

            assert high_last_sample_at is not None
            recovery_deadline = high_last_sample_at.timestamp() + WINDOW_SECONDS + 3
            sequence += 1
            submit_sample(api, agent_id, credential, boot_id, sequence, dt.datetime.now(dt.timezone.utc), 20, 30)
            while time.time() < recovery_deadline:
                time.sleep(min(20, max(0, recovery_deadline - time.time())))
                if time.time() < recovery_deadline:
                    sequence += 1
                    submit_sample(api, agent_id, credential, boot_id, sequence, dt.datetime.now(dt.timezone.utc), 20, 30)
            sequence += 1
            submit_sample(api, agent_id, credential, boot_id, sequence, dt.datetime.now(dt.timezone.utc), 20, 30)

            def both_recovered() -> bool:
                states = read_states(api, resource_id)
                counts = incident_counts(database_env, monitor_ids)
                print(f"threshold_recovery states={states} open={counts[0]} resolved={counts[1]} events={counts[3:]}", flush=True)
                return all(states.get(monitor_id) == "UP" for monitor_id in monitor_ids) and counts[0] == 0 and counts[1] == 2

            wait_until("CPU and disk incident recovery", 45, both_recovered)
            counts = incident_counts(database_env, monitor_ids)
            if counts != (0, 2, 2, 2, 2):
                raise RuntimeError(f"expected two HIGH PERFORMANCE incidents with OPENED and RESOLVED events: {counts}")
            for monitor_id in monitor_ids:
                assert_history(api, monitor_id, "METRIC_RECOVERY_PENDING")
            status, system_status = api.request("GET", "/api/v1/system/status")
            health = system_status.get("platformHealth") if isinstance(system_status, dict) else None
            if status != 200 or not isinstance(health, dict):
                raise RuntimeError(f"could not read platform health after the scheduler run (HTTP {status})")
            if (health.get("status") != "HEALTHY" or health.get("enabledChecks", 0) < 3
                    or health.get("recentCheckCount", 0) < 2 or health.get("recentResultP95LatencyMs") is None):
                raise RuntimeError(f"platform status omitted healthy scheduler/check telemetry: {health!r}")
            if health.get("rejectedAgentBatchesSinceStart") != 1:
                raise RuntimeError(f"rejected Agent batch counter changed unexpectedly: {health!r}")
            if health.get("notificationSuccessRate24h") is not None:
                raise RuntimeError(f"notification success rate should be unknown before any deliveries: {health!r}")
            if health.get("retention", {}).get("status") not in {"CURRENT", "RUNNING"}:
                raise RuntimeError(f"retention job status did not record a successful run: {health!r}")
            print("PASS: trusted CPU and disk metrics opened HIGH PERFORMANCE incidents and the scheduler resolved both after recovery", flush=True)
            print("PASS: admin system status reports scheduler, check freshness/latency, Agent rejection, delivery, and retention telemetry", flush=True)
            return 0
        except Exception:
            if app_log_path.is_file():
                app_log.flush()
                tail = app_log_path.read_text(encoding="utf-8", errors="replace").splitlines()[-80:]
                print("--- isolated application log tail ---", flush=True)
                print("\n".join(tail), flush=True)
            raise
        finally:
            if app_process is not None:
                stop_application(app_process)
            app_log.close()


if __name__ == "__main__":
    raise SystemExit(main())
