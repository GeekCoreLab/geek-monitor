#!/usr/bin/env python3
"""Exercise an isolated PostgreSQL tmpfs exhaustion and recovery path.

The application must be a blank, disposable instance on loopback. The named
database container must be a small-memory-limited PostgreSQL container with a
bounded tmpfs mounted at /var/lib/postgresql/data. The script fills only that
tmpfs and removes its pressure file in a finally block.
"""

from __future__ import annotations

import json
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request


ADMIN_EMAIL = "admin@biliwind.com"
ADMIN_PASSWORD = "admin@biliwind.com"
DB_CONTAINER = "geek-monitor-disk-pressure-db"
PRESSURE_FILE = "/var/lib/postgresql/data/.geek-monitor-capacity-pressure"


def podman(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        ["podman", *args], text=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=False, timeout=120,
    )
    if check and result.returncode:
        raise RuntimeError(f"podman {' '.join(args[:3])} failed: {result.stderr[-500:]}")
    return result


class Api:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self.cookie: str | None = None

    def request(self, method: str, path: str, payload: object | None = None) -> tuple[int, object, str | None]:
        body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = urllib.request.Request(
            self.base_url + path, data=body, method=method,
            headers={"Accept": "application/json", "Content-Type": "application/json"},
        )
        if self.cookie:
            request.add_header("Cookie", self.cookie)
        try:
            response = urllib.request.urlopen(request, timeout=20)
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                result = json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                result = {"body": raw.decode("utf-8", errors="replace")[:300]}
            return error.code, result, None
        with response:
            raw = response.read()
            result = json.loads(raw) if raw else {}
            set_cookie = response.headers.get("Set-Cookie")
            if set_cookie:
                self.cookie = set_cookie.split(";", 1)[0]
            return response.status, result, self.cookie


def require_loopback(base_url: str) -> None:
    parsed = urllib.parse.urlsplit(base_url)
    if (parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}
            or parsed.username or parsed.password or parsed.query or parsed.fragment):
        raise SystemExit("base URL must be plain HTTP on 127.0.0.1, localhost, or ::1")


def tmpfs_capacity_kib() -> tuple[int, int]:
    inspected = json.loads(podman("inspect", DB_CONTAINER).stdout)[0]
    if inspected.get("State", {}).get("Running") is not True:
        raise SystemExit("database container must be running")
    image = inspected.get("Config", {}).get("Image", "")
    environment = set(inspected.get("Config", {}).get("Env", []))
    if "postgres:17-alpine" not in image or "PGDATA=/var/lib/postgresql/data" not in environment:
        raise SystemExit("database container must use the PostgreSQL 17 Alpine test image and default PGDATA")
    tmpfs = inspected.get("HostConfig", {}).get("Tmpfs", {})
    mount = tmpfs.get("/var/lib/postgresql/data", "")
    size_match = re.search(r"(?:^|,)size=(\d+)([kmg])(?:,|$)", mount, re.IGNORECASE)
    memory_limit = int(inspected.get("HostConfig", {}).get("Memory", 0))
    if not size_match or memory_limit < 1024**3:
        raise SystemExit(
            f"refusing to fill database: {DB_CONTAINER} must have a bounded data tmpfs "
            "and at least 1 GiB container memory"
        )
    size = int(size_match.group(1))
    unit = size_match.group(2).lower()
    total_bytes = size * {"k": 1024, "m": 1024**2, "g": 1024**3}[unit]
    if total_bytes > 2 * 1024**3:
        raise SystemExit("database tmpfs must not exceed 2 GiB")
    result = podman("exec", DB_CONTAINER, "df", "-Pk", "/var/lib/postgresql/data")
    fields = result.stdout.strip().splitlines()[-1].split()
    if len(fields) < 5 or int(fields[1]) > 2 * 1024**3:
        raise SystemExit("database filesystem does not match the bounded tmpfs guard")
    return int(fields[1]), int(fields[3])


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18091")
    args = parser.parse_args()
    require_loopback(args.base_url)
    total_kib, available_kib = tmpfs_capacity_kib()
    if available_kib < total_kib // 2:
        raise SystemExit("database tmpfs already has less than half its space available")

    api = Api(args.base_url)
    status, health, _ = api.request("GET", "/q/health/ready")
    if status != 200 or health.get("status") != "UP":
        raise SystemExit("application must be ready before the storage-pressure test")
    status, install, _ = api.request("GET", "/api/v1/install/status")
    if status != 200 or install.get("initialized") is not False:
        raise SystemExit("refusing to run: application must be uninitialized and disposable")

    try:
        podman("exec", DB_CONTAINER, "rm", "-f", PRESSURE_FILE)
        fill = podman(
            "exec", DB_CONTAINER, "sh", "-c",
            f"dd if=/dev/zero of={PRESSURE_FILE} bs=1M status=none",
            check=False,
        )
        if fill.returncode == 0 or "No space left on device" not in fill.stderr:
            raise RuntimeError("bounded tmpfs did not reach ENOSPC as expected")
        full_total_kib, full_available_kib = tmpfs_capacity_kib()
        if full_available_kib != 0:
            raise RuntimeError(f"tmpfs is not full: {full_available_kib} KiB remains")

        status, health, _ = api.request("GET", "/q/health/ready")
        if status != 200 or health.get("status") != "UP":
            raise RuntimeError("database became unavailable before the write failure could be observed")
        status, failure, _ = api.request("POST", "/api/v1/install/setup", {
            "email": ADMIN_EMAIL,
            "password": ADMIN_PASSWORD,
            "projectName": "database space pressure E2E",
        })
        if status != 500:
            raise RuntimeError(f"expected controlled HTTP 500 while database is full, got {status}")
        failure_status = status
        failure_text = json.dumps(failure, ensure_ascii=False).lower()
        if "no space left" in failure_text or "postgresql" in failure_text or "stacktrace" in failure_text:
            raise RuntimeError("database internals leaked in the HTTP error response")
        status, install, _ = api.request("GET", "/api/v1/install/status")
        if status != 200 or install.get("initialized") is not False:
            raise RuntimeError("failed setup left partial initialized state in the database")
        print(f"database_tmpfs={full_total_kib}KiB full_available={full_available_kib}KiB "
              f"ready=UP failed_write_http={failure_status} rollback=clean")
    finally:
        podman("exec", DB_CONTAINER, "rm", "-f", PRESSURE_FILE, check=False)

    _, recovered_available_kib = tmpfs_capacity_kib()
    if recovered_available_kib < total_kib // 2:
        raise RuntimeError("database tmpfs did not recover after removing the pressure file")
    status, setup, _ = api.request("POST", "/api/v1/install/setup", {
        "email": ADMIN_EMAIL,
        "password": ADMIN_PASSWORD,
        "projectName": "database space pressure E2E",
    })
    if status != 201 or setup.get("user", {}).get("email") != ADMIN_EMAIL:
        raise RuntimeError(f"setup did not recover after space was released (HTTP {status})")
    status, login, _ = api.request("POST", "/api/v1/auth/login", {
        "email": ADMIN_EMAIL,
        "password": ADMIN_PASSWORD,
    })
    if status != 200 or login.get("user", {}).get("email") != ADMIN_EMAIL:
        raise RuntimeError(f"login did not recover after space was released (HTTP {status})")
    status, health, _ = api.request("GET", "/q/health/ready")
    if status != 200 or health.get("status") != "UP":
        raise RuntimeError("application readiness did not recover after database space was released")
    print(f"recovered_tmpfs_available={recovered_available_kib}KiB setup=201 login=200 ready=UP")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
