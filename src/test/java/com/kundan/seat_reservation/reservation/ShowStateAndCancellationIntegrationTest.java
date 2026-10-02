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
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ShowStateAndCancellationIntegrationTest {

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

    private UUID reserveSeats(UUID showId, String userId, String idempotencyKey, List<String> seats) throws Exception {
        String jsonSeats = "[\"" + String.join("\",\"", seats) + "\"]";
        MvcResult result = mockMvc.perform(post("/shows/" + showId + "/reserve")
                .with(userJwt(userId))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\": " + jsonSeats + "}"))
                .andExpect(status().isCreated())
                .andReturn();

        String resIdStr = JsonPath.read(result.getResponse().getContentAsString(), "$.reservation_id");
        return UUID.fromString(resIdStr);
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

    private void releaseWorkersAndAwaitCompletion(
            CountDownLatch readyLatch,
            CountDownLatch startLatch,
            ExecutorService executor) throws InterruptedException {
        boolean allWorkersReady = readyLatch.await(10, TimeUnit.SECONDS);
        startLatch.countDown();

        if (allWorkersReady) {
            executor.shutdown();
        } else {
            executor.shutdownNow();
        }

        boolean completed = executor.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(allWorkersReady).as("All worker threads should reach start barrier").isTrue();
        assertThat(completed).as("All concurrent tasks should complete").isTrue();
    }

    // -------------------------------------------------------------
    // GET /shows/{id} tests
    // -------------------------------------------------------------

    @Test
    @DisplayName("GET show 1: Existing show returns 200 with correct ID, name, price, limit, seat list, and counts")
    void testGetExistingShowSuccess() throws Exception {
        UUID showId = createShow("friday-movie", List.of("A1", "A2", "A3"), 25000L, 4);

        mockMvc.perform(get("/shows/" + showId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(showId.toString()))
                .andExpect(jsonPath("$.name").value("friday-movie"))
                .andExpect(jsonPath("$.price_paise").value(25000))
                .andExpect(jsonPath("$.per_user_limit").value(4))
                .andExpect(jsonPath("$.total_seats").value(3))
                .andExpect(jsonPath("$.available").value(3))
                .andExpect(jsonPath("$.held").value(0))
                .andExpect(jsonPath("$.confirmed").value(0))
                .andExpect(jsonPath("$.seats.length()").value(3))
                .andExpect(jsonPath("$.seats[0].seat").value("A1"))
                .andExpect(jsonPath("$.seats[0].status", equalToIgnoringCase("AVAILABLE")))
                .andExpect(jsonPath("$.seats[1].seat").value("A2"))
                .andExpect(jsonPath("$.seats[1].status", equalToIgnoringCase("AVAILABLE")))
                .andExpect(jsonPath("$.seats[2].seat").value("A3"))
                .andExpect(jsonPath("$.seats[2].status", equalToIgnoringCase("AVAILABLE")));

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("GET show 2: Fresh show reports all seats as available; held and confirmed are zero")
    void testGetFreshShowAllAvailable() throws Exception {
        UUID showId = createShow("fresh-show", List.of("B1", "B2"), 15000L, 2);

        mockMvc.perform(get("/shows/" + showId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_seats").value(2))
                .andExpect(jsonPath("$.available").value(2))
                .andExpect(jsonPath("$.held").value(0))
                .andExpect(jsonPath("$.confirmed").value(0));

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("GET show 3: After booking one seat, GET reports that seat as confirmed and counts reconcile")
    void testGetShowAfterBookingOneSeat() throws Exception {
        UUID showId = createShow("booking-show", List.of("C1", "C2", "C3"), 20000L, 4);

        reserveSeats(showId, "user-c", "key-c1", List.of("C2"));

        mockMvc.perform(get("/shows/" + showId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_seats").value(3))
                .andExpect(jsonPath("$.available").value(2))
                .andExpect(jsonPath("$.held").value(0))
                .andExpect(jsonPath("$.confirmed").value(1))
                .andExpect(jsonPath("$.seats[0].seat").value("C1"))
                .andExpect(jsonPath("$.seats[0].status", equalToIgnoringCase("AVAILABLE")))
                .andExpect(jsonPath("$.seats[1].seat").value("C2"))
                .andExpect(jsonPath("$.seats[1].status", equalToIgnoringCase("CONFIRMED")))
                .andExpect(jsonPath("$.seats[2].seat").value("C3"))
                .andExpect(jsonPath("$.seats[2].status", equalToIgnoringCase("AVAILABLE")));

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("GET show 4: Unknown show ID returns 404 with SHOW_NOT_FOUND")
    void testGetUnknownShowNotFound() throws Exception {
        UUID randomId = UUID.randomUUID();

        mockMvc.perform(get("/shows/" + randomId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOW_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET show 5: Assert available + held + confirmed == total_seats invariant in response")
    void testGetShowCountsInvariant() throws Exception {
        UUID showId = createShow("invariant-show", List.of("D1", "D2", "D3", "D4"), 12000L, 4);
        reserveSeats(showId, "user-d", "key-d1", List.of("D1", "D3"));

        MvcResult result = mockMvc.perform(get("/shows/" + showId))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        int total = JsonPath.read(body, "$.total_seats");
        int available = JsonPath.read(body, "$.available");
        int held = JsonPath.read(body, "$.held");
        int confirmed = JsonPath.read(body, "$.confirmed");

        assertThat(total).isEqualTo(available + held + confirmed);
        assertThat(total).isEqualTo(4);
        assertThat(confirmed).isEqualTo(2);
        assertThat(available).isEqualTo(2);

        assertReconciliation(showId);
    }

    // -------------------------------------------------------------
    // POST /reservations/{id}/cancel tests
    // -------------------------------------------------------------

    @Test
    @DisplayName("Cancel 1: Owner cancellation cancels reservation, sets cancelled_at, and releases seats")
    void testOwnerCancellationSuccess() throws Exception {
        UUID showId = createShow("cancel-show-1", List.of("A1", "A2"), 25000L, 4);
        UUID reservationId = reserveSeats(showId, "user-owner", "key-owner-1", List.of("A1"));

        mockMvc.perform(post("/reservations/" + reservationId + "/cancel")
                .with(userJwt("user-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservation_id").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.seats[0]").value("A1"));

        Map<String, Object> resRow = jdbcTemplate.queryForMap(
                "SELECT status, cancelled_at FROM reservations WHERE id = ?",
                reservationId);
        assertThat(resRow.get("status")).isEqualTo("CANCELLED");
        assertThat(resRow.get("cancelled_at")).isNotNull();

        Map<String, Object> seatRow = jdbcTemplate.queryForMap(
                "SELECT status, current_reservation_id FROM seats WHERE show_id = ? AND seat_label = 'A1'",
                showId);
        assertThat(seatRow.get("status")).isEqualTo("AVAILABLE");
        assertThat(seatRow.get("current_reservation_id")).isNull();

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("Cancel 2: Non-owner cancellation returns 403 FORBIDDEN and leaves rows unchanged")
    void testNonOwnerCancellationForbidden() throws Exception {
        UUID showId = createShow("cancel-show-2", List.of("A1"), 10000L, 4);
        UUID reservationId = reserveSeats(showId, "user-alice", "key-alice-1", List.of("A1"));

        mockMvc.perform(post("/reservations/" + reservationId + "/cancel")
                .with(userJwt("user-bob")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        Map<String, Object> resRow = jdbcTemplate.queryForMap(
                "SELECT status, cancelled_at FROM reservations WHERE id = ?",
                reservationId);
        assertThat(resRow.get("status")).isEqualTo("CONFIRMED");
        assertThat(resRow.get("cancelled_at")).isNull();

        Map<String, Object> seatRow = jdbcTemplate.queryForMap(
                "SELECT status, current_reservation_id FROM seats WHERE show_id = ? AND seat_label = 'A1'",
                showId);
        assertThat(seatRow.get("status")).isEqualTo("CONFIRMED");
        assertThat(seatRow.get("current_reservation_id")).isEqualTo(reservationId);

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("Cancel 3: Unknown reservation returns 404 RESERVATION_NOT_FOUND")
    void testUnknownReservationNotFound() throws Exception {
        UUID unknownId = UUID.randomUUID();

        mockMvc.perform(post("/reservations/" + unknownId + "/cancel")
                .with(userJwt("user-anyone")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("Cancel 4: Repeated cancellation returns idempotent 200 OK and does not update seats again")
    void testRepeatedCancellationIdempotentReplay() throws Exception {
        UUID showId = createShow("cancel-show-4", List.of("A1"), 10000L, 4);
        UUID reservationId = reserveSeats(showId, "user-repeat", "key-rep-1", List.of("A1"));

        // First cancellation
        mockMvc.perform(post("/reservations/" + reservationId + "/cancel")
                .with(userJwt("user-repeat")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));

        // Second cancellation replay
        mockMvc.perform(post("/reservations/" + reservationId + "/cancel")
                .with(userJwt("user-repeat")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservation_id").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.seats[0]").value("A1"));

        Map<String, Object> resRow = jdbcTemplate.queryForMap(
                "SELECT status, cancelled_at FROM reservations WHERE id = ?",
                reservationId);
        assertThat(resRow.get("status")).isEqualTo("CANCELLED");

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("Cancel 5: Rebooking after cancellation allows another user to become current owner")
    void testRebookingAfterCancellation() throws Exception {
        UUID showId = createShow("cancel-show-5", List.of("A1"), 15000L, 4);
        UUID firstReservationId = reserveSeats(showId, "user-first", "key-first-1", List.of("A1"));

        // First user cancels
        mockMvc.perform(post("/reservations/" + firstReservationId + "/cancel")
                .with(userJwt("user-first")))
                .andExpect(status().isOk());

        // Second user books the released seat
        UUID secondReservationId = reserveSeats(showId, "user-second", "key-second-1", List.of("A1"));

        assertThat(secondReservationId).isNotEqualTo(firstReservationId);

        // Verify old reservation is still CANCELLED
        String firstStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM reservations WHERE id = ?",
                String.class, firstReservationId);
        assertThat(firstStatus).isEqualTo("CANCELLED");

        // Verify seat now belongs to second reservation
        Map<String, Object> seatRow = jdbcTemplate.queryForMap(
                "SELECT status, current_reservation_id FROM seats WHERE show_id = ? AND seat_label = 'A1'",
                showId);
        assertThat(seatRow.get("status")).isEqualTo("CONFIRMED");
        assertThat(seatRow.get("current_reservation_id")).isEqualTo(secondReservationId);

        // First user replaying cancellation does not release the rebooked seat
        mockMvc.perform(post("/reservations/" + firstReservationId + "/cancel")
                .with(userJwt("user-first")))
                .andExpect(status().isOk());

        Map<String, Object> seatRowAfterOldReplay = jdbcTemplate.queryForMap(
                "SELECT status, current_reservation_id FROM seats WHERE show_id = ? AND seat_label = 'A1'",
                showId);
        assertThat(seatRowAfterOldReplay.get("status")).isEqualTo("CONFIRMED");
        assertThat(seatRowAfterOldReplay.get("current_reservation_id")).isEqualTo(secondReservationId);

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("Cancel 6: Multi-seat reservation releases all its seats")
    void testMultiSeatCancellationReleasesAllSeats() throws Exception {
        UUID showId = createShow("cancel-show-6", List.of("M1", "M2", "M3"), 20000L, 4);
        UUID reservationId = reserveSeats(showId, "user-multi", "key-m-1", List.of("M1", "M2", "M3"));

        mockMvc.perform(post("/reservations/" + reservationId + "/cancel")
                .with(userJwt("user-multi")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.seats.length()").value(3));

        List<Map<String, Object>> seats = jdbcTemplate.queryForList(
                "SELECT seat_label, status, current_reservation_id FROM seats WHERE show_id = ? ORDER BY seat_label ASC",
                showId);
        assertThat(seats).allMatch(s -> "AVAILABLE".equals(s.get("status")) && s.get("current_reservation_id") == null);

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("Cancel 7: Spoofed user_id in body is ignored; uses only JWT sub")
    void testSpoofedUserIdIgnoredOnCancel() throws Exception {
        UUID showId = createShow("cancel-show-7", List.of("A1"), 10000L, 4);
        UUID reservationId = reserveSeats(showId, "real-user", "key-r-1", List.of("A1"));

        mockMvc.perform(post("/reservations/" + reservationId + "/cancel")
                .with(userJwt("real-user"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"user_id\": \"attacker\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.user_id").value("real-user"));

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("Cancel 8: Concurrent cancellation requests both return 200 OK and leave one cancelled reservation")
    void testConcurrentCancellationRequests() throws Exception {
        UUID showId = createShow("cancel-show-8", List.of("A1"), 10000L, 4);
        UUID reservationId = reserveSeats(showId, "user-race-cancel", "key-rc-1", List.of("A1"));

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                if (!startLatch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for concurrent start");
                }
                MvcResult result = mockMvc.perform(post("/reservations/" + reservationId + "/cancel")
                        .with(userJwt("user-race-cancel")))
                        .andReturn();
                return result.getResponse().getStatus();
            }));
        }

        releaseWorkersAndAwaitCompletion(readyLatch, startLatch, executor);

        for (Future<Integer> f : futures) {
            assertThat(f.get()).isEqualTo(200);
        }

        Map<String, Object> resRow = jdbcTemplate.queryForMap(
                "SELECT status, cancelled_at FROM reservations WHERE id = ?",
                reservationId);
        assertThat(resRow.get("status")).isEqualTo("CANCELLED");
        assertThat(resRow.get("cancelled_at")).isNotNull();

        assertReconciliation(showId);
    }

    @Test
    @DisplayName("Cancel 9: Race between cancellation and rebooking produces valid serial outcome")
    void testCancelVsRebookRace() throws Exception {
        UUID showId = createShow("cancel-show-9", List.of("HOT_RACE_SEAT"), 20000L, 4);
        UUID reservationId = reserveSeats(showId, "user-canceller", "key-cr-1", List.of("HOT_RACE_SEAT"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<Integer> cancelFuture = executor.submit(() -> {
            readyLatch.countDown();
            if (!startLatch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for start");
            }
            MvcResult result = mockMvc.perform(post("/reservations/" + reservationId + "/cancel")
                    .with(userJwt("user-canceller")))
                    .andReturn();
            return result.getResponse().getStatus();
        });

        Future<Integer> rebookFuture = executor.submit(() -> {
            readyLatch.countDown();
            if (!startLatch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for start");
            }
            MvcResult result = mockMvc.perform(post("/shows/" + showId + "/reserve")
                    .with(userJwt("user-rebooker"))
                    .header("Idempotency-Key", "key-rebook-race")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"seats\": [\"HOT_RACE_SEAT\"]}"))
                    .andReturn();
            return result.getResponse().getStatus();
        });

        releaseWorkersAndAwaitCompletion(readyLatch, startLatch, executor);

        int cancelStatus = cancelFuture.get();
        int rebookStatus = rebookFuture.get();

        assertThat(cancelStatus).isEqualTo(200);
        assertThat(rebookStatus).isIn(201, 409);

        // Verification of reconciliation
        assertReconciliation(showId);

        Map<String, Object> seatRow = jdbcTemplate.queryForMap(
                "SELECT status, current_reservation_id FROM seats WHERE show_id = ? AND seat_label = 'HOT_RACE_SEAT'",
                showId);

        if (rebookStatus == 201) {
            // Rebook happened after cancellation
            assertThat(seatRow.get("status")).isEqualTo("CONFIRMED");
            assertThat(seatRow.get("current_reservation_id")).isNotEqualTo(reservationId);
        } else {
            // Rebook failed with 409 before cancellation completed
            assertThat(seatRow.get("status")).isEqualTo("AVAILABLE");
            assertThat(seatRow.get("current_reservation_id")).isNull();
        }
    }
}
