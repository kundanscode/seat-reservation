package com.kundan.seat_reservation.common;

public class DuplicateSeatLabelException extends RuntimeException {
    public DuplicateSeatLabelException(String message) {
        super(message);
    }
}
