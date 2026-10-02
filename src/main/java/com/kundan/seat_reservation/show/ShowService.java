package com.kundan.seat_reservation.show;

import com.kundan.seat_reservation.common.DuplicateSeatLabelException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ShowService {

    private static final int DEFAULT_PER_USER_LIMIT = 4;

    private final ShowRepository showRepository;

    public ShowService(ShowRepository showRepository) {
        this.showRepository = showRepository;
    }

    @Transactional(readOnly = true)
    public ShowResponse getShow(UUID showId) {
        return showRepository.findShowByIdWithConsistentCounts(showId)
                .orElseThrow(() -> new com.kundan.seat_reservation.common.ShowNotFoundException("Show not found: " + showId));
    }

    @Transactional
    public ShowResponse createShow(CreateShowRequest request) {
        if (request.name() == null || request.name().trim().isEmpty()) {
            throw new IllegalArgumentException("Show name must not be blank");
        }
        String trimmedName = request.name().trim();
        if (trimmedName.length() > 120) {
            throw new IllegalArgumentException("Show name cannot exceed 120 characters");
        }

        if (request.seats() == null || request.seats().isEmpty()) {
            throw new IllegalArgumentException("Show must have at least one seat");
        }

        if (request.pricePaise() == null) {
            throw new IllegalArgumentException("Price is required");
        }
        if (request.pricePaise() < 0) {
            throw new IllegalArgumentException("Price must be zero or greater");
        }

        if (request.perUserLimit() != null && request.perUserLimit() <= 0) {
            throw new IllegalArgumentException("Per-user limit must be greater than zero");
        }
        int effectiveLimit = request.perUserLimit() != null ? request.perUserLimit() : DEFAULT_PER_USER_LIMIT;

        Set<String> seenLabels = new HashSet<>();
        List<SeatToInsert> seatsToInsert = new ArrayList<>();

        for (String seatLabel : request.seats()) {
            if (seatLabel == null || seatLabel.trim().isEmpty()) {
                throw new IllegalArgumentException("Seat label must not be blank");
            }
            String trimmedLabel = seatLabel.trim();
            if (trimmedLabel.length() > 40) {
                throw new IllegalArgumentException("Seat label cannot exceed 40 characters");
            }
            if (!seenLabels.add(trimmedLabel)) {
                throw new DuplicateSeatLabelException("Seat labels must be unique within a show: " + trimmedLabel);
            }
            seatsToInsert.add(new SeatToInsert(UUID.randomUUID(), trimmedLabel, "AVAILABLE"));
        }

        UUID showId = UUID.randomUUID();
        showRepository.insertShow(showId, trimmedName, request.pricePaise(), effectiveLimit);
        showRepository.insertSeats(showId, seatsToInsert);

        List<ShowSeatRow> savedSeats = showRepository.findSeatsByShowId(showId);

        int totalSeats = savedSeats.size();
        int available = (int) savedSeats.stream().filter(s -> "AVAILABLE".equalsIgnoreCase(s.status())).count();
        int held = (int) savedSeats.stream().filter(s -> "HELD".equalsIgnoreCase(s.status())).count();
        int confirmed = (int) savedSeats.stream().filter(s -> "CONFIRMED".equalsIgnoreCase(s.status())).count();

        List<SeatResponse> seatResponses = savedSeats.stream()
                .map(s -> new SeatResponse(s.id(), s.seatLabel(), s.status()))
                .toList();

        return new ShowResponse(
                showId,
                trimmedName,
                request.pricePaise(),
                effectiveLimit,
                totalSeats,
                available,
                held,
                confirmed,
                seatResponses
        );
    }
}
