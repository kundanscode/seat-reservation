package com.kundan.seat_reservation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigrationValidationTest {

    @Test
    @DisplayName("Verify Flyway migration V1 script exists and contains all required tables and constraints")
    void testV1MigrationScriptContainsRequiredSchema() throws IOException {
        ClassPathResource resource = new ClassPathResource("db/migration/V1__create_core_schema.sql");
        assertThat(resource.exists()).isTrue();

        String sql = resource.getContentAsString(StandardCharsets.UTF_8);

        // Verify tables exist
        assertThat(sql).containsIgnoringCase("CREATE TABLE shows");
        assertThat(sql).containsIgnoringCase("CREATE TABLE seats");
        assertThat(sql).containsIgnoringCase("CREATE TABLE reservations");
        assertThat(sql).containsIgnoringCase("CREATE TABLE reservation_seats");
        assertThat(sql).containsIgnoringCase("CREATE TABLE idempotency_records");
        assertThat(sql).containsIgnoringCase("CREATE TABLE show_user_booking_guards");

        // Verify shows table requirements
        assertThat(sql).containsIgnoringCase("price_paise");
        assertThat(sql).containsIgnoringCase("per_user_limit");
        assertThat(sql).containsIgnoringCase("price_paise >= 0");
        assertThat(sql).containsIgnoringCase("per_user_limit > 0");

        // Verify seats table requirements
        assertThat(sql).containsIgnoringCase("seat_label");
        assertThat(sql).containsIgnoringCase("AVAILABLE");
        assertThat(sql).containsIgnoringCase("HELD");
        assertThat(sql).containsIgnoringCase("CONFIRMED");
        assertThat(sql).containsIgnoringCase("uq_seats_show_seat_label");
        assertThat(sql).containsIgnoringCase("uq_seats_show_id");

        // Verify reservations table requirements
        assertThat(sql).containsIgnoringCase("amount_paise");
        assertThat(sql).containsIgnoringCase("amount_paise >= 0");
        assertThat(sql).containsIgnoringCase("CANCELLED");
        assertThat(sql).containsIgnoringCase("uq_reservations_show_id");

        // Verify reservation_seats composite foreign keys
        assertThat(sql).containsIgnoringCase("fk_reservation_seats_reservation");
        assertThat(sql).containsIgnoringCase("fk_reservation_seats_seat");
        assertThat(sql).containsIgnoringCase("FOREIGN KEY (show_id, reservation_id)");
        assertThat(sql).containsIgnoringCase("REFERENCES reservations (show_id, id)");
        assertThat(sql).containsIgnoringCase("FOREIGN KEY (show_id, seat_id)");
        assertThat(sql).containsIgnoringCase("REFERENCES seats (show_id, id)");

        // Verify idempotency_records
        assertThat(sql).containsIgnoringCase("request_key");
        assertThat(sql).containsIgnoringCase("request_hash");

        // Verify show_user_booking_guards unique constraint
        assertThat(sql).containsIgnoringCase("uq_show_user_booking_guards");
    }

    @Test
    @DisplayName("Verify Flyway migration V2 script exists and contains response_json and current_reservation_id")
    void testV2MigrationScriptContainsRequiredSchema() throws IOException {
        ClassPathResource resource = new ClassPathResource("db/migration/V2__store_idempotency_response.sql");
        assertThat(resource.exists()).isTrue();

        String sql = resource.getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).containsIgnoringCase("response_json");
        assertThat(sql).containsIgnoringCase("current_reservation_id");
        assertThat(sql).containsIgnoringCase("ALTER TABLE idempotency_records");
        assertThat(sql).containsIgnoringCase("ALTER TABLE seats");
    }

    @Test
    @DisplayName("Verify Flyway migration V3 script exists and contains cancelled_at")
    void testV3MigrationScriptContainsRequiredSchema() throws IOException {
        ClassPathResource resource = new ClassPathResource("db/migration/V3__add_cancelled_at_to_reservations.sql");
        assertThat(resource.exists()).isTrue();

        String sql = resource.getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).containsIgnoringCase("cancelled_at");
        assertThat(sql).containsIgnoringCase("ALTER TABLE reservations");
    }
}
