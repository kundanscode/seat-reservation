package com.kundan.seat_reservation.reservation;

import com.jayway.jsonpath.JsonPath;
import com.kundan.seat_reservation.TestcontainersConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ReservationMetricsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ReservationMetrics reservationMetrics;

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j.claim("scope", "admin").subject("admin-metrics"));
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

    @Test
    @DisplayName("Increments confirmed, declined, and tracks available seats in Prometheus metrics")
    void testMetricsTrackReservationsAndDeclines() throws Exception {
        Counter confirmed = meterRegistry.find("reservations.confirmed").counter();
        assertThat(confirmed).isNotNull();
        double initialConfirmed = confirmed.count();

        Counter seatTaken = meterRegistry.find("reservations.declined").tag("reason", "seat-taken").counter();
        assertThat(seatTaken).isNotNull();
        double initialSeatTaken = seatTaken.count();

        Counter userLimit = meterRegistry.find("reservations.declined").tag("reason", "per-user-limit").counter();
        assertThat(userLimit).isNotNull();
        double initialUserLimit = userLimit.count();

        Counter replayCounter = meterRegistry.find("reservations.declined").tag("reason", "idempotent-replay").counter();
        assertThat(replayCounter).isNotNull();
        double initialReplay = replayCounter.count();

        // 1. Create a show with 3 seats
        UUID showId = createShow("metrics-show-1", List.of("M1", "M2", "M3"), 10000L, 1);
        double availableBefore = reservationMetrics.getAvailableSeatsCount();
        assertThat(availableBefore).isGreaterThanOrEqualTo(3.0);

        // 2. Make a valid reservation -> confirmed increments
        mockMvc.perform(post("/shows/" + showId + "/reserve")
                        .with(userJwt("user-metrics-1"))
                        .header("Idempotency-Key", "key-m1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\": [\"M1\"]}"))
                .andExpect(status().isCreated());

        assertThat(confirmed.count()).isEqualTo(initialConfirmed + 1);

        // 3. Retry with same key -> idempotent-replay increments
        mockMvc.perform(post("/shows/" + showId + "/reserve")
                        .with(userJwt("user-metrics-1"))
                        .header("Idempotency-Key", "key-m1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\": [\"M1\"]}"))
                .andExpect(status().isOk());

        assertThat(replayCounter.count()).isEqualTo(initialReplay + 1);

        // 4. Another user tries to reserve M1 -> seat-taken increments
        mockMvc.perform(post("/shows/" + showId + "/reserve")
                        .with(userJwt("user-metrics-2"))
                        .header("Idempotency-Key", "key-m2-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\": [\"M1\"]}"))
                .andExpect(status().isConflict());

        assertThat(seatTaken.count()).isEqualTo(initialSeatTaken + 1);

        // 5. User 1 tries to reserve another seat (M2), exceeding limit of 1 -> per-user-limit increments
        mockMvc.perform(post("/shows/" + showId + "/reserve")
                        .with(userJwt("user-metrics-1"))
                        .header("Idempotency-Key", "key-m1-overlimit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\": [\"M2\"]}"))
                .andExpect(status().isConflict());

        assertThat(userLimit.count()).isEqualTo(initialUserLimit + 1);

        // 6. Scrape /actuator/prometheus and verify output format
        MvcResult scrapeResult = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn();

        String body = scrapeResult.getResponse().getContentAsString();
        assertThat(body).contains("reservations_confirmed_total");
        assertThat(body).contains("reservations_declined_total");
        assertThat(body).contains("reason=\"seat-taken\"");
        assertThat(body).contains("reason=\"per-user-limit\"");
        assertThat(body).contains("reason=\"idempotent-replay\"");
        assertThat(body).contains("seats_available");
        assertThat(body).contains("seats_confirmed");
    }
}
