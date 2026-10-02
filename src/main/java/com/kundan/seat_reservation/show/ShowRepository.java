package com.kundan.seat_reservation.show;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ShowRepository {

    private final JdbcTemplate jdbcTemplate;

    public ShowRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertShow(UUID id, String name, long pricePaise, int perUserLimit) {
        String sql = "INSERT INTO shows (id, name, price_paise, per_user_limit) VALUES (?, ?, ?, ?)";
        jdbcTemplate.update(sql, id, name, pricePaise, perUserLimit);
    }

    public void insertSeats(UUID showId, List<SeatToInsert> seats) {
        String sql = "INSERT INTO seats (id, show_id, seat_label, status) VALUES (?, ?, ?, ?)";
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                SeatToInsert seat = seats.get(i);
                ps.setObject(1, seat.id());
                ps.setObject(2, showId);
                ps.setString(3, seat.seatLabel());
                ps.setString(4, seat.status());
            }

            @Override
            public int getBatchSize() {
                return seats.size();
            }
        });
    }

    public List<ShowSeatRow> findSeatsByShowId(UUID showId) {
        String sql = "SELECT id, show_id, seat_label, status FROM seats WHERE show_id = ? ORDER BY seat_label ASC";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new ShowSeatRow(
                rs.getObject("id", UUID.class),
                rs.getObject("show_id", UUID.class),
                rs.getString("seat_label"),
                rs.getString("status")
        ), showId);
    }

    private record ShowResponseRow(
            UUID showId,
            String name,
            long pricePaise,
            int perUserLimit,
            int totalSeats,
            int available,
            int held,
            int confirmed,
            UUID seatId,
            String seatLabel,
            String seatStatus
    ) {}

    public Optional<ShowResponse> findShowByIdWithConsistentCounts(UUID showId) {
        String sql = """
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
                ORDER BY st.seat_label ASC
                """;

        List<ShowResponseRow> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new ShowResponseRow(
                rs.getObject("show_id", UUID.class),
                rs.getString("name"),
                rs.getLong("price_paise"),
                rs.getInt("per_user_limit"),
                rs.getInt("total_seats"),
                rs.getInt("available"),
                rs.getInt("held"),
                rs.getInt("confirmed"),
                rs.getObject("seat_id", UUID.class),
                rs.getString("seat_label"),
                rs.getString("seat_status")
        ), showId);

        if (rows.isEmpty()) {
            return Optional.empty();
        }

        ShowResponseRow first = rows.get(0);
        List<SeatResponse> seatResponses = rows.stream()
                .map(r -> new SeatResponse(r.seatId(), r.seatLabel(), r.seatStatus()))
                .toList();

        return Optional.of(new ShowResponse(
                first.showId(),
                first.name(),
                first.pricePaise(),
                first.perUserLimit(),
                first.totalSeats(),
                first.available(),
                first.held(),
                first.confirmed(),
                seatResponses
        ));
    }
}
