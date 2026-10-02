package com.kundan.seat_reservation.security.demo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DemoTokenRequest(
        @NotBlank(message = "user_id must not be blank")
        @Pattern(
                regexp = "^loadtest-[A-Za-z0-9_-]{1,48}$",
                message = "user_id must start with loadtest- and contain only letters, numbers, _ or -"
        )
        String user_id
) {
}
