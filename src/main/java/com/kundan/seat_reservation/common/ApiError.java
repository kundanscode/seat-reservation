package com.kundan.seat_reservation.common;

public record ApiError(
        String code,
        String message
) {}
