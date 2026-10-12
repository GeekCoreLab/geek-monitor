#!/usr/bin/env python3
"""Run real Geek Monitor Agent processes against an uninitialized local instance."""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import http.client
import json
import math
import os
from pathlib import Path
import statistics
import subprocess
import tempfile
import time
import urllib.parse
import uuid


ADMIN_EMAIL = "admin@biliwind.com"
ADMIN_PASSWORD = "admin@biliwind.com"


def api_request(base: urllib.parse.SplitResult, method: str, path: str,
                payload: object | None = None, cookie: str | None = None) -> tuple[int, object, http.client.HTTPMessage]:
    connection = http.client.HTTPConnection(base.hostname, base.port or 80, timeout=20)
    headers = {"Accept": "application/json"}
    body = None
    if payload is not None:
        body = json.dumps(payload, separators=(",", ":")).encode()
        headers["Content-Type"] = "application/json"
    if cookie:
        headers["Cookie"] = cookie
    prefix = base.path.rstrip("/")
    connection.request(method, f"{prefix}{path}", body=body, headers=headers)
    response = connection.getresponse()
    raw = response.read()
    result = json.loads(raw) if raw else None
    result_headers = response.headers
    status = response.status
    connection.close()
    return status, result, result_headers


def proc_usage(pid: int) -> tuple[int, int] | None:
    try:
        stat = Path(f"/proc/{pid}/stat").read_text()
        fields = stat[stat.rfind(")") + 2 :].split()
        cpu_ticks = int(fields[11]) + int(fields[12])
        rss_bytes = int(fields[21]) * os.sysconf("SC_PAGE_SIZE")
        return cpu_ticks, rss_bytes
    except (OSError, IndexError, ValueError):
        return None


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return math.nan
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, math.ceil(fraction * len(ordered)) - 1)]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18084")
    parser.add_argument("--agent-binary", required=True, type=Path)
    parser.add_argument("--hosts", type=int, default=100)
    parser.add_argument("--duration-seconds", type=int, default=75)
    args = parser.parse_args()

    base = urllib.parse.urlsplit(args.base_url)
    if (base.scheme != "http" or base.hostname not in {"127.0.0.1", "localhost", "::1"}
            or base.username or base.password or base.query or base.fragment):
        parser.error("base URL must be plain HTTP on 127.0.0.1, localhost, or ::1")
    if not (1 <= args.hosts <= 100):
        parser.error("hosts must be between 1 and 100")
    if not (65 <= args.duration_seconds <= 604800):
        parser.error("duration-seconds must be between 65 and 604800")
    binary = args.agent_binary.expanduser().resolve()
    if not binary.is_file() or not os.access(binary, os.X_OK):
        parser.error("agent-binary must point to an executable file")

    status, install_state, _ = api_request(base, "GET", "/api/v1/install/status")
    if status != 200 or not isinstance(install_state, dict) or install_state.get("initialized") is not False:
        raise SystemExit("refusing to run: target must be a healthy, uninitialized disposable instance")

    status, _, setup_headers = api_request(
        base, "POST", "/api/v1/install/setup",
        {"email": ADMIN_EMAIL, "password": ADMIN_PASSWORD, "projectName": "Agent process E2E"},
    )
    if status != 201:
        raise SystemExit(f"test initialization failed with HTTP {status}")
    cookie = setup_headers.get("Set-Cookie", "").split(";", 1)[0]
    if not cookie:
        raise SystemExit("test initialization did not return a session cookie")

    status, projects, _ = api_request(base, "GET", "/api/v1/projects", cookie=cookie)
    if status != 200 or not isinstance(projects, list) or not projects:
        raise SystemExit(f"could not load test project (HTTP {status})")
    project_id = projects[0]["id"]

    token_records: list[tuple[str, str]] = []
    for host_index in range(1, args.hosts + 1):
        hostname = f"agent-process-e2e-{host_index:03d}"
        status, resource, _ = api_request(
            base, "POST", "/api/v1/resources",
            {"projectId": project_id, "name": hostname, "type": "HOST", "environment": "e2e"},
            cookie=cookie,
        )
        if status != 201:
            raise SystemExit(f"HOST resource creation failed with HTTP {status}")
        status, enrollment, _ = api_request(
            base, "POST", "/api/v1/agent-enrollments", {"resourceId": resource["id"]}, cookie=cookie,
        )
        if status != 201:
            raise SystemExit(f"Agent enrollment failed with HTTP {status}")
        token_records.append((resource["id"], enrollment["token"]))

    processes: list[subprocess.Popen[bytes]] = []
    log_handles: list[object] = []
    temp_context = tempfile.TemporaryDirectory(prefix="geek-monitor-agent-process-e2e-")
    temp_root = Path(temp_context.name)
    temp_root.chmod(0o700)
    cpu_start: dict[int, tuple[int, float]] = {}
    max_rss: dict[int, int] = {}
    failure: str | None = None
    try:
        for host_index, (_, token) in enumerate(token_records, start=1):
            host_dir = temp_root / f"host-{host_index:03d}"
            host_dir.mkdir(mode=0o700)
            token_path = host_dir / "enrollment.token"
            token_path.write_text(token + "\n")
            token_path.chmod(0o600)
            state_path = host_dir / "agent.json"
            log_path = host_dir / "agent.log"
            log_handle = log_path.open("wb")
            log_handles.append(log_handle)
            process = subprocess.Popen(
                [str(binary), "-server", args.base_url.rstrip("/"), "-config", str(state_path),
                 "-enrollment-token-file", str(token_path)],
                stdin=subprocess.DEVNULL, stdout=log_handle, stderr=subprocess.STDOUT,
                close_fds=True, start_new_session=True,
            )
            processes.append(process)
        run_started_at = dt.datetime.now(dt.timezone.utc)
        for _ in range(20):
            for process in processes:
                current = proc_usage(process.pid)
                if current:
                    cpu_start[process.pid] = (current[0], 0.0)
                    max_rss[process.pid] = max(max_rss.get(process.pid, 0), current[1])
            if len(cpu_start) == len(processes):
                break
            time.sleep(0.1)
        measurement_start = time.monotonic()
        for process in processes:
            if process.pid in cpu_start:
                cpu_start[process.pid] = (cpu_start[process.pid][0], measurement_start)

        deadline = measurement_start + args.duration_seconds
        process_sample_interval = min(60.0, max(1.0, args.duration_seconds / 3600))
        status_report_interval = min(300.0, max(10.0, args.duration_seconds / 100))
        next_process_sample = measurement_start
        next_report = measurement_start + status_report_interval
        while time.monotonic() < deadline:
            now = time.monotonic()
            if now >= next_process_sample:
                for process in processes:
                    if process.poll() is not None:
                        failure = f"Agent process exited early with status {process.returncode}"
                        break
                    current = proc_usage(process.pid)
                    if current:
                        max_rss[process.pid] = max(max_rss.get(process.pid, 0), current[1])
                next_process_sample = now + process_sample_interval
            if failure:
                break
            if now >= next_report:
                status, agents, _ = api_request(base, "GET", f"/api/v1/agents?projectId={project_id}", cookie=cookie)
                online = sum(1 for agent in agents if agent.get("status") == "ONLINE") if status == 200 else 0
                print(f"elapsed_seconds={int(now - measurement_start)} online_agents={online}/{args.hosts}", flush=True)
                if status != 200 or online != args.hosts:
                    failure = f"Agent status check failed (HTTP {status}, online {online}/{args.hosts})"
                    break
                next_report = now + status_report_interval
            wake_at = min(deadline, next_process_sample, next_report)
            time.sleep(max(0, wake_at - time.monotonic()))

        elapsed = max(1.0, time.monotonic() - measurement_start)
        cpu_percentages: list[float] = []
        ticks_per_second = os.sysconf("SC_CLK_TCK")
        for process in processes:
            before = cpu_start.get(process.pid)
            after = proc_usage(process.pid)
            if before and after:
                cpu_percentages.append((after[0] - before[0]) / ticks_per_second / elapsed * 100)
                max_rss[process.pid] = max(max_rss.get(process.pid, 0), after[1])

        status, agents, _ = api_request(base, "GET", f"/api/v1/agents?projectId={project_id}", cookie=cookie)
        if status != 200 or not isinstance(agents, list):
            failure = failure or f"could not read Agent status (HTTP {status})"
            agents = []
        online_resource_ids = {agent["resourceId"] for agent in agents if agent.get("status") == "ONLINE"}

        curve_from = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(seconds=120)).isoformat()
        curve_to = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=1)).isoformat()

        def read_curve(resource_id: str) -> tuple[int, int, int]:
            query = urllib.parse.urlencode({"from": curve_from, "to": curve_to, "stepSeconds": 30})
            curve_status, result, _ = api_request(
                base, "GET", f"/api/v1/resources/{resource_id}/metrics?{query}", cookie=cookie,
            )
            if curve_status != 200 or not isinstance(result, dict):
                return curve_status, 0, 0
            return curve_status, len(result.get("series", [])), int(result.get("pointCount", 0))

        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            curves = list(executor.map(read_curve, [resource_id for resource_id, _ in token_records]))
        curves_with_data = sum(1 for code, series, points in curves if code == 200 and series > 0 and points > 0)
        total_series = sum(series for code, series, _ in curves if code == 200)
        total_points = sum(points for code, _, points in curves if code == 200)

        rss_mib = [value / (1024 * 1024) for value in max_rss.values()]
        cpu_resolution_pct = 100 / (ticks_per_second * elapsed)
        print(f"hosts={args.hosts} duration_seconds={elapsed:.1f} online={len(online_resource_ids)}/{args.hosts}")
        print(f"resources_with_trusted_curve={curves_with_data}/{args.hosts} series={total_series} points={total_points}")
        print(f"agent_avg_cpu_pct_mean={statistics.fmean(cpu_percentages) if cpu_percentages else math.nan:.3f} "
              f"p95={percentile(cpu_percentages, 0.95):.3f} max={max(cpu_percentages, default=math.nan):.3f} "
              f"one_tick_resolution_pct={cpu_resolution_pct:.3f}")
        print(f"agent_peak_rss_mib_p95={percentile(rss_mib, 0.95):.2f} max={max(rss_mib, default=math.nan):.2f}")
        if failure:
            print(f"failure={failure}")
        if (failure or len(online_resource_ids) != args.hosts or curves_with_data != args.hosts
                or len(cpu_percentages) != args.hosts or len(max_rss) != args.hosts):
            return 1
        print("all real Agent processes stayed alive, online, and produced readable trusted metrics")
        return 0
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
        for process in processes:
            if process.poll() is None:
                try:
                    process.wait(timeout=3)
                except Exception:
                    process.kill()
                    process.wait(timeout=3)
        for handle in log_handles:
            handle.close()
        temp_context.cleanup()


if __name__ == "__main__":
    raise SystemExit(main())
