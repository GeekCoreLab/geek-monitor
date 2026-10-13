#!/usr/bin/env python3
"""Exercise scheduled heartbeat success, failure, duplicate, expiry and recovery.

The script creates a private Podman network and an ephemeral PostgreSQL-backed
application. It initializes the application with the documented test admin,
then drives the heartbeat endpoint and verifies real scheduler results and
incident transitions. All temporary containers, credentials and network state
are removed on exit.
"""

from __future__ import annotations

import argparse
import base64
import json
import secrets
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone


APP_PORT = 18094
ADMIN_EMAIL = "admin@biliwind.com"
ADMIN_PASSWORD = "admin@biliwind.com"
POSTGRES_IMAGE = "docker.io/library/postgres:17-alpine"
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

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, object] | None = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, object]:
        encoded = json.dumps(body, separators=(",", ":")).encode() if body is not None else None
        request = urllib.request.Request(
            self.base_url + path,
            data=encoded,
            method=method,
            headers={"Accept": "application/json", "Content-Type": "application/json", **(headers or {})},
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
        time.sleep(0.5)
    raise TimeoutError(f"timed out waiting for {description}; last observation={last_value!r}")


def wait_for_database(container: str, timeout_seconds: int = 90) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        result = podman(
            "exec", container, "pg_isready", "-U", "geek_monitor", "-d", "geek_monitor",
            check=False, timeout=5,
        )
        if result.returncode == 0:
            return
        time.sleep(1)
    raise TimeoutError(f"disposable PostgreSQL container {container} did not become ready")


def check_result(api: Api, monitor_id: str) -> tuple[dict[str, object], dict[str, object] | None]:
    status, monitor = api.request("GET", f"/api/v1/monitors/{monitor_id}")
    if status != 200 or not isinstance(monitor, dict):
        raise RuntimeError(f"could not read heartbeat monitor (HTTP {status})")
    status, rows = api.request("GET", f"/api/v1/monitors/{monitor_id}/results?limit=1")
    if status != 200 or not isinstance(rows, list):
        raise RuntimeError(f"could not read heartbeat results (HTTP {status})")
    latest = rows[0] if rows and isinstance(rows[0], dict) else None
    return monitor, latest


def wait_for_state(
    api: Api,
    monitor_id: str,
    expected_state: str,
    expected_result: str,
    expected_error: str | None,
    timeout_seconds: float,
):
    last_observation: dict[str, object] = {}

    def matches():
        monitor, result = check_result(api, monitor_id)
        last_observation.update({"state": monitor.get("state"), "result": result})
        if monitor.get("state") != expected_state or result is None or result.get("status") != expected_result:
            return None
        if expected_error is not None and result.get("errorCode") != expected_error:
            return None
        return monitor, result

    try:
        return wait_until(f"heartbeat state {expected_state}/{expected_result}", timeout_seconds, matches)
    except TimeoutError as error:
        raise TimeoutError(f"{error}; last observation={last_observation!r}") from error


def report_heartbeat(
    api: Api,
    monitor_id: str,
    token: str,
    status: str,
    run_id: str | None = None,
    run_at: datetime | None = None,
) -> tuple[int, object, str, datetime]:
    report_id = run_id or str(uuid.uuid4())
    reported_at = run_at or datetime.now(timezone.utc)
    payload = {
        "runId": report_id,
        "runAt": reported_at.isoformat(timespec="milliseconds").replace("+00:00", "Z"),
        "status": status,
        "summary": f"isolated heartbeat {status.lower()} run",
    }
    code, response = api.request(
        "POST",
        f"/api/v1/heartbeats/{monitor_id}",
        payload,
        {"Authorization": f"Bearer {token}"},
    )
    return code, response, report_id, reported_at


def incidents(api: Api, status_value: str, monitor_id: str) -> list[dict[str, object]]:
    code, rows = api.request("GET", f"/api/v1/incidents?status={status_value}&limit=100")
    if code != 200 or not isinstance(rows, list):
        raise RuntimeError(f"could not read {status_value.lower()} incidents (HTTP {code})")
    return [
        row for row in rows
        if isinstance(row, dict) and row.get("monitorId") == monitor_id
    ]


def ensure_port_free(port: int) -> None:
    with socket.socket() as probe:
        probe.settimeout(0.2)
        if probe.connect_ex(("127.0.0.1", port)) == 0:
            raise SystemExit(f"loopback port {port} is already in use")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-image", required=True, help="already-built Geek Monitor JVM container image")
    parser.add_argument("--timeout", type=int, default=45, help="maximum seconds to wait for scheduler transitions")
    args = parser.parse_args()
    if args.timeout < 20 or args.timeout > 180:
        parser.error("--timeout must be between 20 and 180 seconds")
    ensure_port_free(APP_PORT)
    if podman("image", "exists", args.app_image, check=False).returncode != 0:
        parser.error(f"Geek Monitor image {args.app_image!r} is not available locally")
    if podman("image", "exists", POSTGRES_IMAGE, check=False).returncode != 0:
        podman("pull", POSTGRES_IMAGE, timeout=600)

    suffix = uuid.uuid4().hex[:10]
    network = f"geek-monitor-heartbeat-{suffix}"
    db_container = f"geek-monitor-heartbeat-db-{suffix}"
    app_container = f"geek-monitor-heartbeat-app-{suffix}"
    db_password = secrets.token_urlsafe(24)
    encryption_key = base64.b64encode(secrets.token_bytes(32)).decode("ascii")
    secrets_to_redact = [db_password, ADMIN_PASSWORD]
    app_started = False
    completed = False

    try:
        podman("network", "create", "--internal", network)
        podman(
            "run", "-d", "--name", db_container, "--network", network,
            "--network-alias", "db", "--memory", "1g",
            "--tmpfs", "/var/lib/postgresql/data:rw,size=512m",
            "-e", "POSTGRES_DB=geek_monitor", "-e", "POSTGRES_USER=geek_monitor",
            "-e", f"POSTGRES_PASSWORD={db_password}", POSTGRES_IMAGE,
        )
        wait_for_database(db_container)
        podman(
            "run", "-d", "--name", app_container, "--network", network,
            "-p", f"127.0.0.1:{APP_PORT}:8080", "--memory", "2g",
            "-e", "DB_URL=jdbc:postgresql://db:5432/geek_monitor",
            "-e", "DB_USERNAME=geek_monitor", "-e", f"DB_PASSWORD={db_password}",
            "-e", f"GEEK_MONITOR_ENCRYPTION_KEY={encryption_key}",
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
            "exec", db_container, "psql", "-X", "-A", "-t", "-U", "geek_monitor",
            "-d", "geek_monitor", "-c",
            "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
        ).stdout.strip()
        if schema_version != EXPECTED_FLYWAY_VERSION:
            raise RuntimeError(
                f"application image schema version is V{schema_version or 'unknown'}, "
                f"expected current V{EXPECTED_FLYWAY_VERSION}"
            )

        api = Api(base_url)
        status, install_status = api.request("GET", "/api/v1/install/status")
        if status != 200 or not isinstance(install_status, dict) or install_status.get("initialized") is not False:
            raise RuntimeError("fresh database did not expose the first-install setup state")

        status, setup = api.request("POST", "/api/v1/install/setup", {
            "email": ADMIN_EMAIL,
            "password": ADMIN_PASSWORD,
            "projectName": "心跳任务生命周期验收",
        })
        if (
            status != 201
            or not api.cookie
            or not isinstance(setup, dict)
            or setup.get("user", {}).get("email") != ADMIN_EMAIL
            or setup.get("user", {}).get("role") != "ADMIN"
        ):
            raise RuntimeError(f"could not initialize the disposable application (HTTP {status})")

        status, initialized = api.request("GET", "/api/v1/install/status")
        if status != 200 or not isinstance(initialized, dict) or initialized.get("initialized") is not True:
            raise RuntimeError("successful setup did not close the first-install gate")
        setup_session = api.cookie
        status, current_user = api.request("GET", "/api/v1/auth/me")
        if status != 200 or current_user.get("email") != ADMIN_EMAIL or current_user.get("role") != "ADMIN":
            raise RuntimeError("first-install session did not authenticate the requested administrator")

        status, repeated_setup = api.request("POST", "/api/v1/install/setup", {
            "email": ADMIN_EMAIL,
            "password": ADMIN_PASSWORD,
            "projectName": "重复初始化不应创建",
        })
        if status != 409 or not isinstance(repeated_setup, dict) or repeated_setup.get("code") != "ALREADY_INITIALIZED":
            raise RuntimeError(f"repeated initialization returned HTTP {status}, expected ALREADY_INITIALIZED")

        status, _ = api.request("POST", "/api/v1/auth/logout")
        if status != 204:
            raise RuntimeError(f"first-install session logout returned HTTP {status}, expected 204")
        api.cookie = setup_session
        status, _ = api.request("GET", "/api/v1/auth/me")
        if status != 401:
            raise RuntimeError(f"logged-out first-install session remained valid (HTTP {status})")
        api.cookie = None

        status, _ = api.request("POST", "/api/v1/auth/login", {
            "email": ADMIN_EMAIL,
            "password": "incorrect-password-not-used",
        })
        if status != 401:
            raise RuntimeError(f"invalid administrator password returned HTTP {status}, expected 401")
        status, login = api.request("POST", "/api/v1/auth/login", {
            "email": ADMIN_EMAIL,
            "password": ADMIN_PASSWORD,
        })
        if (
            status != 200
            or not api.cookie
            or not isinstance(login, dict)
            or login.get("user", {}).get("email") != ADMIN_EMAIL
            or login.get("user", {}).get("role") != "ADMIN"
        ):
            raise RuntimeError(f"requested test administrator login failed (HTTP {status})")
        login_session = api.cookie
        status, current_user = api.request("GET", "/api/v1/auth/me")
        if status != 200 or current_user.get("email") != ADMIN_EMAIL or current_user.get("role") != "ADMIN":
            raise RuntimeError("login session did not authenticate the requested administrator")
        status, _ = api.request("POST", "/api/v1/auth/logout")
        if status != 204:
            raise RuntimeError(f"administrator logout returned HTTP {status}, expected 204")
        api.cookie = login_session
        status, _ = api.request("GET", "/api/v1/auth/me")
        if status != 401:
            raise RuntimeError(f"logged-out administrator session remained valid (HTTP {status})")
        api.cookie = None

        status, login = api.request("POST", "/api/v1/auth/login", {
            "email": ADMIN_EMAIL,
            "password": ADMIN_PASSWORD,
        })
        if (
            status != 200
            or not api.cookie
            or not isinstance(login, dict)
            or login.get("user", {}).get("email") != ADMIN_EMAIL
            or login.get("user", {}).get("role") != "ADMIN"
        ):
            raise RuntimeError(f"administrator could not establish a fresh session (HTTP {status})")

        status, projects = api.request("GET", "/api/v1/projects")
        if status != 200 or not isinstance(projects, list) or not projects:
            raise RuntimeError("administrator login did not restore access to its default project")
        print("first_install=201 initialized=true repeated_setup=409 test_admin_login=200 logout=revoked", flush=True)

        status, resource = api.request("POST", "/api/v1/resources", {
            "projectId": projects[0]["id"],
            "name": "定时任务心跳验收",
            "type": "HEARTBEAT",
            "environment": "e2e",
        })
        if status != 201 or not isinstance(resource, dict):
            raise RuntimeError(f"could not create HEARTBEAT resource (HTTP {status})")
        status, monitor = api.request("POST", "/api/v1/monitors", {
            "resourceId": resource["id"],
            "name": "计划任务运行状态",
            "type": "HEARTBEAT",
            "intervalSeconds": 10,
            "timeoutSeconds": 1,
            "heartbeatGraceSeconds": 0,
            "failureThreshold": 1,
            "recoveryThreshold": 1,
            "reminderIntervalMinutes": 0,
            "critical": True,
            "enabled": True,
        })
        if status != 201 or not isinstance(monitor, dict):
            raise RuntimeError(f"could not create heartbeat monitor (HTTP {status})")
        monitor_id = str(monitor.get("id", ""))
        token = monitor.get("heartbeatToken")
        if not monitor_id or not isinstance(token, str) or not token:
            raise RuntimeError("heartbeat monitor creation did not issue its one-time token")
        secrets_to_redact.append(token)
        status, stored_monitor = api.request("GET", f"/api/v1/monitors/{monitor_id}")
        serialized_monitor = json.dumps(stored_monitor, separators=(",", ":"))
        if status != 200 or token in serialized_monitor or stored_monitor.get("heartbeatToken"):
            raise RuntimeError("heartbeat token was readable again after monitor creation")
        print(f"application_schema=V{schema_version} heartbeat_monitor={monitor_id} token_storage=one-time", flush=True)

        status, _ = api.request(
            "POST",
            f"/api/v1/heartbeats/{monitor_id}",
            {"runId": str(uuid.uuid4()), "runAt": datetime.now(timezone.utc).isoformat(), "status": "SUCCESS"},
        )
        if status != 401:
            raise RuntimeError(f"heartbeat report without bearer token returned HTTP {status}, expected 401")
        status, _ = api.request(
            "POST",
            f"/api/v1/heartbeats/{monitor_id}",
            {"runId": str(uuid.uuid4()), "runAt": datetime.now(timezone.utc).isoformat(), "status": "SUCCESS"},
            {"Authorization": "Bearer invalid-heartbeat-token"},
        )
        if status != 401:
            raise RuntimeError(f"invalid heartbeat bearer token returned HTTP {status}, expected 401")

        status, rotated = api.request("POST", f"/api/v1/heartbeats/{monitor_id}/rotate-token")
        if status != 200 or not isinstance(rotated, dict) or not isinstance(rotated.get("token"), str):
            raise RuntimeError(f"admin could not rotate the heartbeat token (HTTP {status})")
        rotated_token = rotated["token"]
        secrets_to_redact.append(rotated_token)
        status, _, _, _ = report_heartbeat(api, monitor_id, token, "SUCCESS")
        if status != 401:
            raise RuntimeError(f"rotated heartbeat token remained usable (HTTP {status})")
        status, success_response, success_id, success_at = report_heartbeat(api, monitor_id, rotated_token, "SUCCESS")
        if status != 202 or not isinstance(success_response, dict) or success_response.get("accepted") is not True or success_response.get("stale") is not False:
            raise RuntimeError(f"valid heartbeat success was not accepted (HTTP {status})")
        healthy = wait_for_state(api, monitor_id, "UP", "UP", None, args.timeout)
        print(f"heartbeat_success={healthy[0]['state']} result={healthy[1]['status']} report=ACCEPTED token_rotation=PASS", flush=True)

        status, duplicate, _, _ = report_heartbeat(api, monitor_id, rotated_token, "FAILURE", run_id=success_id)
        if status != 200 or not isinstance(duplicate, dict) or duplicate.get("duplicate") is not True or duplicate.get("stale") is not False:
            raise RuntimeError(f"duplicate heartbeat run was not acknowledged as a duplicate (HTTP {status})")
        database_count = podman(
            "exec", db_container, "psql", "-X", "-A", "-t", "-U", "geek_monitor", "-d", "geek_monitor", "-c",
            f"SELECT count(*) FROM heartbeat_report WHERE monitor_id = '{monitor_id}'::uuid AND run_id = '{success_id}'::uuid",
        ).stdout.strip()
        if database_count != "1":
            raise RuntimeError(f"duplicate heartbeat run created {database_count} rows instead of exactly one")
        stable = wait_for_state(api, monitor_id, "UP", "UP", None, args.timeout)
        if stable[1].get("errorCode") is not None:
            raise RuntimeError("duplicate FAILURE payload changed the existing successful run")
        print("heartbeat_duplicate=ACKNOWLEDGED rows=1 health=UP", flush=True)

        status, stale_response, _, _ = report_heartbeat(
            api, monitor_id, rotated_token, "FAILURE", run_at=success_at - timedelta(seconds=1),
        )
        if status != 202 or not isinstance(stale_response, dict) or stale_response.get("stale") is not True:
            raise RuntimeError(f"out-of-order heartbeat was not marked stale (HTTP {status})")
        stale_state = wait_for_state(api, monitor_id, "UP", "UP", None, args.timeout)
        print(f"heartbeat_out_of_order=STALE health={stale_state[0]['state']}", flush=True)

        status, failure_response, _, _ = report_heartbeat(api, monitor_id, rotated_token, "FAILURE")
        if status != 202 or not isinstance(failure_response, dict) or failure_response.get("accepted") is not True:
            raise RuntimeError(f"heartbeat FAILURE report was not accepted (HTTP {status})")
        failed = wait_for_state(api, monitor_id, "DOWN", "DOWN", "HEARTBEAT_REPORTED_FAILURE", args.timeout)
        open_failure = incidents(api, "OPEN", monitor_id)
        incident = next((row for row in open_failure if row.get("category") == "AVAILABILITY"), None)
        if incident is None:
            raise RuntimeError("reported heartbeat failure did not open an availability incident")
        print(f"heartbeat_reported_failure={failed[0]['state']} result={failed[1]['status']} "
              f"error={failed[1]['errorCode']} incident=OPEN", flush=True)

        status, recovered_response, _, _ = report_heartbeat(api, monitor_id, rotated_token, "SUCCESS")
        if status != 202 or not isinstance(recovered_response, dict) or recovered_response.get("accepted") is not True:
            raise RuntimeError(f"heartbeat recovery report was not accepted (HTTP {status})")
        recovered = wait_for_state(api, monitor_id, "UP", "UP", None, args.timeout)
        resolved = next((row for row in incidents(api, "RESOLVED", monitor_id) if row.get("id") == incident.get("id")), None)
        if resolved is None:
            raise RuntimeError("heartbeat recovery did not resolve the original incident")
        print(f"heartbeat_failure_recovery={recovered[0]['state']} result={recovered[1]['status']} "
              "same_incident=RESOLVED", flush=True)

        missed = wait_for_state(api, monitor_id, "DOWN", "DOWN", "HEARTBEAT_MISSED", args.timeout + 20)
        missed_incidents = incidents(api, "OPEN", monitor_id)
        missed_incident = next((row for row in missed_incidents if row.get("category") == "AVAILABILITY"), None)
        if missed_incident is None or missed_incident.get("id") == incident.get("id"):
            raise RuntimeError("missed heartbeat did not open a new availability incident")
        print(f"heartbeat_missing={missed[0]['state']} result={missed[1]['status']} "
              f"error={missed[1]['errorCode']} incident=OPEN", flush=True)

        status, final_response, _, _ = report_heartbeat(api, monitor_id, rotated_token, "SUCCESS")
        if status != 202 or not isinstance(final_response, dict) or final_response.get("accepted") is not True:
            raise RuntimeError(f"post-miss heartbeat recovery was not accepted (HTTP {status})")
        final_state = wait_for_state(api, monitor_id, "UP", "UP", None, args.timeout)
        final_incident = next((row for row in incidents(api, "RESOLVED", monitor_id) if row.get("id") == missed_incident.get("id")), None)
        if final_incident is None:
            raise RuntimeError("recovery after a missed heartbeat did not resolve its incident")
        print(f"heartbeat_missed_recovery={final_state[0]['state']} result={final_state[1]['status']} "
              "same_incident=RESOLVED", flush=True)
        completed = True
        return 0
    finally:
        if app_started and not completed:
            logs = podman("logs", "--tail", "80", app_container, check=False).stdout
            for secret in secrets_to_redact:
                logs = logs.replace(secret, "[REDACTED]")
            if logs:
                print(f"temporary application log tail:\n{logs[-12000:]}", file=sys.stderr)
        if app_started:
            podman("rm", "-f", app_container, check=False)
        podman("rm", "-f", db_container, check=False)
        podman("network", "rm", network, check=False)


if __name__ == "__main__":
    raise SystemExit(main())
