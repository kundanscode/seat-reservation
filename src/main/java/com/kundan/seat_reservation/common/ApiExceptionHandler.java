package com.kundan.seat_reservation.common;

import com.kundan.seat_reservation.reservation.ReservationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ReservationMetrics reservationMetrics;

    public ApiExceptionHandler(ReservationMetrics reservationMetrics) {
        this.reservationMetrics = reservationMetrics;
    }

    @ExceptionHandler(DuplicateSeatLabelException.class)
    public ResponseEntity<ApiError> handleDuplicateSeatLabel(DuplicateSeatLabelException ex) {
        log.warn("Duplicate seat label rejected: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Invalid request argument: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Validation failed for request";
        }
        log.warn("Request validation failed: {}", message);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", message));
    }

    @ExceptionHandler(ShowNotFoundException.class)
    public ResponseEntity<ApiError> handleShowNotFound(ShowNotFoundException ex) {
        log.warn("Show not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiError("SHOW_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    public ResponseEntity<ApiError> handleReservationNotFound(ReservationNotFoundException ex) {
        log.warn("Reservation not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiError("RESERVATION_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(ReservationOwnershipException.class)
    public ResponseEntity<ApiError> handleReservationOwnership(ReservationOwnershipException ex) {
        log.warn("Reservation ownership mismatch: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new ApiError("FORBIDDEN", ex.getMessage()));
    }

    @ExceptionHandler(com.kundan.seat_reservation.security.demo.DemoTokenAccessDeniedException.class)
    public ResponseEntity<ApiError> handleDemoTokenAccessDenied(com.kundan.seat_reservation.security.demo.DemoTokenAccessDeniedException ex) {
        log.warn("Demo token access denied: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new ApiError("DEMO_TOKEN_FORBIDDEN", ex.getMessage()));
    }

    @ExceptionHandler(SeatNotFoundException.class)
    public ResponseEntity<ApiError> handleSeatNotFound(SeatNotFoundException ex) {
        log.warn("Seat not found: {}", ex.getMessage());
        reservationMetrics.recordDeclined("seat-not-found");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiError("SEAT_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(SeatTakenException.class)
    public ResponseEntity<ApiError> handleSeatTaken(SeatTakenException ex) {
        log.warn("Seat already taken: {}", ex.getMessage());
        reservationMetrics.recordDeclined("seat-taken");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ApiError("SEAT_TAKEN", ex.getMessage()));
    }

    @ExceptionHandler(PerUserLimitExceededException.class)
    public ResponseEntity<ApiError> handlePerUserLimitExceeded(PerUserLimitExceededException ex) {
        log.warn("Per-user limit exceeded: {}", ex.getMessage());
        reservationMetrics.recordDeclined("per-user-limit");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ApiError("PER_USER_LIMIT_EXCEEDED", ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ApiError> handleIdempotencyKeyReused(IdempotencyKeyReusedException ex) {
        log.warn("Idempotency key reused: {}", ex.getMessage());
        reservationMetrics.recordDeclined("idempotency-key-reused");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ApiError("IDEMPOTENCY_KEY_REUSED", ex.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingRequestHeader(org.springframework.web.bind.MissingRequestHeaderException ex) {
        log.warn("Missing request header: {}", ex.getHeaderName());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", "Missing required header: " + ex.getHeaderName()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", "Malformed or unreadable request body"));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResourceFound(org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGenericException(Exception ex) {
        log.error("Unexpected error occurred", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
