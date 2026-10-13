#!/usr/bin/env python3
"""Verify notification outbox retry against a loopback SMTP sink.

Run against a fresh local instance configured with SMTP_HOST=127.0.0.1,
SMTP_PORT matching --smtp-port, SMTP_START_TLS=DISABLED, and SMTP_MOCK=false.
The SMTP sink starts only after the first real delivery attempt has failed.
"""

from __future__ import annotations

import argparse
import json
import socket
import socketserver
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from urllib.parse import urlsplit


class SmtpSinkServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address: tuple[str, int]):
        super().__init__(address, SmtpSinkHandler)
        self.accepted_messages = 0
        self.connection_count = 0
        self.commands: list[str] = []
        self.counter_lock = threading.Lock()


class SmtpSinkHandler(socketserver.StreamRequestHandler):
    def respond(self, data: bytes) -> None:
        self.wfile.write(data)
        self.wfile.flush()

    def handle(self) -> None:
        with self.server.counter_lock:  # type: ignore[attr-defined]
            self.server.connection_count += 1  # type: ignore[attr-defined]
        self.respond(b"220 geek-monitor-test-sink ESMTP\r\n")
        while line := self.rfile.readline():
            command = line.decode("ascii", errors="replace").strip().split(" ", 1)[0].upper()
            with self.server.counter_lock:  # type: ignore[attr-defined]
                self.server.commands.append(command)  # type: ignore[attr-defined]
            if command == "EHLO":
                self.respond(b"250-localhost\r\n250-PIPELINING\r\n250 8BITMIME\r\n")
            elif command in {"HELO", "MAIL", "RCPT", "RSET", "NOOP"}:
                self.respond(b"250 OK\r\n")
            elif command == "DATA":
                self.respond(b"354 End data with <CR><LF>.<CR><LF>\r\n")
                while self.rfile.readline() not in {b".\r\n", b".\n", b""}:
                    pass
                with self.server.counter_lock:  # type: ignore[attr-defined]
                    self.server.accepted_messages += 1  # type: ignore[attr-defined]
                self.respond(b"250 Message accepted by local test sink\r\n")
            elif command == "QUIT":
                self.respond(b"221 Bye\r\n")
                return
            elif command == "STARTTLS":
                self.respond(b"454 TLS is disabled on the local test sink\r\n")
            else:
                self.respond(b"502 Command not supported by local test sink\r\n")


class ApiClient:
    def __init__(self, base_url: str, email: str, password: str):
        self.base_url = base_url.rstrip("/")
        self.email = email
        self.password = password
        self.cookie: str | None = None

    def request(self, method: str, path: str, body: dict[str, object] | None = None) -> tuple[int, dict[str, object], str | None]:
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
            {"email": self.email, "password": self.password, "projectName": "SMTP 故障注入验收"},
        )
        if status != 201 or payload.get("user", {}).get("email") != self.email.lower():
            raise RuntimeError(f"initial setup did not create the requested administrator (HTTP {status})")
        if not set_cookie:
            raise RuntimeError("setup response did not include the session cookie")
        self.cookie = set_cookie.split(";", 1)[0]


def ensure_sink_port_is_closed(port: int) -> None:
    with socket.socket() as connection:
        connection.settimeout(0.25)
        if connection.connect_ex(("127.0.0.1", port)) == 0:
            raise RuntimeError(f"loopback SMTP port {port} is already listening; refusing to use an unknown sink")


def wait_for_deliveries(api: ApiClient, channel_id: str, timeout_seconds: float) -> list[dict[str, object]]:
    deadline = time.monotonic() + timeout_seconds
    query = urllib.parse.urlencode({"channelId": channel_id, "limit": 20})
    while time.monotonic() < deadline:
        _, payload, _ = api.request("GET", f"/api/v1/notification-deliveries?{query}")
        rows = payload if isinstance(payload, list) else []
        if rows:
            return rows
        time.sleep(0.25)
    return []


def wait_for_retry_success(api: ApiClient, channel_id: str, timeout_seconds: float) -> list[dict[str, object]]:
    deadline = time.monotonic() + timeout_seconds
    query = urllib.parse.urlencode({"channelId": channel_id, "limit": 20})
    while time.monotonic() < deadline:
        _, payload, _ = api.request("GET", f"/api/v1/notification-deliveries?{query}")
        rows = payload if isinstance(payload, list) else []
        by_attempt = {row.get("attemptNumber"): row for row in rows}
        if by_attempt.get(2, {}).get("status") == "DELIVERED":
            return rows
        if by_attempt.get(2, {}).get("status") == "FAILED":
            raise RuntimeError("second notification attempt failed instead of recovering")
        time.sleep(0.5)
    return []


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18086")
    parser.add_argument("--email", default="admin@biliwind.com")
    parser.add_argument("--password", default="admin@biliwind.com")
    parser.add_argument("--smtp-port", type=int, default=2526)
    parser.add_argument("--first-failure-timeout", type=float, default=25)
    parser.add_argument("--retry-timeout", type=float, default=60)
    args = parser.parse_args()

    base = urlsplit(args.base_url)
    if base.scheme != "http" or base.hostname not in {"127.0.0.1", "localhost", "::1"} or not base.port:
        parser.error("base-url must use HTTP on a loopback address with an explicit port")
    if args.smtp_port not in range(1, 65536):
        parser.error("smtp-port must be between 1 and 65535")
    ensure_sink_port_is_closed(args.smtp_port)

    api = ApiClient(args.base_url, args.email, args.password)
    api.initialize()
    _, projects, _ = api.request("GET", "/api/v1/projects")
    if not isinstance(projects, list) or not projects:
        raise RuntimeError("initialization did not create a project")
    project_id = str(projects[0]["id"])

    channel_name = f"smtp-retry-e2e-{int(time.time())}"
    status, channel, _ = api.request(
        "POST",
        "/api/v1/notification-channels",
        {
            "projectId": project_id,
            "name": channel_name,
            "type": "EMAIL",
            "emailRecipients": [args.email],
            "enabled": True,
            "rateLimitPerMinute": 60,
        },
    )
    if status != 201:
        raise RuntimeError(f"could not create the isolated email channel (HTTP {status})")
    channel_id = str(channel["id"])
    status, test_request, _ = api.request("POST", f"/api/v1/notification-channels/{channel_id}/test")
    if status != 202:
        raise RuntimeError(f"could not queue the email test (HTTP {status})")

    deliveries = wait_for_deliveries(api, channel_id, args.first_failure_timeout)
    first_attempt = next((row for row in deliveries if row.get("attemptNumber") == 1), None)
    if not first_attempt or first_attempt.get("status") != "RETRYING":
        raise RuntimeError("the first SMTP failure was not recorded as RETRYING")

    sink = SmtpSinkServer(("127.0.0.1", args.smtp_port))
    sink_thread = threading.Thread(target=sink.serve_forever, daemon=True)
    sink_thread.start()
    try:
        deliveries = wait_for_retry_success(api, channel_id, args.retry_timeout)
        second_attempt = next((row for row in deliveries if row.get("attemptNumber") == 2), None)
        if not second_attempt or second_attempt.get("status") != "DELIVERED":
            print(json.dumps({
                "smtp_sink_connections": sink.connection_count,
                "smtp_sink_commands": sink.commands,
                "smtp_sink_accepted_messages": sink.accepted_messages,
                "attempts": [{key: row.get(key) for key in ("attemptNumber", "status", "errorCode")} for row in deliveries],
            }, separators=(",", ":")), flush=True)
            raise RuntimeError("notification outbox did not recover on its second attempt")
        if sink.accepted_messages != 1:
            raise RuntimeError(f"local SMTP sink accepted {sink.accepted_messages} messages; expected exactly one")
        _, channels, _ = api.request("GET", f"/api/v1/notification-channels?projectId={project_id}")
        current = next((item for item in channels if item.get("id") == channel_id), None)
        if not current or current.get("lastDeliveryStatus") != "DELIVERED":
            raise RuntimeError("the notification channel did not report the recovered DELIVERED state")
        print(json.dumps({
            "initial_attempt": {key: first_attempt.get(key) for key in ("attemptNumber", "status", "errorCode")},
            "retry_attempt": {key: second_attempt.get(key) for key in ("attemptNumber", "status", "errorCode")},
            "smtp_sink_accepted_messages": sink.accepted_messages,
            "outbox_status": current.get("lastDeliveryStatus"),
        }, separators=(",", ":")))
        return 0
    finally:
        sink.shutdown()
        sink.server_close()
        sink_thread.join(timeout=2)


if __name__ == "__main__":
    raise SystemExit(main())
