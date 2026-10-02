package com.kundan.seat_reservation.show;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
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
}
