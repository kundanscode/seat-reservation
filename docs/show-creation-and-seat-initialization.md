# Step 3 — Show Creation and Atomic Seat Initialization

**Planned commit:** `feat: implement show creation and seat initialization`

## Goal

Implement `POST /shows` so an administrator can create a show and all of its seats. A show and its seats must be created in **one database transaction**: either every row is persisted, or none is.

This milestone builds on the PostgreSQL schema and Flyway migration from the previous step. Do not start implementing reservations yet; first make show creation reliable and testable.

> **Security note:** If authentication has not been implemented yet, keep this endpoint available only in your local development environment. Do not expose an unauthenticated show-creation endpoint on the public deployment. Add and verify admin authorization before deploying this endpoint publicly.

## Scope

- Add request and response DTOs with Jakarta Bean Validation.
- Implement the show-creation service and JDBC repository.
- Insert the show and all seat rows within one `@Transactional` method.
- Reject invalid requests and duplicate seat labels with a clear `400 Bad Request` response.
- Return `201 Created`, including the show ID and every seat in `AVAILABLE` state.
- Add PostgreSQL-backed integration tests using Testcontainers.

Out of scope for this commit: reservation, cancellation, JWT authentication, metrics, and load testing.

## API contract

### Request

`POST /shows`

```json
{
  "name": "friday-night",
  "seats": ["A1", "A2", "A3"],
  "price_paise": 25000,
  "per_user_limit": 4
}
```

`per_user_limit` is optional. If it is omitted, use `4`. `price_paise` is an integer number of paise; never use `float` or `double` for money.

### Successful response

Return HTTP `201 Created` and a `Location: /shows/{id}` header.

```json
{
  "id": "a2ccfb58-9eef-42b1-b16f-26598e32ea1d",
  "name": "friday-night",
  "price_paise": 25000,
  "per_user_limit": 4,
  "total_seats": 3,
  "available": 3,
  "held": 0,
  "confirmed": 0,
  "seats": [
    { "id": "7a8e6cf6-c6dc-49a8-8b3a-37cc5a64f2b7", "seat": "A1", "status": "AVAILABLE" },
    { "id": "86470d17-2b40-4f2a-8050-70860e1e25a4", "seat": "A2", "status": "AVAILABLE" },
    { "id": "90a48d63-889c-49eb-8a5c-84df3d4d9232", "seat": "A3", "status": "AVAILABLE" }
  ]
}
```

The UUIDs above are illustrative; generate new UUIDs at runtime. The API may expose lowercase status strings (`available`) if that is the convention chosen for all responses. Be consistent across the API; the database may keep uppercase enum-like values.

## Request rules

- `name` must not be blank and must be at most 120 characters after trimming.
- `seats` must contain at least one label.
- Each seat label must not be blank and must be at most 40 characters after trimming.
- Seat labels are trimmed before storage.
- Reject duplicate labels in the same request after trimming. For example, `"A1"` and `" A1 "` count as duplicates.
- `price_paise` is required and must be zero or greater.
- `per_user_limit`, when supplied, must be greater than zero; otherwise default to `4`.
- Keep seat-label case significant for now: `A1` and `a1` are different labels. Document this convention rather than silently changing user input.
- On validation failure, return `400 Bad Request` with a useful message. Do not let predictable invalid input become a `500` response.

## Suggested package structure

Adjust the root package to match your Spring Initializr project.

```text
src/main/java/<your-package>/
├── show/
│   ├── ShowController.java
│   ├── ShowService.java
│   ├── ShowRepository.java
│   ├── CreateShowRequest.java
│   ├── ShowResponse.java
│   ├── SeatResponse.java
│   └── ShowSeatRow.java
└── common/
    ├── ApiError.java
    ├── ApiExceptionHandler.java
    └── DuplicateSeatLabelException.java

src/test/java/<your-package>/show/
└── ShowCreationIntegrationTest.java
```

Keep classes in a small, feature-oriented package. Do not create extra interfaces or abstraction layers unless they solve a real requirement.

## Implementation design

### 1. Request DTO

Use a Java record (or a normal immutable DTO if the project is not using a compatible Java version). Add Jakarta Validation annotations.

```java
package <your-package>.show;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateShowRequest(
        @NotBlank
        @Size(max = 120)
        String name,

        @NotEmpty
        List<@NotBlank @Size(max = 40) String> seats,

        @NotNull
        @PositiveOrZero
        Long price_paise,

        @Positive
        Integer per_user_limit
) {}
```

Java DTO property names are normally camelCase. A cleaner option is to name these fields `pricePaise` and `perUserLimit`, and use Jackson's `@JsonProperty("price_paise")` where snake_case is part of the API contract. Use that approach consistently for request and response DTOs. Do not keep Java fields in snake_case just to match JSON.

For example, use:

```java
@JsonProperty("price_paise")
Long pricePaise,

@JsonProperty("per_user_limit")
Integer perUserLimit
```

Add the imports for `com.fasterxml.jackson.annotation.JsonProperty` when using these annotations.

### 2. Repository: persist with JDBC

Use `JdbcTemplate` for the explicit SQL. The database schema already defines the important constraints:

- `shows.id` is the show primary key.
- `seats.id` is the seat primary key.
- `UNIQUE (show_id, seat_label)` prohibits duplicate seat labels for a show.
- Foreign keys ensure seats belong to an existing show.

Generate the UUIDs in Java with `UUID.randomUUID()` because the current schema expects IDs to be supplied by the application.

The repository should have small, focused methods such as:

```java
void insertShow(UUID id, String name, long pricePaise, int perUserLimit);
void insertSeats(UUID showId, List<SeatToInsert> seats);
List<ShowSeatRow> findSeatsByShowId(UUID showId);
```

Use a batch insert for the seat rows instead of issuing one database round trip per seat. Order retrieved seats by `seat_label` so responses are deterministic and easy to compare in tests.

Do not catch and ignore database exceptions. A database error must cause the transaction to fail so Spring can roll it back.

### 3. Service: one transaction for the whole operation

Put the transaction boundary on the service method, not the controller. The method should:

1. Trim the show name and all seat labels.
2. Reject duplicate normalized seat labels before writing to the database.
3. Apply the default user limit (`4`) if it was omitted.
4. Generate a show UUID and seat UUIDs.
5. Insert the show row.
6. Batch-insert every seat row as `AVAILABLE`.
7. Read the saved seats and construct the response.

Use `@Transactional` on the public service method:

```java
@Transactional
public ShowResponse createShow(CreateShowRequest request) {
    // Normalize and validate domain rules, then persist the show and all seats.
}
```

If the show insert or any seat insert fails, let the exception propagate. Spring should roll back the show row and all seat rows. Do not catch an exception and return a success-shaped response.

The uniqueness constraint in PostgreSQL is the final guard against duplicate labels even though the service validates duplicates first. The service-level check exists to return a clear client error rather than exposing a database constraint failure.

### 4. Controller: thin HTTP layer

The controller should validate input, delegate to the service, and return `201 Created`. It should not contain SQL or transaction logic.

```java
@PostMapping("/shows")
public ResponseEntity<ShowResponse> createShow(
        @Valid @RequestBody CreateShowRequest request) {
    ShowResponse response = showService.createShow(request);

    return ResponseEntity
            .created(URI.create("/shows/" + response.id()))
            .body(response);
}
```

Add the required imports and class annotations. Keep the controller thin.

### 5. Consistent validation errors

Add a `@RestControllerAdvice` that maps request validation errors and your duplicate-seat-label exception to `400 Bad Request` with a small, stable JSON shape. For example:

```json
{
  "code": "INVALID_REQUEST",
  "message": "Seat labels must be unique within a show"
}
```

Avoid returning stack traces, SQL statements, database URLs, or other internal details to clients. Log unexpected failures server-side with enough context to investigate them.

## Integration tests

Use Testcontainers with a real PostgreSQL instance. H2 is not a substitute for these tests because PostgreSQL-specific schema rules and locking semantics are important to this project.

At minimum, write tests for:

1. **Creates a show:** response is `201`; the show details are correct; every requested seat is present and `AVAILABLE`; all counts reconcile.
2. **Defaults the limit:** omitting `per_user_limit` results in a limit of `4`.
3. **Rejects duplicate labels:** `{"seats":["A1"," A1 "]}` receives `400`.
4. **Rejects invalid input:** blank name, empty seats, blank label, negative price, or a non-positive limit receives `400`.
5. **Does not leave partial data:** after a rejected request, the number of newly created show rows and seat rows has not increased.
6. **Persists data:** querying PostgreSQL after creation returns the same show and seat rows as the response.

Use unique names or compare row counts before and after each test; do not assume a shared test database is empty between test methods.

Run the tests with:

```bash
./mvnw test
```

The Docker engine must be running for Testcontainers. If tests fail to start a container, inspect the Testcontainers error before changing application code.

## Manual verification

Start PostgreSQL:

```bash
docker compose up -d db
```

Start Spring Boot in another terminal:

```bash
./mvnw spring-boot:run
```

Create a show:

```bash
curl -i -X POST http://localhost:8080/shows \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "friday-night",
    "seats": ["A1", "A2", "A3"],
    "price_paise": 25000
  }'
```

Verify the saved rows directly in PostgreSQL:

```bash
docker compose exec db psql -U seat_app -d seat_reservation \
  -c "SELECT id, name, price_paise, per_user_limit FROM shows ORDER BY created_at DESC LIMIT 5;"

docker compose exec db psql -U seat_app -d seat_reservation \
  -c "SELECT show_id, seat_label, status FROM seats ORDER BY show_id, seat_label;"
```

Then send a duplicate-seat request and confirm it returns `400` rather than `500`:

```bash
curl -i -X POST http://localhost:8080/shows \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "invalid-show",
    "seats": ["A1", " A1 "],
    "price_paise": 25000
  }'
```

If Spring Security was included in the generated project, the endpoint may be protected by its default configuration. Do not disable security globally just to make the test pass. Either keep security out of this milestone or configure and test the intended access policy before deployment.

## Definition of done

- [ ] `POST /shows` returns `201 Created` and a `Location` header.
- [ ] The response includes all seats in `AVAILABLE` state and consistent counts.
- [ ] Missing `per_user_limit` defaults to `4`.
- [ ] Invalid requests and duplicate seat labels return `400`, not `500`.
- [ ] Show and seat inserts run in one `@Transactional` service method.
- [ ] JDBC batch insertion is used for seat creation.
- [ ] PostgreSQL-backed integration tests pass.
- [ ] The manual API request and database verification succeed.
- [ ] The endpoint is not exposed publicly without admin authorization.

## Commit

Once the implementation and checks are complete:

```bash
git status
git add src/main/java src/test/java
git commit -m "feat: implement show creation and seat initialization"
```

If this project keeps step documentation in `docs/`, add this file there as well and include it in the same commit:

```bash
git add docs/show-creation-and-seat-initialization.md
```

Only claim checks that you actually ran. If a test or manual check failed, fix it before committing and record any important design decision in the README or `WRITEUP.md`.

## Why this milestone matters

Reservation correctness depends on a trustworthy initial state. Creating a show and its seats atomically guarantees the API never exposes a successfully created show with only some of its seats initialized. The normalized input checks produce predictable client errors, PostgreSQL constraints protect the data, and integration tests verify the behaviour against the database we will use for concurrency control later.
