#!/usr/bin/env python3
"""Measure a V26-to-V27 migration with the full 100-HOST daily sample load.

The command only connects to an empty disposable database named
``geek_monitor_benchmark`` on 127.0.0.1:55441. Run PostgreSQL in a temporary
4-CPU/6-GiB container before invoking it. It applies V1-V26, reuses
``benchmark-100-hosts.sql`` to insert 14.4 million samples, executes V27 in one
transaction, and records migration time, database/table/index sizes, WAL
generation, partition routing, and a one-HOST time-window query plan.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import time


def psql(
    args: list[str],
    database_env: dict[str, str],
    input_text: str | None = None,
    timeout_seconds: int = 3600,
) -> tuple[str, str]:
    result = subprocess.run(
        ["psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", *args],
        env=database_env,
        check=False,
        capture_output=True,
        text=True,
        input=input_text,
        timeout=timeout_seconds,
    )
    if result.returncode != 0:
        raise RuntimeError(f"psql failed: {result.stderr.strip()}")
    return result.stdout.strip(), result.stderr.strip()


def scalar(sql: str, database_env: dict[str, str]) -> str:
    output, _ = psql(["-c", sql], database_env)
    return output.strip()


def wait_for_database(database_env: dict[str, str], timeout_seconds: int = 60) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        result = subprocess.run(
            ["pg_isready", "-h", database_env["PGHOST"], "-p", database_env["PGPORT"],
             "-U", database_env["PGUSER"], "-d", database_env["PGDATABASE"]],
            env=database_env,
            check=False,
            capture_output=True,
            text=True,
            timeout=5,
        )
        if result.returncode == 0:
            return
        time.sleep(0.5)
    raise TimeoutError("temporary PostgreSQL did not become ready")


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db-port", type=int, default=55441)
    parser.add_argument("--db-name", default="geek_monitor_benchmark")
    args = parser.parse_args()
    if args.db_port != 55441 or args.db_name != "geek_monitor_benchmark":
        parser.error("only geek_monitor_benchmark on loopback port 55441 is allowed")
    password = os.environ.get("GEEK_MONITOR_BENCHMARK_DB_PASSWORD")
    if not password:
        parser.error("set GEEK_MONITOR_BENCHMARK_DB_PASSWORD for the disposable PostgreSQL database")

    database_env = os.environ.copy()
    database_env.update({
        "PGHOST": "127.0.0.1",
        "PGPORT": str(args.db_port),
        "PGDATABASE": args.db_name,
        "PGUSER": "geek_monitor",
        "PGPASSWORD": password,
    })
    wait_for_database(database_env)
    if scalar("SELECT count(*) FROM pg_tables WHERE schemaname = 'public'", database_env) != "0":
        parser.error("database must be empty before running this migration benchmark")

    migration_dir = root / "src/main/resources/db/migration"
    migrations = sorted(
        (path for path in migration_dir.glob("V*.sql") if int(path.name.split("_")[0][1:]) <= 26),
        key=lambda path: int(path.name.split("_")[0][1:]),
    )
    if [int(path.name.split("_")[0][1:]) for path in migrations] != list(range(1, 27)):
        parser.error("expected schema migrations V1 through V26")
    print("applying V1-V26 to the disposable database", flush=True)
    for migration in migrations:
        psql(["-f", str(migration)], database_env, timeout_seconds=120)
    psql([
        "-c",
        "INSERT INTO project(id,name) VALUES ('00000000-0000-0000-0000-000000000100','100 HOST partition migration benchmark')",
    ], database_env)

    print("loading the 14.4-million-sample V26 dataset", flush=True)
    load_started = time.monotonic()
    seed_output, seed_timing = psql(["-f", str(root / "scripts/benchmark-100-hosts.sql")], database_env)
    load_elapsed = time.monotonic() - load_started
    seeded_rows = int(scalar("SELECT count(*) FROM metric_sample", database_env))
    if seeded_rows != 14_400_000:
        raise RuntimeError(f"expected 14,400,000 V26 samples, found {seeded_rows}")

    pre_size = int(scalar("SELECT pg_database_size(current_database())", database_env))
    pre_table_size = int(scalar("SELECT pg_total_relation_size('metric_sample')", database_env))
    pre_index_size = int(scalar("SELECT pg_indexes_size('metric_sample')", database_env))
    wal_start = scalar("SELECT pg_current_wal_lsn()", database_env)
    migration_sql = (migration_dir / "V27__time_partitioned_telemetry.sql").read_text(encoding="utf-8")

    print("running V27 as one Flyway-style transaction", flush=True)
    migration_started = time.monotonic()
    psql(["-f", "-"], database_env, input_text=f"BEGIN;\n{migration_sql}\nCOMMIT;\n", timeout_seconds=7200)
    migration_elapsed = time.monotonic() - migration_started

    post_rows = int(scalar("SELECT count(*) FROM metric_sample", database_env))
    post_partitions = int(scalar(
        "SELECT count(*) FROM pg_partition_tree('metric_sample') WHERE isleaf", database_env
    ))
    post_route = scalar(
        "SELECT tableoid::regclass FROM metric_sample "
        "WHERE resource_id = '30000000-0000-0000-0000-000000000001'::uuid LIMIT 1",
        database_env,
    )
    post_database_size = int(scalar("SELECT pg_database_size(current_database())", database_env))
    post_table_size = int(scalar(
        "SELECT COALESCE(sum(pg_total_relation_size(relid)),0) "
        "FROM pg_partition_tree('metric_sample') WHERE isleaf",
        database_env,
    ))
    post_index_size = int(scalar(
        "SELECT COALESCE(sum(pg_indexes_size(relid)),0) "
        "FROM pg_partition_tree('metric_sample') WHERE isleaf",
        database_env,
    ))
    wal_bytes = int(scalar(
        f"SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '{wal_start}')::bigint",
        database_env,
    ))
    query_plan, _ = psql([
        "-c",
        """EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
           SELECT count(*) FROM metric_sample
           WHERE resource_id = '30000000-0000-0000-0000-000000000001'::uuid
             AND sampled_at >= now() - interval '24 hours' AND sampled_at < now()""",
    ], database_env, timeout_seconds=300)
    if post_rows != seeded_rows or post_route.endswith("_default"):
        raise RuntimeError(f"V27 did not preserve and route all samples: rows={post_rows}, route={post_route}")
    if "Subplans Removed:" not in query_plan or f"{post_route} " not in query_plan:
        raise RuntimeError(f"24-hour query did not prune to its routed month partition: {query_plan}")

    print(json.dumps({
        "postgres_version": scalar("SHOW server_version", database_env),
        "data_rows": post_rows,
        "load_elapsed_seconds": round(load_elapsed, 2),
        "migration_elapsed_seconds": round(migration_elapsed, 2),
        "database_size_before_migration_bytes": pre_size,
        "database_size_after_migration_bytes": post_database_size,
        "metric_sample_size_before_migration_bytes": pre_table_size,
        "metric_sample_indexes_before_migration_bytes": pre_index_size,
        "metric_sample_size_after_migration_bytes": post_table_size,
        "metric_sample_indexes_after_migration_bytes": post_index_size,
        "wal_generated_during_migration_bytes": wal_bytes,
        "metric_sample_leaf_partitions": post_partitions,
        "sample_routed_to": post_route,
        "24_hour_query_plan": query_plan.splitlines(),
        "seed_timing_summary": seed_timing.splitlines()[-8:],
        "seed_output_summary": seed_output.splitlines()[-8:],
        "external_services_contacted": False,
    }, separators=(",", ":")), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
