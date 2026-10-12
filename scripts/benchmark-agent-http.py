#!/usr/bin/env python3
"""Exercise the real Agent enrollment and batch-ingest HTTP APIs on a disposable local app."""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import http.client
import json
import math
import threading
import time
import urllib.parse
import uuid


ADMIN_EMAIL = "admin@biliwind.com"
ADMIN_PASSWORD = "admin@biliwind.com"
THREAD_STATE = threading.local()


def json_bytes(value: object) -> bytes:
    return json.dumps(value, separators=(",", ":")).encode("utf-8")


def api_request(base: urllib.parse.SplitResult, method: str, path: str,
                payload: object | None = None, cookie: str | None = None,
                bearer: str | None = None) -> tuple[int, dict[str, object], http.client.HTTPMessage]:
    connection = http.client.HTTPConnection(base.hostname, base.port or 80, timeout=30)
    headers = {"Accept": "application/json"}
    body = None
    if payload is not None:
        body = json_bytes(payload)
        headers["Content-Type"] = "application/json"
    if cookie:
        headers["Cookie"] = cookie
    if bearer:
        headers["Authorization"] = f"Bearer {bearer}"
    prefix = base.path.rstrip("/")
    connection.request(method, f"{prefix}{path}", body=body, headers=headers)
    response = connection.getresponse()
    raw = response.read()
    result = json.loads(raw) if raw else {}
    headers_out = response.headers
    status = response.status
    connection.close()
    return status, result, headers_out


def current_connection(base: urllib.parse.SplitResult) -> http.client.HTTPConnection:
    connection = getattr(THREAD_STATE, "connection", None)
    target = (base.hostname, base.port or 80)
    if connection is None or getattr(THREAD_STATE, "target", None) != target:
        connection = http.client.HTTPConnection(*target, timeout=30)
        THREAD_STATE.connection = connection
        THREAD_STATE.target = target
    return connection


def metric_series() -> list[dict[str, object]]:
    metrics: list[dict[str, object]] = [
        {"name": "cpu_usage_percent", "value": 23.5},
        {"name": "load_1m", "value": 0.75},
        {"name": "memory_used_percent", "value": 48.0},
        {"name": "memory_available_bytes", "value": 5_000_000_000},
        {"name": "swap_used_percent", "value": 0.0},
    ]
    for index in range(1, 13):
        labels = {"mountpoint": f"/mnt/disk{index}"}
        metrics.append({"name": "disk_used_percent", "labels": labels, "value": 30.0 + index})
        metrics.append({"name": "disk_inode_used_percent", "labels": labels, "value": 10.0 + index})
    for index in range(1, 12):
        labels = {"interface": f"eth{index}"}
        metrics.append({"name": "network_receive_bytes_per_second", "labels": labels, "value": 1000.0 + index})
        if index <= 10:
            metrics.append({"name": "network_transmit_bytes_per_second", "labels": labels, "value": 2000.0 + index})
    assert len(metrics) == 50
    return metrics


def percentile(sorted_values: list[float], fraction: float) -> float:
    if not sorted_values:
        return math.nan
    return sorted_values[min(len(sorted_values) - 1, math.ceil(fraction * len(sorted_values)) - 1)]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18084")
    parser.add_argument("--hosts", type=int, default=100)
    parser.add_argument("--batches-per-host", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=16)
    args = parser.parse_args()

    base = urllib.parse.urlsplit(args.base_url)
    if (base.scheme != "http" or base.hostname not in {"127.0.0.1", "localhost", "::1"}
            or base.username or base.password or base.query or base.fragment):
        parser.error("base URL must be plain HTTP on 127.0.0.1, localhost, or ::1")
    if not (1 <= args.hosts <= 100):
        parser.error("hosts must be between 1 and 100")
    if not (1 <= args.batches_per_host <= 1000):
        parser.error("batches-per-host must be between 1 and 1000")
    if not (1 <= args.concurrency <= 64):
        parser.error("concurrency must be between 1 and 64")

    metric_template = metric_series()
    status, install_state, _ = api_request(base, "GET", "/api/v1/install/status")
    if status != 200 or install_state.get("initialized") is not False:
        raise SystemExit("refusing to benchmark: target must be a healthy, uninitialized disposable instance")

    status, setup, response_headers = api_request(
        base, "POST", "/api/v1/install/setup",
        {"email": ADMIN_EMAIL, "password": ADMIN_PASSWORD, "projectName": "Agent HTTP capacity benchmark"},
    )
    if status != 201:
        raise SystemExit(f"initialization failed with HTTP {status}: {setup}")
    set_cookie = response_headers.get("Set-Cookie", "")
    cookie = set_cookie.split(";", 1)[0]
    if not cookie:
        raise SystemExit("initialization succeeded without a session cookie")

    status, projects, _ = api_request(base, "GET", "/api/v1/projects", cookie=cookie)
    if status != 200 or not projects:
        raise SystemExit(f"could not load benchmark project (HTTP {status})")
    project_id = projects[0]["id"]

    agents: list[dict[str, str]] = []
    for host_index in range(1, args.hosts + 1):
        resource_status, resource, _ = api_request(
            base, "POST", "/api/v1/resources",
            {"projectId": project_id, "name": f"http-capacity-host-{host_index:03d}",
             "type": "HOST", "environment": "benchmark"}, cookie=cookie,
        )
        if resource_status != 201:
            raise SystemExit(f"host resource creation failed with HTTP {resource_status}")
        enrollment_status, enrollment, _ = api_request(
            base, "POST", "/api/v1/agent-enrollments", {"resourceId": resource["id"]}, cookie=cookie,
        )
        if enrollment_status != 201:
            raise SystemExit(f"Agent enrollment failed with HTTP {enrollment_status}")
        register_status, registration, _ = api_request(
            base, "POST", "/api/v1/agents/register",
            {"enrollmentToken": enrollment["token"], "hostname": f"http-capacity-host-{host_index:03d}",
             "operatingSystem": "linux/amd64", "agentVersion": "capacity-benchmark"},
        )
        if register_status != 201:
            raise SystemExit(f"Agent registration failed with HTTP {register_status}")
        agents.append({"id": registration["agentId"], "resourceId": resource["id"],
                       "credential": registration["credential"], "bootId": str(uuid.uuid4())})

    anchor = dt.datetime.now(dt.timezone.utc)
    tasks = [(agent_index, sequence) for agent_index in range(args.hosts)
             for sequence in range(1, args.batches_per_host + 1)]
    latencies: list[float] = []
    failures: list[str] = []
    accepted_samples = 0
    started = time.perf_counter()
    print(f"registered_agents={len(agents)} starting_http_batches={len(tasks)}", flush=True)

    def upload(task: tuple[int, int]) -> tuple[float, int, str | None]:
        agent_index, sequence = task
        agent = agents[agent_index]
        sampled_at = (anchor - dt.timedelta(seconds=(sequence - 1) * 30)).isoformat()
        payload = {
            "bootId": agent["bootId"], "sequence": sequence, "sampledAt": sampled_at,
            "configVersion": 1, "clientClockOffsetMs": 0, "clockSynced": True,
            "metrics": metric_template,
        }
        body = json_bytes(payload)
        headers = {"Accept": "application/json", "Content-Type": "application/json",
                   "Authorization": f"Bearer {agent['credential']}"}
        path = f"{base.path.rstrip('/')}/api/v1/agents/{agent['id']}/batches"
        request_started = time.perf_counter()
        connection = current_connection(base)
        try:
            connection.request("POST", path, body=body, headers=headers)
            response = connection.getresponse()
            raw = response.read()
            elapsed = time.perf_counter() - request_started
            if response.status != 202:
                return elapsed, 0, f"HTTP {response.status}: {raw[:200].decode('utf-8', 'replace')}"
            result = json.loads(raw)
            if result.get("accepted") != len(metric_template) or result.get("duplicate") is not False or result.get("trusted") is not True:
                return elapsed, 0, "unexpected ingestion response"
            return elapsed, int(result["accepted"]), None
        except Exception as exception:  # report all failed requests, then exit nonzero
            try:
                connection.close()
            finally:
                THREAD_STATE.connection = None
            return time.perf_counter() - request_started, 0, type(exception).__name__

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = [executor.submit(upload, task) for task in tasks]
        completed = 0
        for future in concurrent.futures.as_completed(futures):
            elapsed, accepted, error = future.result()
            latencies.append(elapsed)
            accepted_samples += accepted
            completed += 1
            if error:
                failures.append(error)
            progress_step = max(1, len(tasks) // 10)
            if completed % progress_step == 0:
                print(f"completed_http_batches={completed}/{len(tasks)}", flush=True)
    duration = time.perf_counter() - started

    curve_start = anchor - dt.timedelta(seconds=args.batches_per_host * 30)
    curve_end = dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=1)
    curve_path = f"/api/v1/resources/{agents[0]['resourceId']}/metrics?" + urllib.parse.urlencode({
        "from": curve_start.isoformat(), "to": curve_end.isoformat(), "stepSeconds": 60,
    })
    curve_started = time.perf_counter()
    curve_status, curve, _ = api_request(base, "GET", curve_path, cookie=cookie)
    curve_duration = time.perf_counter() - curve_started
    curve_series = curve.get("series", []) if isinstance(curve, dict) else []
    curve_point_count = curve.get("pointCount", 0) if isinstance(curve, dict) else 0
    if curve_status != 200 or len(curve_series) != len(metric_template) or not curve_point_count:
        failures.append(f"metric curve query failed validation (HTTP {curve_status})")

    print(f"hosts={args.hosts} batches_per_host={args.batches_per_host} concurrency={args.concurrency}")
    print(f"requests={len(tasks)} accepted_samples={accepted_samples} failures={len(failures)}")
    print(f"duration_seconds={duration:.3f} requests_per_second={len(tasks) / duration:.2f} samples_per_second={accepted_samples / duration:.2f}")
    sorted_latencies = sorted(latencies)
    print(f"request_latency_ms_p50={percentile(sorted_latencies, 0.50) * 1000:.2f} "
          f"p95={percentile(sorted_latencies, 0.95) * 1000:.2f} "
          f"p99={percentile(sorted_latencies, 0.99) * 1000:.2f} "
          f"max={max(sorted_latencies, default=math.nan) * 1000:.2f}")
    print(f"metric_curve_query_status=HTTP_{curve_status} latency_ms={curve_duration * 1000:.2f} "
          f"series={len(curve_series)} points={curve_point_count}")
    if failures:
        print(f"first_failure={failures[0]}")
        return 1
    print("all HTTP batches were accepted as trusted, non-duplicate samples")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
