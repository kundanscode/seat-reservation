package com.kundan.seat_reservation.show;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateShowRequest(
        @NotBlank(message = "Show name must not be blank")
        @Size(max = 120, message = "Show name cannot exceed 120 characters")
        String name,

        @NotEmpty(message = "Show must contain at least one seat")
        List<@NotBlank(message = "Seat label must not be blank") @Size(max = 40, message = "Seat label cannot exceed 40 characters") String> seats,

        @NotNull(message = "Price is required")
        @PositiveOrZero(message = "Price must be zero or greater")
        @JsonProperty("price_paise")
        Long pricePaise,

        @Positive(message = "Per-user limit must be greater than zero")
        @JsonProperty("per_user_limit")
        Integer perUserLimit
) {}
