package com.kundan.seat_reservation.show;

import java.util.UUID;

public record ShowSeatRow(
        UUID id,
        UUID showId,
        String seatLabel,
        String status
) {}
