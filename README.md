# Seat Reservation Service

High-concurrency, ACID-compliant Seat Reservation backend service built with Java 21, Spring Boot 3, and PostgreSQL.

## Table of Contents

- [Overview](#overview)
- [Key Features & Architecture](#key-features--architecture)
- [Environment Configuration](#environment-configuration)
- [Running Locally](#running-locally)
- [Demo Token Endpoint](#demo-token-endpoint)
- [API Reference](#api-reference)
- [Testing](#testing)

---

## Overview

The service handles concurrent seat bookings with strict concurrency control, seat limit enforcement per user, idempotent reservations, show state inspection, and reservation cancellations.

Security is implemented via a stateless OAuth2 JWT Resource Server (HS256).

---

## Key Features & Architecture

- **PostgreSQL & Flyway**: Versioned schema migrations tracking shows, seats, reservations, reservation seats, and idempotency records.
- **Strict Concurrency Control**: Uses pessimistic locking (`SELECT ... FOR UPDATE`) in deterministic seat ID order to eliminate race conditions and avoid deadlocks under high concurrency.
- **Per-User Booking Limit**: Enforced transactionally via show-user reservation guards.
- **Atomic Multi-Seat Reservations**: Reservations succeed in their entirety or fail cleanly with zero partial allocation.
- **Idempotency**: Cached responses for repeated requests with identical `Idempotency-Key` and payload; rejection with `IDEMPOTENCY_KEY_REUSED` for altered payloads.
- **Cancellation**: Users can cancel their own active reservations, releasing seats back to `AVAILABLE` status and decrementing user limit counts.

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
> - Outside the demo profile, the route does not exist and returns `404 Not Found`.
> - The endpoint **strictly issues `scope=user` tokens**. It cannot issue admin tokens under any circumstances.
> - Protected by `X-Demo-Key` header with constant-time equality validation.
> - Accepts only synthetic user IDs matching the prefix `loadtest-` (`^loadtest-[A-Za-z0-9_-]{1,48}$`).
> - Token expires after 15 minutes (`900` seconds).
> - `DEMO_TOKEN_KEY` is completely separate from `JWT_SECRET`.
> - Evaluators should receive the `DEMO_TOKEN_KEY` and the separate `admin` JWT via a secure/private communication channel (never committed to Git).

### Request

`POST /auth/demo-token`

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

### Response (`200 OK`)

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
- **Response**: `201 Created` with `Location` header and created show details.

### 2. Get Show State (Public)
- **Method**: `GET /shows/{showId}`
- **Security**: Public
- **Response**: `200 OK` with seat availability counts and seat details.

### 3. Reserve Seats (User)
- **Method**: `POST /shows/{showId}/reserve`
- **Security**: Bearer token with `scope=user`
- **Headers**: `Idempotency-Key: <unique-uuid-or-key>`
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

### 5. Health & Actuator
- **Method**: `GET /actuator/health` and `GET /actuator/health/readiness`
- **Security**: Public

---

## Testing

The project uses Testcontainers for isolated PostgreSQL database tests and MockMvc for API contract verification.

Run all tests:
```bash
./mvnw clean test
```
