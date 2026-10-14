#!/usr/bin/env python3
"""Exercise an isolated PostgreSQL tmpfs exhaustion and recovery path.

Pass --app-image to create a disposable application and PostgreSQL stack. The
database is memory-limited and has a bounded tmpfs mounted at
/var/lib/postgresql/data. The script fills only that tmpfs and removes its
pressure file in a finally block. Without --app-image, it uses the pre-started
loopback application and named containers used by the original manual flow.
"""

from __future__ import annotations

import base64
import json
import re
import secrets
import socket
import subprocess
import time
import uuid
import urllib.error
import urllib.parse
import urllib.request


ADMIN_EMAIL = "admin@biliwind.com"
ADMIN_PASSWORD = "admin@biliwind.com"
APP_CONTAINER = "geek-monitor-disk-pressure-app"
DB_CONTAINER = "geek-monitor-disk-pressure-db"
PRESSURE_FILE = "/var/lib/postgresql/data/.geek-monitor-capacity-pressure"
APP_PORT = 18091
POSTGRES_IMAGE = "docker.io/library/postgres:17-alpine"
EXPECTED_FLYWAY_VERSION = "27"


def podman(*args: str, check: bool = True, timeout: int = 120) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        ["podman", *args], text=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=False, timeout=timeout,
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


def tmpfs_capacity_kib(db_container: str) -> tuple[int, int]:
    inspected = json.loads(podman("inspect", db_container).stdout)[0]
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
            f"refusing to fill database: {db_container} must have a bounded data tmpfs "
            "and at least 1 GiB container memory"
        )
    size = int(size_match.group(1))
    unit = size_match.group(2).lower()
    total_bytes = size * {"k": 1024, "m": 1024**2, "g": 1024**3}[unit]
    if total_bytes > 2 * 1024**3:
        raise SystemExit("database tmpfs must not exceed 2 GiB")
    result = podman("exec", db_container, "df", "-Pk", "/var/lib/postgresql/data")
    fields = result.stdout.strip().splitlines()[-1].split()
    if len(fields) < 5 or int(fields[1]) > 2 * 1024**3:
        raise SystemExit("database filesystem does not match the bounded tmpfs guard")
    return int(fields[1]), int(fields[3])


def verify_isolated_stack(base_url: str, app_container: str, db_container: str) -> tuple[int, int]:
    app_inspect = json.loads(podman("inspect", app_container).stdout)[0]
    db_inspect = json.loads(podman("inspect", db_container).stdout)[0]
    if app_inspect.get("State", {}).get("Running") is not True:
        raise SystemExit("application container must be running")
    app_environment = set(app_inspect.get("Config", {}).get("Env", []))
    db_environment = set(db_inspect.get("Config", {}).get("Env", []))
    if "DB_URL=jdbc:postgresql://db:5432/geek_monitor" not in app_environment:
        raise SystemExit("application container must point directly at the isolated db network alias")
    if "DB_USERNAME=geek_monitor" not in app_environment:
        raise SystemExit("application must use the disposable PostgreSQL test user")
    if "POSTGRES_USER=geek_monitor" not in db_environment or "POSTGRES_DB=geek_monitor" not in db_environment:
        raise SystemExit("database container must use the disposable geek_monitor database and user")
    app_networks = set(app_inspect.get("NetworkSettings", {}).get("Networks", {}))
    db_networks = db_inspect.get("NetworkSettings", {}).get("Networks", {})
    shared_networks = app_networks.intersection(db_networks)
    if not shared_networks or not any("db" in db_networks[name].get("Aliases", []) for name in shared_networks):
        raise SystemExit("application and disposable database must share a network with the db alias")
    parsed = urllib.parse.urlsplit(base_url)
    if parsed.port != APP_PORT or parsed.path not in {"", "/"}:
        raise SystemExit(f"application URL must use the isolated loopback port {APP_PORT}")
    bindings = app_inspect.get("HostConfig", {}).get("PortBindings", {}).get("8080/tcp", [])
    if len(bindings) != 1 or bindings[0].get("HostIp") != "127.0.0.1" or bindings[0].get("HostPort") != str(APP_PORT):
        raise SystemExit("application container must publish only 127.0.0.1:18091")
    total_kib, available_kib = tmpfs_capacity_kib(db_container)
    state = podman(
        "exec", db_container, "psql", "-X", "-A", "-t", "-U", "geek_monitor", "-d", "geek_monitor",
        "-c", "SELECT initialized::text FROM installation_state WHERE id = 1",
    ).stdout.strip()
    if state != "false":
        raise SystemExit("disposable PostgreSQL database must be migrated but not initialized")
    return total_kib, available_kib


def ensure_port_free(port: int) -> None:
    with socket.socket() as probe:
        probe.settimeout(0.2)
        if probe.connect_ex(("127.0.0.1", port)) == 0:
            raise SystemExit(f"loopback port {port} is already in use")


def wait_for_database(db_container: str, timeout_seconds: int = 90) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        result = podman("exec", db_container, "pg_isready", "-U", "geek_monitor", "-d", "geek_monitor", check=False, timeout=5)
        if result.returncode == 0:
            return
        time.sleep(1)
    raise TimeoutError(f"disposable PostgreSQL container {db_container} did not become ready")


def wait_for_application(app_container: str, base_url: str, timeout_seconds: int = 120) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        app_state = json.loads(podman("inspect", app_container).stdout)[0].get("State", {})
        if app_state.get("Running") is not True:
            raise RuntimeError(f"Geek Monitor container exited: {app_state.get('ExitCode')}")
        try:
            with urllib.request.urlopen(f"{base_url}/q/health/ready", timeout=2) as response:
                payload = json.loads(response.read())
                if response.status == 200 and payload.get("status") == "UP":
                    return
        except (OSError, TimeoutError, json.JSONDecodeError):
            pass
        time.sleep(1)
    raise TimeoutError("application did not become ready")


def start_isolated_stack(app_image: str) -> tuple[str, str, str, str]:
    ensure_port_free(APP_PORT)
    if podman("image", "exists", app_image, check=False).returncode != 0:
        raise RuntimeError(f"application image {app_image!r} is not available locally")
    if podman("image", "exists", POSTGRES_IMAGE, check=False).returncode != 0:
        podman("pull", POSTGRES_IMAGE, timeout=600)

    suffix = uuid.uuid4().hex[:10]
    network = f"geek-monitor-disk-pressure-{suffix}"
    db_container = f"geek-monitor-disk-pressure-db-{suffix}"
    app_container = f"geek-monitor-disk-pressure-app-{suffix}"
    db_password = secrets.token_urlsafe(24)
    encryption_key = base64.b64encode(secrets.token_bytes(32)).decode("ascii")
    try:
        podman("network", "create", "--internal", network)
        podman(
            "run", "-d", "--name", db_container, "--network", network,
            "--network-alias", "db", "--memory", "1g",
            "--tmpfs", "/var/lib/postgresql/data:rw,size=512m",
            "-e", "POSTGRES_DB=geek_monitor", "-e", "POSTGRES_USER=geek_monitor",
            "-e", f"POSTGRES_PASSWORD={db_password}", POSTGRES_IMAGE,
        )
        wait_for_database(db_container)
        podman(
            "run", "-d", "--name", app_container, "--network", network,
            "-p", f"127.0.0.1:{APP_PORT}:8080", "--memory", "2g",
            "-e", "DB_URL=jdbc:postgresql://db:5432/geek_monitor",
            "-e", "DB_USERNAME=geek_monitor", "-e", f"DB_PASSWORD={db_password}",
            "-e", f"GEEK_MONITOR_ENCRYPTION_KEY={encryption_key}",
            "-e", "GEEK_MONITOR_SECURITY_SECURE_COOKIE=false",
            "-e", "SMTP_MOCK=true", app_image,
        )
        base_url = f"http://127.0.0.1:{APP_PORT}"
        wait_for_application(app_container, base_url)
        schema_version = podman(
            "exec", db_container, "psql", "-X", "-A", "-t", "-U", "geek_monitor",
            "-d", "geek_monitor", "-c",
            "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
        ).stdout.strip()
        if schema_version != EXPECTED_FLYWAY_VERSION:
            raise RuntimeError(f"application schema version is V{schema_version or 'unknown'}, expected V{EXPECTED_FLYWAY_VERSION}")
    except Exception:
        podman("rm", "--force", app_container, check=False)
        podman("rm", "--force", db_container, check=False)
        podman("network", "rm", network, check=False)
        raise
    return base_url, network, app_container, db_container


def run_pressure_test(base_url: str, app_container: str, db_container: str) -> int:
    total_kib, available_kib = verify_isolated_stack(base_url, app_container, db_container)
    if available_kib < total_kib // 2:
        raise SystemExit("database tmpfs already has less than half its space available")

    api = Api(base_url)
    status, health, _ = api.request("GET", "/q/health/ready")
    if status != 200 or health.get("status") != "UP":
        raise SystemExit("application must be ready before the storage-pressure test")
    status, install, _ = api.request("GET", "/api/v1/install/status")
    if status != 200 or install.get("initialized") is not False:
        raise SystemExit("refusing to run: application must be uninitialized and disposable")

    try:
        podman("exec", db_container, "rm", "-f", PRESSURE_FILE)
        fill = podman(
            "exec", db_container, "sh", "-c",
            f"dd if=/dev/zero of={PRESSURE_FILE} bs=1M status=none",
            check=False,
        )
        if fill.returncode == 0 or "No space left on device" not in fill.stderr:
            raise RuntimeError("bounded tmpfs did not reach ENOSPC as expected")
        full_total_kib, full_available_kib = tmpfs_capacity_kib(db_container)
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
        podman("exec", db_container, "rm", "-f", PRESSURE_FILE, check=False)

    _, recovered_available_kib = tmpfs_capacity_kib(db_container)
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


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18091")
    parser.add_argument("--app-image", help="create and remove a disposable application and database stack")
    args = parser.parse_args()
    require_loopback(args.base_url)
    temporary_stack: tuple[str, str, str] | None = None
    if args.app_image:
        base_url, network, app_container, db_container = start_isolated_stack(args.app_image)
        temporary_stack = (network, app_container, db_container)
    else:
        base_url, app_container, db_container = args.base_url, APP_CONTAINER, DB_CONTAINER
    try:
        return run_pressure_test(base_url, app_container, db_container)
    finally:
        if temporary_stack:
            network, app_container, db_container = temporary_stack
            podman("exec", db_container, "rm", "-f", PRESSURE_FILE, check=False)
            podman("rm", "--force", app_container, check=False)
            podman("rm", "--force", db_container, check=False)
            podman("network", "rm", network, check=False)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
