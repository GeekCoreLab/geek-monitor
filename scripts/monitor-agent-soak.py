#!/usr/bin/env python3
"""Record read-only health, storage, and resource checkpoints for an Agent soak."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.parse
import urllib.request


AGENT_COMMAND = b"/tmp/geek-monitor-agent-soak\x00-server\x00"
RUNNER_COMMAND = b"scripts/e2e-agent-processes.py"
RUNNER_ARGUMENTS = b"--base-url\x00http://127.0.0.1:18084"

DATABASE_FAST_QUERY = """
SELECT json_build_object(
  'agents', (SELECT count(*) FROM agent),
  'online_agents', (SELECT count(*) FROM agent WHERE last_seen_at > now() - interval '90 seconds'),
  'batch_rows_estimate', (SELECT n_live_tup FROM pg_stat_user_tables WHERE relname = 'agent_batch'),
  'raw_sample_rows_estimate', (SELECT n_live_tup FROM pg_stat_user_tables WHERE relname = 'metric_sample'),
  'rollup_rows_estimate', (SELECT n_live_tup FROM pg_stat_user_tables WHERE relname = 'metric_rollup'),
  'latest_agent_seen_at', (SELECT max(last_seen_at) FROM agent),
  'db_size_bytes', pg_database_size(current_database()),
  'raw_table_bytes', pg_total_relation_size('metric_sample'),
  'rollup_table_bytes', pg_total_relation_size('metric_rollup')
)::text
""".strip()

DATABASE_EXACT_QUERY = """
SELECT json_build_object(
  'agents', (SELECT count(*) FROM agent),
  'online_agents', (SELECT count(*) FROM agent WHERE last_seen_at > now() - interval '90 seconds'),
  'batches', (SELECT count(*) FROM agent_batch),
  'reporting_agents', (SELECT count(DISTINCT agent_id) FROM agent_batch),
  'trusted_batches', (SELECT count(*) FROM agent_batch WHERE trusted),
  'untrusted_batches', (SELECT count(*) FROM agent_batch WHERE NOT trusted),
  'accepted_samples', (SELECT coalesce(sum(accepted_count), 0) FROM agent_batch WHERE trusted),
  'raw_samples', (SELECT count(*) FROM metric_sample),
  'trusted_raw_samples', (SELECT count(*) FROM metric_sample WHERE trusted),
  'rollup_samples', (SELECT coalesce(sum(sample_count), 0) FROM metric_rollup),
  'first_batch_at', (SELECT min(received_at) FROM agent_batch),
  'last_batch_at', (SELECT max(received_at) FROM agent_batch),
  'db_size_bytes', pg_database_size(current_database()),
  'raw_table_bytes', pg_total_relation_size('metric_sample'),
  'rollup_table_bytes', pg_total_relation_size('metric_rollup')
)::text
""".strip()


def run(command: list[str], timeout: int = 20) -> str:
    result = subprocess.run(command, check=True, capture_output=True, text=True, timeout=timeout)
    return result.stdout.strip()


def matching_processes(markers: tuple[bytes, ...]) -> list[int]:
    matches = []
    for command_line in Path("/proc").glob("[0-9]*/cmdline"):
        try:
            contents = command_line.read_bytes()
        except OSError:
            continue
        if all(marker in contents for marker in markers):
            matches.append(int(command_line.parent.name))
    return matches


def process_elapsed_seconds(pid: int) -> float:
    stat = Path(f"/proc/{pid}/stat").read_text()
    fields_after_comm = stat[stat.rfind(")") + 1 :].split()
    start_ticks = int(fields_after_comm[19])
    uptime_seconds = float(Path("/proc/uptime").read_text().split()[0])
    clock_ticks_per_second = os.sysconf("SC_CLK_TCK")
    return max(0.0, uptime_seconds - start_ticks / clock_ticks_per_second)


def agent_process_metrics() -> tuple[dict[int, dict[str, int]], float]:
    processes: dict[int, dict[str, int]] = {}
    for command_line in Path("/proc").glob("[0-9]*/cmdline"):
        try:
            contents = command_line.read_bytes()
            status_path = command_line.parent / "status"
            status = status_path.read_text()
            stat = (command_line.parent / "stat").read_text()
        except OSError:
            continue
        if AGENT_COMMAND not in contents:
            continue
        rss_line = next((line for line in status.splitlines() if line.startswith("VmRSS:")), None)
        if rss_line is None:
            continue
        try:
            fields_after_comm = stat[stat.rfind(")") + 1 :].split()
            cpu_ticks = int(fields_after_comm[11]) + int(fields_after_comm[12])
            processes[int(command_line.parent.name)] = {
                "rss_kib": int(rss_line.split()[1]),
                "cpu_ticks": cpu_ticks,
            }
        except (IndexError, ValueError):
            continue
    return processes, time.monotonic()


def health(base_url: str) -> dict[str, object]:
    request = urllib.request.Request(f"{base_url.rstrip('/')}/q/health", headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=8) as response:
        result = json.loads(response.read())
        if response.status != 200 or result.get("status") != "UP":
            raise RuntimeError(f"application health is not UP: HTTP {response.status}")
        return result


def container_stats(names: tuple[str, str]) -> dict[str, dict[str, str]]:
    output = run([
        "podman", "stats", "--no-stream", "--format", "{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}", *names,
    ])
    stats: dict[str, dict[str, str]] = {}
    for line in output.splitlines():
        fields = line.split("\t")
        if len(fields) == 3:
            stats[fields[0]] = {"cpu_percent": fields[1], "memory": fields[2]}
    if set(stats) != set(names):
        raise RuntimeError(f"container stats missing entries: {sorted(stats)}")
    return stats


def database_snapshot(container: str, detailed: bool) -> dict[str, object]:
    query = DATABASE_EXACT_QUERY if detailed else DATABASE_FAST_QUERY
    output = run([
        "podman", "exec", container, "psql", "-U", "geek_monitor", "-d", "geek_monitor",
        "-At", "-c", query,
    ])
    result = json.loads(output)
    result["detailed"] = detailed
    if detailed:
        accepted = int(result["accepted_samples"])
        stored = int(result["trusted_raw_samples"]) + int(result["rollup_samples"])
        result["stored_trusted_samples"] = stored
        result["accepted_to_stored_sample_delta"] = accepted - stored
    return result


def snapshot(args: argparse.Namespace, elapsed: float, detailed: bool = False) -> dict[str, object]:
    app_state = run(["podman", "inspect", "--format", "{{.State.Status}}", args.app_container])
    db_state = run(["podman", "inspect", "--format", "{{.State.Status}}", args.db_container])
    if app_state != "running" or db_state != "running":
        raise RuntimeError(f"container state is app={app_state}, db={db_state}")
    agent_processes, agent_cpu_sample_monotonic = agent_process_metrics()
    process_count = len(agent_processes)
    rss_kib = sum(process["rss_kib"] for process in agent_processes.values())
    rss_mib_per_agent = [process["rss_kib"] / 1024 for process in agent_processes.values()]
    runner_pids = matching_processes((RUNNER_COMMAND, RUNNER_ARGUMENTS))
    runner_count = len(runner_pids)
    if runner_count > 1:
        raise RuntimeError(f"found {runner_count} Agent soak runner processes")
    runner_elapsed = process_elapsed_seconds(runner_pids[0]) if runner_pids else None

    database = database_snapshot(args.db_container, detailed)
    stats = container_stats((args.db_container, args.app_container))
    app_health = health(args.base_url)
    disk = shutil.disk_usage("/")
    record = {
        "timestamp": dt.datetime.now(dt.timezone.utc).isoformat(),
        "elapsed_seconds": round(elapsed, 1),
        "runner_processes": runner_count,
        "runner_elapsed_seconds": round(runner_elapsed, 1) if runner_elapsed is not None else None,
        "agent_processes": process_count,
        "agent_rss_mib": round(rss_kib / 1024, 2),
        "agent_rss_mib_per_agent_average": round(sum(rss_mib_per_agent) / process_count, 2) if process_count else 0,
        "agent_rss_mib_per_agent_p95": round(sorted(rss_mib_per_agent)[math.ceil(len(rss_mib_per_agent) * 0.95) - 1], 2) if process_count else 0,
        "agent_rss_mib_per_agent_max": round(max(rss_mib_per_agent), 2) if process_count else 0,
        "_agent_cpu_ticks_by_pid": {pid: process["cpu_ticks"] for pid, process in agent_processes.items()},
        "_agent_cpu_sample_monotonic": agent_cpu_sample_monotonic,
        "app_health": app_health["status"],
        "containers": stats,
        "database": database,
        "host_disk_bytes": {"total": disk.total, "used": disk.used, "free": disk.free},
    }

    problems = []
    if runner_count and process_count != args.expected_agents:
        problems.append(f"Agent processes={process_count}/{args.expected_agents}")
    if runner_count and int(database["online_agents"]) != args.expected_agents:
        problems.append(f"online Agents={database['online_agents']}/{args.expected_agents}")
    if int(database["agents"]) != args.expected_agents:
        problems.append(f"database Agents={database['agents']}/{args.expected_agents}")
    if detailed and int(database["untrusted_batches"]) != 0:
        problems.append(f"untrusted batches={database['untrusted_batches']}")
    if detailed and int(database["accepted_to_stored_sample_delta"]) != 0:
        problems.append(f"accepted/stored sample delta={database['accepted_to_stored_sample_delta']}")
    if disk.free < args.minimum_free_bytes:
        problems.append(f"host free disk below {args.minimum_free_bytes} bytes")
    record["problems"] = problems
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18084")
    parser.add_argument("--app-container", default="geek-monitor-agent-soak-app")
    parser.add_argument("--db-container", default="geek-monitor-agent-soak-db")
    parser.add_argument("--expected-agents", type=int, default=100)
    parser.add_argument("--interval-seconds", type=int, default=300)
    parser.add_argument("--duration-seconds", type=int, default=604800)
    parser.add_argument("--minimum-free-gib", type=float, default=20)
    parser.add_argument("--maximum-agent-rss-mib", type=float, default=50)
    parser.add_argument("--maximum-agent-average-cpu-percent", type=float, default=1)
    parser.add_argument("--log-file", type=Path, default=Path("/tmp/geek-monitor-agent-soak-metrics.jsonl"))
    parser.add_argument("--once", action="store_true", help="record and verify one checkpoint")
    args = parser.parse_args()
    if args.expected_agents < 1 or args.interval_seconds < 10 or args.duration_seconds < 0:
        parser.error("agents must be positive, interval at least 10 seconds, and duration non-negative")
    if args.minimum_free_gib < 0:
        parser.error("minimum-free-gib must be non-negative")
    if args.maximum_agent_rss_mib <= 0 or args.maximum_agent_average_cpu_percent <= 0:
        parser.error("Agent RSS and average CPU limits must be positive")
    base = urllib.parse.urlsplit(args.base_url)
    if base.scheme != "http" or base.hostname not in {"127.0.0.1", "localhost", "::1"} or base.port != 18084:
        parser.error("base-url must use HTTP on loopback port 18084")
    args.minimum_free_bytes = int(args.minimum_free_gib * 1024**3)

    args.log_file.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor = os.open(args.log_file, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    started = time.monotonic()
    deadline = started + args.duration_seconds
    runner_seen = False
    runner_max_elapsed_seconds = 0.0
    previous_agent_cpu_ticks: dict[int, int] | None = None
    previous_agent_cpu_sample_monotonic: float | None = None
    clock_ticks_per_second = os.sysconf("SC_CLK_TCK")
    with os.fdopen(descriptor, "a", encoding="utf-8") as log:
        while True:
            elapsed = time.monotonic() - started
            try:
                record = snapshot(args, elapsed, detailed=args.once)
                runner_count = int(record["runner_processes"])
                if runner_count:
                    runner_seen = True
                    runner_max_elapsed_seconds = max(
                        runner_max_elapsed_seconds,
                        float(record["runner_elapsed_seconds"]),
                    )
                    record["runner_max_observed_elapsed_seconds"] = round(runner_max_elapsed_seconds, 1)
                elif not args.once and not runner_seen:
                    record["problems"].append("Agent soak runner process was not detected")
                elif runner_seen and not args.once:
                    record = snapshot(args, elapsed, detailed=True)
                    record["runner_max_observed_elapsed_seconds"] = round(runner_max_elapsed_seconds, 1)
                    if runner_max_elapsed_seconds < args.duration_seconds:
                        record["problems"].append(
                            f"Agent soak runner was last observed at {runner_max_elapsed_seconds:.1f}s; "
                            f"monitor did not observe the required {args.duration_seconds}s duration"
                        )
            except Exception as exception:
                record = {
                    "timestamp": dt.datetime.now(dt.timezone.utc).isoformat(),
                    "elapsed_seconds": round(elapsed, 1),
                    "problems": [f"snapshot failed: {type(exception).__name__}: {exception}"],
                }
            current_agent_cpu_ticks = record.pop("_agent_cpu_ticks_by_pid", None)
            current_agent_cpu_sample_monotonic = record.pop("_agent_cpu_sample_monotonic", None)
            if current_agent_cpu_ticks is not None and current_agent_cpu_sample_monotonic is not None:
                current_agent_cpu_ticks = {int(pid): int(ticks) for pid, ticks in current_agent_cpu_ticks.items()}
                if previous_agent_cpu_ticks is not None and previous_agent_cpu_sample_monotonic is not None:
                    sample_seconds = current_agent_cpu_sample_monotonic - previous_agent_cpu_sample_monotonic
                    cpu_percentages = [
                        (current_agent_cpu_ticks[pid] - previous_agent_cpu_ticks[pid])
                        / clock_ticks_per_second / sample_seconds * 100
                        for pid in current_agent_cpu_ticks.keys() & previous_agent_cpu_ticks.keys()
                        if sample_seconds > 0 and current_agent_cpu_ticks[pid] >= previous_agent_cpu_ticks[pid]
                    ]
                    if cpu_percentages:
                        sorted_cpu_percentages = sorted(cpu_percentages)
                        average_cpu = sum(cpu_percentages) / len(cpu_percentages)
                        p95_cpu = sorted_cpu_percentages[math.ceil(len(sorted_cpu_percentages) * 0.95) - 1]
                        record["agent_cpu_sample_seconds"] = round(sample_seconds, 1)
                        record["agent_cpu_measured_processes"] = len(cpu_percentages)
                        record["agent_cpu_percent_per_core_average"] = round(average_cpu, 4)
                        record["agent_cpu_percent_per_core_p95"] = round(p95_cpu, 4)
                        record["agent_cpu_percent_per_core_max"] = round(max(cpu_percentages), 4)
                        if record.get("runner_processes") and len(cpu_percentages) != args.expected_agents:
                            record["problems"].append(
                                f"CPU measured Agents={len(cpu_percentages)}/{args.expected_agents}"
                            )
                        if record.get("runner_processes") and average_cpu >= args.maximum_agent_average_cpu_percent:
                            record["problems"].append(
                                f"average Agent CPU={average_cpu:.4f}% per core reaches/exceeds "
                                f"{args.maximum_agent_average_cpu_percent}%"
                            )
                previous_agent_cpu_ticks = current_agent_cpu_ticks
                previous_agent_cpu_sample_monotonic = current_agent_cpu_sample_monotonic
            if record.get("runner_processes") and record.get("agent_rss_mib_per_agent_max", 0) >= args.maximum_agent_rss_mib:
                record["problems"].append(
                    f"max Agent RSS={record['agent_rss_mib_per_agent_max']} MiB reaches/exceeds "
                    f"{args.maximum_agent_rss_mib} MiB"
                )
            if not args.once and time.monotonic() >= deadline and record.get("runner_processes"):
                record["problems"].append("monitor duration elapsed while Agent soak runner remained active")
            log.write(json.dumps(record, separators=(",", ":")) + "\n")
            log.flush()
            os.fsync(log.fileno())
            database = record.get("database", {})
            batch_count = database.get("batches", database.get("batch_rows_estimate", "?"))
            sample_count = database.get("stored_trusted_samples", database.get("raw_sample_rows_estimate", "?"))
            sample_label = "stored_trusted_samples" if database.get("detailed") else "raw_sample_rows_estimate"
            cpu_average = record.get("agent_cpu_percent_per_core_average")
            cpu_summary = f"cpu_avg={cpu_average:.4f}%/core" if isinstance(cpu_average, (int, float)) else "cpu_avg=warming-up"
            summary = (
                f"elapsed={int(elapsed)}s agents={record.get('agent_processes', 0)}/"
                f"{database.get('online_agents', 0)}/{args.expected_agents} "
                f"batches={batch_count} {sample_label}={sample_count} free_gib="
                f"{record.get('host_disk_bytes', {}).get('free', 0) / 1024**3:.1f} "
                f"rss_avg={record.get('agent_rss_mib_per_agent_average', 0):.2f}/"
                f"{record.get('agent_rss_mib_per_agent_p95', 0):.2f}/"
                f"{record.get('agent_rss_mib_per_agent_max', 0):.2f}MiB {cpu_summary}"
            )
            if record["problems"]:
                print(f"FAIL {summary} problems={record['problems']}", flush=True)
                return 1
            print(f"OK {summary}", flush=True)
            if args.once or (runner_seen and not record.get("runner_processes")) or time.monotonic() >= deadline:
                return 0
            sleep_seconds = args.interval_seconds
            runner_elapsed = record.get("runner_elapsed_seconds")
            final_observation_window = max(args.interval_seconds * 2, 600)
            if (
                record.get("runner_processes")
                and isinstance(runner_elapsed, (int, float))
                and runner_elapsed >= max(0, args.duration_seconds - final_observation_window)
            ):
                sleep_seconds = 1
            time.sleep(min(sleep_seconds, max(0, deadline - time.monotonic())))
if __name__ == "__main__":
    raise SystemExit(main())
