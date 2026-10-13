#!/usr/bin/env python3
"""Verify scheduler -> incident -> Webhook -> recovery on an isolated local stack.

The API must run on loopback against the disposable
``geek_monitor_incident_lifecycle`` database on 127.0.0.1:55436. The HTTP
probe and Webhook sink bind only to a private host-only address owned by this
machine (172.16.87.1 by default). No external service is contacted.
"""

from __future__ import annotations

import argparse
from collections import Counter
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

SCHEDULED_STORM_MONITOR_COUNT = 100
SCHEDULED_STORM_TARGETS_PER_PORT = 4
SCHEDULED_STORM_PORT_BASE = 28300


class ProbeState:
    def __init__(self) -> None:
        self.healthy = False
        self.active_slow = 0
        self.max_active_slow = 0
        self.lock = threading.Lock()

    def set_healthy(self, healthy: bool) -> None:
        with self.lock:
            self.healthy = healthy

    def is_healthy(self) -> bool:
        with self.lock:
            return self.healthy

    def begin_slow(self) -> None:
        with self.lock:
            self.active_slow += 1
            self.max_active_slow = max(self.max_active_slow, self.active_slow)

    def end_slow(self) -> None:
        with self.lock:
            self.active_slow -= 1

    def slow_snapshot(self) -> tuple[int, int]:
        with self.lock:
            return self.active_slow, self.max_active_slow


class ProbeServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], state: ProbeState, slow_delay_seconds: float = 6.0):
        super().__init__(address, ProbeHandler)
        self.state = state
        self.slow_delay_seconds = slow_delay_seconds


class ProbeHandler(http.server.BaseHTTPRequestHandler):
    server_version = "GeekMonitorLifecycleProbe/1"

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def do_GET(self) -> None:
        if self.path == "/slow":
            self.server.state.begin_slow()  # type: ignore[attr-defined]
            try:
                time.sleep(self.server.slow_delay_seconds)  # type: ignore[attr-defined]
            finally:
                self.server.state.end_slow()  # type: ignore[attr-defined]
            body = b"slow probe healthy"
        elif self.path == "/fast":
            body = b"fast probe healthy"
        elif self.path == "/probe":
            healthy = self.server.state.is_healthy()  # type: ignore[attr-defined]
            body = b"probe healthy" if healthy else b"probe intentionally failing"
            self.send_response(200 if healthy else 503)
        else:
            self.send_error(404)
            return
        if self.path != "/probe":
            self.send_response(200)
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

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, object] | None = None,
        *,
        allow_http_error: bool = False,
    ) -> tuple[int, object]:
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
            if allow_http_error:
                try:
                    payload = json.loads(detail) if detail else {}
                except json.JSONDecodeError:
                    payload = {"body": detail[:300]}
                return error.code, payload
            raise RuntimeError(f"HTTP {error.code} for {method} {path}: {detail}") from error
        with response:
            raw = response.read()
            payload = json.loads(raw) if raw else {}
            if method == "POST" and path in {"/api/v1/install/setup", "/api/v1/auth/login"}:
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
    parser.add_argument("--slow-probe-port", type=int, default=28283)
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
    storm_target_count = (SCHEDULED_STORM_MONITOR_COUNT + SCHEDULED_STORM_TARGETS_PER_PORT - 1) // SCHEDULED_STORM_TARGETS_PER_PORT
    storm_ports = tuple(range(SCHEDULED_STORM_PORT_BASE, SCHEDULED_STORM_PORT_BASE + storm_target_count))
    ports = (args.probe_port, args.webhook_port, args.slow_probe_port, *storm_ports)
    if len(set(ports)) != len(ports) or any(port not in range(1, 65536) for port in ports):
        parser.error("all test ports must be distinct valid ports")
    try:
        for port in ports:
            local_address(args.service_address, port)
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
    slow_probe = ProbeServer((args.service_address, args.slow_probe_port), state)
    sink = WebhookServer((args.service_address, args.webhook_port))
    servers = [probe, slow_probe, sink]
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

        read_only_email = "incident-readonly@example.test"
        read_only_password = "local-readonly-incident-password"
        status, read_only_member = api.request(
            "POST",
            f"/api/v1/projects/{project_id}/members",
            {"email": read_only_email, "password": read_only_password, "role": "READ_ONLY"},
        )
        if status != 201 or not isinstance(read_only_member, dict) or read_only_member.get("role") != "READ_ONLY":
            raise RuntimeError(f"could not create an isolated READ_ONLY member (HTTP {status})")
        read_only_api = ApiClient(args.base_url, read_only_email, read_only_password)
        status, _ = read_only_api.request(
            "POST",
            "/api/v1/auth/login",
            {"email": read_only_email, "password": read_only_password},
        )
        if status != 200 or not read_only_api.cookie:
            raise RuntimeError(f"the isolated READ_ONLY member could not log in (HTTP {status})")
        status, forbidden_ack = read_only_api.request(
            "POST",
            f"/api/v1/incidents/{incident_id}/ack",
            {"note": "read-only members cannot acknowledge incidents"},
            allow_http_error=True,
        )
        if (
            status != 403
            or not isinstance(forbidden_ack, dict)
            or forbidden_ack.get("code") != "READ_ONLY"
        ):
            raise RuntimeError(f"a READ_ONLY member was not forbidden from acknowledging incidents (HTTP {status})")
        if _incident_by_id(api, incident_id, "OPEN") is None:
            raise RuntimeError("a rejected READ_ONLY acknowledgement changed the incident state")

        acknowledgement_note = "已接手排查；确认事件不能改变仍失败的监控状态。"
        status, acknowledged = api.request(
            "POST",
            f"/api/v1/incidents/{incident_id}/ack",
            {"note": acknowledgement_note},
        )
        if (
            status != 200
            or not isinstance(acknowledged, dict)
            or acknowledged.get("status") != "ACKNOWLEDGED"
            or acknowledged.get("acknowledgedBy") != args.email.lower()
            or acknowledged.get("acknowledgementNote") != acknowledgement_note
            or not isinstance(acknowledged.get("acknowledgedAt"), str)
        ):
            raise RuntimeError(f"incident acknowledgement was not persisted for the administrator (HTTP {status})")
        acknowledged_at = datetime.fromisoformat(
            str(acknowledged["acknowledgedAt"]).replace("Z", "+00:00")
        )
        subsequent_failure = wait_until(
            "a later DOWN result after incident acknowledgement",
            args.failure_timeout,
            lambda: _failure_after(api, monitor_id, acknowledged_at),
        )
        acknowledged_incident = _incident_by_id(api, incident_id, "ACKNOWLEDGED")
        if acknowledged_incident is None:
            raise RuntimeError("a repeated failure changed the acknowledged incident status")
        if _matching_incident(api, monitor_id, "OPEN") is not None:
            raise RuntimeError("a repeated failure created a duplicate OPEN incident after acknowledgement")
        if _monitor_state(api, monitor_id, "DOWN") is None or subsequent_failure.get("state") != "DOWN":
            raise RuntimeError("acknowledging an incident incorrectly marked the failed monitor healthy")
        resource_rows = api.request("GET", f"/api/v1/resources?projectId={urllib.parse.quote(project_id)}")[1]
        if not isinstance(resource_rows, list) or not any(
            row.get("id") == resource_id and row.get("healthStatus") == "DOWN"
            for row in resource_rows
        ):
            raise RuntimeError("acknowledging an incident incorrectly marked the critical resource healthy")

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
        if not isinstance(event_rows, list) or not {row.get("eventType") for row in event_rows}.issuperset({"OPENED", "ACKNOWLEDGED", "RESOLVED"}):
            raise RuntimeError("the incident event history is missing OPENED, ACKNOWLEDGED, or RESOLVED")
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

        status, concurrency_resource = api.request(
            "POST",
            "/api/v1/resources",
            {"projectId": project_id, "name": "目标并发隔离目标", "type": "WEBSITE", "environment": "test"},
        )
        if status != 201 or not isinstance(concurrency_resource, dict):
            raise RuntimeError(f"could not create the isolated concurrency-test resource (HTTP {status})")
        concurrency_resource_id = str(concurrency_resource["id"])
        slow_monitor_ids: list[str] = []
        slow_target_url = f"http://{args.service_address}:{args.slow_probe_port}/slow"
        for index in range(20):
            slow_monitor_id = _create_http_monitor(
                api,
                concurrency_resource_id,
                f"慢目标并发检查-{index + 1}",
                slow_target_url,
                interval_seconds=60,
                timeout_seconds=10,
            )
            slow_monitor_ids.append(slow_monitor_id)
        wait_until(
            "four slow checks admitted for one target",
            12,
            lambda: state.slow_snapshot() if state.slow_snapshot()[0] >= 4 else None,
        )

        fast_started = time.monotonic()
        fast_monitor_id = _create_http_monitor(
            api,
            concurrency_resource_id,
            "慢目标运行期间的快速目标检查",
            f"http://{args.service_address}:{args.probe_port}/fast",
            interval_seconds=60,
            timeout_seconds=3,
        )
        fast_result = wait_until(
            "fast target result while slow checks remain active",
            4.5,
            lambda: _monitor_state(api, fast_monitor_id, "UP")
            if _latest_result(api, fast_monitor_id, "UP", 200) else None,
        )
        fast_elapsed = time.monotonic() - fast_started
        active_slow, _ = state.slow_snapshot()
        if active_slow < 4:
            raise RuntimeError("the fast target completed after the slow-target load had already cleared")

        limit_result = wait_until(
            "persisted TARGET_CONCURRENCY_LIMIT result",
            10,
            lambda: next((
                (monitor_id, row)
                for monitor_id in slow_monitor_ids
                for row in _monitor_results(api, monitor_id)
                if row.get("errorCode") == "TARGET_CONCURRENCY_LIMIT"
            ), None),
        )
        active_snapshot = wait_until(
            "slow target requests to finish",
            10,
            lambda: state.slow_snapshot() if state.slow_snapshot()[0] == 0 else None,
        )
        if active_snapshot[1] != 4:
            raise RuntimeError(f"the slow target exceeded or failed to use its concurrency limit: max={active_snapshot[1]}")
        slow_states = wait_until(
            "all slow-target outcomes to be persisted",
            10,
            lambda: _monitor_states(api, concurrency_resource_id, set(slow_monitor_ids)),
        )
        slow_results = {
            monitor_id: _monitor_results(api, monitor_id)[0]
            for monitor_id in slow_monitor_ids
        }
        accepted_slow = [
            monitor_id for monitor_id, result in slow_results.items()
            if result.get("status") == "UP" and slow_states[monitor_id].get("state") == "UP"
        ]
        limited_slow = [
            monitor_id for monitor_id, result in slow_results.items()
            if result.get("status") == "UNKNOWN"
            and result.get("errorCode") == "TARGET_CONCURRENCY_LIMIT"
            and slow_states[monitor_id].get("state") == "UNKNOWN"
        ]
        if len(accepted_slow) != 4 or len(limited_slow) != len(slow_monitor_ids) - 4:
            raise RuntimeError(
                f"the slow-target admission results were unexpected: accepted={len(accepted_slow)}, limited={len(limited_slow)}"
            )

        released_monitor_id = _create_http_monitor(
            api,
            concurrency_resource_id,
            "慢目标并发名额释放检查",
            slow_target_url,
            interval_seconds=60,
            timeout_seconds=10,
        )
        wait_until(
            "slow target check after a permit was released",
            10,
            lambda: _monitor_state(api, released_monitor_id, "UP")
            if _latest_result(api, released_monitor_id, "UP", 200) else None,
        )
        scheduled_alert_storm = _run_scheduled_alert_storm(
            api,
            state,
            sink,
            args.service_address,
            servers,
            threads,
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
            "incident": {
                "id": incident_id,
                "category": incident.get("category"),
                "acknowledgement": {
                    "status": acknowledged.get("status"),
                    "acknowledgedBy": acknowledged.get("acknowledgedBy"),
                    "note": acknowledged.get("acknowledgementNote"),
                    "readOnlyRequest": "403/READ_ONLY",
                    "monitorStateAfterAck": subsequent_failure.get("state"),
                    "resourceStateAfterAck": "DOWN",
                },
                "events": ["OPENED", "ACKNOWLEDGED", "RESOLVED"],
            },
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
            "target_concurrency": {
                "configured_limit": 4,
                "slow_target_monitors": len(slow_monitor_ids),
                "max_simultaneous_slow_requests": active_snapshot[1],
                "fast_target_elapsed_seconds": round(fast_elapsed, 3),
                "fast_target_state": fast_result.get("state"),
                "limit_result": limit_result[1].get("errorCode"),
                "released_permit_check": "UP",
            },
            "scheduler_generated_alert_storm": scheduled_alert_storm,
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


def _create_http_monitor(
    api: ApiClient,
    resource_id: str,
    name: str,
    target_url: str,
    *,
    interval_seconds: int,
    timeout_seconds: int,
    failure_threshold: int = 20,
    critical: bool = False,
) -> str:
    status, monitor = api.request(
        "POST",
        "/api/v1/monitors",
        {
            "resourceId": resource_id,
            "name": name,
            "type": "HTTP",
            "targetUrl": target_url,
            "method": "GET",
            "intervalSeconds": interval_seconds,
            "timeoutSeconds": timeout_seconds,
            "failureThreshold": failure_threshold,
            "recoveryThreshold": 1,
            "expectedStatusCodes": [200],
            "critical": critical,
            "enabled": True,
        },
    )
    if status != 201 or not isinstance(monitor, dict):
        raise RuntimeError(f"could not create test monitor '{name}' (HTTP {status})")
    return str(monitor["id"])


def _run_scheduled_alert_storm(
    api: ApiClient,
    state: ProbeState,
    sink: WebhookServer,
    service_address: str,
    servers: list[http.server.ThreadingHTTPServer],
    threads: list[threading.Thread],
) -> dict[str, object]:
    status, project = api.request(
        "POST",
        "/api/v1/projects",
        {
            "name": f"调度告警风暴验收-{int(time.time())}",
            "description": "隔离验证真实调度器生成事件和分组 Webhook 投递。",
        },
    )
    if status != 201 or not isinstance(project, dict):
        raise RuntimeError(f"could not create an isolated alert-storm project (HTTP {status})")
    project_id = str(project["id"])
    status, channel = api.request(
        "POST",
        "/api/v1/notification-channels",
        {
            "projectId": project_id,
            "name": f"scheduler-alert-storm-{int(time.time())}",
            "type": "WEBHOOK",
            "webhookUrl": f"http://{service_address}:{sink.server_address[1]}/webhook",
            "enabled": True,
            "rateLimitPerMinute": 600,
        },
    )
    if status != 201 or not isinstance(channel, dict):
        raise RuntimeError(f"could not create the isolated alert-storm Webhook (HTTP {status})")
    channel_id = str(channel["id"])

    target_count = (SCHEDULED_STORM_MONITOR_COUNT + SCHEDULED_STORM_TARGETS_PER_PORT - 1) // SCHEDULED_STORM_TARGETS_PER_PORT
    storm_ports = range(SCHEDULED_STORM_PORT_BASE, SCHEDULED_STORM_PORT_BASE + target_count)
    for port in storm_ports:
        probe = ProbeServer((service_address, port), state)
        thread = threading.Thread(target=probe.serve_forever, daemon=True)
        servers.append(probe)
        threads.append(thread)
        thread.start()

    with sink.lock:
        first_storm_request_index = len(sink.received)
    state.set_healthy(False)
    started = time.monotonic()
    monitor_ids: list[str] = []
    for target_index, port in enumerate(storm_ports):
        status, resource = api.request(
            "POST",
            "/api/v1/resources",
            {
                "projectId": project_id,
                "name": f"scheduler-storm-target-{target_index + 1:02d}",
                "type": "WEBSITE",
                "environment": "acceptance",
            },
        )
        if status != 201 or not isinstance(resource, dict):
            raise RuntimeError(f"could not create alert-storm resource {target_index + 1} (HTTP {status})")
        resource_id = str(resource["id"])
        monitors_for_target = min(
            SCHEDULED_STORM_TARGETS_PER_PORT,
            SCHEDULED_STORM_MONITOR_COUNT - len(monitor_ids),
        )
        for _ in range(monitors_for_target):
            monitor_ids.append(_create_http_monitor(
                api,
                resource_id,
                f"scheduler-storm-{len(monitor_ids) + 1:03d}",
                f"http://{service_address}:{port}/probe",
                interval_seconds=60,
                timeout_seconds=3,
                failure_threshold=1,
                critical=True,
            ))

    monitor_id_set = set(monitor_ids)

    def open_storm_incidents() -> list[dict[str, object]] | None:
        status, incidents = api.request("GET", "/api/v1/incidents?status=OPEN&limit=500")
        if status != 200 or not isinstance(incidents, list):
            return None
        matched = [row for row in incidents if row.get("monitorId") in monitor_id_set]
        if len(matched) != SCHEDULED_STORM_MONITOR_COUNT:
            return None
        return matched

    storm_incidents = wait_until(
        "100 scheduler-generated OPEN incidents",
        90,
        open_storm_incidents,
    )
    storm_incident_ids = {str(row["id"]) for row in storm_incidents}
    if len(storm_incident_ids) != SCHEDULED_STORM_MONITOR_COUNT or {
        str(row.get("monitorId")) for row in storm_incidents
    } != monitor_id_set:
        raise RuntimeError("the scheduler did not create exactly one active incident for each failed monitor")

    status, monitors = api.request("GET", "/api/v1/monitors")
    observed_monitors = {
        str(row.get("id")): row
        for row in monitors
        if isinstance(row, dict) and str(row.get("id")) in monitor_id_set
    } if status == 200 and isinstance(monitors, list) else {}
    if set(observed_monitors) != monitor_id_set or any(
        row.get("state") != "DOWN" or not row.get("lastResultAt")
        for row in observed_monitors.values()
    ):
        raise RuntimeError("the scheduler did not persist DOWN state and a result for every storm monitor")
    for monitor_id in monitor_ids:
        status, results = api.request("GET", f"/api/v1/monitors/{monitor_id}/results?limit=1")
        if (
            status != 200 or not isinstance(results, list) or not results
            or results[0].get("status") != "DOWN" or results[0].get("statusCode") != 503
        ):
            raise RuntimeError(f"alert-storm monitor {monitor_id} did not persist a scheduled DOWN/503 result")

    def delivered_storm_rows() -> list[dict[str, object]] | None:
        query = urllib.parse.urlencode({"channelId": channel_id, "limit": 500})
        status, rows = api.request("GET", f"/api/v1/notification-deliveries?{query}")
        if status != 200 or not isinstance(rows, list):
            return None
        delivered = [
            row for row in rows
            if row.get("status") == "DELIVERED" and row.get("incidentId") in storm_incident_ids
        ]
        counts = Counter(str(row.get("incidentId")) for row in delivered)
        if set(counts) != storm_incident_ids or any(count != 1 for count in counts.values()):
            return None
        return delivered

    storm_deliveries = wait_until(
        "all scheduler-generated alert notifications delivered",
        120,
        delivered_storm_rows,
    )
    with sink.lock:
        webhook_payloads = list(sink.received[first_storm_request_index:])
    notified_incident_ids: list[str] = []
    grouped_requests = 0
    for payload in webhook_payloads:
        notification = payload.get("notification")
        if not isinstance(notification, dict):
            raise RuntimeError("the alert-storm Webhook payload did not contain a notification")
        if notification.get("eventType") == "notification.grouped":
            events = notification.get("events")
            if not isinstance(events, list) or notification.get("groupCount") != len(events) or len(events) < 2:
                raise RuntimeError("the alert-storm Webhook group metadata did not match its events")
            grouped_requests += 1
            grouped_events = events
        else:
            grouped_events = [notification]
        for event in grouped_events:
            if not isinstance(event, dict) or event.get("eventType") != "incident.opened":
                raise RuntimeError("the alert-storm Webhook contained a non-OPENED event")
            notified_incident_ids.append(str(event.get("incidentId")))

    notification_counts = Counter(notified_incident_ids)
    if set(notification_counts) != storm_incident_ids or any(count != 1 for count in notification_counts.values()):
        raise RuntimeError(
            f"the grouped Webhook did not deliver every scheduler-created incident exactly once: "
            f"received={len(notified_incident_ids)} unique={len(notification_counts)}"
        )
    if grouped_requests == 0:
        raise RuntimeError("the scheduler-generated alert burst did not exercise grouped Webhook delivery")
    expected_grouped_requests = (SCHEDULED_STORM_MONITOR_COUNT + 49) // 50
    if grouped_requests != expected_grouped_requests:
        raise RuntimeError(
            f"expected {expected_grouped_requests} Webhook groups for the scheduled alert burst, got {grouped_requests}"
        )
    group_counts = sorted({int(row.get("groupCount") or 1) for row in storm_deliveries})
    group_distribution = Counter(int(row.get("groupCount") or 1) for row in storm_deliveries)
    if group_distribution != Counter({50: SCHEDULED_STORM_MONITOR_COUNT}):
        raise RuntimeError(f"the scheduled alert Webhook delivery recorded invalid group sizes: {group_counts}")

    return {
        "failed_monitors": len(monitor_ids),
        "open_incidents": len(storm_incidents),
        "delivered_incident_notifications": len(storm_deliveries),
        "grouped_webhook_requests": grouped_requests,
        "delivery_group_sizes": group_counts,
        "elapsed_seconds": round(time.monotonic() - started, 2),
        "delivery_result": "every scheduler-created OPENED event delivered exactly once",
    }


def _monitor_results(api: ApiClient, monitor_id: str) -> list[dict[str, object]]:
    status, rows = api.request("GET", f"/api/v1/monitors/{monitor_id}/results?limit=20")
    if status != 200 or not isinstance(rows, list):
        return []
    return rows


def _monitor_states(
    api: ApiClient,
    resource_id: str,
    expected_monitor_ids: set[str],
) -> dict[str, dict[str, object]] | None:
    status, rows = api.request("GET", f"/api/v1/monitors?resourceId={urllib.parse.quote(resource_id)}")
    if status != 200 or not isinstance(rows, list):
        return None
    matched = {
        str(row.get("id")): row
        for row in rows
        if isinstance(row, dict) and str(row.get("id")) in expected_monitor_ids
    }
    if set(matched) != expected_monitor_ids or any(not row.get("lastResultAt") for row in matched.values()):
        return None
    return matched


if __name__ == "__main__":
    raise SystemExit(main())
