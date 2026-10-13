#!/usr/bin/env python3
"""Verify metric rollup failure preserves raw data, then recovers atomically.

The script starts the packaged Quarkus application on loopback port 18092 and
expects a disposable, empty PostgreSQL database at 127.0.0.1:55438 named
``geek_monitor_retention_atomicity``. It injects a temporary database CHECK
constraint to fail one rollup, verifies the raw sample remains, removes the
constraint, and verifies the next scheduled pass commits the rollup and raw
sample deletion together. It contacts no external service.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid


CONSTRAINT = "e2e_retention_atomicity_failure"
METRIC_NAME = "e2e_retention_failure"


def psql(args: list[str], database_env: dict[str, str], input_text: str | None = None, timeout: int = 20) -> str:
    result = subprocess.run(
        ["psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", *args],
        env=database_env,
        check=False,
        capture_output=True,
        text=True,
        input=input_text,
        timeout=timeout,
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


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-port", type=int, default=18092)
    parser.add_argument("--db-port", type=int, default=55438)
    parser.add_argument("--db-name", default="geek_monitor_retention_atomicity")
    parser.add_argument("--timeout", type=float, default=30)
    args = parser.parse_args()

    if args.app_port != 18092 or args.db_port != 55438 or args.db_name != "geek_monitor_retention_atomicity":
        parser.error("only loopback app 18092 and disposable database geek_monitor_retention_atomicity on 55438 are allowed")
    password = os.environ.get("GEEK_MONITOR_E2E_DB_PASSWORD")
    if not password:
        parser.error("set GEEK_MONITOR_E2E_DB_PASSWORD for the disposable PostgreSQL database")
    jar = root / "build/quarkus-app/quarkus-run.jar"
    if not jar.is_file():
        parser.error("build the application first with ./gradlew build")
    if not all(shutil.which(command) for command in ("psql", "java")):
        parser.error("psql and java must be available on PATH")

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
        "GEEK_MONITOR_RETENTION_INTERVAL": "2s",
    })
    app_process: subprocess.Popen | None = None
    with tempfile.TemporaryDirectory(prefix="geek-monitor-retention-e2e-") as directory:
        log_path = Path(directory) / "application.log"
        log = log_path.open("w", encoding="utf-8")
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

            ids = {key: uuid.uuid4() for key in ("project", "resource", "agent", "batch")}
            seed_sql = """
                INSERT INTO project(id, name) VALUES (:'project'::uuid, 'Retention atomicity E2E');
                INSERT INTO monitored_resource(id, project_id, name, type)
                VALUES (:'resource'::uuid, :'project'::uuid, 'Retention target', 'HOST');
                INSERT INTO agent(id, resource_id, credential_hash, hostname, operating_system, agent_version)
                VALUES (:'agent'::uuid, :'resource'::uuid, repeat('a', 64), 'retention-e2e', 'linux', 'e2e');
                INSERT INTO agent_batch(id, agent_id, boot_id, sequence, sampled_at, config_version,
                    receive_sample_delta_ms, client_clock_offset_ms, clock_synced, trusted, accepted_count)
                VALUES (:'batch'::uuid, :'agent'::uuid, gen_random_uuid(), 1, now() - interval '4 days', 1, 0, 0, TRUE, TRUE, 1);
                ALTER TABLE metric_rollup ADD CONSTRAINT e2e_retention_atomicity_failure
                    CHECK (metric_name <> 'e2e_retention_failure');
                INSERT INTO metric_sample(batch_id, agent_id, resource_id, metric_name, labels, value, sampled_at, config_version, trusted)
                VALUES (:'batch'::uuid, :'agent'::uuid, :'resource'::uuid, 'e2e_retention_failure', '{}'::jsonb, 42,
                        now() - interval '4 days', 1, TRUE);
            """
            psql([
                "-v", f"project={ids['project']}", "-v", f"resource={ids['resource']}",
                "-v", f"agent={ids['agent']}", "-v", f"batch={ids['batch']}", "-f", "-",
            ], database_env, input_text=seed_sql)

            wait_until(
                "injected rollup failure to be logged",
                args.timeout,
                lambda: CONSTRAINT in log_path.read_text(encoding="utf-8", errors="replace"),
            )
            before = psql(["-c", f"SELECT (SELECT count(*) FROM metric_sample WHERE metric_name='{METRIC_NAME}')::text || ':' || (SELECT count(*) FROM metric_rollup WHERE metric_name='{METRIC_NAME}')::text"], database_env)
            if before != "1:0":
                raise RuntimeError(f"rollup failure changed raw samples before commit: raw:rollup={before}")

            psql(["-c", f"ALTER TABLE metric_rollup DROP CONSTRAINT {CONSTRAINT}"], database_env)

            def compacted() -> str | None:
                value = psql(["-c", f"SELECT (SELECT count(*) FROM metric_sample WHERE metric_name='{METRIC_NAME}')::text || ':' || (SELECT count(*) FROM metric_rollup WHERE metric_name='{METRIC_NAME}' AND resolution_seconds=300 AND sample_count=1)::text"], database_env)
                return value if value == "0:1" else None

            wait_until("raw sample and rollup to commit together after fault removal", args.timeout, compacted)
            print(json.dumps({
                "injected_failure_logged": True,
                "while_failure_active": {"raw_samples": 1, "rollups": 0},
                "after_recovery": {"raw_samples": 0, "five_minute_rollups": 1, "sample_count": 1},
                "external_services_contacted": False,
            }, separators=(",", ":")))
            return 0
        except Exception:
            log.flush()
            if log_path.exists():
                print("\n".join(log_path.read_text(encoding="utf-8", errors="replace").splitlines()[-60:]))
            raise
        finally:
            if app_process is not None:
                stop_application(app_process)
            log.close()


if __name__ == "__main__":
    raise SystemExit(main())
