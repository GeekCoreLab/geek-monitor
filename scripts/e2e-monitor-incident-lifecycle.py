#!/usr/bin/env python3
"""Verify scheduler -> incident -> Webhook -> recovery on an isolated local stack.

The API must run on loopback against the disposable
``geek_monitor_incident_lifecycle`` database on 127.0.0.1:55436. The HTTP
probe and Webhook sink bind only to a private host-only address owned by this
machine (172.16.87.1 by default). No external service is contacted.
"""

from __future__ import annotations

import argparse
import http.server
import ipaddress
import json
import os
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from urllib.parse import urlsplit


class ProbeState:
    def __init__(self) -> None:
        self.healthy = False
        self.lock = threading.Lock()

    def set_healthy(self, healthy: bool) -> None:
        with self.lock:
            self.healthy = healthy

    def is_healthy(self) -> bool:
        with self.lock:
            return self.healthy


class ProbeServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], state: ProbeState):
        super().__init__(address, ProbeHandler)
        self.state = state


class ProbeHandler(http.server.BaseHTTPRequestHandler):
    server_version = "GeekMonitorLifecycleProbe/1"

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def do_GET(self) -> None:
        if self.path != "/probe":
            self.send_error(404)
            return
        healthy = self.server.state.is_healthy()  # type: ignore[attr-defined]
        body = b"probe healthy" if healthy else b"probe intentionally failing"
        self.send_response(200 if healthy else 503)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class WebhookServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int]):
        super().__init__(address, WebhookHandler)
        self.received: list[dict[str, object]] = []
        self.lock = threading.Lock()


class WebhookHandler(http.server.BaseHTTPRequestHandler):
    server_version = "GeekMonitorLifecycleSink/1"

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        valid = self.path == "/webhook" and length <= 64 * 1024 and bool(self.headers.get("Idempotency-Key"))
        payload: dict[str, object] = {}
        try:
            decoded = json.loads(self.rfile.read(min(length, 64 * 1024 + 1)))
            valid = valid and isinstance(decoded, dict) and isinstance(decoded.get("notification"), dict)
            if isinstance(decoded, dict):
                payload = decoded
                notification = decoded.get("notification")
                valid = valid and isinstance(notification, dict) and isinstance(notification.get("eventType"), str)
                valid = valid and decoded.get("idempotencyKey") == self.headers.get("Idempotency-Key")
        except (json.JSONDecodeError, AttributeError):
            valid = False
        if valid:
            with self.server.lock:  # type: ignore[attr-defined]
                self.server.received.append(payload)  # type: ignore[attr-defined]
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
            response = urllib.request.urlopen(request, timeout=12)
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

    def initialize(self) -> str:
        status, payload = self.request(
            "POST",
            "/api/v1/install/setup",
            {"email": self.email, "password": self.password, "projectName": "监控故障恢复闭环"},
        )
        if status != 201 or not isinstance(payload, dict) or payload.get("user", {}).get("email") != self.email.lower():
            raise RuntimeError(f"initial setup did not create the requested administrator (HTTP {status})")
        if not self.cookie:
            raise RuntimeError("setup response did not include a session cookie")
        status, projects = self.request("GET", "/api/v1/projects")
        if status != 200 or not isinstance(projects, list) or not projects:
            raise RuntimeError("initialization did not create its default project")
        return str(projects[0]["id"])


def local_address(value: str, port: int) -> None:
    try:
        address = ipaddress.ip_address(value)
    except ValueError as error:
        raise RuntimeError("service-address must be an IPv4 address assigned to this host") from error
    if address.version != 4 or not address.is_private or address.is_loopback or address.is_link_local or address.is_reserved:
        raise RuntimeError("service-address must be private, non-loopback, and non-link-local")
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind((value, port))
        except OSError as error:
            raise RuntimeError(f"service-address is not locally bindable on port {port}") from error


def wait_until(description: str, timeout_seconds: float, callback):
    deadline = time.monotonic() + timeout_seconds
    last_value = None
    while time.monotonic() < deadline:
        last_value = callback()
        if last_value:
            return last_value
        time.sleep(0.5)
    raise TimeoutError(f"timed out waiting for {description}; last observation={last_value!r}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18089")
    parser.add_argument("--email", default="admin@biliwind.com")
    parser.add_argument("--password", default="admin@biliwind.com")
    parser.add_argument("--service-address", default="172.16.87.1")
    parser.add_argument("--probe-port", type=int, default=28281)
    parser.add_argument("--webhook-port", type=int, default=28282)
    parser.add_argument("--failure-timeout", type=float, default=45)
    parser.add_argument("--notification-timeout", type=float, default=45)
    parser.add_argument("--recovery-timeout", type=float, default=45)
    parser.add_argument("--db-host", default="127.0.0.1")
    parser.add_argument("--db-port", type=int, default=55436)
    parser.add_argument("--db-name", default="geek_monitor_incident_lifecycle")
    args = parser.parse_args()

    base = urlsplit(args.base_url)
    if base.scheme != "http" or base.hostname not in {"127.0.0.1", "localhost", "::1"} or base.port != 18089:
        parser.error("base-url must use the isolated HTTP application on loopback port 18089")
    if args.db_host not in {"127.0.0.1", "localhost", "::1"} or args.db_port != 55436:
        parser.error("the script may only use the disposable PostgreSQL instance on 127.0.0.1:55436")
    if args.db_name != "geek_monitor_incident_lifecycle":
        parser.error("db-name must be geek_monitor_incident_lifecycle")
    if not os.environ.get("PGPASSWORD") and not os.environ.get("PGPASSFILE"):
        parser.error("set PGPASSWORD or PGPASSFILE for the disposable test database")
    if args.probe_port == args.webhook_port or any(port not in range(1, 65536) for port in (args.probe_port, args.webhook_port)):
        parser.error("probe-port and webhook-port must be distinct valid ports")
    try:
        local_address(args.service_address, args.probe_port)
        local_address(args.service_address, args.webhook_port)
    except RuntimeError as error:
        parser.error(str(error))

    database_environment = os.environ.copy()
    database_environment.update({
        "PGHOST": args.db_host,
        "PGPORT": str(args.db_port),
        "PGDATABASE": args.db_name,
        "PGUSER": "geek_monitor",
    })
    database_check = subprocess.run(
        ["psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c",
         "SELECT current_database() || ':' || initialized::text FROM installation_state WHERE id = 1"],
        env=database_environment,
        check=False,
        capture_output=True,
        text=True,
        timeout=10,
    )
    if database_check.returncode != 0 or database_check.stdout.strip() != f"{args.db_name}:false":
        parser.error("database must be the migrated, still-uninitialized geek_monitor_incident_lifecycle instance")

    started = time.monotonic()
    state = ProbeState()
    probe = ProbeServer((args.service_address, args.probe_port), state)
    sink = WebhookServer((args.service_address, args.webhook_port))
    servers = [probe, sink]
    threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in servers]
    for thread in threads:
        thread.start()

    try:
        api = ApiClient(args.base_url, args.email, args.password)
        project_id = api.initialize()
        webhook_url = f"http://{args.service_address}:{args.webhook_port}/webhook"
        status, channel = api.request(
            "POST",
            "/api/v1/notification-channels",
            {
                "projectId": project_id,
                "name": f"incident-lifecycle-{int(time.time())}",
                "type": "WEBHOOK",
                "webhookUrl": webhook_url,
                "enabled": True,
                "rateLimitPerMinute": 60,
            },
        )
        if status != 201 or not isinstance(channel, dict):
            raise RuntimeError(f"could not create the local Webhook channel (HTTP {status})")
        channel_id = str(channel["id"])

        status, resource = api.request(
            "POST",
            "/api/v1/resources",
            {"projectId": project_id, "name": "故障恢复目标", "type": "WEBSITE", "environment": "test"},
        )
        if status != 201 or not isinstance(resource, dict):
            raise RuntimeError(f"could not create the isolated monitor resource (HTTP {status})")
        resource_id = str(resource["id"])
        target_url = f"http://{args.service_address}:{args.probe_port}/probe"
        status, monitor = api.request(
            "POST",
            "/api/v1/monitors",
            {
                "resourceId": resource_id,
                "name": "自动调度故障及恢复",
                "type": "HTTP",
                "targetUrl": target_url,
                "method": "GET",
                "intervalSeconds": 10,
                "timeoutSeconds": 3,
                "failureThreshold": 2,
                "recoveryThreshold": 1,
                "expectedStatusCodes": [200],
                "critical": True,
                "enabled": True,
            },
        )
        if status != 201 or not isinstance(monitor, dict):
            raise RuntimeError(f"could not create the scheduled HTTP monitor (HTTP {status})")
        monitor_id = str(monitor["id"])

        incidents = wait_until(
            "scheduler-created OPEN incident",
            args.failure_timeout,
            lambda: _matching_incident(api, monitor_id, "OPEN"),
        )
        incident = incidents[0]
        incident_id = str(incident["id"])
        failure_history = api.request("GET", f"/api/v1/monitors/{monitor_id}/results?limit=20")[1]
        if not isinstance(failure_history, list) or not any(row.get("status") == "DOWN" and row.get("statusCode") == 503 for row in failure_history):
            raise RuntimeError("the scheduled HTTP 503 was not persisted as a DOWN check result")
        if len([row for row in incidents if row.get("monitorId") == monitor_id]) != 1:
            raise RuntimeError("repeated failures created duplicate active incidents")

        open_delivery = wait_until(
            "delivered OPENED notification",
            args.notification_timeout,
            lambda: _matching_delivery(api, channel_id, "OPENED", "DELIVERED"),
        )
        with sink.lock:
            open_payloads = list(sink.received)
        if not any(
            (payload.get("notification") or {}).get("eventType") == "incident.opened"
            and (payload.get("notification") or {}).get("incidentId") == incident_id
            for payload in open_payloads
        ):
            raise RuntimeError("the private sink did not receive the matching incident.opened notification")

        state.set_healthy(True)
        resolved = wait_until(
            "monitor UP and incident RESOLVED",
            args.recovery_timeout,
            lambda: _matching_incident(api, monitor_id, "RESOLVED"),
        )
        if len(resolved) != 1 or resolved[0].get("id") != incident_id:
            raise RuntimeError("recovery did not resolve the original single incident")
        monitor_rows = api.request("GET", f"/api/v1/monitors?resourceId={urllib.parse.quote(resource_id)}")[1]
        if not isinstance(monitor_rows, list) or not any(row.get("id") == monitor_id and row.get("state") == "UP" for row in monitor_rows):
            raise RuntimeError("the monitor did not return to UP after the probe recovered")
        resource_rows = api.request("GET", f"/api/v1/resources?projectId={urllib.parse.quote(project_id)}")[1]
        if not isinstance(resource_rows, list) or not any(row.get("id") == resource_id and row.get("healthStatus") == "UP" for row in resource_rows):
            raise RuntimeError("the critical resource health did not return to UP")

        resolved_delivery = wait_until(
            "delivered RESOLVED notification",
            args.notification_timeout,
            lambda: _matching_delivery(api, channel_id, "RESOLVED", "DELIVERED"),
        )
        event_rows = api.request("GET", f"/api/v1/incidents/{incident_id}/events")[1]
        if not isinstance(event_rows, list) or not {row.get("eventType") for row in event_rows}.issuperset({"OPENED", "RESOLVED"}):
            raise RuntimeError("the incident event history is missing OPENED or RESOLVED")
        with sink.lock:
            payloads = list(sink.received)
        webhook_types = sorted({
            (payload.get("notification") or {}).get("eventType")
            for payload in payloads
            if isinstance(payload.get("notification"), dict)
        })
        if not {"incident.opened", "incident.resolved"}.issubset(webhook_types):
            raise RuntimeError(f"the local Webhook sink missed an incident lifecycle event: {webhook_types}")

        maintenance_started = datetime.now(timezone.utc)
        status, maintenance = api.request(
            "POST",
            "/api/v1/maintenance-windows",
            {
                "projectId": project_id,
                "resourceId": resource_id,
                "name": "失败阈值重置验收",
                "description": "维护期间记录检查结果但不创建事件，维护结束后重新累计失败阈值。",
                "startsAt": (maintenance_started - timedelta(seconds=2)).isoformat().replace("+00:00", "Z"),
                "endsAt": (maintenance_started + timedelta(minutes=5)).isoformat().replace("+00:00", "Z"),
            },
        )
        if status != 201 or not isinstance(maintenance, dict) or maintenance.get("status") != "ACTIVE":
            raise RuntimeError(f"could not create an active resource maintenance window (HTTP {status})")
        maintenance_id = str(maintenance["id"])
        with sink.lock:
            deliveries_before_maintenance = len(sink.received)
        state.set_healthy(False)
        down_during_maintenance = wait_until(
            "scheduled DOWN result during maintenance",
            args.failure_timeout,
            lambda: _monitor_state(api, monitor_id, "DOWN"),
        )
        failures_during_maintenance = wait_until(
            "failure threshold reached during maintenance",
            args.failure_timeout,
            lambda: (
                _monitor_state(api, monitor_id, "DOWN")
                if _latest_result(api, monitor_id, "DOWN", 503)
                else None
            ),
        )
        if _matching_incident(api, monitor_id, "OPEN") is not None:
            raise RuntimeError("maintenance suppression opened an incident for a failed monitor")
        with sink.lock:
            if len(sink.received) != deliveries_before_maintenance:
                raise RuntimeError("maintenance suppression sent an unexpected Webhook notification")
        failure_history = api.request("GET", f"/api/v1/monitors/{monitor_id}/results?limit=20")[1]
        if not isinstance(failure_history, list) or not any(
            row.get("status") == "DOWN" and row.get("statusCode") == 503 for row in failure_history
        ):
            raise RuntimeError("maintenance suppressed or lost the failed check result")

        cancelled_at = datetime.now(timezone.utc)
        status, _ = api.request("DELETE", f"/api/v1/maintenance-windows/{maintenance_id}")
        if status != 204:
            raise RuntimeError(f"could not cancel the active maintenance window (HTTP {status})")
        cancelled_windows = api.request("GET", f"/api/v1/maintenance-windows?projectId={project_id}")[1]
        if not isinstance(cancelled_windows, list) or not any(
            row.get("id") == maintenance_id and row.get("status") == "CANCELLED"
            for row in cancelled_windows
        ):
            raise RuntimeError("cancelled maintenance window was not reported as CANCELLED")

        first_failure_after_cancel = wait_until(
            "first failed check after maintenance cancellation",
            args.failure_timeout,
            lambda: _failure_after(api, monitor_id, cancelled_at),
        )
        if _matching_incident(api, monitor_id, "OPEN") is not None:
            raise RuntimeError(
                "maintenance boundary did not reset the failure counter; an incident opened after only one post-window failure"
            )
        second_failure_after_cancel = wait_until(
            "second failed check after maintenance cancellation",
            args.failure_timeout,
            lambda: _failure_after(api, monitor_id, datetime.fromisoformat(first_failure_after_cancel["lastResultAt"].replace("Z", "+00:00"))),
        )
        reopened = wait_until(
            "incident after the post-maintenance failure threshold",
            args.failure_timeout,
            lambda: _matching_incident(api, monitor_id, "OPEN"),
        )
        if len(reopened) != 1 or reopened[0].get("id") == incident_id:
            raise RuntimeError("the post-maintenance failure did not open one new incident")
        maintenance_incident_id = str(reopened[0]["id"])
        if not _has_webhook_incident(sink, "incident.opened", maintenance_incident_id):
            wait_until(
                "delivered post-maintenance OPENED notification",
                args.notification_timeout,
                lambda: _has_webhook_incident(sink, "incident.opened", maintenance_incident_id),
            )

        state.set_healthy(True)
        maintenance_resolved = wait_until(
            "post-maintenance incident RESOLVED",
            args.recovery_timeout,
            lambda: _incident_by_id(api, maintenance_incident_id, "RESOLVED"),
        )
        if maintenance_resolved is None:
            raise RuntimeError("post-maintenance recovery did not resolve its incident")
        if not _has_webhook_incident(sink, "incident.resolved", maintenance_incident_id):
            wait_until(
                "delivered post-maintenance RESOLVED notification",
                args.notification_timeout,
                lambda: _has_webhook_incident(sink, "incident.resolved", maintenance_incident_id),
            )
        with sink.lock:
            payloads = list(sink.received)
        webhook_types = sorted({
            (payload.get("notification") or {}).get("eventType")
            for payload in payloads
            if isinstance(payload.get("notification"), dict)
        })

        print(json.dumps({
            "monitor_result": "DOWN/503 -> UP/200",
            "incident": {"id": incident_id, "category": incident.get("category"), "events": ["OPENED", "RESOLVED"]},
            "incident_count": len([row for row in resolved if row.get("monitorId") == monitor_id]),
            "open_delivery": {"status": open_delivery.get("status"), "groupCount": open_delivery.get("groupCount")},
            "resolved_delivery": {"status": resolved_delivery.get("status"), "groupCount": resolved_delivery.get("groupCount")},
            "webhook_event_types": webhook_types,
            "webhook_requests": len(payloads),
            "maintenance": {
                "during_window": "DOWN/503 recorded, incident and notification suppressed",
                "after_cancel": "first failure did not open an incident; second failure opened a new incident",
                "recovery": "same post-maintenance incident resolved",
                "window_id": maintenance_id,
                "first_maintenance_result": down_during_maintenance.get("lastResultAt"),
                "threshold_failure_result": failures_during_maintenance.get("lastResultAt"),
                "first_post_cancel_failure": first_failure_after_cancel.get("lastResultAt"),
                "second_post_cancel_failure": second_failure_after_cancel.get("lastResultAt"),
                "incident_id": maintenance_incident_id,
            },
            "elapsed_seconds": round(time.monotonic() - started, 2),
        }, separators=(",", ":")))
        return 0
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()
        for thread in threads:
            thread.join(timeout=2)


def _matching_incident(api: ApiClient, monitor_id: str, status: str) -> list[dict[str, object]] | None:
    payload = api.request("GET", f"/api/v1/incidents?status={status}&limit=100")[1]
    if not isinstance(payload, list):
        return None
    matched = [row for row in payload if row.get("monitorId") == monitor_id]
    return matched or None


def _matching_delivery(api: ApiClient, channel_id: str, event_type: str, status: str) -> dict[str, object] | None:
    query = urllib.parse.urlencode({"channelId": channel_id, "limit": 100})
    payload = api.request("GET", f"/api/v1/notification-deliveries?{query}")[1]
    if not isinstance(payload, list):
        return None
    return next((row for row in payload if row.get("eventType") == event_type and row.get("status") == status), None)


def _monitor_state(api: ApiClient, monitor_id: str, expected_state: str) -> dict[str, object] | None:
    status, payload = api.request("GET", f"/api/v1/monitors/{monitor_id}")
    if status != 200 or not isinstance(payload, dict) or payload.get("state") != expected_state:
        return None
    return payload


def _latest_result(api: ApiClient, monitor_id: str, expected_status: str, expected_http_status: int) -> bool:
    status, rows = api.request("GET", f"/api/v1/monitors/{monitor_id}/results?limit=1")
    return (
        status == 200 and isinstance(rows, list) and bool(rows)
        and rows[0].get("status") == expected_status
        and rows[0].get("statusCode") == expected_http_status
    )


def _failure_after(api: ApiClient, monitor_id: str, instant: datetime) -> dict[str, object] | None:
    monitor = _monitor_state(api, monitor_id, "DOWN")
    if monitor is None or not isinstance(monitor.get("lastResultAt"), str):
        return None
    try:
        observed = datetime.fromisoformat(monitor["lastResultAt"].replace("Z", "+00:00"))
    except ValueError:
        return None
    if observed <= instant or not _latest_result(api, monitor_id, "DOWN", 503):
        return None
    return monitor


def _incident_by_id(api: ApiClient, incident_id: str, status: str) -> dict[str, object] | None:
    rows = api.request("GET", f"/api/v1/incidents?status={status}&limit=100")[1]
    if not isinstance(rows, list):
        return None
    return next((row for row in rows if row.get("id") == incident_id), None)


def _has_webhook_incident(sink: WebhookServer, event_type: str, incident_id: str) -> bool:
    with sink.lock:
        return any(
            isinstance(payload.get("notification"), dict)
            and payload["notification"].get("eventType") == event_type
            and payload["notification"].get("incidentId") == incident_id
            for payload in sink.received
        )


if __name__ == "__main__":
    raise SystemExit(main())
