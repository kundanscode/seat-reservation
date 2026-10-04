package com.kundan.seat_reservation.reservation;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ReserveSeatsRequest(
        @NotEmpty(message = "Seats list must not be empty")
        @Size(max = 20, message = "Cannot reserve more than 20 seats at once")
        List<@NotBlank(message = "Seat label must not be blank") @Size(max = 40, message = "Seat label cannot exceed 40 characters") String> seats,

        @JsonProperty("idempotency_key")
        @JsonAlias({"idempotency_key", "idempotencyKey"})
        String idempotencyKey
) {
    public ReserveSeatsRequest(List<String> seats) {
        this(seats, null);
    }
}
