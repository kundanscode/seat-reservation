package com.kundan.seat_reservation.show;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

public record ShowResponse(
        UUID id,
        String name,

        @JsonProperty("price_paise")
        Long pricePaise,

        @JsonProperty("per_user_limit")
        Integer perUserLimit,

        @JsonProperty("total_seats")
        Integer totalSeats,

        Integer available,
        Integer held,
        Integer confirmed,
        List<SeatResponse> seats
) {}
