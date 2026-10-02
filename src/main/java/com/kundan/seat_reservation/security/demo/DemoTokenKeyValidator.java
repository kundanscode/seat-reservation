package com.kundan.seat_reservation.security.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DemoTokenKeyValidator {

    private final byte[] expectedKey;

    public DemoTokenKeyValidator(
            @Value("${app.security.demo-token-key:}") String configuredKey) {
        this.expectedKey = configuredKey.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(String suppliedKey) {
        if (suppliedKey == null || suppliedKey.isBlank() || expectedKey.length == 0) {
            return false;
        }

        return MessageDigest.isEqual(
                expectedKey,
                suppliedKey.getBytes(StandardCharsets.UTF_8)
        );
    }
}
