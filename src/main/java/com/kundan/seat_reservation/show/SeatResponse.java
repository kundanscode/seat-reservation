package com.kundan.seat_reservation.show;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record SeatResponse(
        UUID id,
        String seat,
        String status
) {
    @JsonProperty("seat_id")
    public UUID seatId() {
        return id;
    }
}
