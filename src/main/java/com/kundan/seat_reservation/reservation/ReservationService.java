package com.kundan.seat_reservation.reservation;

import com.kundan.seat_reservation.common.IdempotencyKeyReusedException;
import com.kundan.seat_reservation.common.PerUserLimitExceededException;
import com.kundan.seat_reservation.common.SeatNotFoundException;
import com.kundan.seat_reservation.common.SeatTakenException;
import com.kundan.seat_reservation.common.ShowNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ObjectMapper objectMapper;

    public ReservationService(ReservationRepository reservationRepository, ObjectMapper objectMapper) {
        this.reservationRepository = reservationRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReservationResult reserve(UUID showId, String userId, String idempotencyKey, ReserveSeatsRequest request) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        String trimmedKey = idempotencyKey.trim();
        if (trimmedKey.length() > 200) {
            throw new IllegalArgumentException("Idempotency-Key must not exceed 200 characters");
        }

        if (request.seats() == null || request.seats().isEmpty()) {
            throw new IllegalArgumentException("Show must contain at least one seat");
        }
        if (request.seats().size() > 20) {
            throw new IllegalArgumentException("Cannot reserve more than 20 seats at once");
        }

        Set<String> seenLabels = new HashSet<>();
        List<String> normalizedSeats = new ArrayList<>();
        for (String seatLabel : request.seats()) {
            if (seatLabel == null || seatLabel.trim().isEmpty()) {
                throw new IllegalArgumentException("Seat label must not be blank");
            }
            String trimmedLabel = seatLabel.trim();
            if (trimmedLabel.length() > 40) {
                throw new IllegalArgumentException("Seat label cannot exceed 40 characters");
            }
            if (!seenLabels.add(trimmedLabel)) {
                throw new IllegalArgumentException("Duplicate seat label: " + trimmedLabel);
            }
            normalizedSeats.add(trimmedLabel);
        }

        List<String> sortedSeats = new ArrayList<>(normalizedSeats);
        Collections.sort(sortedSeats);

        String canonicalHash = computeCanonicalHash(sortedSeats);

        ReservationRepository.ShowPriceAndLimit show = reservationRepository.findShowPriceAndLimit(showId)
                .orElseThrow(() -> new ShowNotFoundException("Show not found: " + showId));

        reservationRepository.insertGuardRow(showId, userId);
        reservationRepository.lockGuardRow(showId, userId);

        var priorRecord = reservationRepository.findIdempotencyRecord(showId, userId, trimmedKey);
        if (priorRecord.isPresent()) {
            var prior = priorRecord.get();
            if (!prior.requestHash().equals(canonicalHash)) {
                throw new IdempotencyKeyReusedException("Idempotency key has already been used with different request parameters");
            }
            ReservationResponse cachedResponse = deserializeResponse(prior.responseJson());
            return new ReservationResult(cachedResponse, true);
        }

        List<UUID> seatIds = new ArrayList<>();
        for (String seatLabel : sortedSeats) {
            var lockedSeat = reservationRepository.lockSeat(showId, seatLabel)
                    .orElseThrow(() -> new SeatNotFoundException("Seat not found: " + seatLabel));
            if (!"AVAILABLE".equalsIgnoreCase(lockedSeat.status())) {
                throw new SeatTakenException("Seat is not available: " + seatLabel);
            }
            seatIds.add(lockedSeat.id());
        }

        int currentCount = reservationRepository.countConfirmedSeats(showId, userId);
        if (currentCount + sortedSeats.size() > show.perUserLimit()) {
            throw new PerUserLimitExceededException("Booking would exceed the show limit of " + show.perUserLimit() + " seats per user");
        }

        long amountPaise;
        try {
            amountPaise = Math.multiplyExact(show.pricePaise(), (long) sortedSeats.size());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Total amount overflow");
        }

        UUID reservationId = UUID.randomUUID();
        reservationRepository.insertReservation(reservationId, showId, userId, amountPaise);
        reservationRepository.insertReservationSeats(reservationId, showId, seatIds);
        reservationRepository.updateSeatsConfirmed(reservationId, seatIds);

        ReservationResponse response = new ReservationResponse(
                reservationId,
                showId,
                userId,
                sortedSeats,
                amountPaise,
                "confirmed"
        );

        String responseJson = serializeResponse(response);
        reservationRepository.insertIdempotencyRecord(
                UUID.randomUUID(),
                showId,
                userId,
                trimmedKey,
                canonicalHash,
                reservationId,
                responseJson
        );

        return new ReservationResult(response, false);
    }

    @Transactional
    public ReservationResponse cancelReservation(UUID reservationId, String userId) {
        if (reservationId == null) {
            throw new IllegalArgumentException("Reservation ID is required");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User identity missing in token");
        }

        var reservation = reservationRepository.findReservationForUpdate(reservationId)
                .orElseThrow(() -> new com.kundan.seat_reservation.common.ReservationNotFoundException("Reservation not found: " + reservationId));

        if (!reservation.userId().equals(userId)) {
            throw new com.kundan.seat_reservation.common.ReservationOwnershipException("You are not authorized to cancel this reservation");
        }

        if ("CANCELLED".equalsIgnoreCase(reservation.status())) {
            List<String> seatLabels = reservationRepository.findSeatLabelsForReservation(reservationId);
            return new ReservationResponse(
                    reservation.id(),
                    reservation.showId(),
                    reservation.userId(),
                    seatLabels,
                    reservation.amountPaise(),
                    "cancelled"
            );
        }

        List<ReservationRepository.ReservationSeatLockRow> lockedSeats = reservationRepository.lockSeatsForReservation(reservationId);
        if (lockedSeats.isEmpty()) {
            throw new IllegalStateException("Reservation has no associated seats: " + reservationId);
        }

        for (ReservationRepository.ReservationSeatLockRow seat : lockedSeats) {
            if (!"CONFIRMED".equalsIgnoreCase(seat.status()) || !reservationId.equals(seat.currentReservationId())) {
                throw new IllegalStateException("Seat ownership inconsistent for reservation " + reservationId + ", seat: " + seat.seatLabel());
            }
        }

        int releasedCount = reservationRepository.releaseSeatsForReservation(reservationId);
        if (releasedCount != lockedSeats.size()) {
            throw new IllegalStateException("Expected to release " + lockedSeats.size() + " seats but released " + releasedCount);
        }

        reservationRepository.markReservationCancelled(reservationId);

        List<String> seatLabels = lockedSeats.stream().map(ReservationRepository.ReservationSeatLockRow::seatLabel).toList();
        return new ReservationResponse(
                reservation.id(),
                reservation.showId(),
                reservation.userId(),
                seatLabels,
                reservation.amountPaise(),
                "cancelled"
        );
    }

    private String computeCanonicalHash(List<String> sortedSeats) {
        try {
            String canonicalJson = objectMapper.writeValueAsString(sortedSeats);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    private String serializeResponse(ReservationResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize reservation response", e);
        }
    }

    private ReservationResponse deserializeResponse(String json) {
        try {
            return objectMapper.readValue(json, ReservationResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize reservation response", e);
        }
    }
}
