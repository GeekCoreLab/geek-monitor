#!/usr/bin/env bash
set -euo pipefail

backup_file=${1:-}
if [[ -z "$backup_file" || ! -f "$backup_file" ]]; then
	printf 'Usage: PGDATABASE=empty_target RESTORE_CONFIRM_DATABASE=empty_target %s BACKUP.dump\n' "$0" >&2
	exit 2
fi

: "${PGDATABASE:?Set PGDATABASE to an empty target database}"
: "${RESTORE_CONFIRM_DATABASE:?Set RESTORE_CONFIRM_DATABASE to the exact PGDATABASE value}"
if [[ "$RESTORE_CONFIRM_DATABASE" != "$PGDATABASE" ]]; then
	printf 'Restore confirmation does not match PGDATABASE.\n' >&2
	exit 2
fi

checksum_file="${backup_file}.sha256"
if [[ ! -f "$checksum_file" ]]; then
	printf 'Checksum file is missing: %s\n' "$checksum_file" >&2
	exit 1
fi

expected_checksum=$(<"$checksum_file")
actual_checksum=$(sha256sum "$backup_file" | awk '{print $1}')
if [[ "$expected_checksum" != "$actual_checksum" ]]; then
	printf 'Backup checksum validation failed.\n' >&2
	exit 1
fi
pg_restore --list "$backup_file" >/dev/null

has_objects=$(psql --no-psqlrc --quiet --tuples-only --no-align --set=ON_ERROR_STOP=1 --dbname="$PGDATABASE" \
	--command="SELECT EXISTS (
		SELECT 1 FROM pg_class c
		JOIN pg_namespace n ON n.oid = c.relnamespace
		WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
		  AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
	)" | tr -d '[:space:]')
if [[ "$has_objects" != "f" ]]; then
	printf 'Target database %s is not empty; restore was not started.\n' "$PGDATABASE" >&2
	exit 1
fi

pg_restore --exit-on-error --single-transaction --no-owner --no-privileges \
	--dbname="$PGDATABASE" "$backup_file"
printf 'Restore completed into empty database: %s\n' "$PGDATABASE"
printf 'Start Geek Monitor against this database and verify readiness and an authenticated API request.\n'
