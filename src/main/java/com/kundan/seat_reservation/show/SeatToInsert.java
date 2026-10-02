package com.kundan.seat_reservation.show;

import java.util.UUID;

public record SeatToInsert(
        UUID id,
        String seatLabel,
        String status
) {}
