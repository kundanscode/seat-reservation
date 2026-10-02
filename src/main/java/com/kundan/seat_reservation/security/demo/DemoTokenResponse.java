package com.kundan.seat_reservation.security.demo;

public record DemoTokenResponse(
        String access_token,
        String token_type,
        long expires_in
) {
}
