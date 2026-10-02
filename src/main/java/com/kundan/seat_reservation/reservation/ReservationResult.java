package com.kundan.seat_reservation.reservation;

public record ReservationResult(
        ReservationResponse response,
        boolean replayed
) {}
