package com.kundan.seat_reservation.common;

public class PerUserLimitExceededException extends RuntimeException {
    public PerUserLimitExceededException(String message) {
        super(message);
    }
}
