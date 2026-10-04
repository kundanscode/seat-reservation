# Seat Reservation Service

High-concurrency, ACID-compliant Seat Reservation backend service built with **Java 21**, **Spring Boot 3**, and **PostgreSQL**.

- **Live Deployment URL:** `https://seat-reservation-api-kundan.onrender.com`
- **Architecture & Design Write-Up:** [`WRITEUP.md`](./WRITEUP.md)

---

## Table of Contents

- [Overview](#overview)
- [Key Features & Architecture](#key-features--architecture)
- [One-Command Burst Testing](#one-command-burst-testing)
- [Observability & Metrics](#observability--metrics)
- [Environment Configuration](#environment-configuration)
- [Running Locally](#running-locally)
- [Demo Token Endpoint](#demo-token-endpoint)
- [API Reference](#api-reference)
- [Testing](#testing)

---

## Overview

The service sells assigned seats for an event (concert or movie hall) with correctness under extreme load:
1. **Never double-sells the same seat** even when thousands of users hit "book" at the exact same millisecond.
2. **Strictly enforces per-user booking limits** across parallel requests.
3. **Database-backed exact idempotency** returning cached confirmations for repeated identical requests and `409 IDEMPOTENCY_KEY_REUSED` for altered payloads.
4. **Zero 5xx errors under contention** — race conditions resolve cleanly to `409 SEAT_TAKEN` or `409 PER_USER_LIMIT_EXCEEDED`.
5. **Real-time observability** with Prometheus counters, DB-backed availability gauges, and structured correlation IDs.

Security is implemented via a stateless OAuth2 JWT Resource Server (HS256).

---

## Key Features & Architecture

- **PostgreSQL & Flyway:** Versioned schema migrations tracking shows, seats, reservations, reservation seats, and idempotency records.
- **Strict Concurrency Control:** Uses pessimistic row locking (`SELECT ... FOR UPDATE`) with **deterministic alphabetical seat sorting** (`ORDER BY seat_number ASC`) to completely eliminate race conditions and avoid cyclic deadlocks.
- **Transactional Per-User Limit:** Enforces show booking limits transactionally, preventing parallel requests from exceeding the limit.
- **Exact Idempotency:** Accepted via HTTP header (`Idempotency-Key`) or JSON body (`idempotency_key`). Requests with matching keys and payloads return `200 OK` with cached original reservations; re-using keys with different payloads yields `409 IDEMPOTENCY_KEY_REUSED`.
- **Atomic Multi-Seat Bookings:** Reservations succeed in their entirety or fail cleanly with zero partial seat allocation.
- **Explicit Cancellation:** Users can cancel their active reservations (`POST /reservations/{id}/cancel`), atomically releasing seats to `AVAILABLE` and decrementing their booking count.
- **Reconciliation Invariant:** For every show, `available + held + confirmed == total_seats` holds to the unit at all times.

---

## One-Command Burst Testing

A standalone script (`./burst.sh`) fires parallel concurrent requests behind a synchronized barrier, testing:
1. **Hot-Seat Race:** 10 concurrent users racing to reserve the same seat simultaneously $\to$ exactly 1 winner (`201 Created`), 9 conflicts (`409 SEAT_TAKEN`), 0 server errors (`5xx`).
2. **Exact Idempotency Replay:** Replaying the winner's key $\to$ `200 OK` with identical reservation ID.
3. **Per-User Limit Storm:** 1 user firing 10 parallel requests for a limit of 4 $\to$ exactly 4 confirmed (`201 Created`), 6 rejected (`409 PER_USER_LIMIT_EXCEEDED`), 0 server errors (`5xx`).
4. **Reconciliation Invariant:** Validates that total seats match the sum of available, held, and confirmed seats.

### Running Against Production:
```bash
./burst.sh https://seat-reservation-api-kundan.onrender.com
```

### Running Locally:
```bash
./burst.sh http://localhost:8080
```

*(Note: When running against `localhost`, `./burst.sh` automatically supplies default evaluation keys for zero-config operation.)*

---

## Observability & Metrics

### 1. Prometheus Metrics Endpoint
Scrape endpoint available at: `GET /actuator/prometheus`

- **Counters:**
  - `reservations_confirmed_total` — Total confirmed seat reservations.
  - `reservations_declined_total{reason="seat-taken"}` — Reservations declined due to taken seats.
  - `reservations_declined_total{reason="per-user-limit"}` — Reservations declined due to per-user limits.
  - `reservations_declined_total{reason="idempotent-replay"}` — Successful idempotent replays.
  - `reservations_declined_total{reason="idempotency-key-reused"}` — Invocations with mismatched payloads.
- **Gauges:**
  - `seats_available` — Live count of seats in `AVAILABLE` status across database.
  - `seats_confirmed` — Live count of seats in `CONFIRMED` status across database.
  - `seats_held` — Live count of seats in `HELD` status across database.

### 2. Structured Logging with Correlation IDs
Every request is assigned a Correlation / Request ID:
- Extracted from incoming `X-Request-ID` or `X-Correlation-ID` header (or generated as a UUID).
- Injected into SLF4J MDC as `requestId`.
- Returned to the client in the `X-Request-ID` response header.
- Logged on every console line:
  ```text
  2026-10-04T09:42:52.931+05:30 WARN [http-nio-8080-exec-5] [req_id=ec74f935-3356-4381-a268-5005b80a74e9] c.k.s.common.ApiExceptionHandler : Seat already taken: Seat is not available: A1
  ```

---

## Environment Configuration

Configure the following environment variables. Do **not** commit secrets to source control.

| Variable | Description | Default | Required in Production |
|---|---|---|---|
| `DB_URL` | PostgreSQL JDBC connection URL | `jdbc:postgresql://localhost:5432/seat_reservation` | Yes |
| `DB_USERNAME` | Database username | `seat_app` | Yes |
| `DB_PASSWORD` | Database password | `seat_app_local_only` | Yes |
| `JWT_ISSUER` | Expected JWT issuer claim (`iss`) | `seat-reservation` | No |
| `JWT_SECRET` | HMAC SHA-256 secret (minimum 32 bytes) | _None_ | **Yes** |
| `DEMO_TOKEN_ENABLED` | Enables the `/auth/demo-token` endpoint | `false` | Only for evaluator demo |
| `DEMO_TOKEN_KEY` | Secret key for `X-Demo-Key` authentication | _None_ | If demo enabled |
| `SPRING_PROFILES_ACTIVE` | Spring active profiles (e.g. `demo`) | `default` | Set `demo` for demo endpoint |

---

## Running Locally

### 1. Start PostgreSQL
```bash
docker run -d --name postgres-seat-res \
  -e POSTGRES_DB=seat_reservation \
  -e POSTGRES_USER=seat_app \
  -e POSTGRES_PASSWORD=seat_app_local_only \
  -p 5432:5432 \
  postgres:16-alpine
```

### 2. Export Environment Variables
```bash
export JWT_SECRET="$(openssl rand -base64 32)"
export DEMO_TOKEN_KEY="$(openssl rand -base64 32)"
export DEMO_TOKEN_ENABLED=true
export SPRING_PROFILES_ACTIVE=demo
```

### 3. Run the Application
```bash
./mvnw spring-boot:run
```

---

## Demo Token Endpoint

A **development/evaluator-only endpoint** is provided to generate short-lived JWTs for synthetic user accounts without requiring a complete user registration/login flow.

> **Security Guardrails:**
> - The endpoint is **disabled by default**. It is active **only** when `SPRING_PROFILES_ACTIVE=demo` **and** `DEMO_TOKEN_ENABLED=true`.
> - Outside the demo profile, the route returns `404 Not Found`.
> - The endpoint **strictly issues `scope=user` tokens**. It cannot issue admin tokens under any circumstances.
> - Protected by `X-Demo-Key` header with constant-time equality validation.
> - Accepts only synthetic user IDs matching the prefix `loadtest-` (`^loadtest-[A-Za-z0-9_-]{1,48}$`).
> - Token expires after 15 minutes (`900` seconds).

### Request: `POST /auth/demo-token`
**Headers:**
```http
Content-Type: application/json
X-Demo-Key: <configured-demo-token-key>
```
**Body:**
```json
{
  "user_id": "loadtest-001"
}
```

### Response (`200 OK`):
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiJ9...",
  "token_type": "Bearer",
  "expires_in": 900
}
```

---

## API Reference

### 1. Create Show (Admin)
- **Method**: `POST /shows`
- **Security**: Bearer token with `scope=admin`
- **Body**:
  ```json
  {
    "name": "Evening Gala",
    "seats": ["A1", "A2", "A3", "B1", "B2"],
    "price_paise": 25000,
    "per_user_limit": 4
  }
  ```
- **Response**: `201 Created` with created show details.

### 2. Get Show State (Public)
- **Method**: `GET /shows/{showId}`
- **Security**: Public
- **Response**: `200 OK` with seat availability counts (`available_seats`, `held_seats`, `confirmed_seats`, `total_seats`) and seat details.

### 3. Reserve Seats (User)
- **Method**: `POST /shows/{showId}/reserve`
- **Security**: Bearer token with `scope=user`
- **Headers**: `Idempotency-Key: <uuid-or-key>` (or in body as `idempotency_key`)
- **Body**:
  ```json
  {
    "seats": ["A1", "A2"]
  }
  ```
- **Response**: `201 Created` (or `200 OK` on idempotency replay) with reservation details.

### 4. Cancel Reservation (User)
- **Method**: `POST /reservations/{reservationId}/cancel`
- **Security**: Bearer token with `scope=user` (must match reservation owner)
- **Response**: `200 OK` with cancellation confirmation and released seats.

### 5. Health & Readiness (Public)
- `GET /actuator/health` — Liveness status.
- `GET /actuator/health/readiness` — Readiness probe (validates database connection; fails closed if DB unreachable).

---

## Testing

The project uses Testcontainers for isolated PostgreSQL database tests and MockMvc for API contract verification.

Run all tests:
```bash
./mvnw clean test
```
