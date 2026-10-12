#!/usr/bin/env python3
"""Verify interrupted-check and notification recovery across a real app restart.

The script starts the packaged Quarkus application on loopback port 18090 and
expects a disposable, empty PostgreSQL database at 127.0.0.1:55437 named
``geek_monitor_restart_recovery``. Its probe and Webhook sink bind only to a
private host-only address on this machine. It never contacts an external
target or notification service.
"""

from __future__ import annotations

import argparse
import base64
import http.server
import ipaddress
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
from urllib.parse import quote


class LocalServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], handler: type[http.server.BaseHTTPRequestHandler]):
        super().__init__(address, handler)
        self.received: list[dict[str, object]] = []
        self.lock = threading.Lock()


class ProbeHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, _format: str, *_args: object) -> None:
        return

    def do_GET(self) -> None:
        body = b"restart recovery probe healthy" if self.path == "/probe" else b"not found"
        self.send_response(200 if self.path == "/probe" else 404)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class WebhookHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, _format: str, *_args: object) -> None:
        return

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        valid = self.path == "/webhook" and 0 < length <= 64 * 1024
        payload: dict[str, object] = {}
        try:
            decoded = json.loads(self.rfile.read(min(length, 64 * 1024 + 1)))
            notification = decoded.get("notification") if isinstance(decoded, dict) else None
            valid = valid and isinstance(notification, dict)
            valid = valid and notification.get("eventType") == "notification.test"
            valid = valid and decoded.get("idempotencyKey") == self.headers.get("Idempotency-Key")
            if valid:
                payload = decoded
                with self.server.lock:  # type: ignore[attr-defined]
                    self.server.received.append(payload)  # type: ignore[attr-defined]
        except (json.JSONDecodeError, AttributeError):
            valid = False
        self.send_response(202 if valid else 400)
        self.send_header("Content-Length", "0")
        self.end_headers()


class ApiClient:
    def __init__(self, base_url: str, email: str, password: str):
        self.base_url = base_url.rstrip("/")
        self.email = email
        self.password = password
        self.cookie: str | None = None

    def request(self, method: str, path: str, body: dict[str, object] | None = None) -> tuple[int, object]:
        encoded = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(
            f"{self.base_url}{path}",
            data=encoded,
            method=method,
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


def wait_until(description: str, timeout: float, callback):
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            value = callback()
            if value:
                return value
        except Exception as error:  # keep polling through startup/transient HTTP errors
            last_error = error
        time.sleep(0.5)
    detail = f"; last error={last_error}" if last_error else ""
    raise TimeoutError(f"timed out waiting for {description}{detail}")


def assert_local_address(value: str, ports: tuple[int, ...]) -> None:
    try:
        address = ipaddress.ip_address(value)
    except ValueError as error:
        raise RuntimeError("service-address must be an IPv4 address assigned to this host") from error
    if address.version != 4 or not address.is_private or address.is_loopback or address.is_link_local or address.is_reserved:
        raise RuntimeError("service-address must be private, non-loopback, and non-link-local")
    for port in ports:
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                probe.bind((value, port))
            except OSError as error:
                raise RuntimeError(f"service-address is not locally bindable on port {port}") from error


def psql(args: list[str], database_env: dict[str, str], timeout: int = 15, input_text: str | None = None) -> str:
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


def application_environment(args, password: str, encryption_key: str) -> dict[str, str]:
    environment = os.environ.copy()
    environment.update({
        "DB_URL": f"jdbc:postgresql://127.0.0.1:{args.db_port}/{args.db_name}",
        "DB_USERNAME": "geek_monitor",
        "DB_PASSWORD": password,
        "QUARKUS_HTTP_HOST": "127.0.0.1",
        "QUARKUS_HTTP_PORT": str(args.app_port),
        "QUARKUS_MAILER_MOCK": "true",
        "GEEK_MONITOR_ENCRYPTION_KEY": encryption_key,
        "GEEK_MONITOR_NETWORK_ALLOW_PRIVATE_TARGETS": "true",
        "MONITOR_ALLOWED_CIDRS": f"{args.service_address}/32",
    })
    return environment


def start_application(root: Path, jar: Path, args, password: str, encryption_key: str, log) -> subprocess.Popen:
    process = subprocess.Popen(
        ["java", "-jar", str(jar)],
        cwd=root,
        env=application_environment(args, password, encryption_key),
        stdout=log,
        stderr=subprocess.STDOUT,
    )
    base_url = f"http://127.0.0.1:{args.app_port}"

    def ready() -> bool:
        if process.poll() is not None:
            raise RuntimeError(f"application exited with code {process.returncode}")
        try:
            with urllib.request.urlopen(f"{base_url}/q/health/ready", timeout=2) as response:
                return response.status == 200 and json.loads(response.read()).get("status") == "UP"
        except (urllib.error.URLError, TimeoutError):
            return False

    try:
        wait_until("application readiness", 120, ready)
    except Exception:
        stop_application(process)
        raise
    return process


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
    parser.add_argument("--email", default="admin@biliwind.com")
    parser.add_argument("--password", default="admin@biliwind.com")
    parser.add_argument("--service-address", default="172.16.87.1")
    parser.add_argument("--probe-port", type=int, default=28283)
    parser.add_argument("--webhook-port", type=int, default=28284)
    parser.add_argument("--app-port", type=int, default=18090)
    parser.add_argument("--db-port", type=int, default=55437)
    parser.add_argument("--db-name", default="geek_monitor_restart_recovery")
    parser.add_argument("--timeout", type=float, default=60)
    args = parser.parse_args()

    if args.db_name != "geek_monitor_restart_recovery" or args.db_port != 55437:
        parser.error("only the disposable geek_monitor_restart_recovery database on port 55437 is allowed")
    if args.app_port != 18090:
        parser.error("the isolated application must use loopback port 18090")
    if args.probe_port == args.webhook_port or any(port not in range(1, 65536) for port in (args.probe_port, args.webhook_port)):
        parser.error("probe-port and webhook-port must be distinct valid ports")
    database_password = os.environ.get("GEEK_MONITOR_E2E_DB_PASSWORD")
    if not database_password:
        parser.error("set GEEK_MONITOR_E2E_DB_PASSWORD for the disposable PostgreSQL database")
    if not (root / "build/quarkus-app/quarkus-run.jar").is_file():
        parser.error("build the application first with ./gradlew build")
    if shutil.which("psql") is None or shutil.which("java") is None:
        parser.error("psql and java must be available on PATH")
    try:
        assert_local_address(args.service_address, (args.probe_port, args.webhook_port))
    except RuntimeError as error:
        parser.error(str(error))

    base_url = f"http://127.0.0.1:{args.app_port}"
    with socket.socket() as port_check:
        port_check.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            port_check.bind(("127.0.0.1", args.app_port))
        except OSError:
            parser.error(f"loopback port {args.app_port} is already in use")

    database_env = os.environ.copy()
    database_env.update({
        "PGHOST": "127.0.0.1",
        "PGPORT": str(args.db_port),
        "PGDATABASE": args.db_name,
        "PGUSER": "geek_monitor",
        "PGPASSWORD": database_password,
    })
    if psql(["-c", "SELECT 1"], database_env) != "1":
        parser.error("could not connect to the disposable database")
    migrated = psql(["-c", "SELECT to_regclass('public.installation_state') IS NOT NULL"], database_env)
    if migrated == "t":
        initialized = psql(["-c", "SELECT initialized FROM installation_state WHERE id=1"], database_env)
        if initialized != "f":
            parser.error("database must be empty or migrated but not initialized")
    elif migrated != "f":
        parser.error("could not verify the disposable database state")

    probe = LocalServer((args.service_address, args.probe_port), ProbeHandler)
    sink = LocalServer((args.service_address, args.webhook_port), WebhookHandler)
    servers = [probe, sink]
    threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in servers]
    for thread in threads:
        thread.start()

    app_process: subprocess.Popen | None = None
    encryption_key = base64.b64encode(os.urandom(32)).decode("ascii")
    with tempfile.TemporaryDirectory(prefix="geek-monitor-restart-e2e-") as log_directory:
        log_path = Path(log_directory) / "application.log"
        log = log_path.open("w", encoding="utf-8")
        try:
            jar = root / "build/quarkus-app/quarkus-run.jar"
            app_process = start_application(root, jar, args, database_password, encryption_key, log)
            api = ApiClient(base_url, args.email, args.password)
            status, setup = api.request(
                "POST", "/api/v1/install/setup",
                {"email": args.email, "password": args.password, "projectName": "重启恢复验收"},
            )
            if status != 201 or not isinstance(setup, dict) or not api.cookie:
                raise RuntimeError("could not initialize the disposable test instance")
            status, projects = api.request("GET", "/api/v1/projects")
            if status != 200 or not isinstance(projects, list) or not projects:
                raise RuntimeError("initialization did not create its default project")
            project_id = str(projects[0]["id"])
            user_id = psql(["-c", "SELECT id::text FROM app_user WHERE email='admin@biliwind.com'"], database_env)

            webhook_url = f"http://{args.service_address}:{args.webhook_port}/webhook"
            status, channel = api.request("POST", "/api/v1/notification-channels", {
                "projectId": project_id,
                "name": "restart-recovery-webhook",
                "type": "WEBHOOK",
                "webhookUrl": webhook_url,
                "enabled": True,
                "rateLimitPerMinute": 60,
            })
            if status != 201 or not isinstance(channel, dict):
                raise RuntimeError(f"could not create the private test Webhook channel (HTTP {status})")

            status, resource = api.request("POST", "/api/v1/resources", {
                "projectId": project_id,
                "name": "重启恢复目标",
                "type": "WEBSITE",
                "environment": "test",
            })
            if status != 201 or not isinstance(resource, dict):
                raise RuntimeError(f"could not create the restart test resource (HTTP {status})")
            resource_id = str(resource["id"])
            status, monitor = api.request("POST", "/api/v1/monitors", {
                "resourceId": resource_id,
                "name": "重启中断检查",
                "type": "HTTP",
                "targetUrl": f"http://{args.service_address}:{args.probe_port}/probe",
                "method": "GET",
                "intervalSeconds": 3600,
                "timeoutSeconds": 3,
                "failureThreshold": 1,
                "recoveryThreshold": 1,
                "expectedStatusCodes": [200],
                "critical": True,
                "enabled": True,
            })
            if status != 201 or not isinstance(monitor, dict):
                raise RuntimeError(f"could not create the scheduled HTTP monitor (HTTP {status})")
            monitor_id = str(monitor["id"])
            wait_until("initial HTTP check to complete", args.timeout, lambda: psql([
                "-c", f"SELECT result_status FROM check_execution WHERE monitor_id='{monitor_id}'::uuid ORDER BY scheduled_at DESC LIMIT 1",
            ], database_env) in {"UP", "DOWN"})
            if psql(["-c", f"SELECT result_status FROM check_execution WHERE monitor_id='{monitor_id}'::uuid ORDER BY scheduled_at DESC LIMIT 1"], database_env) != "UP":
                raise RuntimeError("the local HTTP probe did not reach UP before restart")

            stop_application(app_process)
            app_process = None
            execution_id = str(uuid.uuid4())
            stale_owner = str(uuid.uuid4())
            outbox_id = str(uuid.uuid4())
            idempotency_key = f"restart-recovery:{outbox_id}"
            notification = {
                "eventId": outbox_id,
                "eventType": "notification.test",
                "title": "重启恢复测试通知",
                "message": "应用重启后，Outbox 应恢复并完成投递。",
                "requestedBy": args.email,
                "isTest": True,
            }
            seed_sql = """
                INSERT INTO check_execution(id, monitor_id, scheduled_at, started_at, result_status, interval_seconds, execution_owner)
                VALUES (:'execution_id'::uuid, :'monitor_id'::uuid, now() - interval '30 seconds', now() - interval '30 seconds', 'RUNNING', 3600, :'stale_owner'::uuid);
                INSERT INTO notification_outbox(id, channel_id, requested_by, event_type, idempotency_key, payload, status, next_attempt_at, claimed_at)
                VALUES (:'outbox_id'::uuid, :'channel_id'::uuid, :'user_id'::uuid, 'TEST', :'idempotency_key', :'payload'::jsonb,
                    'RUNNING', now() - interval '3 minutes', now() - interval '3 minutes');
            """
            psql([
                "-v", f"execution_id={execution_id}", "-v", f"monitor_id={monitor_id}", "-v", f"stale_owner={stale_owner}",
                "-v", f"outbox_id={outbox_id}", "-v", f"channel_id={channel['id']}", "-v", f"user_id={user_id}",
                "-v", f"idempotency_key={idempotency_key}", "-v", f"payload={json.dumps(notification, ensure_ascii=False)}",
                "-f", "-",
            ], database_env, input_text=seed_sql)

            app_process = start_application(root, jar, args, database_password, encryption_key, log)
            history_path = f"/api/v1/monitors/{quote(monitor_id)}/results?limit=100"
            interrupted = wait_until("interrupted execution to appear as UNKNOWN", args.timeout, lambda: next((
                row for row in api.request("GET", history_path)[1]
                if row.get("id") == execution_id and row.get("status") == "UNKNOWN" and row.get("errorCode") == "EXECUTION_INTERRUPTED"
            ), None))

            def delivered_outbox_status() -> str | None:
                value = psql(["-c", f"SELECT status || ':' || attempt_count::text || ':' || coalesce(last_http_status::text, '') FROM notification_outbox WHERE id='{outbox_id}'::uuid"], database_env)
                return value if value.startswith("DELIVERED:") else None

            outbox_status = wait_until("stale notification Outbox to deliver after restart", args.timeout, delivered_outbox_status)
            delivery_status = psql(["-c", f"SELECT count(*)::text || ':' || min(status) || ':' || min(http_status)::text FROM notification_delivery WHERE outbox_id='{outbox_id}'::uuid"], database_env)
            if outbox_status != "DELIVERED:1:202" or delivery_status != "1:DELIVERED:202":
                raise RuntimeError(f"restart notification delivery was not exactly once in this test: {outbox_status}, {delivery_status}")
            with sink.lock:
                received = list(sink.received)
            matching = [
                row for row in received
                if isinstance(row.get("notification"), dict)
                and row["notification"].get("eventType") == "notification.test"
                and row.get("idempotencyKey") == idempotency_key
            ]
            if len(matching) != 1:
                raise RuntimeError(f"private Webhook received {len(matching)} copies of the restarted notification")

            print(json.dumps({
                "interrupted_check": {"status": interrupted["status"], "errorCode": interrupted["errorCode"]},
                "notification": {"status": "DELIVERED", "httpStatus": 202, "attempts": 1, "webhookRequests": len(matching)},
                "application_restarts": 1,
                "external_services_contacted": False,
            }, ensure_ascii=False, separators=(",", ":")))
            return 0
        except Exception:
            log.flush()
            if log_path.exists():
                print("\n".join(log_path.read_text(encoding="utf-8", errors="replace").splitlines()[-80:]))
            raise
        finally:
            if app_process is not None:
                stop_application(app_process)
            log.close()
            for server in servers:
                server.shutdown()
                server.server_close()
            for thread in threads:
                thread.join(timeout=2)


if __name__ == "__main__":
    raise SystemExit(main())
