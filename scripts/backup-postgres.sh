#!/usr/bin/env bash
set -euo pipefail

: "${PGDATABASE:?Set PGDATABASE to the Geek Monitor database name}"

backup_dir=${BACKUP_DIR:-./backups}
umask 077
mkdir -p -- "$backup_dir"
chmod 700 -- "$backup_dir"

timestamp=$(date -u +'%Y%m%dT%H%M%SZ')
backup_file="$backup_dir/geek-monitor-${timestamp}.dump"
checksum_file="${backup_file}.sha256"
if [[ -e "$backup_file" || -e "$checksum_file" ]]; then
	printf 'Backup already exists: %s\n' "$backup_file" >&2
	exit 1
fi

temporary_dump=$(mktemp "${backup_file}.tmp.XXXXXX")
temporary_checksum=$(mktemp "${checksum_file}.tmp.XXXXXX")
cleanup() {
	rm -f -- "$temporary_dump" "$temporary_checksum"
}
trap cleanup EXIT

pg_dump --format=custom --no-owner --no-privileges --dbname="$PGDATABASE" --file="$temporary_dump"
pg_restore --list "$temporary_dump" >/dev/null
sha256sum "$temporary_dump" | awk '{print $1}' >"$temporary_checksum"
chmod 600 -- "$temporary_dump" "$temporary_checksum"
mv -- "$temporary_dump" "$backup_file"
mv -- "$temporary_checksum" "$checksum_file"
trap - EXIT

printf 'Backup created: %s\nChecksum: %s\n' "$backup_file" "$checksum_file"
backup_filename=$(basename -- "$backup_file")
backup_size_bytes=$(stat --format='%s' -- "$backup_file")
backup_sha256=$(<"$checksum_file")
backup_status_table=$(psql --no-psqlrc --quiet --tuples-only --no-align --set=ON_ERROR_STOP=1 \
	--dbname="$PGDATABASE" --command="SELECT to_regclass('public.backup_status') IS NOT NULL" | tr -d '[:space:]')
if [[ "$backup_status_table" == "t" ]]; then
	psql --no-psqlrc --quiet --set=ON_ERROR_STOP=1 \
		--set="backup_filename=$backup_filename" \
		--set="backup_size_bytes=$backup_size_bytes" \
		--set="backup_sha256=$backup_sha256" \
		--dbname="$PGDATABASE" <<'SQL'
INSERT INTO backup_status(id, filename, size_bytes, sha256, completed_at)
VALUES (1, :'backup_filename', :backup_size_bytes, :'backup_sha256', now())
ON CONFLICT (id) DO UPDATE SET filename = EXCLUDED.filename, size_bytes = EXCLUDED.size_bytes,
	sha256 = EXCLUDED.sha256, completed_at = EXCLUDED.completed_at;
SQL
else
	printf 'Backup status was not recorded; apply Geek Monitor migration V22 to enable status reporting.\n' >&2
fi
