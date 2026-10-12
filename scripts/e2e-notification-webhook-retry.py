#!/usr/bin/env python3
"""Verify notification outbox recovery against a private-address HTTP sink.

Run against a fresh local instance configured with
geek-monitor.network.allow-private-targets=true and an isolated database. The
sink must bind to a private, non-loopback IPv4 address assigned to this host.
It starts only after the first real Webhook attempt has failed.
"""

from __future__ import annotations

import argparse
import http.server
import ipaddress
import json
import socket
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from urllib.parse import urlsplit


class WebhookSinkServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int]):
        super().__init__(address, WebhookSinkHandler)
        self.accepted_requests = 0
        self.valid_requests = 0
        self.idempotency_keys: list[str] = []
        self.counter_lock = threading.Lock()


class WebhookSinkHandler(http.server.BaseHTTPRequestHandler):
    server_version = "GeekMonitorLocalWebhookSink/1"

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def do_POST(self) -> None:
        content_length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(min(content_length, 64 * 1024 + 1))
        idempotency_key = self.headers.get("Idempotency-Key", "")
        valid = content_length <= 64 * 1024 and self.path == "/webhook" and bool(idempotency_key)
        if valid:
            try:
                payload = json.loads(body)
                valid = payload.get("idempotencyKey") == idempotency_key and "notification" in payload
            except (json.JSONDecodeError, AttributeError):
                valid = False
        with self.server.counter_lock:  # type: ignore[attr-defined]
            self.server.accepted_requests += 1  # type: ignore[attr-defined]
            if valid:
                self.server.valid_requests += 1  # type: ignore[attr-defined]
                self.server.idempotency_keys.append(idempotency_key)  # type: ignore[attr-defined]
        self.send_response(202 if valid else 400)
        self.send_header("Content-Length", "0")
        self.end_headers()


class ApiClient:
    def __init__(self, base_url: str, email: str, password: str):
        self.base_url = base_url.rstrip("/")
        self.email = email
        self.password = password
        self.cookie: str | None = None

    def request(self, method: str, path: str, body: dict[str, object] | None = None) -> tuple[int, object, str | None]:
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
            return response.status, payload, response.headers.get("Set-Cookie")

    def initialize(self) -> None:
        status, payload, set_cookie = self.request(
            "POST",
            "/api/v1/install/setup",
            {"email": self.email, "password": self.password, "projectName": "Webhook 故障注入验收"},
        )
        if status != 201 or not isinstance(payload, dict) or payload.get("user", {}).get("email") != self.email.lower():
            raise RuntimeError(f"initial setup did not create the requested administrator (HTTP {status})")
        if not set_cookie:
            raise RuntimeError("setup response did not include the session cookie")
        self.cookie = set_cookie.split(";", 1)[0]


def validate_local_sink(address: str, port: int) -> None:
    try:
        parsed = ipaddress.ip_address(address)
    except ValueError as error:
        raise RuntimeError("sink-address must be an IPv4 address assigned to this host") from error
    if parsed.version != 4 or not parsed.is_private or parsed.is_loopback or parsed.is_link_local or parsed.is_reserved:
        raise RuntimeError("sink-address must be a private, non-loopback, non-link-local IPv4 address")
    with socket.socket() as probe:
        probe.settimeout(0.25)
        if probe.connect_ex((address, port)) == 0:
            raise RuntimeError(f"private sink port {port} is already listening; refusing to use an unknown receiver")
    with socket.socket() as probe:
        try:
            probe.bind((address, port))
        except OSError as error:
            raise RuntimeError("sink-address is not locally bindable or the port is unavailable") from error


def deliveries(api: ApiClient, channel_id: str) -> list[dict[str, object]]:
    query = urllib.parse.urlencode({"channelId": channel_id, "limit": 20})
    _, payload, _ = api.request("GET", f"/api/v1/notification-deliveries?{query}")
    return payload if isinstance(payload, list) else []


def wait_for_first_failure(api: ApiClient, channel_id: str, timeout_seconds: float) -> list[dict[str, object]]:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        rows = deliveries(api, channel_id)
        first_attempt = next((row for row in rows if row.get("attemptNumber") == 1), None)
        if first_attempt:
            if first_attempt.get("status") != "RETRYING":
                raise RuntimeError("the first Webhook failure was not recorded as RETRYING")
            return rows
        time.sleep(0.25)
    raise RuntimeError("the first Webhook attempt did not finish within the timeout")


def wait_for_retry_success(api: ApiClient, channel_id: str, timeout_seconds: float) -> list[dict[str, object]]:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        rows = deliveries(api, channel_id)
        by_attempt = {row.get("attemptNumber"): row for row in rows}
        if by_attempt.get(2, {}).get("status") == "DELIVERED":
            return rows
        if by_attempt.get(2, {}).get("status") == "FAILED":
            raise RuntimeError("second Webhook attempt failed instead of recovering")
        time.sleep(0.5)
    return deliveries(api, channel_id)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18087")
    parser.add_argument("--email", default="admin@biliwind.com")
    parser.add_argument("--password", default="admin@biliwind.com")
    parser.add_argument("--sink-address", default="172.16.87.1")
    parser.add_argument("--sink-port", type=int, default=2527)
    parser.add_argument("--first-failure-timeout", type=float, default=25)
    parser.add_argument("--retry-timeout", type=float, default=60)
    args = parser.parse_args()

    base = urlsplit(args.base_url)
    if base.scheme != "http" or base.hostname not in {"127.0.0.1", "localhost", "::1"} or not base.port:
        parser.error("base-url must use HTTP on a loopback address with an explicit port")
    if args.sink_port not in range(1, 65536):
        parser.error("sink-port must be between 1 and 65535")
    validate_local_sink(args.sink_address, args.sink_port)

    api = ApiClient(args.base_url, args.email, args.password)
    api.initialize()
    _, projects, _ = api.request("GET", "/api/v1/projects")
    if not isinstance(projects, list) or not projects:
        raise RuntimeError("initialization did not create a project")
    project_id = str(projects[0]["id"])

    webhook_url = f"http://{args.sink_address}:{args.sink_port}/webhook"
    status, channel, _ = api.request(
        "POST",
        "/api/v1/notification-channels",
        {
            "projectId": project_id,
            "name": f"webhook-retry-e2e-{int(time.time())}",
            "type": "WEBHOOK",
            "webhookUrl": webhook_url,
            "enabled": True,
            "rateLimitPerMinute": 60,
        },
    )
    if status != 201 or not isinstance(channel, dict):
        raise RuntimeError(f"could not create the isolated Webhook channel (HTTP {status})")
    channel_id = str(channel["id"])
    status, _, _ = api.request("POST", f"/api/v1/notification-channels/{channel_id}/test")
    if status != 202:
        raise RuntimeError(f"could not queue the Webhook test (HTTP {status})")

    first_rows = wait_for_first_failure(api, channel_id, args.first_failure_timeout)
    first_attempt = next(row for row in first_rows if row.get("attemptNumber") == 1)
    sink = WebhookSinkServer((args.sink_address, args.sink_port))
    sink_thread = threading.Thread(target=sink.serve_forever, daemon=True)
    sink_thread.start()
    try:
        rows = wait_for_retry_success(api, channel_id, args.retry_timeout)
        second_attempt = next((row for row in rows if row.get("attemptNumber") == 2), None)
        if not second_attempt or second_attempt.get("status") != "DELIVERED":
            raise RuntimeError("notification outbox did not recover on its second Webhook attempt")
        if sink.accepted_requests != 1 or sink.valid_requests != 1 or len(set(sink.idempotency_keys)) != 1:
            raise RuntimeError("the private sink did not accept exactly one valid idempotent Webhook request")
        _, channels, _ = api.request("GET", f"/api/v1/notification-channels?projectId={project_id}")
        current = next((item for item in channels if item.get("id") == channel_id), None)
        if not current or current.get("lastDeliveryStatus") != "DELIVERED":
            raise RuntimeError("the notification channel did not report the recovered DELIVERED state")
        print(json.dumps({
            "initial_attempt": {key: first_attempt.get(key) for key in ("attemptNumber", "status", "errorCode")},
            "retry_attempt": {key: second_attempt.get(key) for key in ("attemptNumber", "status", "httpStatus", "errorCode")},
            "private_sink_accepted_requests": sink.accepted_requests,
            "idempotency_key_present": bool(sink.idempotency_keys[0]),
            "outbox_status": current.get("lastDeliveryStatus"),
        }, separators=(",", ":")))
        return 0
    finally:
        sink.shutdown()
        sink.server_close()
        sink_thread.join(timeout=2)


if __name__ == "__main__":
    raise SystemExit(main())
