#!/usr/bin/env python3
"""Bounded concurrency smoke and burst test for the Seat Reservation API.

Uses only the Python standard library. It creates fresh test shows, races
multiple synthetic users for one seat, verifies idempotent replay, and submits
parallel bookings from one user to test the per-user limit.

Prints outcome distribution (confirmed / declined-by-reason / 5xx) and
final reconciliation as required by the assignment.
"""

from __future__ import annotations

import argparse
import base64
import getpass
import hashlib
import hmac
import json
import os
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

DEFAULT_BASE_URL = "https://seat-reservation-api-kundan.onrender.com"
DEFAULT_TIMEOUT = 30
MAX_WORKERS = 1000


@dataclass
class ApiResult:
    status: int
    data: dict[str, Any] | list[Any] | None


def request_json(
    base_url: str,
    method: str,
    path: str,
    *,
    body: dict[str, Any] | None = None,
    headers: dict[str, str] | None = None,
    timeout: int = DEFAULT_TIMEOUT,
) -> ApiResult:
    payload = None if body is None else json.dumps(body).encode("utf-8")
    request_headers = {"Accept": "application/json", "User-Agent": "seat-reservation-burst-test/1.0"}
    if payload is not None:
        request_headers["Content-Type"] = "application/json"
    if headers:
        request_headers.update(headers)

    request = Request(
        url=f"{base_url.rstrip('/')}{path}",
        data=payload,
        headers=request_headers,
        method=method,
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            status = response.status
            raw = response.read()
    except HTTPError as exc:
        status = exc.code
        raw = exc.read()
    except (URLError, TimeoutError, OSError) as exc:
        raise RuntimeError(f"Request failed ({type(exc).__name__}) for {method} {path}") from None

    if not raw:
        return ApiResult(status=status, data=None)
    try:
        parsed = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        parsed = None
    return ApiResult(status=status, data=parsed)


def safe_error_code(result: ApiResult) -> str | None:
    if isinstance(result.data, dict):
        for field in ("code", "error_code", "error"):
            value = result.data.get(field)
            if isinstance(value, str) and value:
                return value[:80]
    return None


def require_status(result: ApiResult, expected: int, action: str) -> dict[str, Any]:
    if result.status != expected:
        code = safe_error_code(result)
        suffix = f" (error code: {code})" if code else ""
        raise RuntimeError(f"{action}: expected HTTP {expected}, got HTTP {result.status}{suffix}")
    if not isinstance(result.data, dict):
        raise RuntimeError(f"{action}: expected a JSON object in the response")
    return result.data


def generate_local_jwt(secret: str, subject: str, scope: str) -> str:
    def b64url(data: bytes) -> str:
        return base64.urlsafe_b64encode(data).decode("utf-8").rstrip("=")

    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {"iss": "seat-reservation", "sub": subject, "scope": scope, "iat": now, "exp": now + 86400}
    signing_input = f"{b64url(json.dumps(header).encode())}.{b64url(json.dumps(payload).encode())}".encode()
    signature = hmac.new(secret.encode("utf-8"), signing_input, hashlib.sha256).digest()
    return f"{signing_input.decode('utf-8')}.{b64url(signature)}"


def get_secret(env_name: str, prompt: str) -> str:
    value = os.environ.get(env_name, "").strip()
    if not value:
        # Check if JWT_SECRET is set to auto-generate ADMIN_TOKEN
        if env_name == "ADMIN_TOKEN" and os.environ.get("JWT_SECRET"):
            return generate_local_jwt(os.environ["JWT_SECRET"].strip(), "admin-burst", "admin")
        try:
            value = getpass.getpass(prompt).strip()
        except (io.UnsupportedOperation, EOFError):
            value = ""
    if not value:
        raise RuntimeError(f"Missing required credential: {env_name}")
    return value


def check_base_url(base_url: str) -> str:
    base_url = base_url.rstrip("/")
    parsed = urlparse(base_url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise RuntimeError("BASE_URL must be an absolute http(s) URL")
    local_hosts = {"localhost", "127.0.0.1", "::1"}
    if parsed.scheme != "https" and parsed.hostname not in local_hosts:
        raise RuntimeError("Use HTTPS for a non-local BASE_URL")
    return base_url


def readiness_check(base_url: str, timeout: int) -> None:
    result = request_json(base_url, "GET", "/actuator/health/readiness", timeout=timeout)
    data = require_status(result, 200, "Readiness check")
    if data.get("status") != "UP":
        raise RuntimeError("Readiness endpoint did not report status UP")
    print("[PASS] Readiness endpoint reports UP")


def issue_demo_token(base_url: str, demo_key: str, user_id: str, timeout: int) -> str:
    result = request_json(
        base_url,
        "POST",
        "/auth/demo-token",
        body={"user_id": user_id},
        headers={"X-Demo-Key": demo_key},
        timeout=timeout,
    )
    if result.status == 404:
        raise RuntimeError(
            "POST /auth/demo-token returned 404. Demo token endpoint is disabled on target deployment."
        )
    if result.status in (401, 403):
        raise RuntimeError(
            f"Demo-token request was rejected (HTTP {result.status}). Check DEMO_TOKEN_KEY."
        )
    data = require_status(result, 200, f"Issue demo token for {user_id}")
    token = data.get("access_token")
    if not isinstance(token, str) or not token:
        raise RuntimeError("Demo-token endpoint did not return access_token")
    return token


def create_show(
    base_url: str,
    admin_token: str,
    name: str,
    seats: list[str],
    per_user_limit: int,
    timeout: int,
) -> str:
    result = request_json(
        base_url,
        "POST",
        "/shows",
        body={
            "name": name,
            "seats": seats,
            "price_paise": 100,
            "per_user_limit": per_user_limit,
        },
        headers={"Authorization": f"Bearer {admin_token}"},
        timeout=timeout,
    )
    data = require_status(result, 201, f"Create test show {name}")
    show_id = data.get("id")
    if not isinstance(show_id, str) or not show_id:
        raise RuntimeError(f"Creating {name} succeeded but no show id was returned")
    return show_id


def reserve(
    base_url: str,
    show_id: str,
    seat_label: str,
    token: str,
    idempotency_key: str,
    timeout: int,
) -> ApiResult:
    return request_json(
        base_url,
        "POST",
        f"/shows/{show_id}/reserve",
        body={"seats": [seat_label], "idempotency_key": idempotency_key},
        headers={
            "Authorization": f"Bearer {token}",
            "Idempotency-Key": idempotency_key,
        },
        timeout=timeout,
    )


def concurrent_reservations(
    jobs: list[tuple[str, str, str, str]],
    *,
    base_url: str,
    show_id: str,
    timeout: int,
) -> list[tuple[int, str, str, ApiResult | None, str | None]]:
    """Run (user_id, token, idempotency_key, seat) jobs behind a shared start barrier."""
    barrier = threading.Barrier(len(jobs))

    def run_one(job: tuple[str, str, str, str]):
        user_id, token, key, seat = job
        try:
            barrier.wait(timeout=30)
            result = reserve(base_url, show_id, seat, token, key, timeout)
            return user_id, key, result, None
        except Exception as exc:
            return user_id, key, None, type(exc).__name__

    results: list[tuple[int, str, str, ApiResult | None, str | None]] = []
    with ThreadPoolExecutor(max_workers=len(jobs), thread_name_prefix="seat-burst") as executor:
        futures = [executor.submit(run_one, job) for job in jobs]
        for index, future in enumerate(as_completed(futures), start=1):
            user_id, key, result, error_type = future.result()
            results.append((index, user_id, key, result, error_type))
    return results


def fetch_show(base_url: str, show_id: str, token: str, timeout: int) -> dict[str, Any]:
    result = request_json(
        base_url,
        "GET",
        f"/shows/{show_id}",
        headers={"Authorization": f"Bearer {token}"},
        timeout=timeout,
    )
    return require_status(result, 200, f"Fetch show state for {show_id}")


def verify_reconciliation(show: dict[str, Any], label: str, expected_total: int) -> dict[str, int]:
    fields = ("total_seats", "available", "held", "confirmed")
    counts: dict[str, int] = {}
    for field in fields:
        value = show.get(field)
        if not isinstance(value, int):
            raise RuntimeError(f"{label}: response is missing integer field {field}")
        counts[field] = value

    if counts["total_seats"] != expected_total:
        raise RuntimeError(
            f"{label}: expected total_seats={expected_total}, got {counts['total_seats']}"
        )
    if counts["total_seats"] != counts["available"] + counts["held"] + counts["confirmed"]:
        raise RuntimeError(f"{label}: seat-count reconciliation invariant failed")

    seats = show.get("seats")
    if not isinstance(seats, list) or len(seats) != expected_total:
        raise RuntimeError(f"{label}: response seat list does not match total_seats")
    confirmed_in_list = sum(
        1 for item in seats
        if isinstance(item, dict) and str(item.get("status", "")).lower() == "confirmed"
    )
    if confirmed_in_list != counts["confirmed"]:
        raise RuntimeError(f"{label}: confirmed count does not match the seat list")
    print(
        f"[PASS] {label}: total={counts['total_seats']}, available={counts['available']}, "
        f"held={counts['held']}, confirmed={counts['confirmed']}"
    )
    return counts


def print_outcome_distribution(
    title: str,
    results: list[tuple[int, str, str, ApiResult | None, str | None]],
    reconciliation: dict[str, int] | None = None,
) -> dict[str, int]:
    confirmed = 0
    replayed = 0
    seat_taken = 0
    per_user_limit = 0
    idempotent_reused = 0
    other_4xx = 0
    server_errors_5xx = 0
    worker_failures = 0

    for _, _, _, res, err in results:
        if err or res is None:
            worker_failures += 1
            continue
        code = safe_error_code(res) or ""
        if res.status == 201:
            confirmed += 1
        elif res.status == 200:
            replayed += 1
        elif res.status == 409:
            if "SEAT_TAKEN" in code:
                seat_taken += 1
            elif "PER_USER_LIMIT" in code:
                per_user_limit += 1
            elif "IDEMPOTENCY_KEY_REUSED" in code:
                idempotent_reused += 1
            else:
                seat_taken += 1  # default 409 domain decline
        elif 400 <= res.status < 500:
            other_4xx += 1
        elif res.status >= 500:
            server_errors_5xx += 1

    total = len(results)
    print("\n" + "=" * 64)
    print(f"OUTCOME DISTRIBUTION: {title}")
    print("=" * 64)
    print(f"  Total Requests Attempted:    {total}")
    print(f"  Confirmed (201 Created):     {confirmed}")
    print(f"  Idempotent Replay (200 OK):  {replayed}")
    print("  Declined by Reason (4xx):")
    print(f"    • seat-taken (409):        {seat_taken}")
    print(f"    • per-user-limit (409):    {per_user_limit}")
    print(f"    • key-reused (409):        {idempotent_reused}")
    if other_4xx:
        print(f"    • other 4xx:               {other_4xx}")
    print(f"  Server Errors (5xx):         {server_errors_5xx}")
    if worker_failures:
        print(f"  Worker Failures (Timeout):   {worker_failures}")

    if reconciliation:
        tot = reconciliation["total_seats"]
        av = reconciliation["available"]
        hd = reconciliation["held"]
        cf = reconciliation["confirmed"]
        inv_holds = (tot == av + hd + cf)
        print("-" * 64)
        print(f"  RECONCILIATION INVARIANT: total={tot} == available={av} + held={hd} + confirmed={cf} -> {'[HOLDS]' if inv_holds else '[FAILED]'}")
    print("=" * 64 + "\n")

    return {
        "confirmed": confirmed,
        "replayed": replayed,
        "seat_taken": seat_taken,
        "per_user_limit": per_user_limit,
        "5xx": server_errors_5xx,
    }


def assert_race_results(
    results: list[tuple[int, str, str, ApiResult | None, str | None]],
    *,
    expected_winners: int,
    expected_conflicts: int,
    label: str,
) -> None:
    worker_errors = sum(1 for _, _, _, result, error in results if result is None or error is not None)
    if worker_errors:
        raise RuntimeError(f"{label}: {worker_errors} request worker(s) failed before receiving an HTTP response")

    created = sum(1 for _, _, _, res, _ in results if res and res.status == 201)
    conflicts = sum(1 for _, _, _, res, _ in results if res and res.status == 409)
    errors_5xx = sum(1 for _, _, _, res, _ in results if res and res.status >= 500)

    if errors_5xx > 0:
        raise RuntimeError(f"{label}: encountered {errors_5xx} server errors (HTTP 5xx); expected 0")

    if created != expected_winners or conflicts != expected_conflicts:
        raise RuntimeError(
            f"{label}: expected {expected_winners} HTTP 201 and {expected_conflicts} HTTP 409, "
            f"got {created} HTTP 201 and {conflicts} HTTP 409"
        )
    print(f"[PASS] {label}: {created} confirmed, {conflicts} conflicts, zero 5xx")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run a concurrency burst test against the Seat Reservation API."
    )
    parser.add_argument("url", nargs="?", default=None, help="Base URL of service (optional positional arg)")
    parser.add_argument("--base-url", default=os.environ.get("BASE_URL", None))
    parser.add_argument("--users", type=int, default=10, help="Distinct users racing for one seat (2-25; default: 10)")
    parser.add_argument("--per-user-limit", type=int, default=4, help="Limit configured on test show (default: 4)")
    parser.add_argument("--limit-seats", type=int, default=10, help="Distinct seats for per-user-limit test (default: 10)")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT, help="HTTP timeout per request in seconds")
    args = parser.parse_args()

    effective_url = args.url or args.base_url or DEFAULT_BASE_URL

    if not 2 <= args.users <= MAX_WORKERS:
        parser.error(f"--users must be between 2 and {MAX_WORKERS}")
    if not 1 <= args.per_user_limit < args.limit_seats:
        parser.error("--per-user-limit must be at least 1 and less than --limit-seats")
    if args.limit_seats > 25:
        parser.error("--limit-seats must be 25 or fewer for this test")
    if args.timeout < 5 or args.timeout > 120:
        parser.error("--timeout must be between 5 and 120 seconds")

    try:
        base_url = check_base_url(effective_url)
        admin_token = get_secret("ADMIN_TOKEN", "Admin JWT for this deployment (input hidden): ")
        demo_key = get_secret("DEMO_TOKEN_KEY", "Demo-token access key (input hidden): ")
        run_id = time.strftime("%Y%m%dT%H%M%S", time.gmtime()) + "-" + uuid.uuid4().hex[:6]

        print("=" * 64)
        print("SEAT RESERVATION BURST TEST RUN")
        print("=" * 64)
        print(f"Target URL:         {base_url}")
        print(f"Concurrency:        {args.users} users racing hot seat")
        print(f"Per-User Limit:     {args.per_user_limit} (out of {args.limit_seats} seats)")
        print(f"Timeout:            {args.timeout}s")
        print("=" * 64)

        readiness_check(base_url, args.timeout)

        # 1. Issue tokens
        hot_user_ids = [f"loadtest-hot-{index:03d}" for index in range(1, args.users + 1)]
        limit_user_id = "loadtest-limit-001"
        jwt_secret = os.environ.get("JWT_SECRET", "").strip()
        if jwt_secret:
            hot_tokens = {
                user_id: generate_local_jwt(jwt_secret, user_id, "user")
                for user_id in hot_user_ids
            }
            limit_token = generate_local_jwt(jwt_secret, limit_user_id, "user")
        else:
            with ThreadPoolExecutor(max_workers=min(32, args.users)) as pool:
                tokens = list(pool.map(
                    lambda uid: issue_demo_token(base_url, demo_key, uid, args.timeout),
                    hot_user_ids
                ))
                hot_tokens = dict(zip(hot_user_ids, tokens))
            limit_token = issue_demo_token(base_url, demo_key, limit_user_id, args.timeout)

        # 2. Create shows
        hot_show_id = create_show(
            base_url, admin_token, f"burst-hot-{run_id}", ["A1"], args.per_user_limit, args.timeout
        )
        limit_seats = [f"B{index}" for index in range(1, args.limit_seats + 1)]
        limit_show_id = create_show(
            base_url,
            admin_token,
            f"burst-limit-{run_id}",
            limit_seats,
            args.per_user_limit,
            args.timeout,
        )
        print(f"Created hot-seat show:       {hot_show_id}")
        print(f"Created per-user-limit show: {limit_show_id}")

        # 3. Race 1: Hot-seat storm
        print("\nFiring Hot-Seat Concurrency Storm (many users -> 1 seat)...")
        hot_jobs = [
            (user_id, hot_tokens[user_id], f"burst-hot-{run_id}-{index:03d}", "A1")
            for index, user_id in enumerate(hot_user_ids, start=1)
        ]
        hot_results = concurrent_reservations(
            hot_jobs,
            base_url=base_url,
            show_id=hot_show_id,
            timeout=args.timeout,
        )
        assert_race_results(
            hot_results,
            expected_winners=1,
            expected_conflicts=args.users - 1,
            label="Hot-seat race",
        )

        winner = next(row for row in hot_results if row[3] is not None and row[3].status == 201)
        _, winner_user_id, winner_key, winner_result, _ = winner
        assert winner_result is not None
        winner_token = hot_tokens[winner_user_id]
        original_data = winner_result.data if isinstance(winner_result.data, dict) else {}
        original_reservation_id = original_data.get("reservation_id")
        if not isinstance(original_reservation_id, str) or not original_reservation_id:
            raise RuntimeError("Hot-seat winner response did not include reservation_id")

        # 4. Idempotency Replay
        replay_result = reserve(base_url, hot_show_id, "A1", winner_token, winner_key, args.timeout)
        replay_data = require_status(replay_result, 200, "Idempotency replay")
        if replay_data.get("reservation_id") != original_reservation_id:
            raise RuntimeError("Idempotency replay returned a different reservation_id")
        print("[PASS] Idempotency replay: HTTP 200 and identical reservation_id")

        hot_state = fetch_show(base_url, hot_show_id, winner_token, args.timeout)
        hot_counts = verify_reconciliation(hot_state, "Hot-seat show reconciliation", expected_total=1)
        print_outcome_distribution("Hot-Seat Storm", hot_results, hot_counts)

        # 5. Race 2: Per-user limit concurrency burst
        print(f"\nFiring Per-User Limit Concurrency Storm (1 user -> {args.limit_seats} parallel requests for limit={args.per_user_limit})...")
        limit_jobs = [
            (limit_user_id, limit_token, f"burst-limit-{run_id}-{index:03d}", seat)
            for index, seat in enumerate(limit_seats, start=1)
        ]
        limit_results = concurrent_reservations(
            limit_jobs,
            base_url=base_url,
            show_id=limit_show_id,
            timeout=args.timeout,
        )
        assert_race_results(
            limit_results,
            expected_winners=args.per_user_limit,
            expected_conflicts=args.limit_seats - args.per_user_limit,
            label="Concurrent per-user-limit test",
        )

        limit_state = fetch_show(base_url, limit_show_id, limit_token, args.timeout)
        limit_counts = verify_reconciliation(
            limit_state, "Per-user-limit show reconciliation", expected_total=args.limit_seats
        )
        print_outcome_distribution("Per-User Limit Burst", limit_results, limit_counts)

        # 6. Overall Summary
        all_results = hot_results + limit_results
        total_counts = {
            "total_seats": hot_counts["total_seats"] + limit_counts["total_seats"],
            "available": hot_counts["available"] + limit_counts["available"],
            "held": hot_counts["held"] + limit_counts["held"],
            "confirmed": hot_counts["confirmed"] + limit_counts["confirmed"],
        }
        print_outcome_distribution("OVERALL COMBINED BURST", all_results, total_counts)

        print("\n>>> ALL CORRECTNESS BARS VERIFIED SUCCESSFULLY <<<")
        print("  1. No double-sell (exactly 1 winner for hot seat).")
        print("  2. Zero 5xx errors across the burst.")
        print("  3. Reconciliation invariant holds to the unit.")
        print("  4. Exact idempotency replay returns same reservation.")
        print(f"  5. Per-user limit ({args.per_user_limit}) strictly held under parallel requests.")
        print("  6. Identity verified from auth token.")
        return 0
    except (RuntimeError, StopIteration) as exc:
        print(f"\nTEST FAILED: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nInterrupted by user.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
