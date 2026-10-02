# Step 5 — Show State and Reservation Cancellation

This guide adds two required API behaviours to the existing Seat Reservation service:

1. `GET /shows/{id}` — return the show, all seat states, and consistent seat counts.
2. `POST /reservations/{reservationId}/cancel` — let only the reservation owner cancel it and release its seats safely.

The project already uses Spring Boot, JDBC, PostgreSQL, Flyway, JWT-based identity, and Testcontainers. Follow the existing package structure and reuse the existing response/error conventions rather than introducing a second style.

## 1. Contract and decisions

### `GET /shows/{id}`

Success: `200 OK`.

Example response (UUIDs are illustrative):

```json
{
  "id": "7dbad6da-9bfa-46e3-ae7f-d34c8d723525",
  "name": "friday-night",
  "price_paise": 25000,
  "per_user_limit": 4,
  "total_seats": 3,
  "available": 2,
  "held": 0,
  "confirmed": 1,
  "seats": [
    { "seat_id": "00000000-0000-0000-0000-000000000001", "seat": "A1", "status": "confirmed" },
    { "seat_id": "00000000-0000-0000-0000-000000000002", "seat": "A2", "status": "available" },
    { "seat_id": "00000000-0000-0000-0000-000000000003", "seat": "A3", "status": "available" }
  ]
}
```

Use the exact field names and seat DTO shape already defined by `ShowResponse` and `SeatResponse`. The example above illustrates the intended state and counts; do not change existing response contracts unnecessarily.

Missing show: return `404` with the project's established `SHOW_NOT_FOUND` error code.

### `POST /reservations/{reservationId}/cancel`

Identity must come from the authenticated JWT subject (`sub`). Never accept a user ID from the request body or path as the acting identity.

Recommended behaviour:

- Reservation exists and belongs to caller, status `CONFIRMED`: cancel it and release its seats; return `200 OK`.
- Reservation exists but belongs to a different user: return `403 FORBIDDEN`; do not change any rows.
- Reservation does not exist: return `404 RESERVATION_NOT_FOUND`.
- Reservation is already cancelled: treat cancellation as an idempotent replay and return `200 OK` with the already-cancelled result. Do not touch seat rows again.

If the project's established error naming differs, follow the convention already used by `ApiExceptionHandler` and document it in the README.

## 2. Inspect the existing schema before changing it

The original schema is expected to contain:

- `reservations.status` with `CONFIRMED` and `CANCELLED` values
- `reservations.cancelled_at`
- `seats.status` with `AVAILABLE`, `HELD`, and `CONFIRMED` values
- `seats.current_reservation_id`
- `reservation_seats` to retain reservation history

Check these columns in DBeaver or by reading `V1__create_core_schema.sql` and any later migrations. If they already exist, **do not create a redundant migration**. Add a new Flyway migration only if the actual current schema is missing a required field or index.

## 3. GET show state: keep the counts and seat list consistent

Add a `GET` handler to the existing `ShowController`; do not create a duplicate controller for `/shows`.

Suggested responsibilities:

- `ShowController`: parse the UUID and return `200 OK`.
- `ShowService`: call the repository and raise the established show-not-found exception when no show exists.
- `ShowRepository`: read the show and its seats from PostgreSQL and assemble the existing `ShowResponse` / `SeatResponse` DTOs.

### Important consistency detail

Avoid running one query for the seat list and a separate query for counts under ordinary `READ COMMITTED` isolation. A concurrent reservation could commit between the two queries, causing the returned list and counts to describe different database snapshots.

Prefer a **single SQL statement** that returns each seat row alongside window-aggregate counts. For example, adapt this query to your existing DTO/property names:

```sql
SELECT
    sh.id AS show_id,
    sh.name,
    sh.price_paise,
    sh.per_user_limit,
    COUNT(st.id) OVER () AS total_seats,
    COUNT(*) FILTER (WHERE st.status = 'AVAILABLE') OVER () AS available,
    COUNT(*) FILTER (WHERE st.status = 'HELD') OVER () AS held,
    COUNT(*) FILTER (WHERE st.status = 'CONFIRMED') OVER () AS confirmed,
    st.id AS seat_id,
    st.seat_label,
    st.status AS seat_status
FROM shows sh
JOIN seats st ON st.show_id = sh.id
WHERE sh.id = ?
ORDER BY st.seat_label ASC;
```

This assumes a valid show always has at least one seat, which matches the current create-show validation. The list and counts come from the same SQL statement snapshot. If you choose a different approach, make sure the counts and list are still consistent during concurrent updates.

Map the repeated show/count fields from the first returned row and collect every row into the seats list. If the query returns no rows, report `SHOW_NOT_FOUND`.

Keep money as integer paise (`BIGINT` / Java `long`); never convert it to a floating-point type.

## 4. Cancellation: transaction and locking algorithm

Implement cancellation in the existing reservation service and repository layers. The complete state change must run within one Spring `@Transactional` method.

### Required sequence

1. Obtain `userId` from the authenticated JWT subject in the controller/security context.
2. Select the reservation by ID using `SELECT ... FOR UPDATE`. This serializes concurrent cancellation attempts on the same reservation.
3. If the reservation doesn't exist, return the established `404` error.
4. Verify the authenticated user owns it. If not, return `403` without changing state.
5. If its status is already `CANCELLED`, return the existing cancelled result without updating seats.
6. Load and lock the associated seat rows in deterministic order (for example `ORDER BY seat_label ASC FOR UPDATE OF st`).
7. Verify every seat is still `CONFIRMED` and `current_reservation_id` equals this reservation ID. If the expected ownership is inconsistent, fail and roll back rather than partially releasing seats.
8. Release only seats still owned by this reservation.
9. Mark the reservation `CANCELLED` and set `cancelled_at = now()`.
10. Return a response showing the reservation as cancelled and its seat labels.

All database mutations must commit or roll back together.

### Safe seat release SQL

Use a conditional update, not a broad update by `seat_id` or `show_id`:

```sql
UPDATE seats
SET status = 'AVAILABLE',
    current_reservation_id = NULL
WHERE current_reservation_id = ?
  AND status = 'CONFIRMED';
```

Bind the reservation ID to the placeholder. Before running the update, lock and validate the reservation's linked seats. Compare the number of rows released with the expected number of seats. If the counts differ, throw an exception that rolls the transaction back; do not mark the reservation cancelled while leaving a partial seat release.

The condition `current_reservation_id = ?` is important: a repeated/late cancellation must never release a seat that now belongs to a different reservation.

### Lock ordering

- For a multi-seat reservation, the existing reservation code should lock seats in a deterministic order, such as ascending `seat_label`.
- Cancellation should lock its seat rows in that same deterministic order.
- Lock the reservation row before its linked seats to serialize duplicate cancellation requests.

Do not use Java `synchronized` as a correctness mechanism. It does not coordinate separate application instances.

### Keep history

Do not delete the `reservations` or `reservation_seats` rows when cancelling. They are historical records. Only clear the seats' current ownership and change the reservation status. A later reservation should create a new reservation and new reservation-seat links.

## 5. Controller and DTO integration

Follow the naming and style already present in the project. A possible endpoint shape is:

```java
@PostMapping("/reservations/{reservationId}/cancel")
public ResponseEntity<ReservationResponse> cancelReservation(
        @PathVariable UUID reservationId,
        @AuthenticationPrincipal Jwt jwt) {
    String userId = jwt.getSubject();
    ReservationResponse response = reservationService.cancelReservation(
            reservationId, userId);
    return ResponseEntity.ok(response);
}
```

This is a shape example, not a drop-in replacement: adapt the method to your actual `ReservationController`, response record, security configuration, and exception types. If the current response DTO cannot accurately express cancellation, create a small dedicated cancellation response DTO. Keep status values consistent with existing API responses (the JSON API currently uses lowercase values such as `confirmed`).

Ensure the security configuration permits only authenticated users with the correct user scope to call this route. Tests should use the same `jwt()` MockMvc testing pattern already used in `ReservationIntegrationTest`.

## 6. Integration tests to add

Add tests to the existing integration test classes or create a focused `ShowStateAndCancellationIntegrationTest`. Use Testcontainers/PostgreSQL, fresh shows per test, and the existing helpers where practical.

### GET show tests

1. Existing show returns `200` and the correct ID, name, price, limit, seat list, and counts.
2. A fresh show reports all seats as `available`; `held` and `confirmed` are zero.
3. After booking one seat, the GET response reports that seat as `confirmed` and counts reconcile.
4. Unknown show ID returns `404` with `SHOW_NOT_FOUND`.
5. Assert `available + held + confirmed == total_seats`.

### Cancellation tests

1. **Owner cancellation:** book one or more seats, cancel as the owner, verify response is `200`, reservation status is `CANCELLED`, `cancelled_at` is set, and its seats are `AVAILABLE` with `current_reservation_id IS NULL`.
2. **Non-owner cancellation:** a different JWT subject gets `403`; reservation and seat rows remain unchanged.
3. **Unknown reservation:** return `404`.
4. **Repeated cancellation:** return the documented idempotent response (`200` in this guide) and do not change seats a second time.
5. **Rebooking after cancellation:** another user can book the released seat and becomes its current owner; the old reservation stays `CANCELLED`.
6. **Cancellation of a multi-seat reservation:** all its seats are released, or none are released if the transaction fails.
7. **Spoofing:** any `user_id` in a request body cannot change the JWT-derived identity. Prefer a bodyless cancel endpoint so there is no reason to accept a user ID.
8. **Concurrent cancellation:** send two simultaneous cancel requests for the same reservation. They should both follow the documented success/replay semantics, leave one cancelled reservation, and never release a seat owned by a later reservation.
9. **Cancel vs rebook race:** race a cancellation with another user's attempt to book the same seat. Accept only valid serial outcomes: the booking may be declined before release, or succeed after release. Final state must reconcile and the seat must never have two current owners.

For concurrent tests, use a ready latch and a start latch so workers are ready before release. Always assert futures complete; propagate exceptions from `Future.get()`; count unexpected `5xx` statuses as failures.

## 7. DBeaver verification SQL

Replace `YOUR_SHOW_UUID` and `YOUR_RESERVATION_UUID` with test values.

### Inspect the show and seats

```sql
SELECT id, name, price_paise, per_user_limit
FROM shows
WHERE id = 'YOUR_SHOW_UUID';

SELECT seat_label, status, current_reservation_id
FROM seats
WHERE show_id = 'YOUR_SHOW_UUID'
ORDER BY seat_label;
```

### Inspect reservation history

```sql
SELECT id, show_id, user_id, amount_paise, status,
       created_at, cancelled_at
FROM reservations
WHERE id = 'YOUR_RESERVATION_UUID';

SELECT rs.seat_id, s.seat_label, s.status AS current_seat_status,
       s.current_reservation_id
FROM reservation_seats rs
JOIN seats s ON s.id = rs.seat_id AND s.show_id = rs.show_id
WHERE rs.reservation_id = 'YOUR_RESERVATION_UUID'
ORDER BY s.seat_label;
```

After cancellation, the history rows should remain, the reservation should be `CANCELLED`, and its seats should be available unless a new reservation has already rebooked them.

### Verify reconciliation

```sql
SELECT
    COUNT(*) AS total_seats,
    COUNT(*) FILTER (WHERE status = 'AVAILABLE') AS available,
    COUNT(*) FILTER (WHERE status = 'HELD') AS held,
    COUNT(*) FILTER (WHERE status = 'CONFIRMED') AS confirmed
FROM seats
WHERE show_id = 'YOUR_SHOW_UUID';
```

Confirm `total_seats = available + held + confirmed`.

## 8. Postman test sequence

Use a show created through `POST /shows` and a valid user JWT.

1. `GET http://localhost:8080/shows/{showId}` — inspect seat states and counts.
2. `POST http://localhost:8080/shows/{showId}/reserve` — reserve `A1` with an `Idempotency-Key` header.
3. `GET /shows/{showId}` again — `A1` should show as confirmed.
4. `POST http://localhost:8080/reservations/{reservationId}/cancel` — use the same user's JWT.
5. `GET /shows/{showId}` again — `A1` should be available if nobody has rebooked it.
6. Repeat the cancellation request — confirm your documented replay behaviour.
7. Repeat cancellation with a different user's JWT — expect `403` and verify that state is unchanged.
8. Use a different user to reserve the released seat — expect `201`.

Do not deploy an unauthenticated cancellation or administrative endpoint publicly.

## 9. Run the checks

First run the focused tests, adjusting the class name to the one you add:

```bash
./mvnw -Dtest=ShowStateAndCancellationIntegrationTest test
```

Then run the entire suite:

```bash
./mvnw clean test
```

Review results and confirm zero failures, zero errors, and zero skipped tests unless any skipped test is intentional and documented.

## 10. Definition of done

- [ ] `GET /shows/{id}` returns every seat with a consistent status/count snapshot.
- [ ] Missing show returns the documented `404` response.
- [ ] Only the reservation owner can cancel.
- [ ] Cancellation is one transaction: reservation status and all seat releases change together.
- [ ] A cancellation can only release seats still owned by that reservation.
- [ ] A cancelled reservation remains in history.
- [ ] Released seats can be rebooked.
- [ ] Repeated cancellation follows documented replay behaviour.
- [ ] Tests cover non-owner cancellation, multi-seat cancellation, repeat cancellation, rebooking, and cancellation races.
- [ ] Full Maven test suite passes.
- [ ] Manual Postman and DBeaver checks match the automated tests.

Suggested commit after verification:

```bash
git add src/main/java src/test/java
# Add any required migration and documentation files explicitly if you changed them.
git status
git commit -m "feat: add show state and reservation cancellation"
```

Before committing, inspect `git status` and the diff. Do not commit secrets, local-only configuration, or generated files.
