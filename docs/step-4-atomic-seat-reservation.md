# Step 4 — Atomic Seat Reservation

**Planned commit:** `feat: implement atomic seat reservation`

This guide builds the first version of `POST /shows/{id}/reserve`. It assumes these earlier milestones are complete:

- Spring Boot application starts locally.
- PostgreSQL runs through Docker Compose.
- Flyway migration `V1__create_core_schema.sql` has been applied.
- `POST /shows` creates a show and all its seats in one transaction.
- Show creation and validation tests pass.
- Postman and DBeaver are installed and connected to the local application/database.

The goal is to implement a reservation flow that is correct under concurrent requests—not just one that works when tested manually.

---

## 1. Scope and explicit behaviour

For this milestone, implement:

- `POST /shows/{id}/reserve`
- JWT authentication and token-derived user identity
- Atomic seat reservation using PostgreSQL row locks
- A per-user/per-show booking limit
- All-or-nothing multi-seat requests
- Database-backed idempotency
- Consistent domain errors
- PostgreSQL integration tests for concurrency

We will use explicit cancellation in a later step, not timed holds. Consequently, the first reservation implementation will create `CONFIRMED` reservations directly; it will not create `HELD` seats.

### API behaviour decisions

| Situation | Behaviour |
|---|---|
| All requested seats are available and under the limit | `201 Created` |
| Same key and same request retried | `200 OK`, return the stored original response |
| Any requested seat is already held/confirmed | `409 Conflict`, code `SEAT_TAKEN` |
| User would exceed the show's seat limit | `409 Conflict`, code `PER_USER_LIMIT_EXCEEDED` |
| Same key used by the same user/show with different seats | `409 Conflict`, code `IDEMPOTENCY_KEY_REUSED` |
| Requested seat label doesn't exist in this show | `404 Not Found`, code `SEAT_NOT_FOUND` |
| Duplicate seat labels in one request after trimming | `400 Bad Request`, code `INVALID_REQUEST` |
| Invalid/missing JWT | `401 Unauthorized` |
| Valid token without the required authority | `403 Forbidden` |
| Show doesn't exist | `404 Not Found`, code `SHOW_NOT_FOUND` |

**Multi-seat policy: all-or-nothing.** If a request asks for `A12` and `A13`, but either seat is unavailable, the whole request is declined and neither requested seat is newly reserved.

The assignment's default per-user limit is four. Read the configured limit from the `shows` row rather than hardcoding `4` in the reservation service.

---

## 2. Add dependencies

Add these dependencies to `pom.xml` if they are not already present. Let the Spring Boot parent manage versions; do not add arbitrary versions for these dependencies.

```xml
<!-- JWT validation and stateless API security -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>

<!-- Security test helpers, including MockMvc jwt() support -->
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

Keep the JDBC, PostgreSQL, Flyway, Actuator, Micrometer, Spring Boot test and Testcontainers dependencies from the previous step. This implementation uses `JdbcTemplate` for SQL whose locking behaviour should be visible and explicit.

---

## 3. Add a Flyway migration for exact idempotent replay

The existing `idempotency_records` table stores the request hash and reservation ID. For a stronger idempotency contract, also store the original response payload. This allows a retry to return the original response even if the reservation is subsequently changed by a later operation, such as cancellation.

Create:

`src/main/resources/db/migration/V2__store_idempotency_response.sql`

```sql
ALTER TABLE idempotency_records
    ADD COLUMN response_json JSONB;
```

Why is the new column nullable in this migration? It makes the migration safe for a database that might already contain older idempotency rows. New code must always write `response_json` for every successful reservation. If this application is still a local-only project and you have verified the table is empty, you may choose to enforce `NOT NULL` in a later migration after backfilling any existing rows.

Do not edit the already-applied V1 migration. Flyway records checksums; a new change to an applied migration can break startup in an existing environment.

After adding V2, restart the application and verify in DBeaver or `psql` that the `response_json` column exists and V2 appears in `flyway_schema_history`.

---

## 4. Reservation request and response DTOs

Create a request record such as:

`ReserveSeatsRequest.java`

```java
public record ReserveSeatsRequest(
        @NotEmpty
        @Size(max = 20)
        List<@NotBlank @Size(max = 40) String> seats
) {}
```

The maximum of 20 seats per request is an API safety bound, not the per-user booking limit. A request can still exceed the configured booking limit once existing confirmed seats are counted. Keep this bound documented; adjust it if the product contract changes.

The idempotency key should be sent in the `Idempotency-Key` HTTP header, not in the JSON body. Require a nonblank key of a reasonable maximum length (for example 200 characters).

Create a response record matching the assignment contract, for example:

```java
public record ReservationResponse(
        UUID reservation_id,
        UUID show_id,
        String user_id,
        List<String> seats,
        long amount_paise,
        String status
) {}
```

Java record component naming is conventionally camelCase. If your project already uses camelCase components, keep those and configure JSON naming/annotations so the JSON contract uses the assignment's expected field names. Do not rename all Java fields to snake_case just to produce snake_case JSON.

The response for a newly created reservation must contain:

```json
{
  "reservation_id": "<uuid>",
  "show_id": "<uuid>",
  "user_id": "user-101",
  "seats": ["A12"],
  "amount_paise": 25000,
  "status": "confirmed"
}
```

Use integer minor units (`long` / PostgreSQL `BIGINT`) for money. Never calculate money using `float` or `double`.

---

## 5. Authentication: identity must come from the JWT

The request body must not decide the user identity. Do not add a `user_id` field to `ReserveSeatsRequest`.

Use Spring Security's OAuth2 Resource Server support to validate signed JWTs. The JWT `sub` claim will be the application user ID. Use a scope claim to distinguish a normal user from an admin:

- `scope: "user"` for reservation requests
- `scope: "admin"` for creating shows

Spring Security maps these to `SCOPE_user` and `SCOPE_admin` authorities by default.

### Application configuration

Keep the secret outside source control. Add the following configuration to `application.yml` (merge it with your existing configuration rather than replacing other settings):

```yaml
app:
  security:
    jwt-issuer: seat-reservation
    jwt-secret: ${JWT_SECRET}
```

Set a local secret in the terminal before starting Spring Boot. Use a long random value of at least 32 bytes for local HS256 signing, and never commit the actual value:

```bash
export JWT_SECRET='replace-with-a-long-random-local-development-secret'
./mvnw spring-boot:run
```

For deployment, configure a different secret through the hosting platform's environment variables. Do not reuse the local secret in production.

### Security configuration requirements

Create a `SecurityFilterChain` that:

- disables CSRF for this stateless JSON API;
- uses stateless sessions;
- permits health endpoints needed by deployment probes;
- permits `GET /shows/**` if show state is intended to be publicly readable;
- requires `SCOPE_admin` for `POST /shows`;
- requires `SCOPE_user` for `POST /shows/*/reserve`;
- requires authentication for other non-public routes;
- configures JWT validation with HS256 and issuer `seat-reservation`.

Create a `JwtDecoder` backed by the configured secret and validate the issuer and timestamps. Do not accept unsigned tokens or trust an unverified `sub` claim.

**Important:** do not add an unrestricted public endpoint that lets callers mint their own admin JWT. For local testing, generate signed test tokens with a small local script that reads `JWT_SECRET`, or use Spring Security's `jwt()` test helper in tests. Do not commit real secrets or production tokens.

Update the existing show-creation integration tests to authenticate as an admin, since `POST /shows` is no longer a public endpoint.

---

## 6. Repository operations needed for safe booking

Keep SQL in `ReservationRepository` (or a similarly named repository), not inside the controller. The repository should provide narrowly named operations for:

1. Find show price and per-user limit.
2. Insert a guard row if it doesn't already exist.
3. Lock that user's guard row with `SELECT ... FOR UPDATE`.
4. Find an existing idempotency record for `(show_id, user_id, idempotency_key)`.
5. Lock a seat row by `(show_id, seat_label)` using `SELECT ... FOR UPDATE`.
6. Count the user's currently confirmed seats for that show.
7. Insert the reservation.
8. Insert `reservation_seats` rows.
9. Update each seat to `CONFIRMED` and set `current_reservation_id`.
10. Insert the idempotency record including the request hash, reservation ID and response JSON.

Use prepared parameters for all user input. Never concatenate user-supplied labels or IDs into SQL strings.

### Lock acquisition order

Use a consistent sequence across all booking requests:

1. Validate and normalize the request before opening the transaction where possible.
2. Read the show's immutable price and limit; return `404` if it doesn't exist.
3. Create the `(show_id, user_id)` guard row with `INSERT ... ON CONFLICT DO NOTHING`.
4. Lock that guard row using `SELECT ... FOR UPDATE`.
5. Check idempotency and return a replay if a matching successful record exists.
6. Lock requested seats **one at a time in sorted label order**.
7. Count currently confirmed seats for the user/show and enforce the limit.
8. Insert the reservation and reservation-seat links; update the seat rows.
9. Insert the idempotency record with the saved response JSON.
10. Commit.

Locking requested seat rows one at a time in sorted order is explicit and avoids relying on a query plan to acquire multiple row locks in the desired order. All code paths that lock multiple seat rows should follow the same ordering rule.

The guard row is necessary because two concurrent requests from the same user could otherwise both count the same existing reservations and both pass the per-user limit. Ensure the guard row exists before selecting it `FOR UPDATE`; selecting a missing row cannot lock it.

---

## 7. Request normalization and request hashing

Before reserving:

- trim each seat label;
- reject null or blank labels;
- reject duplicates after trimming;
- sort the normalized seat labels for deterministic locking and hashing;
- require a nonblank `Idempotency-Key` header;
- calculate a SHA-256 hash of a canonical representation of the sorted seat list.

Do not change label case if the project intentionally treats `A1` and `a1` as distinct seats. Keep this policy consistent with show creation and the database collation/unique constraint.

Use a deterministic serialization of the sorted list (for example, Jackson serializing the sorted list to JSON) as the hash input. This avoids ambiguous concatenations. Store the SHA-256 digest as 64 hexadecimal characters.

The idempotency key is scoped to the authenticated user and show by the primary key already defined in V1:

`(show_id, user_id, idempotency_key)`

Never scope idempotency globally across all users or shows.

---

## 8. Transactional reservation algorithm

The reservation method should be a public service method marked `@Transactional`. Keep it on a Spring-managed service bean and call it through the Spring proxy; self-invocation within the same class does not apply proxy-based transaction advice.

High-level algorithm:

```text
reserve(showId, authenticatedUserId, idempotencyKey, request):
    normalize and validate the seat list
    canonicalHash = SHA256(canonicalJson(sortedSeats))

    BEGIN TRANSACTION

    show = find show price and per-user limit
    if show missing:
        return/raise SHOW_NOT_FOUND

    ensure (showId, userId) guard row exists
    lock this guard row FOR UPDATE

    prior = find idempotency record for show/user/key
    if prior exists:
        if prior.requestHash != canonicalHash:
            raise IDEMPOTENCY_KEY_REUSED (409)
        return deserialize prior.responseJson

    lockedSeats = lock every requested seat in sorted order
    if any requested label does not exist in this show:
        raise SEAT_NOT_FOUND (404)
    if any locked seat is not AVAILABLE:
        raise SEAT_TAKEN (409)

    currentCount = count seats belonging to this user's CONFIRMED reservations
    if currentCount + requestedSeatCount > show.perUserLimit:
        raise PER_USER_LIMIT_EXCEEDED (409)

    amount = Math.multiplyExact(show.pricePaise, requestedSeatCount)
    create reservation with status CONFIRMED
    link every seat using reservation_seats
    update every locked seat to CONFIRMED and set current_reservation_id
    construct ReservationResponse
    insert idempotency row with requestHash, reservationId, responseJson

    COMMIT
    return response with HTTP 201
```

The replay path returns the stored response with HTTP `200 OK` and must not create another reservation. It must be checked before checking current seat availability: a successful retry is not supposed to fail just because the seat is now taken by its own original reservation.

`Math.multiplyExact` protects against silent `long` overflow when calculating `price_paise * numberOfSeats`. Handle overflow as a clear `400 INVALID_REQUEST` (or another documented client-input error); do not let it become a `500`.

### Why this is race-safe

- **Seat race:** the transaction locks each seat row. A competing transaction waits, then observes the committed status. Only the transaction that changes `AVAILABLE` to `CONFIRMED` can succeed.
- **User limit race:** all booking attempts for the same `(show_id, user_id)` serialize on one guard row. The next request counts after the previous booking commits.
- **Idempotency race:** requests from the same user/show serialize on the same guard row. The later request sees the completed idempotency record. The unique primary key remains a database backstop.
- **Partial booking:** reservation insert, seat links, seat status changes and idempotency response are committed in the same transaction. A decline/exception rolls back all changes.
- **Multiple app instances:** correctness comes from PostgreSQL, not a Java `synchronized` block or in-memory lock.

Do not catch a domain exception and continue committing within the transaction. Expected domain declines should cause the transaction to roll back and then be mapped by the API exception handler to a stable `4xx` response.

---

## 9. Idempotency replay details

For every successful new reservation, insert one `idempotency_records` row with:

- `show_id`
- `user_id` from the JWT `sub`
- `idempotency_key` from the HTTP header
- `request_hash`
- `reservation_id`
- `response_json`

The reservation, seat updates, reservation-seat rows and idempotency row must be part of the same database transaction. That means the database cannot commit the reservation without the replay record.

If a request fails with a domain decline, let the transaction roll back; don't leave a successful idempotency record for a failed reservation. A later retry with that key can try again. Document this policy.

The response JSON is a replay snapshot. It allows the endpoint to return the original successful response even if a future cancellation changes the reservation's current state. Keep the database's current state and the idempotency response snapshot conceptually separate.

---

## 10. Error handling

Continue using the existing `ApiError` and `ApiExceptionHandler` pattern. Add domain exceptions or an equivalent typed error mechanism for:

- `SHOW_NOT_FOUND` → `404`
- `SEAT_NOT_FOUND` → `404`
- `SEAT_TAKEN` → `409`
- `PER_USER_LIMIT_EXCEEDED` → `409`
- `IDEMPOTENCY_KEY_REUSED` → `409`

Use one stable JSON shape, for example:

```json
{
  "code": "SEAT_TAKEN",
  "message": "One or more requested seats are no longer available."
}
```

Do not expose SQL errors, stack traces, database URLs or secrets in the response. Unexpected failures should be logged with a correlation/request ID and returned as a generic `500` response. Expected booking conflicts must not be treated as `500` errors.

---

## 11. Controller contract

The controller should:

- map `POST /shows/{id}/reserve`;
- validate the request DTO;
- require the `Idempotency-Key` header;
- obtain the user ID from the authenticated JWT principal (`JwtAuthenticationToken` or the `Jwt` principal), specifically the validated `sub` claim;
- never read identity from the body or trust a user ID header;
- return `201 Created` for a newly created reservation;
- return `200 OK` for a successful idempotent replay.

Do not place transaction logic or SQL in the controller.

---

## 12. Integration tests to add

Use the existing Testcontainers PostgreSQL setup. Do not use H2 for the locking tests. Update earlier show-creation tests to authenticate with an admin JWT after security is enabled.

At minimum, add these tests:

### Basic behaviour

1. A user can reserve an available seat: `201`, correct reservation ID, show ID, token-derived user ID, seat list, integer amount and `confirmed` status.
2. A second user requesting that seat receives `409 SEAT_TAKEN`.
3. A request naming a seat that does not belong to the show receives `404 SEAT_NOT_FOUND`.
4. A multi-seat request with one occupied seat declines as a whole; the other seat remains available.
5. A request with duplicate labels after normalization returns `400`.
6. Invalid/missing token returns `401`; wrong scope returns `403`.
7. A spoofed `user_id` in an extra JSON field cannot change the identity; preferably reject unknown JSON fields if that's the API's policy. Regardless, the service must derive the identity only from JWT `sub`.

### Idempotency

8. Repeating the same key and same seat list returns the same reservation ID and the stored response, without inserting another reservation.
9. Reusing a key with different seats returns `409 IDEMPOTENCY_KEY_REUSED`.
10. Many concurrent requests using the same key/body create one reservation ID and return that reservation for replaying requests.
11. A failed booking doesn't permanently consume the key under the documented policy.

### Concurrency and limit

12. Two different users race for one seat: exactly one new reservation, one seat owner, all other results are `409`, no unexpected `5xx`.
13. One user sends ten concurrent requests for different free seats: no more than the configured limit is confirmed.
14. Multiple requests each asking for multiple seats lock rows in sorted order and do not leave partial reservations.
15. The database state after each concurrency test satisfies the reconciliation invariant.

For concurrency tests, use a fixed-size executor and a start barrier/latch so requests begin together. Assert persisted database state, not just HTTP responses. Avoid using an unbounded number of test threads.

A useful invariant query is:

```sql
SELECT
    COUNT(*) AS total_seats,
    COUNT(*) FILTER (WHERE status = 'AVAILABLE') AS available,
    COUNT(*) FILTER (WHERE status = 'HELD') AS held,
    COUNT(*) FILTER (WHERE status = 'CONFIRMED') AS confirmed
FROM seats
WHERE show_id = ?;
```

Assert:

`total_seats = available + held + confirmed`

Also verify that every `CONFIRMED` seat has a `current_reservation_id`, and every `AVAILABLE` seat has `current_reservation_id IS NULL`.

---

## 13. Manual testing in Postman

Once automated tests pass, test the running application using Postman.

### Generate local test tokens

Use a small local token-generation script that signs HS256 JWTs with the `JWT_SECRET` environment variable. The token should include:

```json
{
  "iss": "seat-reservation",
  "sub": "user-101",
  "scope": "user",
  "iat": 0,
  "exp": 0
}
```

The `iat` and `exp` values must be generated as real Unix timestamps by the script; the zeros above are placeholders to explain the claims. Generate a separate token with `sub: "admin-1"` and `scope: "admin"` for show creation. Never commit the generated tokens.

### Reserve a seat

- Method: `POST`
- URL: `http://localhost:8080/shows/<SHOW_UUID>/reserve`
- Authorization: Bearer Token — a generated `user` token
- Headers: `Content-Type: application/json`, `Idempotency-Key: postman-user101-a12-001`
- Body:

```json
{
  "seats": ["A12"]
}
```

Expected first request: `201 Created`.

Send the exact request again with the same token and idempotency key: expect `200 OK` with the same reservation ID.

Now reuse the same key with body `{"seats":["A13"]}`: expect `409 IDEMPOTENCY_KEY_REUSED`.

Try booking `A12` using a different user's token and a different key: expect `409 SEAT_TAKEN`.

Make sure the chosen show actually contains `A12` and `A13`. If your Step 3 show used `A1`, `A2`, and `A3`, create a separate test show with those labels or use the matching labels.

### Verify in DBeaver

Inspect these tables after booking:

- `reservations`: one confirmed row for the winner;
- `reservation_seats`: one link per booked seat;
- `seats`: the seat status is `CONFIRMED` and `current_reservation_id` matches the reservation;
- `idempotency_records`: one record for the user/show/key, with the reservation ID and response JSON.

For a hot-seat test, use a separate fresh show or an unused seat for every run. A seat already confirmed by a previous manual test cannot produce a fresh one-winner race.

---

## 14. Important correctness notes

- `@Transactional` alone does not prevent double booking. The row locks and constraints are essential.
- Do not use Java `synchronized` as the correctness mechanism; it does not coordinate separate application instances.
- Don't use Redis or an in-memory map as the source of truth for seat availability.
- Don't return success before the transaction commits.
- Don't add a seat to a reservation before checking all requested seats and the user's limit.
- Don't update availability using only an in-memory counter. Derive current seat state from PostgreSQL.
- A database outage should prevent a new successful reservation. Do not guess that a seat is available.
- Keep metric labels low-cardinality when metrics are added: avoid user IDs, request IDs and individual seat IDs as labels.
- A clean `409` is an expected domain outcome; a database deadlock, connection-pool exhaustion or unhandled exception is not a seat conflict and should be investigated.

---

## 15. Commands to run before committing

Run the full test suite:

```bash
./mvnw clean test
```

Start PostgreSQL if needed:

```bash
docker compose up -d db
```

Start the application with the local JWT secret set:

```bash
export JWT_SECRET='replace-with-a-long-random-local-development-secret'
./mvnw spring-boot:run
```

Check migration history and confirm V2 succeeded in DBeaver or with:

```bash
docker compose exec db \
  psql -U seat_app -d seat_reservation \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

Before committing, verify:

- [ ] V2 migration applies without modifying V1.
- [ ] Existing show creation endpoint now requires admin authority.
- [ ] Reservation identity comes only from a verified JWT `sub` claim.
- [ ] Basic reservation and conflict tests pass.
- [ ] Same-key replay and changed-body conflict tests pass.
- [ ] Concurrent hot-seat test has exactly one winner.
- [ ] Concurrent user-limit test never exceeds the configured limit.
- [ ] Multi-seat failure leaves all requested seats unchanged.
- [ ] Reconciliation invariant holds after the test burst.
- [ ] No test relies on H2 for PostgreSQL locking behaviour.

Then commit the implementation and tests:

```bash
git status
git add pom.xml src/main/java src/main/resources src/test/java
git commit -m "feat: implement atomic seat reservation"
```

Include `docs/step-4-atomic-seat-reservation.md` in the commit if you keep this guide in the repository.

---

## 16. Definition of done

Step 4 is complete only when the endpoint works manually **and** the PostgreSQL concurrency tests prove its behaviour. A single successful Postman request is not evidence of concurrency correctness.

The evidence to keep for the next milestone is:

- test output from `./mvnw clean test`;
- the hot-seat concurrency result;
- the per-user limit concurrency result;
- the same-key replay result;
- a database check showing one current owner per confirmed seat and correct seat counts.

The next milestone can then add owner-only cancellation, followed by the show-state API, metrics/logging, and the full deployed burst script.
