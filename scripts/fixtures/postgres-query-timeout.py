#!/usr/bin/env python3
"""Minimal PostgreSQL wire-protocol peer that cancels a deliberately stalled query."""

from __future__ import annotations

import socket
import struct
import threading


HOST = "0.0.0.0"
PORT = 5432
BACKEND_PID = 31741
BACKEND_SECRET = 92814
CANCEL_REQUEST = 80877102
SSL_REQUEST = 80877103
query_cancelled = threading.Event()


def read_exact(connection: socket.socket, size: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < size:
        chunk = connection.recv(size - len(chunks))
        if not chunk:
            raise EOFError("client disconnected")
        chunks.extend(chunk)
    return bytes(chunks)


def backend_message(kind: bytes, payload: bytes = b"") -> bytes:
    return kind + struct.pack("!I", len(payload) + 4) + payload


def startup_response(connection: socket.socket) -> None:
    connection.sendall(backend_message(b"R", struct.pack("!I", 0)))
    for name, value in (
        ("server_version", "17.0"),
        ("server_encoding", "UTF8"),
        ("client_encoding", "UTF8"),
        ("DateStyle", "ISO, MDY"),
        ("integer_datetimes", "on"),
        ("standard_conforming_strings", "on"),
        ("TimeZone", "UTC"),
    ):
        connection.sendall(backend_message(b"S", name.encode() + b"\0" + value.encode() + b"\0"))
    connection.sendall(backend_message(b"K", struct.pack("!II", BACKEND_PID, BACKEND_SECRET)))
    connection.sendall(backend_message(b"Z", b"I"))


def row_description() -> bytes:
    column = (
        b"?column?\0"
        + struct.pack("!IhIhi", 0, 0, 23, 4, -1)
        + struct.pack("!h", 0)
    )
    return backend_message(b"T", struct.pack("!h", 1) + column)


def error_response() -> bytes:
    fields = b"SERROR\0VERROR\0C57014\0Mcanceling statement due to user request\0\0"
    return backend_message(b"E", fields)


def handle_cancel(payload: bytes) -> None:
    if len(payload) != 12:
        return
    process_id, secret = struct.unpack("!II", payload[4:])
    if process_id == BACKEND_PID and secret == BACKEND_SECRET:
        query_cancelled.set()
        print("POSTGRES_QUERY_TIMEOUT_CANCEL_RECEIVED", flush=True)


def handle_client(connection: socket.socket) -> None:
    try:
        connection.settimeout(15)
        packet_length = struct.unpack("!I", read_exact(connection, 4))[0]
        startup_body = read_exact(connection, packet_length - 4)
        protocol = struct.unpack("!I", startup_body[:4])[0]
        if protocol == CANCEL_REQUEST:
            handle_cancel(startup_body)
            return
        if protocol == SSL_REQUEST:
            connection.sendall(b"N")
            packet_length = struct.unpack("!I", read_exact(connection, 4))[0]
            startup_body = read_exact(connection, packet_length - 4)
        startup_response(connection)

        current_query = ""
        ignoring_until_sync = False
        while True:
            kind = read_exact(connection, 1)
            message_length = struct.unpack("!I", read_exact(connection, 4))[0]
            payload = read_exact(connection, message_length - 4)
            if kind == b"X":
                return
            if kind == b"P":
                _, query_and_remainder = payload.split(b"\0", 1)
                current_query = query_and_remainder.split(b"\0", 1)[0].decode("utf-8", errors="replace")
                connection.sendall(backend_message(b"1"))
            elif kind == b"B":
                connection.sendall(backend_message(b"2"))
            elif kind == b"D":
                connection.sendall(row_description())
            elif kind == b"E" and current_query.strip().upper().startswith("SELECT 1"):
                if not query_cancelled.wait(10):
                    return
                connection.sendall(error_response())
                ignoring_until_sync = True
            elif kind == b"E":
                connection.sendall(backend_message(b"C", b"SET\0"))
            elif kind == b"Q":
                current_query = payload.rstrip(b"\0").decode("utf-8", errors="replace")
                if current_query.strip().upper().startswith("SELECT 1"):
                    if not query_cancelled.wait(10):
                        return
                    connection.sendall(error_response())
                    connection.sendall(backend_message(b"Z", b"I"))
                else:
                    connection.sendall(backend_message(b"C", b"SET\0"))
                    connection.sendall(backend_message(b"Z", b"I"))
            elif kind == b"S":
                if ignoring_until_sync:
                    connection.sendall(backend_message(b"Z", b"I"))
                    ignoring_until_sync = False
                else:
                    connection.sendall(backend_message(b"Z", b"I"))
    except (EOFError, OSError, struct.error):
        return
    finally:
        connection.close()


def main() -> None:
    with socket.socket() as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((HOST, PORT))
        server.listen(16)
        print("POSTGRES_QUERY_TIMEOUT_SERVER_READY", flush=True)
        while True:
            connection, _ = server.accept()
            threading.Thread(target=handle_client, args=(connection,), daemon=True).start()


if __name__ == "__main__":
    main()
