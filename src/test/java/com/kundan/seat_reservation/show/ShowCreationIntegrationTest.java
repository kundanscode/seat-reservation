package com.kundan.seat_reservation.show;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ShowCreationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Creates a show: response is 201, show details and seats are AVAILABLE, counts reconcile, location header set")
    void testCreateShowSuccess() throws Exception {
        String requestJson = """
                {
                  "name": "friday-night",
                  "seats": ["A1", "A2", "A3"],
                  "price_paise": 25000,
                  "per_user_limit": 4
                }
                """;

        MvcResult result = mockMvc.perform(post("/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("friday-night"))
                .andExpect(jsonPath("$.price_paise").value(25000))
                .andExpect(jsonPath("$.per_user_limit").value(4))
                .andExpect(jsonPath("$.total_seats").value(3))
                .andExpect(jsonPath("$.available").value(3))
                .andExpect(jsonPath("$.held").value(0))
                .andExpect(jsonPath("$.confirmed").value(0))
                .andExpect(jsonPath("$.seats.length()").value(3))
                .andExpect(jsonPath("$.seats[0].seat").value("A1"))
                .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.seats[1].seat").value("A2"))
                .andExpect(jsonPath("$.seats[1].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.seats[2].seat").value("A3"))
                .andExpect(jsonPath("$.seats[2].status").value("AVAILABLE"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        String showIdStr = JsonPath.read(responseBody, "$.id");
        UUID showId = UUID.fromString(showIdStr);

        String locationHeader = result.getResponse().getHeader("Location");
        assertThat(locationHeader).isEqualTo("/shows/" + showIdStr);

        // Verify PostgreSQL persistence
        Map<String, Object> showRow = jdbcTemplate.queryForMap(
                "SELECT id, name, price_paise, per_user_limit FROM shows WHERE id = ?",
                showId
        );
        assertThat(showRow.get("name")).isEqualTo("friday-night");
        assertThat(((Number) showRow.get("price_paise")).longValue()).isEqualTo(25000L);
        assertThat(((Number) showRow.get("per_user_limit")).intValue()).isEqualTo(4);

        List<Map<String, Object>> seatRows = jdbcTemplate.queryForList(
                "SELECT id, show_id, seat_label, status FROM seats WHERE show_id = ? ORDER BY seat_label ASC",
                showId
        );
        assertThat(seatRows).hasSize(3);
        assertThat(seatRows.get(0).get("seat_label")).isEqualTo("A1");
        assertThat(seatRows.get(0).get("status")).isEqualTo("AVAILABLE");
        assertThat(seatRows.get(1).get("seat_label")).isEqualTo("A2");
        assertThat(seatRows.get(1).get("status")).isEqualTo("AVAILABLE");
        assertThat(seatRows.get(2).get("seat_label")).isEqualTo("A3");
        assertThat(seatRows.get(2).get("status")).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("Defaults the limit: omitting per_user_limit results in a default limit of 4")
    void testCreateShowDefaultLimit() throws Exception {
        String requestJson = """
                {
                  "name": "saturday-matinee",
                  "seats": ["B1", "B2"],
                  "price_paise": 15000
                }
                """;

        mockMvc.perform(post("/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.per_user_limit").value(4));
    }

    @Test
    @DisplayName("Rejects duplicate labels: ['A1', ' A1 '] receives 400 Bad Request with code INVALID_REQUEST")
    void testRejectDuplicateSeatLabels() throws Exception {
        String requestJson = """
                {
                  "name": "sunday-evening",
                  "seats": ["A1", " A1 "],
                  "price_paise": 20000
                }
                """;

        mockMvc.perform(post("/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("Rejects invalid input: blank name, empty seats, blank label, negative price, or non-positive limit receives 400")
    void testRejectInvalidInputs() throws Exception {
        // Blank name
        mockMvc.perform(post("/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "   ",
                                  "seats": ["A1"],
                                  "price_paise": 1000
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // Empty seats
        mockMvc.perform(post("/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "valid-name",
                                  "seats": [],
                                  "price_paise": 1000
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // Blank seat label
        mockMvc.perform(post("/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "valid-name",
                                  "seats": ["   "],
                                  "price_paise": 1000
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // Negative price
        mockMvc.perform(post("/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "valid-name",
                                  "seats": ["A1"],
                                  "price_paise": -500
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // Non-positive per_user_limit
        mockMvc.perform(post("/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "valid-name",
                                  "seats": ["A1"],
                                  "price_paise": 1000,
                                  "per_user_limit": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("Does not leave partial data: after a rejected request, show and seat rows have not increased")
    void testTransactionalRollbackOnFailure() throws Exception {
        int initialShowsCount = jdbcTemplate.queryForObject("SELECT count(*) FROM shows", Integer.class);
        int initialSeatsCount = jdbcTemplate.queryForObject("SELECT count(*) FROM seats", Integer.class);

        String duplicateSeatsJson = """
                {
                  "name": "rollback-test-show",
                  "seats": ["C1", "C2", " C1 "],
                  "price_paise": 10000
                }
                """;

        mockMvc.perform(post("/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateSeatsJson))
                .andExpect(status().isBadRequest());

        int finalShowsCount = jdbcTemplate.queryForObject("SELECT count(*) FROM shows", Integer.class);
        int finalSeatsCount = jdbcTemplate.queryForObject("SELECT count(*) FROM seats", Integer.class);

        assertThat(finalShowsCount).isEqualTo(initialShowsCount);
        assertThat(finalSeatsCount).isEqualTo(initialSeatsCount);
    }

    @Test
    @DisplayName("Seat labels are trimmed and case-sensitive: 'A1' and 'a1' are allowed as distinct")
    void testCaseSensitivityAndTrimming() throws Exception {
        String requestJson = """
                {
                  "name": "case-test-show",
                  "seats": [" A1 ", " a1 "],
                  "price_paise": 5000
                }
                """;

        MvcResult result = mockMvc.perform(post("/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total_seats").value(2))
                .andReturn();

        List<String> seatLabels = JsonPath.read(result.getResponse().getContentAsString(), "$.seats[*].seat");
        assertThat(seatLabels).containsExactlyInAnyOrder("A1", "a1");
    }
}
