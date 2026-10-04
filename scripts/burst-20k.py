#!/usr/bin/env python3
"""High-concurrency 20,000 requests burst test using Python asyncio standard library.

Blasts ~20,000 concurrent reservation requests against hot seats using
persistent HTTP keep-alive connections with zero external dependencies.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import hmac
import json
import os
import ssl
import sys
import time
import urllib.request
from typing import Any
from urllib.parse import urlparse


def generate_jwt(secret: str, subject: str, scope: str = "user") -> str:
    def b64url(data: bytes) -> str:
        return base64.urlsafe_b64encode(data).decode("utf-8").rstrip("=")

    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {"iss": "seat-reservation", "sub": subject, "scope": scope, "iat": now, "exp": now + 86400}
    signing_input = f"{b64url(json.dumps(header).encode())}.{b64url(json.dumps(payload).encode())}".encode()
    signature = hmac.new(secret.encode("utf-8"), signing_input, hashlib.sha256).digest()
    return f"{signing_input.decode('utf-8')}.{b64url(signature)}"


def http_post_sync(url: str, body: dict[str, Any], headers: dict[str, str]) -> dict[str, Any]:
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", **headers},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def http_get_sync(url: str, headers: dict[str, str] | None = None) -> dict[str, Any]:
    req = urllib.request.Request(url, headers=headers or {}, method="GET")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


async def worker(
    worker_id: int,
    host: str,
    port: int,
    path: str,
    ssl_context: ssl.SSLContext | None,
    jobs_queue: asyncio.Queue,
    stats: dict[str, int],
    lock: asyncio.Lock,
):
    reader, writer = None, None

    async def get_connection():
        nonlocal reader, writer
        if writer is not None and not writer.is_closing():
            return reader, writer
        reader, writer = await asyncio.open_connection(host, port, ssl=ssl_context)
        return reader, writer

    while True:
        try:
            job = jobs_queue.get_nowait()
        except asyncio.QueueEmpty:
            break

        user_id, token, seat, key = job
        payload = json.dumps({"seats": [seat]}).encode("utf-8")
        req_lines = [
            f"POST {path} HTTP/1.1",
            f"Host: {host}",
            f"Authorization: Bearer {token}",
            f"Idempotency-Key: {key}",
            "Content-Type: application/json",
            f"Content-Length: {len(payload)}",
            "Connection: keep-alive",
            "",
            "",
        ]
        raw_req = "\r\n".join(req_lines).encode("utf-8") + payload

        retries = 2
        success = False
        while retries > 0 and not success:
            try:
                r, w = await get_connection()
                w.write(raw_req)
                await w.drain()

                # Read HTTP response status line
                line = await r.readline()
                if not line:
                    raise ConnectionResetError("Empty response, reconnecting")

                status_code = int(line.split(b" ")[1])

                # Read headers to find Content-Length
                content_length = 0
                is_chunked = False
                while True:
                    hdr = await r.readline()
                    if hdr in (b"\r\n", b"\n", b""):
                        break
                    lower = hdr.lower()
                    if lower.startswith(b"content-length:"):
                        content_length = int(lower.split(b":")[1].strip())
                    elif lower.startswith(b"transfer-encoding:") and b"chunked" in lower:
                        is_chunked = True

                # Read response body
                if content_length > 0:
                    await r.readexactly(content_length)
                elif is_chunked:
                    while True:
                        chunk_size_line = await r.readline()
                        chunk_size = int(chunk_size_line.strip().split(b";")[0], 16)
                        if chunk_size == 0:
                            await r.readline()  # trailing crlf
                            break
                        await r.readexactly(chunk_size)
                        await r.readline()  # chunk crlf

                async with lock:
                    if status_code == 201:
                        stats["confirmed_201"] += 1
                    elif status_code == 200:
                        stats["replay_200"] += 1
                    elif status_code == 409:
                        stats["conflict_409"] += 1
                    elif 500 <= status_code < 600:
                        stats["server_error_5xx"] += 1
                    else:
                        stats[f"other_{status_code}"] = stats.get(f"other_{status_code}", 0) + 1
                    stats["completed"] += 1
                success = True
            except Exception:
                if writer:
                    try:
                        writer.close()
                        await writer.wait_closed()
                    except Exception:
                        pass
                writer, reader = None, None
                retries -= 1
                if retries == 0:
                    async with lock:
                        stats["connection_errors"] += 1
                        stats["completed"] += 1
        jobs_queue.task_done()

    if writer:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass


async def run_burst(args):
    parsed = urlparse(args.base_url)
    host = parsed.hostname or "localhost"
    is_https = parsed.scheme == "https"
    port = parsed.port or (443 if is_https else 80)
    ssl_context = ssl.create_default_context() if is_https else None

    secret = (
        args.jwt_secret
        or os.environ.get("JWT_SECRET")
        or "test-jwt-secret-must-be-at-least-32-bytes-long-for-hs256!"
    )

    admin_token = generate_jwt(secret, "admin-20k", "admin")

    print("=" * 64)
    print("20,000 CONCURRENT RESERVATION BURST TEST")
    print("=" * 64)
    print(f"Target URL:         {args.base_url}")
    print(f"Total Requests:     {args.requests}")
    print(f"Concurrent Workers: {args.concurrency}")
    print(f"Target Seat:        A1 (Single Hot Seat Contention)")
    print("=" * 64)

    # 1. Create a fresh test show
    print("\n1. Creating fresh test show...")
    create_payload = {
        "name": f"burst-20k-{int(time.time())}",
        "seats": ["A1"],
        "price_paise": 25000,
        "per_user_limit": 4,
    }
    show_resp = http_post_sync(
        f"{args.base_url}/shows",
        create_payload,
        {"Authorization": f"Bearer {admin_token}"},
    )
    show_id = show_resp["id"]
    print(f"   Created show: {show_id} with 1 seat ('A1')")

    # 2. Pre-generate distinct tokens for the burst
    print(f"\n2. Generating {args.requests} tokens & idempotency keys in memory...")
    t0_tok = time.perf_counter()
    jobs = []
    # 95% unique buyers, 5% idempotency retries (same user & key)
    unique_users = max(1, int(args.requests * 0.95))
    tokens_cache = [
        (f"user-{i:06d}", generate_jwt(secret, f"user-{i:06d}", "user"))
        for i in range(1, unique_users + 1)
    ]
    for idx in range(args.requests):
        user_idx = idx % len(tokens_cache)
        uid, tok = tokens_cache[user_idx]
        key = f"idem-20k-{idx:06d}" if idx < unique_users else f"idem-20k-{user_idx:06d}"
        jobs.append((uid, tok, "A1", key))
    t1_tok = time.perf_counter()
    print(f"   Tokens generated in {(t1_tok - t0_tok):.3f}s")

    # 3. Populate queue and fire workers
    queue = asyncio.Queue()
    for job in jobs:
        queue.put_nowait(job)

    stats = {
        "completed": 0,
        "confirmed_201": 0,
        "replay_200": 0,
        "conflict_409": 0,
        "server_error_5xx": 0,
        "connection_errors": 0,
    }
    lock = asyncio.Lock()

    print(f"\n3. Firing {args.concurrency} async workers behind HTTP keep-alive...")
    reserve_path = f"/shows/{show_id}/reserve"

    t_start = time.perf_counter()
    workers = [
        asyncio.create_task(
            worker(i, host, port, reserve_path, ssl_context, queue, stats, lock)
        )
        for i in range(args.concurrency)
    ]

    # Progress monitor
    async def monitor():
        while stats["completed"] < args.requests:
            await asyncio.sleep(1.0)
            pct = (stats["completed"] / args.requests) * 100
            print(
                f"   Progress: {stats['completed']}/{args.requests} ({pct:.1f}%) | "
                f"201: {stats['confirmed_201']} | 409: {stats['conflict_409']} | 5xx: {stats['server_error_5xx']}"
            )

    monitor_task = asyncio.create_task(monitor())
    await queue.join()
    monitor_task.cancel()
    await asyncio.gather(*workers)
    t_end = time.perf_counter()

    elapsed = t_end - t_start
    rps = args.requests / elapsed if elapsed > 0 else 0

    # 4. Fetch final show reconciliation
    print("\n4. Verifying show reconciliation invariant...")
    show_state = http_get_sync(f"{args.base_url}/shows/{show_id}")
    avail = show_state.get("available", show_state.get("available_seats", 0))
    held = show_state.get("held", show_state.get("held_seats", 0))
    conf = show_state.get("confirmed", show_state.get("confirmed_seats", 0))
    tot = show_state.get("total_seats", 0)

    # 5. Output Results
    print("\n" + "=" * 64)
    print("OUTCOME DISTRIBUTION: 20,000 REQUEST BURST")
    print("=" * 64)
    print(f"  Total Requests Attempted:    {args.requests}")
    print(f"  Time Elapsed:                {elapsed:.2f} seconds ({rps:.1f} req/sec)")
    print(f"  Confirmed (201 Created):     {stats['confirmed_201']}")
    print(f"  Idempotent Replay (200 OK):  {stats['replay_200']}")
    print(f"  Declined (409 SEAT_TAKEN):   {stats['conflict_409']}")
    print(f"  Server Errors (5xx):         {stats['server_error_5xx']}")
    print(f"  Connection Errors:           {stats['connection_errors']}")
    print("-" * 64)
    reconciled = (tot == avail + held + conf) and (conf == 1) and (avail == 0)
    print(f"  RECONCILIATION: total={tot} == available={avail} + held={held} + confirmed={conf} -> [{'HOLDS' if reconciled else 'FAILED'}]")
    print("=" * 64)

    if stats["confirmed_201"] == 1 and stats["server_error_5xx"] == 0 and reconciled:
        print("\n>>> ALL CORRECTNESS BARS VERIFIED AT 20,000 SCALE <<<")
        print("  1. Exactly 1 winner for the hot seat (No double-sell).")
        print("  2. Zero 5xx server errors.")
        print(f"  3. Exactly {stats['conflict_409']} losers got clean 409 SEAT_TAKEN.")
        print("  4. Reconciliation invariant verified.")
        return 0
    else:
        print("\n>>> TEST FAILED: Correctness bars violated <<<")
        return 1


def main():
    parser = argparse.ArgumentParser(description="Fire 20,000 concurrent reservations")
    parser.add_argument("base_url", nargs="?", default="http://localhost:8080")
    parser.add_argument("--requests", type=int, default=20000, help="Total requests (default: 20000)")
    parser.add_argument("--concurrency", type=int, default=150, help="Concurrent worker connections (default: 150)")
    parser.add_argument("--jwt-secret", default=None, help="JWT signing secret")
    args = parser.parse_args()

    args.base_url = args.base_url.rstrip("/")
    sys.exit(asyncio.run(run_burst(args)))


if __name__ == "__main__":
    main()
