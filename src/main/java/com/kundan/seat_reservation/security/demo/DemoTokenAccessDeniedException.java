package com.kundan.seat_reservation.security.demo;

public class DemoTokenAccessDeniedException extends RuntimeException {
    public DemoTokenAccessDeniedException(String message) {
        super(message);
    }
}
