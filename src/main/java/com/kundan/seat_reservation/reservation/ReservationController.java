package com.kundan.seat_reservation.reservation;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable("id") UUID showId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody ReserveSeatsRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        String effectiveKey = (idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank())
                ? idempotencyKeyHeader.trim()
                : (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()
                        ? request.idempotencyKey().trim()
                        : null);

        if (effectiveKey == null || effectiveKey.isEmpty()) {
            throw new IllegalArgumentException("Idempotency key is required (in 'Idempotency-Key' header or 'idempotency_key' request body)");
        }
        if (effectiveKey.length() > 200) {
            throw new IllegalArgumentException("Idempotency-Key must not exceed 200 characters");
        }
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new IllegalArgumentException("User identity missing in token");
        }

        String userId = jwt.getSubject();
        ReservationResult result = reservationService.reserve(showId, userId, effectiveKey, request);

        if (result.replayed()) {
            return ResponseEntity.ok(result.response());
        } else {
            return ResponseEntity.status(HttpStatus.CREATED).body(result.response());
        }
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    public ResponseEntity<ReservationResponse> cancel(
            @PathVariable("reservationId") UUID reservationId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new IllegalArgumentException("User identity missing in token");
        }

        String userId = jwt.getSubject();
        ReservationResponse response = reservationService.cancelReservation(reservationId, userId);
        return ResponseEntity.ok(response);
    }
}
