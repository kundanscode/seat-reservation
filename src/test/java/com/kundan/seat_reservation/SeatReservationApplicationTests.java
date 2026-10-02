package com.kundan.seat_reservation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("isDockerAvailable")
class SeatReservationApplicationTests {

	static boolean isDockerAvailable() {
		try {
			return DockerClientFactory.instance().isDockerAvailable();
		} catch (Throwable t) {
			return false;
		}
	}

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("Context loads successfully")
	void contextLoads() {
	}

	@Test
	@DisplayName("Flyway applies V1 migration and creates all required tables")
	void allExpectedTablesExist() {
		List<String> tables = jdbcTemplate.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
				String.class
		);

		assertThat(tables).contains(
				"flyway_schema_history",
				"shows",
				"seats",
				"reservations",
				"reservation_seats",
				"idempotency_records",
				"show_user_booking_guards"
		);
	}

	@Test
	@DisplayName("Actuator readiness endpoint returns UP when database is healthy")
	void readinessEndpointReturnsUp() throws Exception {
		mockMvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}
}
