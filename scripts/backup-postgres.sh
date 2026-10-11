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
