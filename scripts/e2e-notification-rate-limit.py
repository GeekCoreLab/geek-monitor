#!/usr/bin/env python3
"""Exercise alert grouping, rate limiting, and burst dispatch on an isolated app.

Requires a fresh local Quarkus instance backed by the dedicated
``geek_monitor_notification_storm`` database, a private host-only sink address,
and ``psql`` access to that database. The script initializes only the disposable
instance, verifies a one-per-minute channel with five synthetic OPENED rows,
then submits 5,000 rows across 100 Webhook channels and checks their delivery.
"""

from __future__ import annotations

import argparse
import http.server
import ipaddress
import json
import os
import re
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from urllib.parse import urlsplit


class StormSinkServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int]):
        super().__init__(address, StormSinkHandler)
        self.requests: list[dict[str, object]] = []
        self.counter_lock = threading.Lock()


class StormSinkHandler(http.server.BaseHTTPRequestHandler):
    server_version = "GeekMonitorNotificationStormSink/1"

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def do_POST(self) -> None:
        content_length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(min(content_length, 256 * 1024 + 1))
        key = self.headers.get("Idempotency-Key", "")
        valid = content_length <= 256 * 1024 and self.path == "/webhook" and bool(key)
        group_count = 0
        if valid:
            try:
                payload = json.loads(body)
                notification = payload.get("notification", {})
                group_count = int(notification.get("groupCount", 0))
                valid = (
                    payload.get("idempotencyKey") == key
                    and notification.get("eventType") == "notification.grouped"
                    and group_count > 1
                    and len(notification.get("events", [])) == group_count
                )
            except (ValueError, json.JSONDecodeError, AttributeError, TypeError):
                valid = False
        with self.server.counter_lock:  # type: ignore[attr-defined]
            self.server.requests.append({  # type: ignore[attr-defined]
                "accepted_at": time.monotonic(),
                "valid": valid,
                "group_count": group_count,
                "idempotency_key": key,
            })
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

    def initialize(self) -> str:
        status, payload, set_cookie = self.request(
            "POST",
            "/api/v1/install/setup",
            {"email": self.email, "password": self.password, "projectName": "通知风暴验收"},
        )
        if status != 201 or not isinstance(payload, dict) or payload.get("user", {}).get("email") != self.email.lower():
            raise RuntimeError(f"fresh setup did not create the requested administrator (HTTP {status})")
        if not set_cookie:
            raise RuntimeError("setup response did not include the session cookie")
        self.cookie = set_cookie.split(";", 1)[0]
        status, projects, _ = self.request("GET", "/api/v1/projects")
        if status != 200 or not isinstance(projects, list) or not projects:
            raise RuntimeError("initialization did not create the default project")
        return str(projects[0]["id"])


def validate_local_sink(address: str, port: int) -> None:
    try:
        parsed = ipaddress.ip_address(address)
    except ValueError as error:
        raise RuntimeError("sink-address must be a private IPv4 address assigned to this host") from error
    if parsed.version != 4 or not parsed.is_private or parsed.is_loopback or parsed.is_link_local or parsed.is_reserved:
        raise RuntimeError("sink-address must be private, non-loopback, and non-link-local")
    with socket.socket() as probe:
        probe.settimeout(0.25)
        if probe.connect_ex((address, port)) == 0:
            raise RuntimeError(f"sink port {port} is already listening; refusing to use an unknown receiver")
    with socket.socket() as probe:
        try:
            probe.bind((address, port))
        except OSError as error:
            raise RuntimeError("sink-address is not locally bindable or the port is unavailable") from error


def queue_synthetic_event_groups(
    args: argparse.Namespace,
    groups: list[tuple[str, int, str]],
) -> dict[str, list[str]]:
    values = []
    ids_by_prefix: dict[str, list[str]] = {}
    for channel_id, count, title_prefix in groups:
        if not re.fullmatch(r"[0-9a-f-]{36}", channel_id) or not 1 <= count <= 50:
            raise RuntimeError("invalid channel identifier or synthetic group size")
        ids: list[str] = []
        for index in range(1, count + 1):
            event_id = str(uuid.uuid4())
            ids.append(event_id)
            idempotency_key = f"storm:{channel_id}:{event_id}"
            payload = json.dumps({
                "eventId": event_id,
                "eventType": "incident.opened",
                "title": f"{title_prefix} {index}",
                "message": "Synthetic alert-storm acceptance event.",
            }, separators=(",", ":"))
            sql_payload = payload.replace("'", "''")
            values.append(
                f"('{event_id}'::uuid, '{channel_id}'::uuid, 'OPENED', '{idempotency_key}', "
                f"'{sql_payload}'::jsonb, now())"
            )
        ids_by_prefix[channel_id] = ids
    sql = (
        "INSERT INTO notification_outbox(id, channel_id, event_type, idempotency_key, payload, next_attempt_at) VALUES "
        + ",".join(values)
    )
    environment = os.environ.copy()
    environment.update({
        "PGHOST": args.db_host,
        "PGPORT": str(args.db_port),
        "PGDATABASE": args.db_name,
        "PGUSER": args.db_user,
    })
    result = subprocess.run(
        ["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-f", "-"],
        env=environment,
        input=sql,
        check=False,
        capture_output=True,
        text=True,
        timeout=15,
    )
    if result.returncode != 0:
        raise RuntimeError(f"could not queue synthetic events in the dedicated test database: {result.stderr.strip()}")
    return ids_by_prefix


def scalar_query(args: argparse.Namespace, sql: str) -> str:
    environment = os.environ.copy()
    environment.update({
        "PGHOST": args.db_host,
        "PGPORT": str(args.db_port),
        "PGDATABASE": args.db_name,
        "PGUSER": args.db_user,
    })
    result = subprocess.run(
        ["psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", sql],
        env=environment,
        check=False,
        capture_output=True,
        text=True,
        timeout=15,
    )
    if result.returncode != 0:
        raise RuntimeError(f"could not inspect the dedicated test database: {result.stderr.strip()}")
    return result.stdout.strip()


def wait_for_request_count(sink: StormSinkServer, count: int, timeout_seconds: float) -> list[dict[str, object]]:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        with sink.counter_lock:
            rows = list(sink.requests)
        if len(rows) >= count:
            return rows
        time.sleep(0.2)
    with sink.counter_lock:
        return list(sink.requests)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18086")
    parser.add_argument("--email", default="admin@biliwind.com")
    parser.add_argument("--password", default="admin@biliwind.com")
    parser.add_argument("--db-host", default="127.0.0.1")
    parser.add_argument("--db-port", type=int, default=55434)
    parser.add_argument("--db-name", default="geek_monitor_notification_storm")
    parser.add_argument("--db-user", default="geek_monitor")
    parser.add_argument("--sink-address", default="172.16.87.1")
    parser.add_argument("--sink-port", type=int, default=2529)
    parser.add_argument("--first-group-timeout", type=float, default=30)
    parser.add_argument("--rate-window-timeout", type=float, default=80)
    args = parser.parse_args()

    base = urlsplit(args.base_url)
    if base.scheme != "http" or base.hostname not in {"127.0.0.1", "localhost", "::1"} or not base.port:
        parser.error("base-url must use HTTP on a loopback address with an explicit port")
    if args.db_name != "geek_monitor_notification_storm":
        parser.error("db-name must be the dedicated geek_monitor_notification_storm database")
    if args.db_host not in {"127.0.0.1", "localhost", "::1"} or args.db_port != 55434:
        parser.error("db-host/db-port must target the local disposable database on 127.0.0.1:55434")
    if not os.environ.get("PGPASSWORD") and not os.environ.get("PGPASSFILE"):
        parser.error("set PGPASSWORD or PGPASSFILE for the disposable test database")
    if args.sink_port not in range(1, 65536):
        parser.error("sink-port must be between 1 and 65535")
    if args.rate_window_timeout < 65:
        parser.error("rate-window-timeout must be at least 65 seconds")
    validate_local_sink(args.sink_address, args.sink_port)

    sink = StormSinkServer((args.sink_address, args.sink_port))
    sink_thread = threading.Thread(target=sink.serve_forever, daemon=True)
    sink_thread.start()
    try:
        api = ApiClient(args.base_url, args.email, args.password)
        project_id = api.initialize()
        status, channel, _ = api.request(
            "POST",
            "/api/v1/notification-channels",
            {
                "projectId": project_id,
                "name": f"notification-storm-{int(time.time())}",
                "type": "WEBHOOK",
                "webhookUrl": f"http://{args.sink_address}:{args.sink_port}/webhook",
                "enabled": True,
                "rateLimitPerMinute": 1,
            },
        )
        if status != 201 or not isinstance(channel, dict):
            raise RuntimeError(f"could not create the isolated rate-limited Webhook channel (HTTP {status})")
        channel_id = str(channel["id"])

        first_ids = queue_synthetic_event_groups(args, [(channel_id, 3, "Initial alert")])[channel_id]
        first_rows = wait_for_request_count(sink, 1, args.first_group_timeout)
        if len(first_rows) != 1 or not first_rows[0]["valid"] or first_rows[0]["group_count"] != 3:
            raise RuntimeError("the first three same-type alerts were not delivered as one valid group")

        second_ids = queue_synthetic_event_groups(args, [(channel_id, 2, "Deferred alert")])[channel_id]
        first_delivery_at = float(first_rows[0]["accepted_at"])
        early_rows = wait_for_request_count(sink, 2, 52)
        if len(early_rows) != 1:
            raise RuntimeError("the per-channel one-per-minute limit allowed a second Webhook too early")
        second_rows = wait_for_request_count(sink, 2, args.rate_window_timeout - 52)
        if len(second_rows) != 2 or not second_rows[1]["valid"] or second_rows[1]["group_count"] != 2:
            raise RuntimeError("the deferred two-alert group was not delivered after the channel rate window")
        gap_seconds = float(second_rows[1]["accepted_at"]) - first_delivery_at
        if gap_seconds < 55:
            raise RuntimeError(f"rate-limited deliveries were only {gap_seconds:.1f}s apart")
        if len({row["idempotency_key"] for row in second_rows}) != 2:
            raise RuntimeError("grouped Webhook deliveries did not use distinct idempotency keys")

        all_ids = first_ids + second_ids
        quoted_ids = ",".join(f"'{event_id}'::uuid" for event_id in all_ids)
        delivered_rows = scalar_query(
            args,
            f"""SELECT count(*) FILTER (WHERE n.status = 'DELIVERED') || ':' ||
                count(*) FILTER (WHERE d.status = 'DELIVERED') || ':' ||
                string_agg(DISTINCT d.group_count::text, ',' ORDER BY d.group_count::text)
                FROM notification_outbox n JOIN notification_delivery d ON d.outbox_id = n.id
                WHERE n.id IN ({quoted_ids})""",
        )
        if delivered_rows != "5:5:2,3":
            raise RuntimeError(f"outbox and delivery history did not preserve both groups: {delivered_rows}")

        storm_channel_ids: list[str] = []
        for index in range(100):
            status, storm_channel, _ = api.request(
                "POST",
                "/api/v1/notification-channels",
                {
                    "projectId": project_id,
                    "name": f"notification-storm-load-{index:03d}",
                    "type": "WEBHOOK",
                    "webhookUrl": f"http://{args.sink_address}:{args.sink_port}/webhook",
                    "enabled": True,
                    "rateLimitPerMinute": 600,
                },
            )
            if status != 201 or not isinstance(storm_channel, dict):
                raise RuntimeError(f"could not create synthetic storm channel {index} (HTTP {status})")
            storm_channel_ids.append(str(storm_channel["id"]))

        storm_started_at = time.monotonic()
        with sink.counter_lock:
            storm_first_request_index = len(sink.requests)
        queue_synthetic_event_groups(
            args,
            [(storm_channel_id, 50, f"Storm channel {index:03d} event") for index, storm_channel_id in enumerate(storm_channel_ids)],
        )
        storm_rows = wait_for_request_count(sink, storm_first_request_index + 100, 90)
        if len(storm_rows) != storm_first_request_index + 100:
            raise RuntimeError(f"only {len(storm_rows) - storm_first_request_index} of 100 storm groups arrived")
        storm_deliveries = storm_rows[storm_first_request_index:]
        if any(not row["valid"] or row["group_count"] != 50 for row in storm_deliveries):
            raise RuntimeError("each 50-event channel burst was not delivered as one valid grouped Webhook")
        if len({row["idempotency_key"] for row in storm_deliveries}) != 100:
            raise RuntimeError("storm groups did not have unique idempotency keys")
        channel_literals = ",".join(f"'{storm_channel_id}'::uuid" for storm_channel_id in storm_channel_ids)
        storm_status_query = f"""SELECT count(*) FILTER (WHERE n.status = 'DELIVERED') || ':' ||
            count(*) FILTER (WHERE d.status = 'DELIVERED') || ':' ||
            string_agg(DISTINCT d.group_count::text, ',' ORDER BY d.group_count::text)
            FROM notification_outbox n JOIN notification_delivery d ON d.outbox_id = n.id
            WHERE n.channel_id IN ({channel_literals})"""
        # The sink records a request before the dispatcher commits its delivery
        # transaction, so wait for durable delivery history after all 100 HTTP
        # requests arrive instead of racing the final database updates.
        status_deadline = time.monotonic() + 30
        storm_statuses = ""
        while time.monotonic() < status_deadline:
            storm_statuses = scalar_query(args, storm_status_query)
            if storm_statuses == "5000:5000:50":
                break
            time.sleep(0.2)
        if storm_statuses != "5000:5000:50":
            raise RuntimeError(f"the database did not preserve all storm events and grouped deliveries: {storm_statuses}")
        storm_elapsed_seconds = time.monotonic() - storm_started_at
        print(json.dumps({
            "synthetic_events": 5,
            "webhook_requests": len(second_rows),
            "group_sizes": [row["group_count"] for row in second_rows],
            "delivery_gap_seconds": round(gap_seconds, 2),
            "database_statuses_group_sizes": delivered_rows,
            "sink_requests_valid": all(bool(row["valid"]) for row in second_rows),
            "storm_events": 5000,
            "storm_grouped_webhooks": len(storm_deliveries),
            "storm_elapsed_seconds": round(storm_elapsed_seconds, 2),
            "storm_database_statuses_group_sizes": storm_statuses,
        }, separators=(",", ":")))
        return 0
    finally:
        sink.shutdown()
        sink.server_close()
        sink_thread.join(timeout=2)


if __name__ == "__main__":
    raise SystemExit(main())
