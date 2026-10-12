#!/usr/bin/env python3
"""Verify a PostgreSQL monitor detects an actual outage and recovers.

The script creates a private Podman network with disposable service and target
PostgreSQL containers, a query-timeout fixture, and an application container.
It tests a real JDBC query cancellation, then scheduler-driven UP -> DOWN
(TIMEOUT or CONNECTION_FAILED) -> UP and incident resolution through the API.
All containers use temporary data directories and are removed on exit.
"""

from __future__ import annotations

import argparse
import base64
import ipaddress
import json
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time
import urllib.request
import uuid


APP_PORT = 18093
ADMIN_EMAIL = "admin@biliwind.com"
ADMIN_PASSWORD = "admin@biliwind.com"
POSTGRES_IMAGE = "docker.io/library/postgres:17-alpine"
PYTHON_IMAGE = "docker.io/library/python:3.13-alpine"
EXPECTED_FLYWAY_VERSION = "27"


def podman(*args: str, check: bool = True, timeout: int = 120) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        ["podman", *args], text=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=False, timeout=timeout,
    )
    if check and result.returncode:
        raise RuntimeError(f"podman {' '.join(args[:3])} failed: {result.stderr[-500:]}")
    return result


class Api:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self.cookie: str | None = None

    def request(self, method: str, path: str, body: dict[str, object] | None = None) -> tuple[int, object]:
        encoded = json.dumps(body, separators=(",", ":")).encode() if body is not None else None
        request = urllib.request.Request(
            self.base_url + path,
            data=encoded,
            method=method,
            headers={"Accept": "application/json", "Content-Type": "application/json"},
        )
        if self.cookie:
            request.add_header("Cookie", self.cookie)
        try:
            response = urllib.request.urlopen(request, timeout=12)
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                payload = json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                payload = {"body": raw.decode("utf-8", errors="replace")[:300]}
            return error.code, payload
        with response:
            raw = response.read()
            payload = json.loads(raw) if raw else {}
            set_cookie = response.headers.get("Set-Cookie")
            if set_cookie:
                self.cookie = set_cookie.split(";", 1)[0]
            return response.status, payload


def wait_until(description: str, timeout_seconds: float, callback):
    deadline = time.monotonic() + timeout_seconds
    last_value = None
    while time.monotonic() < deadline:
        last_value = callback()
        if last_value:
            return last_value
        time.sleep(1)
    raise TimeoutError(f"timed out waiting for {description}; last observation={last_value!r}")


def wait_for_database(container: str, username: str, database: str, timeout_seconds: int = 90) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        result = podman(
            "exec", container, "pg_isready", "-U", username, "-d", database,
            check=False, timeout=5,
        )
        if result.returncode == 0:
            return
        time.sleep(1)
    raise TimeoutError(f"disposable PostgreSQL container {container} did not become ready")


def wait_for_container_log(container: str, marker: str, timeout_seconds: int = 30) -> None:
    def ready() -> bool:
        state = json.loads(podman("inspect", container).stdout)[0].get("State", {})
        if state.get("Running") is not True:
            raise RuntimeError(f"disposable query-timeout fixture exited: {state.get('ExitCode')}")
        return marker in podman("logs", container, check=False).stdout

    wait_until(f"{container} log marker {marker}", timeout_seconds, ready)


def check_result(api: Api, monitor_id: str) -> tuple[dict[str, object], dict[str, object] | None]:
    status, monitor = api.request("GET", f"/api/v1/monitors/{monitor_id}")
    if status != 200 or not isinstance(monitor, dict):
        raise RuntimeError(f"could not read database monitor (HTTP {status})")
    status, rows = api.request("GET", f"/api/v1/monitors/{monitor_id}/results?limit=1")
    if status != 200 or not isinstance(rows, list):
        raise RuntimeError(f"could not read database monitor results (HTTP {status})")
    latest = rows[0] if rows and isinstance(rows[0], dict) else None
    return monitor, latest


def wait_for_state(api: Api, monitor_id: str, expected_state: str, expected_result: str,
                   expected_error: str | None = None, timeout_seconds: float = 90):
    last_observation: dict[str, object] = {}

    def matches():
        monitor, result = check_result(api, monitor_id)
        last_observation.update({
            "state": monitor.get("state"),
            "result": result,
        })
        if monitor.get("state") != expected_state or result is None or result.get("status") != expected_result:
            return None
        if expected_error is not None and result.get("errorCode") != expected_error:
            return None
        return monitor, result

    try:
        return wait_until(f"monitor state {expected_state}/{expected_result}", timeout_seconds, matches)
    except TimeoutError as error:
        raise TimeoutError(f"{error}; last observation={last_observation!r}") from error


def ensure_port_free(port: int) -> None:
    with socket.socket() as probe:
        probe.settimeout(0.2)
        if probe.connect_ex(("127.0.0.1", port)) == 0:
            raise SystemExit(f"loopback port {port} is already in use")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-image", required=True, help="already-built Geek Monitor JVM container image")
    parser.add_argument("--timeout", type=int, default=90, help="maximum seconds to wait for scheduler transitions")
    args = parser.parse_args()
    if args.timeout < 30 or args.timeout > 600:
        parser.error("--timeout must be between 30 and 600 seconds")
    ensure_port_free(APP_PORT)
    if podman("image", "exists", args.app_image, check=False).returncode != 0:
        parser.error(f"Geek Monitor image {args.app_image!r} is not available locally")
    if podman("image", "exists", POSTGRES_IMAGE, check=False).returncode != 0:
        podman("pull", POSTGRES_IMAGE, timeout=600)
    if podman("image", "exists", PYTHON_IMAGE, check=False).returncode != 0:
        podman("pull", PYTHON_IMAGE, timeout=600)

    suffix = uuid.uuid4().hex[:10]
    network = f"geek-monitor-db-outage-{suffix}"
    app_db_container = f"geek-monitor-db-outage-appdb-{suffix}"
    target_db_container = f"geek-monitor-db-outage-targetdb-{suffix}"
    timeout_container = f"geek-monitor-db-outage-timeout-{suffix}"
    app_container = f"geek-monitor-db-outage-app-{suffix}"
    db_password = secrets.token_urlsafe(24)
    target_password = secrets.token_urlsafe(24)
    rejected_password = secrets.token_urlsafe(24)
    limited_password = secrets.token_urlsafe(24)
    timeout_password = secrets.token_urlsafe(24)
    encryption_key = base64.b64encode(secrets.token_bytes(32)).decode("ascii")
    app_started = False
    completed = False

    try:
        podman("network", "create", "--internal", network)
        network_inspect = json.loads(podman("network", "inspect", network).stdout)[0]
        subnet_rows = network_inspect.get("subnets", [])
        ipv4_subnet = next((
            ipaddress.ip_network(row["subnet"], strict=False)
            for row in subnet_rows
            if ":" not in row.get("subnet", "")
        ), None)
        if ipv4_subnet is None or ipv4_subnet.num_addresses < 16:
            raise RuntimeError("isolated Podman network has no usable IPv4 subnet")
        target_ip = ipv4_subnet.network_address + 10
        timeout_ip = ipv4_subnet.network_address + 11
        podman(
            "run", "-d", "--name", app_db_container, "--network", network,
            "--network-alias", "db", "--memory", "1g",
            "--tmpfs", "/var/lib/postgresql/data:rw,size=512m",
            "-e", "POSTGRES_DB=geek_monitor", "-e", "POSTGRES_USER=geek_monitor",
            "-e", f"POSTGRES_PASSWORD={db_password}", POSTGRES_IMAGE,
        )
        podman(
            "run", "-d", "--name", target_db_container, "--network", network,
            "--network-alias", "monitor-db", "--ip", str(target_ip), "--memory", "1g",
            "--tmpfs", "/var/lib/postgresql/data:rw,size=256m",
            "-e", "POSTGRES_DB=monitored", "-e", "POSTGRES_USER=monitor_user",
            "-e", f"POSTGRES_PASSWORD={target_password}", POSTGRES_IMAGE,
        )
        timeout_fixture = Path(__file__).resolve().parent / "fixtures" / "postgres-query-timeout.py"
        podman(
            "run", "-d", "--name", timeout_container, "--network", network,
            "--network-alias", "query-timeout-db", "--ip", str(timeout_ip), "--memory", "64m",
            "--volume", f"{timeout_fixture}:/fixture/postgres-query-timeout.py:ro",
            PYTHON_IMAGE, "python3", "/fixture/postgres-query-timeout.py",
        )
        wait_for_database(app_db_container, "geek_monitor", "geek_monitor")
        wait_for_database(target_db_container, "monitor_user", "monitored")
        wait_for_container_log(timeout_container, "POSTGRES_QUERY_TIMEOUT_SERVER_READY")

        target_inspect = json.loads(podman("inspect", target_db_container).stdout)[0]
        target_network = target_inspect.get("NetworkSettings", {}).get("Networks", {}).get(network, {})
        inspected_target_ip = target_network.get("IPAddress")
        try:
            parsed_target_ip = ipaddress.ip_address(inspected_target_ip)
        except (TypeError, ValueError) as error:
            raise RuntimeError("target PostgreSQL did not receive an isolated network address") from error
        if parsed_target_ip.version != 4 or parsed_target_ip != target_ip or not target_network.get("Aliases"):
            raise RuntimeError("target PostgreSQL network attachment is incomplete")

        podman(
            "run", "-d", "--name", app_container, "--network", network,
            "-p", f"127.0.0.1:{APP_PORT}:8080", "--memory", "2g",
            "-e", "DB_URL=jdbc:postgresql://db:5432/geek_monitor",
            "-e", "DB_USERNAME=geek_monitor", "-e", f"DB_PASSWORD={db_password}",
            "-e", f"GEEK_MONITOR_ENCRYPTION_KEY={encryption_key}",
            "-e", f"MONITOR_ALLOWED_CIDRS={parsed_target_ip}/32,{timeout_ip}/32",
            "-e", "SMTP_MOCK=true", args.app_image,
        )
        app_started = True
        base_url = f"http://127.0.0.1:{APP_PORT}"

        def ready() -> bool:
            app_state = json.loads(podman("inspect", app_container).stdout)[0].get("State", {})
            if app_state.get("Running") is not True:
                raise RuntimeError(f"Geek Monitor container exited: {app_state.get('ExitCode')}")
            try:
                with urllib.request.urlopen(f"{base_url}/q/health/ready", timeout=2) as response:
                    payload = json.loads(response.read())
                    return response.status == 200 and payload.get("status") == "UP"
            except (OSError, TimeoutError):
                return False

        wait_until("application readiness", 120, ready)
        schema_version = podman(
            "exec", app_db_container, "psql", "-X", "-A", "-t", "-U", "geek_monitor",
            "-d", "geek_monitor", "-c",
            "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
        ).stdout.strip()
        if schema_version != EXPECTED_FLYWAY_VERSION:
            raise RuntimeError(
                f"application image schema version is V{schema_version or 'unknown'}, "
                f"expected current V{EXPECTED_FLYWAY_VERSION}"
            )
        api = Api(base_url)
        status, setup = api.request("POST", "/api/v1/install/setup", {
            "email": ADMIN_EMAIL,
            "password": ADMIN_PASSWORD,
            "projectName": "数据库停机与恢复验收",
        })
        if status != 201 or not api.cookie or setup.get("user", {}).get("email") != ADMIN_EMAIL:
            raise RuntimeError(f"could not initialize the disposable application (HTTP {status})")
        status, projects = api.request("GET", "/api/v1/projects")
        if status != 200 or not isinstance(projects, list) or not projects:
            raise RuntimeError("disposable initialization did not create a project")

        status, resource = api.request("POST", "/api/v1/resources", {
            "projectId": projects[0]["id"], "name": "可停机 PostgreSQL 验收目标",
            "type": "DATABASE", "environment": "e2e",
        })
        if status != 201:
            raise RuntimeError(f"could not create disposable DATABASE resource (HTTP {status})")
        status, monitor = api.request("POST", "/api/v1/monitors", {
            "resourceId": resource["id"], "name": "PostgreSQL 停机与恢复",
            "type": "POSTGRESQL", "targetHost": str(parsed_target_ip), "port": 5432,
            "databaseName": "monitored", "databaseUsername": "monitor_user",
            "databasePassword": target_password, "databaseTls": False,
            "intervalSeconds": 10, "timeoutSeconds": 3,
            "failureThreshold": 1, "recoveryThreshold": 1,
            "critical": True, "enabled": True,
        })
        if status != 201:
            raise RuntimeError(f"could not create PostgreSQL monitor (HTTP {status})")
        monitor_id = str(monitor["id"])
        initial = wait_for_state(api, monitor_id, "UP", "UP", timeout_seconds=args.timeout)
        print(f"application_schema=V{schema_version} database_monitor_initial={initial[0]['state']} "
              f"result={initial[1]['status']}", flush=True)

        podman(
            "exec", target_db_container, "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", "monitor_user",
            "-d", "monitored", "-c", f"""
                CREATE ROLE monitor_limited LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT
                    PASSWORD '{limited_password}';
                GRANT CONNECT ON DATABASE monitored TO monitor_limited;
                REVOKE SELECT ON pg_catalog.pg_stat_activity FROM PUBLIC;
                REVOKE SELECT ON pg_catalog.pg_settings FROM PUBLIC;
            """,
        )
        status, limited_monitor = api.request("POST", "/api/v1/monitors", {
            "resourceId": resource["id"], "name": "PostgreSQL 指标权限不足验收",
            "type": "POSTGRESQL", "targetHost": str(parsed_target_ip), "port": 5432,
            "databaseName": "monitored", "databaseUsername": "monitor_limited",
            "databasePassword": limited_password, "databaseTls": False,
            "intervalSeconds": 10, "timeoutSeconds": 2,
            "failureThreshold": 1, "recoveryThreshold": 1,
            "critical": False, "enabled": False,
        })
        if status != 201 or not isinstance(limited_monitor, dict):
            raise RuntimeError(f"could not create limited PostgreSQL monitor (HTTP {status})")
        status, limited_result = api.request(
            "POST", f"/api/v1/monitors/{limited_monitor['id']}/test",
        )
        if (status != 200 or not isinstance(limited_result, dict) or limited_result.get("status") != "UP" or
                limited_result.get("metrics") != {}):
            raise RuntimeError(
                f"missing statistics permissions incorrectly failed PostgreSQL availability: "
                f"HTTP {status}, result={limited_result!r}"
            )
        print("database_monitor_limited_statistics=UP metrics=empty", flush=True)

        status, rejected_monitor = api.request("POST", "/api/v1/monitors", {
            "resourceId": resource["id"], "name": "PostgreSQL 错误凭据验收",
            "type": "POSTGRESQL", "targetHost": str(parsed_target_ip), "port": 5432,
            "databaseName": "monitored", "databaseUsername": "monitor_user",
            "databasePassword": rejected_password, "databaseTls": False,
            "intervalSeconds": 10, "timeoutSeconds": 2,
            "failureThreshold": 1, "recoveryThreshold": 1,
            "critical": False, "enabled": False,
        })
        if status != 201 or not isinstance(rejected_monitor, dict):
            raise RuntimeError(f"could not create invalid-credential PostgreSQL monitor (HTTP {status})")
        status, rejected_result = api.request(
            "POST", f"/api/v1/monitors/{rejected_monitor['id']}/test",
        )
        if (status != 200 or not isinstance(rejected_result, dict) or rejected_result.get("status") != "DOWN" or
                rejected_result.get("errorCode") != "AUTHENTICATION_FAILED"):
            raise RuntimeError(
                f"invalid PostgreSQL credentials were not classified as AUTHENTICATION_FAILED: "
                f"HTTP {status}, result={rejected_result!r}"
            )
        rejected_payload = json.dumps(rejected_result, ensure_ascii=False).lower()
        if rejected_password.lower() in rejected_payload or "org.postgresql" in rejected_payload or "jdbc:" in rejected_payload:
            raise RuntimeError("invalid-credential result leaked credentials or JDBC internals")
        print("database_monitor_invalid_credentials=DOWN/AUTHENTICATION_FAILED sanitized=yes", flush=True)

        status, timeout_monitor = api.request("POST", "/api/v1/monitors", {
            "resourceId": resource["id"], "name": "PostgreSQL 查询超时验收",
            "type": "POSTGRESQL", "targetHost": "query-timeout-db", "port": 5432,
            "databaseName": "monitored", "databaseUsername": "monitor_user",
            "databasePassword": timeout_password, "databaseTls": False,
            "intervalSeconds": 10, "timeoutSeconds": 2,
            "failureThreshold": 1, "recoveryThreshold": 1,
            "critical": False, "enabled": False,
        })
        if status != 201 or not isinstance(timeout_monitor, dict):
            raise RuntimeError(f"could not create PostgreSQL query-timeout monitor (HTTP {status})")
        timeout_monitor_id = str(timeout_monitor["id"])
        status, timed_out = api.request("POST", f"/api/v1/monitors/{timeout_monitor_id}/test")
        if (status != 200 or not isinstance(timed_out, dict) or timed_out.get("status") != "DOWN" or
                timed_out.get("errorCode") != "TIMEOUT"):
            raise RuntimeError(f"stalled PostgreSQL query was not classified as TIMEOUT (HTTP {status}, {timed_out!r})")
        wait_for_container_log(timeout_container, "POSTGRES_QUERY_TIMEOUT_CANCEL_RECEIVED")
        timeout_payload = json.dumps(timed_out, ensure_ascii=False).lower()
        if timeout_password.lower() in timeout_payload or "jdbc:" in timeout_payload or "org.postgresql" in timeout_payload:
            raise RuntimeError("PostgreSQL query-timeout result leaked credentials or JDBC internals")
        print(f"database_monitor_query_timeout={timed_out['status']}/{timed_out['errorCode']} "
              f"elapsed_ms={timed_out['durationMs']} jdbc_cancel=received", flush=True)

        podman("stop", "--time", "1", target_db_container)
        status, manual_outage = api.request("POST", f"/api/v1/monitors/{monitor_id}/test")
        if (status != 200 or not isinstance(manual_outage, dict) or manual_outage.get("status") != "DOWN" or
                manual_outage.get("errorCode") not in {"CONNECTION_FAILED", "TIMEOUT"}):
            error_code = manual_outage.get("errorCode") if isinstance(manual_outage, dict) else "invalid response"
            raise RuntimeError(f"manual test did not detect the stopped PostgreSQL (HTTP {status}, {error_code})")
        outage = wait_for_state(
            api, monitor_id, "DOWN", "DOWN",
            timeout_seconds=args.timeout,
        )
        if outage[1].get("errorCode") not in {"CONNECTION_FAILED", "TIMEOUT"}:
            raise RuntimeError(f"stopped PostgreSQL had an unexpected failure classification: {outage[1].get('errorCode')!r}")
        failure_payload = json.dumps(outage[1], ensure_ascii=False).lower()
        if target_password.lower() in failure_payload or "org.postgresql" in failure_payload or "jdbc:" in failure_payload:
            raise RuntimeError("database outage result leaked credentials or JDBC internals")
        incidents_status, incidents = api.request("GET", "/api/v1/incidents?status=OPEN&limit=100")
        open_incident = next((
            row for row in incidents
            if isinstance(row, dict) and row.get("monitorId") == monitor_id and row.get("category") == "AVAILABILITY"
        ), None) if isinstance(incidents, list) else None
        if incidents_status != 200 or open_incident is None:
            raise RuntimeError("database outage did not open its availability incident")
        print(f"database_monitor_outage={outage[0]['state']} result={outage[1]['status']} "
              f"error={outage[1]['errorCode']} incident=OPEN", flush=True)

        podman("start", target_db_container)
        wait_for_database(target_db_container, "monitor_user", "monitored")
        recovered_target = json.loads(podman("inspect", target_db_container).stdout)[0]
        recovered_ip = recovered_target.get("NetworkSettings", {}).get("Networks", {}).get(network, {}).get("IPAddress")
        if recovered_ip != str(target_ip):
            raise RuntimeError("target PostgreSQL address changed after restart")
        recovered = wait_for_state(api, monitor_id, "UP", "UP", timeout_seconds=args.timeout)
        incidents_status, incidents = api.request("GET", "/api/v1/incidents?status=RESOLVED&limit=100")
        resolved_incident = next((
            row for row in incidents
            if isinstance(row, dict) and row.get("id") == open_incident.get("id")
        ), None) if isinstance(incidents, list) else None
        if incidents_status != 200 or resolved_incident is None:
            raise RuntimeError("database recovery did not resolve its availability incident")
        print(f"database_monitor_recovery={recovered[0]['state']} result={recovered[1]['status']} "
              "incident=RESOLVED ready=UP", flush=True)
        completed = True
        return 0
    finally:
        if app_started and not completed:
            logs = podman("logs", "--tail", "80", app_container, check=False).stdout
            for secret in (db_password, target_password, rejected_password, limited_password, timeout_password):
                logs = logs.replace(secret, "[REDACTED]")
            if logs:
                print(f"temporary application log tail:\n{logs[-12000:]}", file=sys.stderr)
        if app_started:
            podman("rm", "-f", app_container, check=False)
        podman("rm", "-f", target_db_container, timeout_container, app_db_container, check=False)
        podman("network", "rm", network, check=False)


if __name__ == "__main__":
    raise SystemExit(main())
