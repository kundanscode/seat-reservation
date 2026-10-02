package com.kundan.seat_reservation.reservation;

import com.jayway.jsonpath.JsonPath;
import com.kundan.seat_reservation.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ReservationIntegrationTest {

    private record MultiSeatRaceResult(
            int requestIndex,
            String userId,
            List<String> requestedSeats,
            int statusCode,
            String responseBody) {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j.claim("scope", "admin").subject("admin-1"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor userJwt(String userId) {
        return jwt().jwt(j -> j.claim("scope", "user").subject(userId));
    }

    private UUID createShow(String name, List<String> seats, long pricePaise, int perUserLimit) throws Exception {
        StringBuilder seatsJson = new StringBuilder("[");
        for (int i = 0; i < seats.size(); i++) {
            seatsJson.append("\"").append(seats.get(i)).append("\"");
            if (i < seats.size() - 1) {
                seatsJson.append(",");
            }
        }
        seatsJson.append("]");

        String requestJson = String.format("""
                {
                  "name": "%s",
                  "seats": %s,
                  "price_paise": %d,
                  "per_user_limit": %d
                }
                """, name, seatsJson, pricePaise, perUserLimit);

        MvcResult result = mockMvc.perform(post("/shows")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isCreated())
                .andReturn();

        String showIdStr = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return UUID.fromString(showIdStr);
    }

    private void assertReconciliation(UUID showId) {
        String invariantSql = """
                SELECT
                    COUNT(*) AS total_seats,
                    COUNT(*) FILTER (WHERE status = 'AVAILABLE') AS available,
                    COUNT(*) FILTER (WHERE status = 'HELD') AS held,
                    COUNT(*) FILTER (WHERE status = 'CONFIRMED') AS confirmed
                FROM seats
                WHERE show_id = ?
                """;

        Map<String, Object> counts = jdbcTemplate.queryForMap(invariantSql, showId);
        long total = ((Number) counts.get("total_seats")).longValue();
        long available = ((Number) counts.get("available")).longValue();
        long held = ((Number) counts.get("held")).longValue();
        long confirmed = ((Number) counts.get("confirmed")).longValue();

        assertThat(total).isEqualTo(available + held + confirmed);

        Integer invalidConfirmed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'CONFIRMED' AND current_reservation_id IS NULL",
                Integer.class, showId);
        assertThat(invalidConfirmed).isZero();

        Integer invalidAvailable = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'AVAILABLE' AND current_reservation_id IS NOT NULL",
                Integer.class, showId);
        assertThat(invalidAvailable).isZero();
    }

    /**
     * Wait until all workers are ready, release them together, and ensure
     * every task finishes. Each concurrent test uses a fixed-size pool whose
     * size equals the number of submitted workers.
     */
    private void releaseWorkersAndAwaitCompletion(
            CountDownLatch readyLatch,
            CountDownLatch startLatch,
            ExecutorService executor) throws InterruptedException {
        boolean allWorkersReady = readyLatch.await(10, TimeUnit.SECONDS);

        // Always release waiting workers, even if the readiness timeout occurs.
        startLatch.countDown();

        if (allWorkersReady) {
            executor.shutdown();
        } else {
            executor.shutdownNow();
        }

        boolean completed = executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(allWorkersReady)
                .as("All worker threads should reach the start barrier")
                .isTrue();
        assertThat(completed)
                .as("All concurrent requests should complete")
                .isTrue();
    }

    private List<String> findSeatLabelsForReservation(UUID reservationId) {
        return jdbcTemplate.queryForList(
                """
                        SELECT s.seat_label
                        FROM reservation_seats rs
                        JOIN seats s ON s.id = rs.seat_id AND s.show_id = rs.show_id
                        WHERE rs.reservation_id = ?
                        ORDER BY s.seat_label
                        """,
                String.class,
                reservationId);
    }

    @Test
    @DisplayName("1. A user can reserve an available seat: 201, correct fields, confirmed status")
    void testReserveAvailableSeatSuccess() throws Exception {
        UUID showId = createShow("show-1", List.of("A1", "A2"), 25000L, 4);

        MvcResult result = mockMvc.perform(post("/shows/" + showId + "/reserve")
                .with(userJwt("user-101"))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "seats": ["A1"]
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservation_id").isNotEmpty())
                .andExpect(jsonPath("$.show_id").value(showId.toString()))
                .andExpect(jsonPath("$.user_id").value("user-101"))
                .andExpect(jsonPath("$.seats[0]").value("A1"))
                .andExpect(jsonPath("$.amount_paise").value(25000))
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andReturn();

        String reservationIdStr = JsonPath.read(result.getResponse().getContentAsString(), "$.reservation_id");
        UUID reservationId = UUID.fromString(reservationIdStr);

        Map<String, Object> seatRow = jdbcTemplate.queryForMap(
                "SELECT status, current_reservation_id FROM seats WHERE show_id = ? AND seat_label = 'A1'",
                showId);
        assertThat(seatRow.get("status")).isEqualTo("CONFIRMED");
        assertThat(seatRow.get("current_reservation_id")).isEqualTo(reservationId);

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("2. A second user requesting that seat receives 409 SEAT_TAKEN")
    void testSecondUserCannotReserveTakenSeat() throws Exception {
        UUID showId = createShow("show-2", List.of("A1"), 10000L, 4);

        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .with(userJwt("user-1"))
                .header("Idempotency-Key", "key-u1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\": [\"A1\"]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .with(userJwt("user-2"))
                .header("Idempotency-Key", "key-u2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\": [\"A1\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_TAKEN"));

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("3. A request naming a seat that does not belong to the show receives 404 SEAT_NOT_FOUND")
    void testSeatNotFound() throws Exception {
        UUID showId = createShow("show-3", List.of("A1"), 10000L, 4);

        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .with(userJwt("user-1"))
                .header("Idempotency-Key", "key-not-found")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\": [\"Z99\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SEAT_NOT_FOUND"));

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("4. A multi-seat request with one occupied seat declines as a whole; other seat remains available")
    void testMultiSeatAllOrNothingFailure() throws Exception {
        UUID showId = createShow("show-4", List.of("A1", "A2"), 15000L, 4);

        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .with(userJwt("user-1"))
                .header("Idempotency-Key", "key-take-a1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\": [\"A1\"]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .with(userJwt("user-2"))
                .header("Idempotency-Key", "key-take-both")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\": [\"A1\", \"A2\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_TAKEN"));

        Map<String, Object> a2Row = jdbcTemplate.queryForMap(
                "SELECT status, current_reservation_id FROM seats WHERE show_id = ? AND seat_label = 'A2'",
                showId);
        assertThat(a2Row.get("status")).isEqualTo("AVAILABLE");
        assertThat(a2Row.get("current_reservation_id")).isNull();

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("5. A request with duplicate labels after normalization returns 400")
    void testDuplicateSeatLabelsRejected() throws Exception {
        UUID showId = createShow("show-5", List.of("A1", "A2"), 10000L, 4);

        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .with(userJwt("user-1"))
                .header("Idempotency-Key", "key-dup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\": [\"A1\", \" A1 \"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("6. Invalid/missing token returns 401; wrong scope returns 403")
    void testAuthConstraintsOnReserve() throws Exception {
        UUID showId = createShow("show-6", List.of("A1"), 10000L, 4);

        // Missing token -> 401
        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .header("Idempotency-Key", "key-no-auth")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\": [\"A1\"]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        // Wrong scope (admin scope on reserve endpoint requiring user scope) -> 403
        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .with(jwt().jwt(j -> j.claim("scope", "admin").subject("admin-user")))
                .header("Idempotency-Key", "key-wrong-scope")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\": [\"A1\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("7. A spoofed user_id in an extra JSON field cannot change identity; derives only from JWT sub")
    void testSpoofedUserIdIgnored() throws Exception {
        UUID showId = createShow("show-7", List.of("A1"), 10000L, 4);

        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .with(userJwt("legit-user"))
                .header("Idempotency-Key", "key-spoof")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "seats": ["A1"],
                          "user_id": "attacker-user"
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user_id").value("legit-user"));

        Map<String, Object> reservation = jdbcTemplate.queryForMap(
                "SELECT user_id FROM reservations WHERE show_id = ?",
                showId);
        assertThat(reservation.get("user_id")).isEqualTo("legit-user");

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("8. Repeating the same key and same seat list returns same reservation ID and stored response (200 OK)")
    void testIdempotencyExactReplay() throws Exception {
        UUID showId = createShow("show-8", List.of("A1"), 20000L, 4);

        MvcResult firstResult = mockMvc.perform(post("/shows/" + showId + "/reserve")
                .with(userJwt("user-idem"))
                .header("Idempotency-Key", "key-replay-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\": [\"A1\"]}"))
                .andExpect(status().isCreated())
                .andReturn();

        String firstReservationId = JsonPath.read(firstResult.getResponse().getContentAsString(), "$.reservation_id");

        MvcResult replayResult = mockMvc.perform(post("/shows/" + showId + "/reserve")
                .with(userJwt("user-idem"))
                .header("Idempotency-Key", "key-replay-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\": [\"A1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservation_id").value(firstReservationId))
                .andExpect(jsonPath("$.amount_paise").value(20000))
                .andReturn();

        Integer reservationsCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservations WHERE show_id = ?",
                Integer.class, showId);
        assertThat(reservationsCount).isEqualTo(1);

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("9. Reusing a key with different seats returns 409 IDEMPOTENCY_KEY_REUSED")
    void testIdempotencyKeyReusedWithDifferentSeats() throws Exception {
        UUID showId = createShow("show-9", List.of("A1", "A2"), 20000L, 4);

        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .with(userJwt("user-idem-diff"))
                .header("Idempotency-Key", "shared-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\": [\"A1\"]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .with(userJwt("user-idem-diff"))
                .header("Idempotency-Key", "shared-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\": [\"A2\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("10. Many concurrent requests using the same key/body create one reservation ID and return that reservation")
    void testConcurrentIdempotentRequests() throws Exception {
        UUID showId = createShow("show-10", List.of("A1"), 30000L, 4);

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                if (!startLatch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for concurrent test start");
                }
                return mockMvc.perform(post("/shows/" + showId + "/reserve")
                        .with(userJwt("user-race-idem"))
                        .header("Idempotency-Key", "concurrent-idem-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\": [\"A1\"]}"))
                        .andReturn();
            }));
        }

        releaseWorkersAndAwaitCompletion(readyLatch, startLatch, executor);

        int createdCount = 0;
        int replayCount = 0;
        List<String> reservationIds = new ArrayList<>();

        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get();
            int statusCode = result.getResponse().getStatus();

            if (statusCode == 201) {
                createdCount++;
            } else if (statusCode == 200) {
                replayCount++;
            } else {
                throw new AssertionError("Unexpected HTTP status: " + statusCode
                        + ", body=" + result.getResponse().getContentAsString());
            }

            String reservationId = JsonPath.read(
                    result.getResponse().getContentAsString(),
                    "$.reservation_id");
            reservationIds.add(reservationId);
        }

        assertThat(createdCount).isEqualTo(1);
        assertThat(replayCount).isEqualTo(threadCount - 1);
        assertThat(reservationIds).allMatch(id -> id.equals(reservationIds.get(0)));

        Integer reservationsCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservations WHERE show_id = ?",
                Integer.class, showId);
        assertThat(reservationsCount).isEqualTo(1);

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("11. A failed booking doesn't permanently consume the key under the documented policy")
    void testFailedBookingDoesNotConsumeKey() throws Exception {
        UUID showId = createShow("show-11", List.of("A1"), 10000L, 4);

        // Attempt 1: non-existent seat -> 404
        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .with(userJwt("user-retry"))
                .header("Idempotency-Key", "retry-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\": [\"NON_EXISTENT\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SEAT_NOT_FOUND"));

        // Attempt 2: retry with same key and valid seat -> 201 Created
        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .with(userJwt("user-retry"))
                .header("Idempotency-Key", "retry-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\": [\"A1\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seats[0]").value("A1"));

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("12. Two different users race for one seat: exactly one new reservation, one seat owner, others 409")
    void testHotSeatConcurrencyRace() throws Exception {
        UUID showId = createShow("show-12", List.of("HOT_SEAT"), 50000L, 4);

        int participants = 10;
        ExecutorService executor = Executors.newFixedThreadPool(participants);
        CountDownLatch readyLatch = new CountDownLatch(participants);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < participants; i++) {
            final String userId = "race-user-" + i;
            final String idempotencyKey = "race-key-" + i;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                if (!startLatch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for concurrent test start");
                }
                MvcResult result = mockMvc.perform(post("/shows/" + showId + "/reserve")
                        .with(userJwt(userId))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\": [\"HOT_SEAT\"]}"))
                        .andReturn();
                return result.getResponse().getStatus();
            }));
        }

        releaseWorkersAndAwaitCompletion(readyLatch, startLatch, executor);

        int createdCount = 0;
        int conflictCount = 0;
        for (Future<Integer> f : futures) {
            int status = f.get();
            if (status == 201) {
                createdCount++;
            } else if (status == 409) {
                conflictCount++;
            } else {
                throw new AssertionError("Unexpected HTTP status in hot-seat race: " + status);
            }
        }

        assertThat(createdCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(participants - 1);

        Integer reservationsCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservations WHERE show_id = ?",
                Integer.class, showId);
        assertThat(reservationsCount).isEqualTo(1);

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("13. One user sends ten concurrent requests for different free seats: no more than configured limit is confirmed")
    void testPerUserLimitConcurrencyRace() throws Exception {
        int limit = 4;
        List<String> seats = List.of("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "S10");
        UUID showId = createShow("show-13", seats, 10000L, limit);

        int attempts = 10;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch readyLatch = new CountDownLatch(attempts);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            final String seat = seats.get(i);
            final String key = "user-limit-key-" + i;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                if (!startLatch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for concurrent test start");
                }
                MvcResult result = mockMvc.perform(post("/shows/" + showId + "/reserve")
                        .with(userJwt("bulk-user"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"seats\": [\"%s\"]}", seat)))
                        .andReturn();
                return result.getResponse().getStatus();
            }));
        }

        releaseWorkersAndAwaitCompletion(readyLatch, startLatch, executor);

        int successCount = 0;
        int limitExceededCount = 0;
        for (Future<Integer> f : futures) {
            int status = f.get();
            if (status == 201) {
                successCount++;
            } else if (status == 409) {
                limitExceededCount++;
            } else {
                throw new AssertionError("Unexpected HTTP status in per-user limit race: " + status);
            }
        }

        assertThat(successCount).isEqualTo(limit);
        assertThat(limitExceededCount).isEqualTo(attempts - limit);

        Integer confirmedSeatsInDb = jdbcTemplate.queryForObject(
                """
                        SELECT count(rs.seat_id)
                        FROM reservation_seats rs
                        JOIN reservations r ON rs.reservation_id = r.id AND rs.show_id = r.show_id
                        WHERE r.show_id = ? AND r.user_id = 'bulk-user' AND r.status = 'CONFIRMED'
                        """,
                Integer.class, showId);
        assertThat(confirmedSeatsInDb).isEqualTo(limit);

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("14. Concurrent overlapping multi-seat requests are all-or-nothing and leave no partial data")
    void testMultiSeatSortedLockingNoPartialData() throws Exception {
        UUID showId = createShow("show-14", List.of("A1", "A2", "A3", "A4", "A5"), 10000L, 4);

        List<List<String>> requestSeatSets = List.of(
                List.of("A1", "A2"),
                List.of("A2", "A3"),
                List.of("A1", "A3"),
                List.of("A3", "A4"),
                List.of("A4", "A5"),
                List.of("A2", "A5"));

        int workers = requestSeatSets.size();
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch readyLatch = new CountDownLatch(workers);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<MultiSeatRaceResult>> futures = new ArrayList<>();

        for (int i = 0; i < workers; i++) {
            final int index = i;
            final String userId = "multi-user-" + i;
            final List<String> requestedSeats = requestSeatSets.get(i);

            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                if (!startLatch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for concurrent test start");
                }

                String jsonSeats = "[\"" + String.join("\",\"", requestedSeats) + "\"]";
                MvcResult result = mockMvc.perform(post("/shows/" + showId + "/reserve")
                        .with(userJwt(userId))
                        .header("Idempotency-Key", "multi-key-" + index)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\": " + jsonSeats + "}"))
                        .andReturn();

                return new MultiSeatRaceResult(
                        index,
                        userId,
                        requestedSeats,
                        result.getResponse().getStatus(),
                        result.getResponse().getContentAsString());
            }));
        }

        releaseWorkersAndAwaitCompletion(readyLatch, startLatch, executor);

        for (Future<MultiSeatRaceResult> future : futures) {
            MultiSeatRaceResult result = future.get();
            assertThat(result.statusCode())
                    .as("Request %s response body: %s", result.requestIndex(), result.responseBody())
                    .isIn(201, 409);

            Integer reservationsForUser = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM reservations WHERE show_id = ? AND user_id = ?",
                    Integer.class,
                    showId,
                    result.userId());
            assertThat(reservationsForUser).isNotNull();

            if (result.statusCode() == 409) {
                // A declined all-or-nothing request must not leave a reservation row.
                assertThat(reservationsForUser)
                        .as("Declined request %s must not create a reservation", result.requestIndex())
                        .isZero();
            } else {
                // A successful request must create exactly one reservation containing every
                // requested seat.
                assertThat(reservationsForUser)
                        .as("Successful request %s should create one reservation", result.requestIndex())
                        .isEqualTo(1);

                UUID reservationId = UUID.fromString(
                        JsonPath.read(result.responseBody(), "$.reservation_id"));
                List<String> actualSeats = findSeatLabelsForReservation(reservationId);
                List<String> expectedSeats = result.requestedSeats().stream().sorted().toList();

                assertThat(actualSeats)
                        .as("Reservation from request %s must contain exactly its requested seats",
                                result.requestIndex())
                        .containsExactlyElementsOf(expectedSeats);
            }
        }

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("15. Show not found returns 404 SHOW_NOT_FOUND")
    void testShowNotFound() throws Exception {
        UUID nonExistentShowId = UUID.randomUUID();

        mockMvc.perform(post("/shows/" + nonExistentShowId + "/reserve")
                .with(userJwt("user-1"))
                .header("Idempotency-Key", "key-missing-show")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\": [\"A1\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOW_NOT_FOUND"));
    }
}
