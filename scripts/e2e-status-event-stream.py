#!/usr/bin/env python3
"""Verify SSE authentication, project filtering, cursor replay, and live updates.

Run only against a fresh local JVM instance backed by the disposable
``geek_monitor_status_stream`` database on 127.0.0.1:55435. The script creates
synthetic monitor rows directly in that database and never touches the dev or
Agent soak databases.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from urllib.parse import urlsplit


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
            return error.code, error.read().decode("utf-8", errors="replace"), None
        with response:
            raw = response.read()
            payload = json.loads(raw) if raw else {}
            return response.status, payload, response.headers.get("Set-Cookie")

    def initialize(self) -> str:
        status, payload, set_cookie = self.request(
            "POST",
            "/api/v1/install/setup",
            {"email": self.email, "password": self.password, "projectName": "SSE 权限验收"},
        )
        if status != 201 or not isinstance(payload, dict):
            raise RuntimeError(f"fresh setup failed with HTTP {status}")
        if not set_cookie:
            raise RuntimeError("setup response did not include the session cookie")
        self.cookie = set_cookie.split(";", 1)[0]
        status, projects, _ = self.request("GET", "/api/v1/projects")
        if status != 200 or not isinstance(projects, list) or not projects:
            raise RuntimeError("initialization did not create its default project")
        return str(projects[0]["id"])


def psql(args: argparse.Namespace, sql: str) -> str:
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
        raise RuntimeError(f"synthetic SSE fixture failed: {result.stderr.strip()}")
    return result.stdout.strip()


def seed_fixtures(args: argparse.Namespace, allowed_project_id: str) -> tuple[str, str, str, str]:
    hidden_project_id, allowed_resource_id, hidden_resource_id, allowed_monitor_id, hidden_monitor_id = [
        str(uuid.uuid4()) for _ in range(5)
    ]
    sql = f"""
        INSERT INTO project(id,name) VALUES ('{hidden_project_id}','SSE 隔离项目');
        INSERT INTO monitored_resource(id,project_id,name,type)
            VALUES ('{allowed_resource_id}','{allowed_project_id}','授权资源','WEBSITE');
        INSERT INTO monitored_resource(id,project_id,name,type)
            VALUES ('{hidden_resource_id}','{hidden_project_id}','隔离资源','WEBSITE');
        INSERT INTO monitor(id,resource_id,name,type,config,interval_seconds,timeout_seconds,enabled)
            VALUES ('{allowed_monitor_id}','{allowed_resource_id}','授权检查','HTTP','{{}}'::jsonb,60,5,FALSE);
        INSERT INTO monitor(id,resource_id,name,type,config,interval_seconds,timeout_seconds,enabled)
            VALUES ('{hidden_monitor_id}','{hidden_resource_id}','隔离检查','HTTP','{{}}'::jsonb,60,5,FALSE);
    """
    psql(args, sql)
    return hidden_project_id, allowed_resource_id, hidden_resource_id, allowed_monitor_id


def open_stream(base_url: str, cookie: str | None = None, last_event_id: str | None = None):
    request = urllib.request.Request(
        f"{base_url}/api/v1/events/stream",
        headers={"Accept": "text/event-stream"},
    )
    if cookie:
        request.add_header("Cookie", cookie)
    if last_event_id:
        request.add_header("Last-Event-ID", last_event_id)
    return urllib.request.urlopen(request, timeout=12)


def read_event(response, timeout_seconds: float = 10) -> dict[str, object]:
    socket = response.fp.raw._sock
    socket.settimeout(timeout_seconds)
    event: dict[str, object] = {"data_lines": []}
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        line = response.readline().decode("utf-8", errors="replace").rstrip("\r\n")
        if not line:
            if event.get("data_lines"):
                event["data"] = json.loads("\n".join(event.pop("data_lines")))
                return event
            continue
        if line.startswith(":"):
            continue
        field, _, value = line.partition(":")
        value = value.removeprefix(" ")
        if field == "data":
            event["data_lines"].append(value)  # type: ignore[union-attr]
        elif field in {"id", "event"}:
            event[field] = value
    raise TimeoutError("no SSE data event arrived before the deadline")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18088")
    parser.add_argument("--email", default="admin@biliwind.com")
    parser.add_argument("--password", default="admin@biliwind.com")
    parser.add_argument("--db-host", default="127.0.0.1")
    parser.add_argument("--db-port", type=int, default=55435)
    parser.add_argument("--db-name", default="geek_monitor_status_stream")
    parser.add_argument("--db-user", default="geek_monitor")
    args = parser.parse_args()

    base = urlsplit(args.base_url)
    if base.scheme != "http" or base.hostname not in {"127.0.0.1", "localhost", "::1"} or not base.port:
        parser.error("base-url must use HTTP on a loopback address with an explicit port")
    if args.db_host not in {"127.0.0.1", "localhost", "::1"} or args.db_port != 55435:
        parser.error("db-host/db-port must target the local disposable database on 127.0.0.1:55435")
    if args.db_name != "geek_monitor_status_stream":
        parser.error("db-name must be the dedicated geek_monitor_status_stream database")
    if not os.environ.get("PGPASSWORD") and not os.environ.get("PGPASSFILE"):
        parser.error("set PGPASSWORD or PGPASSFILE for the disposable test database")

    api = ApiClient(args.base_url, args.email, args.password)
    project_id = api.initialize()
    hidden_project, allowed_resource, hidden_resource, allowed_monitor = seed_fixtures(args, project_id)

    try:
        open_stream(args.base_url).close()
        raise RuntimeError("unauthenticated SSE connection unexpectedly succeeded")
    except urllib.error.HTTPError as error:
        if error.code != 401:
            raise RuntimeError(f"unauthenticated SSE request returned HTTP {error.code}") from error

    hidden_url = f"{args.base_url}/api/v1/events/stream?projectId={urllib.parse.quote(hidden_project)}"
    try:
        request = urllib.request.Request(hidden_url, headers={"Cookie": api.cookie or ""})
        urllib.request.urlopen(request, timeout=12).close()
        raise RuntimeError("an unassigned project was accepted as the SSE scope")
    except urllib.error.HTTPError as error:
        if error.code != 403:
            raise RuntimeError(f"unassigned project SSE filter returned HTTP {error.code}") from error

    response = open_stream(args.base_url, api.cookie)
    if not response.headers.get("Content-Type", "").startswith("text/event-stream"):
        raise RuntimeError("authorized stream did not return text/event-stream")
    if response.headers.get("Cache-Control") != "no-cache, no-transform":
        raise RuntimeError("authorized SSE response is missing its no-cache header")
    psql(args,
        f"""INSERT INTO check_execution(id,monitor_id,scheduled_at,started_at,finished_at,result_status,error_code,interval_seconds)
            VALUES ('{uuid.uuid4()}','{allowed_monitor}',now(),now(),now(),'DOWN','SSE_TEST',60),
                   ('{uuid.uuid4()}','{_monitor_for_resource(args, hidden_resource)}',now(),now(),now(),'DOWN','SSE_HIDDEN_TEST',60)""",
    )
    event = read_event(response)
    response.close()
    if event.get("event") != "monitor.status" or event.get("data", {}).get("resourceId") != allowed_resource:
        raise RuntimeError("the stream did not emit the authorized resource status first")
    if event.get("data", {}).get("errorCode") != "SSE_TEST":
        raise RuntimeError("the status event payload was incomplete")

    replay = open_stream(args.base_url, api.cookie, str(event["id"]))
    replay_resource = str(uuid.uuid4())
    psql(args,
        f"""INSERT INTO monitored_resource(id,project_id,name,type)
                VALUES ('{replay_resource}','{project_id}','重连资源','WEBSITE');
            INSERT INTO monitor(id,resource_id,name,type,config,interval_seconds,timeout_seconds,enabled)
                VALUES ('{uuid.uuid4()}','{replay_resource}','重连检查','HTTP','{{}}'::jsonb,60,5,FALSE);
            INSERT INTO check_execution(id,monitor_id,scheduled_at,started_at,finished_at,result_status,error_code,interval_seconds)
                SELECT '{uuid.uuid4()}',id,now(),now(),now(),'UP','SSE_REPLAY_TEST',60
                FROM monitor WHERE resource_id='{replay_resource}'""",
    )
    replay_event = read_event(replay)
    replay.close()
    if replay_event.get("id") == event.get("id") or replay_event.get("data", {}).get("resourceId") != replay_resource:
        raise RuntimeError("Last-Event-ID did not resume at the next authorized status event")
    print(json.dumps({
        "unauthenticated_status": 401,
        "unassigned_project_status": 403,
        "content_type": response.headers.get("Content-Type"),
        "first_event": event.get("event"),
        "first_resource": event.get("data", {}).get("resourceId"),
        "hidden_resource_leaked": event.get("data", {}).get("resourceId") == hidden_resource,
        "replay_event": replay_event.get("event"),
        "replay_resource": replay_event.get("data", {}).get("resourceId"),
        "last_event_id_resumed": replay_event.get("id") != event.get("id"),
    }, separators=(",", ":")))
    return 0


def _monitor_for_resource(args: argparse.Namespace, resource_id: str) -> str:
    return psql(args, f"SELECT id FROM monitor WHERE resource_id='{resource_id}' LIMIT 1")


if __name__ == "__main__":
    raise SystemExit(main())
