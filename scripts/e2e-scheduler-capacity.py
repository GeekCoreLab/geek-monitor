#!/usr/bin/env python3
"""Measure a 500-check scheduler burst on an isolated, disposable instance.

The application must allow the exact isolated Podman network CIDR and a local
HTTP probe at ``http://scheduler-probe/``. The script refuses an
initialized instance, initializes it with the documented test administrator,
creates up to 500 HTTP checks, and waits for each to run.
"""

from __future__ import annotations

import concurrent.futures
from collections import Counter
import json
import time
import urllib.error
import urllib.parse
import urllib.request


ADMIN_EMAIL = "admin@biliwind.com"
ADMIN_PASSWORD = "admin@biliwind.com"
TARGET = "http://scheduler-probe/"


class Api:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self.cookie: str | None = None

    def request(self, method: str, path: str, payload: object | None = None) -> tuple[int, object]:
        body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = urllib.request.Request(
            self.base_url + path,
            data=body,
            method=method,
            headers={"Accept": "application/json", "Content-Type": "application/json"},
        )
        if self.cookie:
            request.add_header("Cookie", self.cookie)
        try:
            response = urllib.request.urlopen(request, timeout=15)
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"HTTP {error.code} for {method} {path}: {detail[:240]}") from error
        with response:
            raw = response.read()
            result = json.loads(raw) if raw else {}
            if method == "POST" and path in {"/api/v1/install/setup", "/api/v1/auth/login"}:
                session = response.headers.get("Set-Cookie")
                if session:
                    self.cookie = session.split(";", 1)[0]
            return response.status, result


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18089")
    parser.add_argument("--checks", type=int, default=500)
    parser.add_argument("--concurrency", type=int, default=16)
    parser.add_argument("--deadline-seconds", type=int, default=60)
    args = parser.parse_args()
    base = urllib.parse.urlsplit(args.base_url)
    if (base.scheme != "http" or base.hostname not in {"127.0.0.1", "localhost", "::1"}
            or base.username or base.password or base.query or base.fragment):
        parser.error("base URL must be plain HTTP on 127.0.0.1, localhost, or ::1")
    if not 1 <= args.checks <= 500:
        parser.error("checks must be between 1 and 500")
    if not 1 <= args.concurrency <= 64:
        parser.error("concurrency must be between 1 and 64")
    if not 10 <= args.deadline_seconds <= 300:
        parser.error("deadline-seconds must be between 10 and 300")

    api = Api(args.base_url)
    status, install = api.request("GET", "/api/v1/install/status")
    if status != 200 or install.get("initialized") is not False:
        raise SystemExit("refusing to run: target must be a disposable, uninitialized instance")
    status, setup = api.request("POST", "/api/v1/install/setup", {
        "email": ADMIN_EMAIL,
        "password": ADMIN_PASSWORD,
        "projectName": "500-check scheduler capacity",
    })
    if status != 201 or not api.cookie:
        raise SystemExit(f"test administrator initialization failed (HTTP {status})")
    if setup.get("user", {}).get("email") != ADMIN_EMAIL:
        raise SystemExit("setup did not create the documented test administrator")
    status, login = api.request("POST", "/api/v1/auth/login", {
        "email": ADMIN_EMAIL,
        "password": ADMIN_PASSWORD,
    })
    if status != 200 or not api.cookie or login.get("user", {}).get("email") != ADMIN_EMAIL:
        raise SystemExit(f"documented test administrator could not log in (HTTP {status})")
    status, projects = api.request("GET", "/api/v1/projects")
    if status != 200 or not projects:
        raise SystemExit("initialization did not create its default project")
    project_id = projects[0]["id"]

    host_count = (args.checks + 4) // 5

    def create_host(index: int) -> str:
        code, host = api.request("POST", "/api/v1/resources", {
            "projectId": project_id,
            "name": f"scheduler-capacity-host-{index:03d}",
            "type": "HOST",
            "environment": "capacity-test",
        })
        if code != 201:
            raise RuntimeError(f"could not create scheduler test host {index} (HTTP {code})")
        return str(host["id"])

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        host_ids = list(executor.map(create_host, range(1, host_count + 1)))

    def create_check(index: int) -> str:
        host_id = host_ids[(index - 1) // 5]
        code, monitor = api.request("POST", "/api/v1/monitors", {
            "resourceId": host_id,
            "name": f"capacity-http-{index:03d}",
            "type": "HTTP",
            "targetUrl": TARGET,
            "method": "GET",
            "intervalSeconds": 60,
            "timeoutSeconds": 5,
            "failureThreshold": 1,
            "recoveryThreshold": 1,
            "expectedStatusCodes": [200],
            "critical": True,
            "enabled": True,
        })
        if code != 201:
            raise RuntimeError(f"could not create check {index} (HTTP {code})")
        return str(monitor["id"])

    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        monitor_ids = list(executor.map(create_check, range(1, args.checks + 1)))

    deadline = time.monotonic() + args.deadline_seconds
    completed = 0
    last_monitors: list[dict[str, object]] = []
    while time.monotonic() < deadline:
        status, monitors = api.request("GET", "/api/v1/monitors")
        if status != 200 or not isinstance(monitors, list):
            raise SystemExit("could not verify completed checks")
        last_monitors = [monitor for monitor in monitors if monitor.get("id") in monitor_ids]
        completed = sum(
            1 for monitor in monitors
            if monitor.get("id") in monitor_ids and monitor.get("state") == "UP"
            and monitor.get("lastResultAt") is not None
        )
        if completed == args.checks:
            break
        time.sleep(0.5)

    total_seconds = time.monotonic() - started
    if completed != args.checks:
        states = dict(Counter(str(monitor.get("state")) for monitor in last_monitors))
        recent_results: list[object] = []
        for monitor_id in monitor_ids[:3]:
            _, history = api.request("GET", f"/api/v1/monitors/{monitor_id}/results?limit=1")
            if isinstance(history, list) and history:
                result = history[0]
                recent_results.append({key: result.get(key) for key in ("status", "statusCode", "errorCode", "durationMs")})
            else:
                recent_results.append(history)
        raise SystemExit(
            f"only {completed}/{args.checks} checks completed within the deadline; "
            f"monitor_states={states}; recent_results={recent_results}"
        )
    status, system = api.request("GET", "/api/v1/system/status")
    if status != 200:
        raise SystemExit("could not read scheduler telemetry")
    health = system.get("platformHealth", {})
    result_p95 = health.get("recentResultP95LatencyMs")
    recent_checks = health.get("recentCheckCount") or 0
    if result_p95 is None or result_p95 > 5000:
        raise SystemExit(f"scheduled-to-result P95 exceeded 5 seconds: {result_p95}")
    if recent_checks < args.checks or health.get("enabledChecks") != args.checks:
        raise SystemExit(f"system status did not count all checks: recent={recent_checks}, enabled={health.get('enabledChecks')}")
    if health.get("dueChecks") != 0 or health.get("runningChecks") != 0 or health.get("staleChecks") != 0:
        raise SystemExit("scheduler retained due, running, or stale checks after completion")

    print(f"schema_version={system.get('schemaVersion')} checks={args.checks} completed={completed} "
          f"recent_check_count={recent_checks} total_seconds={total_seconds:.2f}")
    print(f"scheduled_to_result_p95_ms={result_p95} due={health.get('dueChecks')} "
          f"running={health.get('runningChecks')} stale={health.get('staleChecks')}")
    print("all checks reached UP through the real scheduler and loopback HTTP checker")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
