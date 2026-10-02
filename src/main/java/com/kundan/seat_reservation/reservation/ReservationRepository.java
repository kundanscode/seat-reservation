package com.kundan.seat_reservation.reservation;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReservationRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ShowPriceAndLimit(long pricePaise, int perUserLimit) {}
    public record IdempotencyRecord(String requestHash, String responseJson) {}
    public record LockedSeat(UUID id, UUID showId, String seatLabel, String status) {}

    public Optional<ShowPriceAndLimit> findShowPriceAndLimit(UUID showId) {
        String sql = "SELECT price_paise, per_user_limit FROM shows WHERE id = ?";
        List<ShowPriceAndLimit> results = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ShowPriceAndLimit(
                        rs.getLong("price_paise"),
                        rs.getInt("per_user_limit")
                ),
                showId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void insertGuardRow(UUID showId, String userId) {
        String sql = "INSERT INTO show_user_booking_guards (id, show_id, user_id) VALUES (?, ?, ?) ON CONFLICT (show_id, user_id) DO NOTHING";
        jdbcTemplate.update(sql, UUID.randomUUID(), showId, userId);
    }

    public void lockGuardRow(UUID showId, String userId) {
        String sql = "SELECT id FROM show_user_booking_guards WHERE show_id = ? AND user_id = ? FOR UPDATE";
        jdbcTemplate.queryForObject(sql, UUID.class, showId, userId);
    }

    public Optional<IdempotencyRecord> findIdempotencyRecord(UUID showId, String userId, String idempotencyKey) {
        String sql = "SELECT request_hash, response_json::text FROM idempotency_records WHERE show_id = ? AND user_id = ? AND idempotency_key = ?";
        List<IdempotencyRecord> results = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new IdempotencyRecord(
                        rs.getString("request_hash"),
                        rs.getString("response_json")
                ),
                showId, userId, idempotencyKey
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<LockedSeat> lockSeat(UUID showId, String seatLabel) {
        String sql = "SELECT id, show_id, seat_label, status FROM seats WHERE show_id = ? AND seat_label = ? FOR UPDATE";
        List<LockedSeat> results = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new LockedSeat(
                        rs.getObject("id", UUID.class),
                        rs.getObject("show_id", UUID.class),
                        rs.getString("seat_label"),
                        rs.getString("status")
                ),
                showId, seatLabel
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public int countConfirmedSeats(UUID showId, String userId) {
        String sql = """
                SELECT COUNT(rs.seat_id)
                FROM reservation_seats rs
                JOIN reservations r ON rs.reservation_id = r.id AND rs.show_id = r.show_id
                WHERE r.show_id = ? AND r.user_id = ? AND r.status = 'CONFIRMED'
                """;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, showId, userId);
        return count != null ? count : 0;
    }

    public void insertReservation(UUID reservationId, UUID showId, String userId, long amountPaise) {
        String sql = "INSERT INTO reservations (id, show_id, user_id, status, amount_paise) VALUES (?, ?, ?, 'CONFIRMED', ?)";
        jdbcTemplate.update(sql, reservationId, showId, userId, amountPaise);
    }

    public void insertReservationSeats(UUID reservationId, UUID showId, List<UUID> seatIds) {
        String sql = "INSERT INTO reservation_seats (reservation_id, seat_id, show_id) VALUES (?, ?, ?)";
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setObject(1, reservationId);
                ps.setObject(2, seatIds.get(i));
                ps.setObject(3, showId);
            }

            @Override
            public int getBatchSize() {
                return seatIds.size();
            }
        });
    }

    public void updateSeatsConfirmed(UUID reservationId, List<UUID> seatIds) {
        String sql = "UPDATE seats SET status = 'CONFIRMED', current_reservation_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setObject(1, reservationId);
                ps.setObject(2, seatIds.get(i));
            }

            @Override
            public int getBatchSize() {
                return seatIds.size();
            }
        });
    }

    public void insertIdempotencyRecord(UUID id, UUID showId, String userId, String idempotencyKey, String requestHash, UUID reservationId, String responseJson) {
        String sql = """
                INSERT INTO idempotency_records (id, show_id, user_id, idempotency_key, request_key, request_hash, status, reservation_id, response_json)
                VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, ?::jsonb)
                """;
        String requestKey = showId + ":" + userId + ":" + idempotencyKey;
        jdbcTemplate.update(sql, id, showId, userId, idempotencyKey, requestKey, requestHash, reservationId, responseJson);
    }
}
