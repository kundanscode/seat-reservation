-- V1: Core schema for seat reservation service

CREATE TABLE shows (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    start_time TIMESTAMP WITH TIME ZONE NOT NULL,
    end_time TIMESTAMP WITH TIME ZONE,
    price_paise INTEGER NOT NULL CHECK (price_paise >= 0),
    booking_limit_per_user INTEGER NOT NULL CHECK (booking_limit_per_user > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE seats (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    show_id BIGINT NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    seat_label VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_seats_status CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMED')),
    CONSTRAINT uq_seats_show_seat_label UNIQUE (show_id, seat_label),
    CONSTRAINT uq_seats_show_id UNIQUE (show_id, id)
);

CREATE TABLE reservations (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    show_id BIGINT NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    user_id VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    amount_paise INTEGER NOT NULL CHECK (amount_paise >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_reservations_status CHECK (status IN ('PENDING', 'HELD', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT uq_reservations_show_id UNIQUE (show_id, id)
);

CREATE TABLE reservation_seats (
    reservation_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    show_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (reservation_id, seat_id),
    CONSTRAINT fk_reservation_seats_reservation
        FOREIGN KEY (show_id, reservation_id)
        REFERENCES reservations (show_id, id)
        ON DELETE CASCADE,
    CONSTRAINT fk_reservation_seats_seat
        FOREIGN KEY (show_id, seat_id)
        REFERENCES seats (show_id, id)
        ON DELETE CASCADE
);

CREATE TABLE idempotency_records (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_key VARCHAR(255) NOT NULL UNIQUE,
    request_hash VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    response_status_code INTEGER,
    response_body TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE show_user_booking_guards (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    show_id BIGINT NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    user_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_show_user_booking_guards UNIQUE (show_id, user_id)
);

CREATE INDEX idx_seats_show_id ON seats (show_id);
CREATE INDEX idx_seats_status ON seats (status);
CREATE INDEX idx_reservations_show_id ON reservations (show_id);
CREATE INDEX idx_reservations_user_id ON reservations (user_id);
CREATE INDEX idx_reservation_seats_seat_id ON reservation_seats (seat_id);
CREATE INDEX idx_reservation_seats_show_id ON reservation_seats (show_id);
CREATE INDEX idx_show_user_booking_guards_show_user ON show_user_booking_guards (show_id, user_id);
