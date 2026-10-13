#!/usr/bin/env python3
"""Verify a completed, continuous Agent telemetry cadence window from PostgreSQL."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import sys


QUERY = r"""
WITH bounds AS (
    SELECT :'since'::timestamptz AS window_start,
           :'duration_seconds'::integer * interval '1 second' AS duration
), window_bounds AS (
    SELECT window_start, window_start + duration AS window_end
    FROM bounds
), active_agents AS (
    SELECT id, last_seen_at
    FROM agent
    WHERE revoked_at IS NULL
), previous_trusted AS (
    SELECT DISTINCT ON (b.agent_id)
           b.agent_id, b.sampled_at, b.received_at
    FROM agent_batch b
    JOIN active_agents a ON a.id = b.agent_id
    CROSS JOIN window_bounds w
    WHERE b.trusted AND b.sampled_at < w.window_start
    ORDER BY b.agent_id, b.sampled_at DESC
), window_batches AS (
    SELECT b.agent_id, b.sampled_at, b.received_at, b.trusted
    FROM agent_batch b
    JOIN active_agents a ON a.id = b.agent_id
    CROSS JOIN window_bounds w
    WHERE b.sampled_at >= w.window_start AND b.sampled_at <= w.window_end
), trusted_sequence AS (
    SELECT agent_id, sampled_at, received_at
    FROM previous_trusted
    UNION ALL
    SELECT agent_id, sampled_at, received_at
    FROM window_batches
    WHERE trusted
), trusted_lagged AS (
    SELECT agent_id, sampled_at, received_at,
           lag(sampled_at) OVER (PARTITION BY agent_id ORDER BY sampled_at, received_at) AS previous_sampled_at,
           lag(received_at) OVER (PARTITION BY agent_id ORDER BY sampled_at, received_at) AS previous_received_at
    FROM trusted_sequence
), window_counts AS (
    SELECT agent_id,
           count(*) AS batch_count,
           count(*) FILTER (WHERE trusted) AS trusted_batch_count,
           count(*) FILTER (WHERE NOT trusted) AS untrusted_batch_count
    FROM window_batches
    GROUP BY agent_id
), coverage AS (
    SELECT l.agent_id,
           min(l.sampled_at) FILTER (WHERE l.sampled_at >= w.window_start AND l.sampled_at <= w.window_end) AS first_sampled_at,
           max(l.sampled_at) FILTER (WHERE l.sampled_at >= w.window_start AND l.sampled_at <= w.window_end) AS last_sampled_at,
           min(l.received_at) FILTER (WHERE l.sampled_at >= w.window_start AND l.sampled_at <= w.window_end) AS first_received_at,
           max(l.received_at) FILTER (WHERE l.sampled_at >= w.window_start AND l.sampled_at <= w.window_end) AS last_received_at,
           max(l.sampled_at) FILTER (WHERE l.sampled_at < w.window_start) AS previous_sampled_at,
           max(l.received_at) FILTER (WHERE l.sampled_at < w.window_start) AS previous_received_at
    FROM trusted_lagged l
    CROSS JOIN window_bounds w
    GROUP BY l.agent_id
), intervals AS (
    SELECT l.agent_id,
           extract(epoch FROM (l.sampled_at - l.previous_sampled_at))::double precision AS sampled_gap_seconds,
           extract(epoch FROM (l.received_at - l.previous_received_at))::double precision AS received_gap_seconds
    FROM trusted_lagged l
    CROSS JOIN window_bounds w
    WHERE l.sampled_at >= w.window_start
      AND l.sampled_at <= w.window_end
      AND l.previous_sampled_at >= w.window_start
), interval_stats AS (
    SELECT agent_id,
           count(*) AS interval_count,
           min(sampled_gap_seconds) AS sampled_gap_min_seconds,
           percentile_cont(0.50) WITHIN GROUP (ORDER BY sampled_gap_seconds) AS sampled_gap_p50_seconds,
           percentile_cont(0.95) WITHIN GROUP (ORDER BY sampled_gap_seconds) AS sampled_gap_p95_seconds,
           max(sampled_gap_seconds) AS sampled_gap_max_seconds,
           count(*) FILTER (WHERE sampled_gap_seconds < 25 OR sampled_gap_seconds > 35) AS sampled_gap_out_of_range_count,
           min(received_gap_seconds) AS received_gap_min_seconds,
           percentile_cont(0.50) WITHIN GROUP (ORDER BY received_gap_seconds) AS received_gap_p50_seconds,
           percentile_cont(0.95) WITHIN GROUP (ORDER BY received_gap_seconds) AS received_gap_p95_seconds,
           max(received_gap_seconds) AS received_gap_max_seconds,
           count(*) FILTER (WHERE received_gap_seconds < 25 OR received_gap_seconds > 35) AS received_gap_out_of_range_count
    FROM intervals
    GROUP BY agent_id
)
SELECT json_build_object(
    'window_start', w.window_start,
    'window_end', w.window_end,
    'checked_at', now(),
    'expected_agent_count', :'expected_agents'::integer,
    'active_agent_count', (SELECT count(*) FROM active_agents),
    'agents', coalesce((
        SELECT json_agg(json_build_object(
            'id', a.id::text,
            'online', a.last_seen_at >= now() - interval '90 seconds',
            'batch_count', coalesce(c.batch_count, 0),
            'trusted_batch_count', coalesce(c.trusted_batch_count, 0),
            'untrusted_batch_count', coalesce(c.untrusted_batch_count, 0),
            'first_sampled_at', cv.first_sampled_at,
            'last_sampled_at', cv.last_sampled_at,
            'first_received_at', cv.first_received_at,
            'last_received_at', cv.last_received_at,
            'start_edge_gap_seconds', CASE WHEN cv.first_sampled_at IS NULL THEN NULL
                ELSE extract(epoch FROM (cv.first_sampled_at - coalesce(cv.previous_sampled_at, w.window_start)))::double precision END,
            'end_edge_gap_seconds', CASE WHEN cv.last_sampled_at IS NULL THEN NULL
                ELSE extract(epoch FROM (w.window_end - cv.last_sampled_at))::double precision END,
            'interval_count', coalesce(i.interval_count, 0),
            'sampled_gap_min_seconds', i.sampled_gap_min_seconds,
            'sampled_gap_p50_seconds', i.sampled_gap_p50_seconds,
            'sampled_gap_p95_seconds', i.sampled_gap_p95_seconds,
            'sampled_gap_max_seconds', i.sampled_gap_max_seconds,
            'sampled_gap_out_of_range_count', coalesce(i.sampled_gap_out_of_range_count, 0),
            'received_gap_min_seconds', i.received_gap_min_seconds,
            'received_gap_p50_seconds', i.received_gap_p50_seconds,
            'received_gap_p95_seconds', i.received_gap_p95_seconds,
            'received_gap_max_seconds', i.received_gap_max_seconds,
            'received_gap_out_of_range_count', coalesce(i.received_gap_out_of_range_count, 0)
        ) ORDER BY a.id)
        FROM active_agents a
        LEFT JOIN window_counts c ON c.agent_id = a.id
        LEFT JOIN coverage cv ON cv.agent_id = a.id
        LEFT JOIN interval_stats i ON i.agent_id = a.id
    ), '[]'::json)
)::text
FROM window_bounds w
""".strip()


def parse_timestamp(value: str) -> dt.datetime:
    parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError(f"timestamp must include a timezone: {value}")
    return parsed.astimezone(dt.timezone.utc)


def validate_report(
    report: dict[str, object],
    *,
    expected_agents: int,
    duration_seconds: int,
    now: dt.datetime | None = None,
) -> list[str]:
    """Return acceptance failures for one aggregate SQL snapshot."""
    problems: list[str] = []
    try:
        start = parse_timestamp(str(report["window_start"]))
        end = parse_timestamp(str(report["window_end"]))
        checked_at = parse_timestamp(str(report["checked_at"]))
    except (KeyError, TypeError, ValueError) as error:
        return [f"invalid report timestamps: {error}"]

    if int(report.get("expected_agent_count", expected_agents)) != expected_agents:
        problems.append("report expected_agent_count does not match the requested value")
    if end - start != dt.timedelta(seconds=duration_seconds):
        problems.append("reported window duration does not match the requested duration")
    if checked_at < end:
        problems.append(f"window is incomplete: checked_at={checked_at.isoformat()} window_end={end.isoformat()}")
    if now is not None and checked_at > now + dt.timedelta(minutes=5):
        problems.append("database clock is more than five minutes ahead of the verifier clock")

    try:
        active_count = int(report["active_agent_count"])
        agents = report["agents"]
        if not isinstance(agents, list):
            raise TypeError("agents must be a list")
    except (KeyError, TypeError, ValueError) as error:
        return problems + [f"invalid report agent data: {error}"]

    if active_count != expected_agents or len(agents) != expected_agents:
        problems.append(
            f"active agent cohort mismatch: active={active_count}, rows={len(agents)}, expected={expected_agents}"
        )

    for agent in agents:
        if not isinstance(agent, dict):
            problems.append("invalid agent entry in report")
            continue
        agent_id = str(agent.get("id", "unknown"))
        prefix = f"agent {agent_id}"
        if not agent.get("online"):
            problems.append(f"{prefix} is not currently online")
        if int(agent.get("trusted_batch_count", 0)) <= 0:
            problems.append(f"{prefix} has no trusted batches in the window")
        if int(agent.get("untrusted_batch_count", 0)) != 0:
            problems.append(f"{prefix} has {agent['untrusted_batch_count']} untrusted batches in the window")
        if int(agent.get("interval_count", 0)) <= 0:
            problems.append(f"{prefix} has no complete internal cadence intervals")

        for field in ("start_edge_gap_seconds", "end_edge_gap_seconds"):
            value = agent.get(field)
            if value is None or float(value) < 0 or float(value) > 60:
                problems.append(f"{prefix} {field}={value!r} is outside 0–60 seconds")
        for timeline in ("sampled", "received"):
            out_of_range = int(agent.get(f"{timeline}_gap_out_of_range_count", 0))
            if out_of_range:
                problems.append(f"{prefix} has {out_of_range} {timeline} intervals outside 25–35 seconds")
            maximum = agent.get(f"{timeline}_gap_max_seconds")
            if maximum is None or float(maximum) > 60:
                problems.append(f"{prefix} {timeline} maximum interval={maximum!r} exceeds 60 seconds")
        for field in ("first_sampled_at", "last_sampled_at", "first_received_at", "last_received_at"):
            value = agent.get(field)
            if not value:
                problems.append(f"{prefix} is missing {field}")
    return problems


def query_report(args: argparse.Namespace) -> dict[str, object]:
    result = subprocess.run(
        [
            "podman", "exec", "-i", args.db_container, "psql", "-U", "geek_monitor", "-d", "geek_monitor",
            "-X", "-q", "-A", "-t", "-v", f"since={args.since}",
            "-v", f"duration_seconds={args.duration_seconds}", "-v", f"expected_agents={args.expected_agents}",
        ],
        input=QUERY,
        check=True,
        capture_output=True,
        text=True,
        timeout=args.timeout_seconds,
    )
    return json.loads(result.stdout.strip())


def append_record(path: Path, record: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    flags = os.O_APPEND | os.O_CREAT | os.O_WRONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    with os.fdopen(os.open(path, flags, 0o600), "a", encoding="utf-8") as output:
        os.fchmod(output.fileno(), 0o600)
        output.write(json.dumps(record, sort_keys=True) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--since", required=True, help="UTC ISO-8601 start of the acceptance window")
    parser.add_argument("--duration-seconds", type=int, default=86_400)
    parser.add_argument("--expected-agents", type=int, default=100)
    parser.add_argument("--db-container", default="geek-monitor-agent-soak-db")
    parser.add_argument("--timeout-seconds", type=int, default=180)
    parser.add_argument("--jsonl-file", type=Path)
    args = parser.parse_args()

    try:
        start = parse_timestamp(args.since)
        if args.duration_seconds <= 0 or args.expected_agents <= 0:
            raise ValueError("duration and expected agent count must be positive")
        report = query_report(args)
        problems = validate_report(
            report,
            expected_agents=args.expected_agents,
            duration_seconds=args.duration_seconds,
            now=dt.datetime.now(dt.timezone.utc),
        )
        outcome = {
            "observation_kind": "agent_cadence_window",
            "window_start": start.isoformat(),
            "checked_at": report.get("checked_at"),
            "passed": not problems,
            "problems": problems,
            "report": report,
        }
    except (OSError, ValueError, KeyError, json.JSONDecodeError, subprocess.SubprocessError) as error:
        detail = error.stderr.strip() if isinstance(error, subprocess.CalledProcessError) and error.stderr else str(error)
        outcome = {
            "observation_kind": "agent_cadence_window",
            "window_start": args.since,
            "checked_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            "passed": False,
            "problems": [f"cadence query failed: {detail}"],
        }

    print(json.dumps(outcome, indent=2, sort_keys=True))
    if args.jsonl_file:
        append_record(args.jsonl_file, outcome)
    return 0 if outcome["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
