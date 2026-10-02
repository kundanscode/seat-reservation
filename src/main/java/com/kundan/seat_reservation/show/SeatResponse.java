package com.kundan.seat_reservation.show;

import java.util.UUID;

public record SeatResponse(
        UUID id,
        String seat,
        String status
) {}
