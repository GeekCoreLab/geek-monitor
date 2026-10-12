#!/usr/bin/env python3
"""Verify V1-V26 data survives the V27 monthly-partition migration.

The script only connects to a disposable empty PostgreSQL database on loopback
port 55440. It applies the existing migrations through V26 with psql, inserts
one historical row into each telemetry table, applies V27, then verifies row
preservation, partition routing, query pruning, identity sequences, and the
global check-execution ID constraint.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time


def psql(args: list[str], database_env: dict[str, str], input_text: str | None = None) -> str:
    result = subprocess.run(
        ["psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", *args],
        env=database_env,
        check=False,
        capture_output=True,
        text=True,
        input=input_text,
        timeout=60,
    )
    if result.returncode != 0:
        raise RuntimeError(f"psql failed: {result.stderr.strip()}")
    return result.stdout.strip()


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
    raise TimeoutError("disposable PostgreSQL database did not become ready")


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db-port", type=int, default=55440)
    parser.add_argument("--db-name", default="geek_monitor_partition_test")
    args = parser.parse_args()
    if args.db_port != 55440 or args.db_name != "geek_monitor_partition_test":
        parser.error("only the disposable geek_monitor_partition_test database on loopback port 55440 is allowed")
    password = os.environ.get("GEEK_MONITOR_E2E_DB_PASSWORD")
    if not password:
        parser.error("set GEEK_MONITOR_E2E_DB_PASSWORD for the disposable PostgreSQL database")

    database_env = os.environ.copy()
    database_env.update({
        "PGHOST": "127.0.0.1",
        "PGPORT": str(args.db_port),
        "PGDATABASE": args.db_name,
        "PGUSER": "geek_monitor",
        "PGPASSWORD": password,
    })
    wait_for_database(database_env)
    if psql(["-c", "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'"], database_env) != "0":
        parser.error("database must be empty before running this migration test")

    migration_dir = root / "src/main/resources/db/migration"
    migrations = sorted(
        (path for path in migration_dir.glob("V*.sql") if int(path.name.split("_")[0][1:]) <= 26),
        key=lambda path: int(path.name.split("_")[0][1:]),
    )
    versions = [int(re.match(r"V(\d+)_", path.name).group(1)) for path in migrations]
    if versions != list(range(1, 27)):
        parser.error(f"expected migrations V1 through V26, found {versions}")
    for migration in migrations:
        psql(["-f", str(migration)], database_env)

    seed_sql = """
        INSERT INTO project(id,name) VALUES ('00000000-0000-0000-0000-000000000101','partition migration test');
        INSERT INTO monitored_resource(id,project_id,name,type)
        VALUES ('00000000-0000-0000-0000-000000000102','00000000-0000-0000-0000-000000000101','partition host','HOST');
        INSERT INTO monitor(id,resource_id,name,type,config,interval_seconds,timeout_seconds)
        VALUES ('00000000-0000-0000-0000-000000000103','00000000-0000-0000-0000-000000000102','partition test','HTTP','{}',60,5);
        INSERT INTO agent(id,resource_id,credential_hash,hostname,operating_system,agent_version)
        VALUES ('00000000-0000-0000-0000-000000000104','00000000-0000-0000-0000-000000000102',repeat('a',64),'partition-agent','linux','e2e');
        INSERT INTO agent_batch(id,agent_id,boot_id,sequence,sampled_at,config_version,receive_sample_delta_ms,
                                client_clock_offset_ms,clock_synced,trusted,accepted_count)
        VALUES ('00000000-0000-0000-0000-000000000105','00000000-0000-0000-0000-000000000104',
                '00000000-0000-0000-0000-000000000106',1,now()-interval '40 days',1,0,0,true,true,1);
        INSERT INTO check_execution(id,monitor_id,scheduled_at,started_at,finished_at,result_status,interval_seconds)
        VALUES ('00000000-0000-0000-0000-000000000107','00000000-0000-0000-0000-000000000103',
                now()-interval '40 days',now()-interval '40 days',now()-interval '40 days','UP',60);
        INSERT INTO metric_sample(batch_id,agent_id,resource_id,metric_name,labels,value,sampled_at,config_version,trusted)
        VALUES ('00000000-0000-0000-0000-000000000105','00000000-0000-0000-0000-000000000104',
                '00000000-0000-0000-0000-000000000102','partition-migration-e2e','{}',8,now()-interval '40 days',1,true);
        INSERT INTO database_metric_sample(monitor_id,resource_id,metric_name,labels,value,sampled_at)
        VALUES ('00000000-0000-0000-0000-000000000103','00000000-0000-0000-0000-000000000102',
                'partition-migration-e2e','{}',9,now()-interval '40 days');
        INSERT INTO metric_rollup(resource_id,metric_name,labels,resolution_seconds,bucket_at,sample_count,sum_value,min_value,max_value)
        VALUES ('00000000-0000-0000-0000-000000000102','partition-migration-e2e','{}',3600,
                date_bin(interval '1 hour',now()-interval '40 days',timestamptz '1970-01-01 00:00:00+00'),3,300,80,120);
        INSERT INTO availability_rollup(monitor_id,bucket_at,scheduled_seconds,up_seconds,degraded_seconds,
                                        down_seconds,unknown_seconds,excluded_seconds)
        VALUES ('00000000-0000-0000-0000-000000000103',
                date_bin(interval '1 hour',now()-interval '40 days',timestamptz '1970-01-01 00:00:00+00'),
                3600,3600,0,0,0,0);
    """
    psql(["-f", "-"], database_env, input_text=seed_sql)
    psql(["-f", str(migration_dir / "V27__time_partitioned_telemetry.sql")], database_env)

    parent_count = int(psql([
        "-c",
        """SELECT count(*) FROM pg_partitioned_table
           WHERE partrelid IN ('check_execution'::regclass, 'metric_sample'::regclass,
             'database_metric_sample'::regclass, 'metric_rollup'::regclass, 'availability_rollup'::regclass)""",
    ], database_env))
    if parent_count != 5:
        raise RuntimeError(f"expected five partitioned tables, found {parent_count}")

    table_rows = {
        "check_execution": psql(["-c", "SELECT tableoid::regclass FROM check_execution WHERE id = '00000000-0000-0000-0000-000000000107'"], database_env),
        "metric_sample": psql(["-c", "SELECT tableoid::regclass FROM metric_sample WHERE metric_name = 'partition-migration-e2e'"], database_env),
        "database_metric_sample": psql(["-c", "SELECT tableoid::regclass FROM database_metric_sample WHERE metric_name = 'partition-migration-e2e'"], database_env),
        "metric_rollup": psql(["-c", "SELECT tableoid::regclass FROM metric_rollup WHERE metric_name = 'partition-migration-e2e'"], database_env),
        "availability_rollup": psql(["-c", "SELECT tableoid::regclass FROM availability_rollup WHERE monitor_id = '00000000-0000-0000-0000-000000000103'"], database_env),
    }
    if any(not re.fullmatch(r"[a-z_]+_\d{6}", partition) for partition in table_rows.values()):
        raise RuntimeError(f"legacy rows were not copied into monthly partitions: {table_rows}")

    counts = {
        table: int(psql(["-c", f"SELECT count(*) FROM {table}"], database_env))
        for table in ("check_execution", "metric_sample", "database_metric_sample", "metric_rollup", "availability_rollup")
    }
    expected_counts = {"check_execution": 1, "metric_sample": 1, "database_metric_sample": 1,
                       "metric_rollup": 1, "availability_rollup": 1}
    if counts != expected_counts:
        raise RuntimeError(f"migration changed telemetry row counts: {counts}")
    if psql(["-c", "SELECT count(*) FROM check_execution_identity_registry"], database_env) != "1":
        raise RuntimeError("legacy check execution ID was not registered")

    explain = psql([
        "-c",
        """EXPLAIN (COSTS OFF) SELECT id FROM metric_sample
           WHERE sampled_at >= now()-interval '2 days' AND sampled_at < now()""",
    ], database_env)
    if "metric_sample_default" in explain or "metric_sample_" not in explain:
        raise RuntimeError(f"two-day metric query did not prune to a monthly partition: {explain}")

    duplicate = subprocess.run(
        ["psql", "-X", "-v", "ON_ERROR_STOP=1", "-c",
         "INSERT INTO check_execution(id,monitor_id,scheduled_at,interval_seconds) "
         "VALUES ('00000000-0000-0000-0000-000000000107','00000000-0000-0000-0000-000000000103',now(),60)"],
        env=database_env,
        check=False,
        capture_output=True,
        text=True,
        timeout=10,
    )
    if duplicate.returncode == 0 or "already exists" not in duplicate.stderr:
        raise RuntimeError("duplicate check execution IDs were not rejected by the registry")

    next_metric_id = psql([
        "-c",
        """INSERT INTO metric_sample(batch_id,agent_id,resource_id,metric_name,labels,value,sampled_at,config_version,trusted)
           VALUES ('00000000-0000-0000-0000-000000000105','00000000-0000-0000-0000-000000000104',
             '00000000-0000-0000-0000-000000000102','partition-sequence-e2e','{}',10,now(),1,true)
           RETURNING id""",
    ], database_env)
    next_metric_id = next_metric_id.splitlines()[0]
    if next_metric_id != "2":
        raise RuntimeError(f"metric identity sequence was not advanced after migration: {next_metric_id}")

    psql([
        "-c",
        "DELETE FROM check_execution WHERE id = '00000000-0000-0000-0000-000000000107' "
        "AND scheduled_at < now()-interval '39 days'",
    ], database_env)
    if psql(["-c", "SELECT count(*) FROM check_execution_identity_registry"], database_env) != "0":
        raise RuntimeError("deleting a check result did not remove its global ID registration")

    print(json.dumps({
        "migrations_applied": "V1-V27",
        "preserved_legacy_rows": counts,
        "partitioned_tables": parent_count,
        "legacy_month_partitions": table_rows,
        "two_day_query_pruned_default_and_old_months": True,
        "duplicate_check_execution_id_rejected": True,
        "identity_sequence_continues_at": int(next_metric_id) + 1,
    }, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
