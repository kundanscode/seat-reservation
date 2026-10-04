package com.kundan.seat_reservation.reservation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ReservationMetrics {

    private final MeterRegistry registry;
    private final JdbcTemplate jdbcTemplate;

    private final Counter confirmedCounter;
    private final Map<String, Counter> declinedCounters = new ConcurrentHashMap<>();

    public ReservationMetrics(MeterRegistry registry, JdbcTemplate jdbcTemplate) {
        this.registry = registry;
        this.jdbcTemplate = jdbcTemplate;

        this.confirmedCounter = Counter.builder("reservations.confirmed")
                .description("Total number of seat reservations confirmed")
                .register(registry);

        // Pre-register canonical declined reason counters
        getOrCreateDeclinedCounter("seat-taken");
        getOrCreateDeclinedCounter("per-user-limit");
        getOrCreateDeclinedCounter("idempotent-replay");
        getOrCreateDeclinedCounter("idempotency-key-reused");

        // Real-time seat gauges that query the database on scrape
        Gauge.builder("seats.available", this, ReservationMetrics::getAvailableSeatsCount)
                .description("Current count of seats in AVAILABLE status")
                .register(registry);

        Gauge.builder("seats.confirmed", this, ReservationMetrics::getConfirmedSeatsCount)
                .description("Current count of seats in CONFIRMED status")
                .register(registry);

        Gauge.builder("seats.held", this, ReservationMetrics::getHeldSeatsCount)
                .description("Current count of seats in HELD status")
                .register(registry);

        Gauge.builder("seats.total", this, ReservationMetrics::getTotalSeatsCount)
                .description("Current total count of all seats")
                .register(registry);
    }

    public void recordConfirmed() {
        confirmedCounter.increment();
    }

    public void recordDeclined(String reason) {
        getOrCreateDeclinedCounter(reason).increment();
    }

    private Counter getOrCreateDeclinedCounter(String reason) {
        return declinedCounters.computeIfAbsent(reason, r ->
                Counter.builder("reservations.declined")
                        .tag("reason", r)
                        .description("Total number of seat reservations declined by reason")
                        .register(registry)
        );
    }

    public double getAvailableSeatsCount() {
        return querySeatCountByStatus("AVAILABLE");
    }

    public double getConfirmedSeatsCount() {
        return querySeatCountByStatus("CONFIRMED");
    }

    public double getHeldSeatsCount() {
        return querySeatCountByStatus("HELD");
    }

    public double getTotalSeatsCount() {
        try {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seats", Integer.class);
            return count != null ? count.doubleValue() : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double querySeatCountByStatus(String status) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM seats WHERE status = ?",
                    Integer.class,
                    status
            );
            return count != null ? count.doubleValue() : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }
}
