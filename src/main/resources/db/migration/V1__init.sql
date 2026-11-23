-- Payment-Service schema (see cinema-data-model.html).
-- Money is integer øre (13500 = 135,00 kr). All timestamps are UTC (timestamptz).

CREATE TABLE payments (
    id                    UUID        PRIMARY KEY,
    booking_id            UUID        NOT NULL UNIQUE,   -- one payment per booking (makes booking.created idempotent)
    user_id               UUID        NOT NULL,
    amount                INTEGER     NOT NULL CHECK (amount >= 0),
    currency              VARCHAR(3)  NOT NULL,
    status                VARCHAR(20) NOT NULL
                          CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'CANCELLED', 'REFUNDED')),

    stripe_session_id     VARCHAR(255) UNIQUE,           -- current Checkout session (created when the client asks)
    checkout_url          VARCHAR(1000),
    checkout_expires_at   TIMESTAMPTZ,
    stripe_payment_intent VARCHAR(255),

    receipt_number        VARCHAR(40),
    receipt_url           VARCHAR(1000),
    failure_reason        VARCHAR(500),

    booking_expires_at    TIMESTAMPTZ,                   -- from booking.created (seat hold)
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    completed_at          TIMESTAMPTZ,

    version               BIGINT      NOT NULL DEFAULT 0 -- optimistic locking (@Version)
);

CREATE INDEX idx_payments_user ON payments (user_id, created_at DESC);

CREATE TABLE refunds (
    id               UUID         PRIMARY KEY,
    payment_id       UUID         NOT NULL REFERENCES payments (id),
    stripe_refund_id VARCHAR(255) UNIQUE,
    amount           INTEGER      NOT NULL CHECK (amount >= 0),
    currency         VARCHAR(3)   NOT NULL,
    reason           VARCHAR(40)  NOT NULL,              -- BOOKING_CANCELLED | PAID_AFTER_CANCEL
    created_at       TIMESTAMPTZ  NOT NULL
);

-- A payment is refunded in full at most once.
CREATE UNIQUE INDEX uq_refunds_payment ON refunds (payment_id);

-- Stripe event ids we have already handled. Stripe retries webhooks, so the same event can arrive more than once.
CREATE TABLE stripe_webhook_events (
    event_id    VARCHAR(255) PRIMARY KEY,
    event_type  VARCHAR(100) NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL
);

-- Transactional outbox: events are written in the same transaction as the payment change
-- and published to RabbitMQ by OutboxRelay.
CREATE TABLE outbox_events (
    id           UUID         PRIMARY KEY,              -- = envelope eventId
    aggregate_id UUID         NOT NULL,                 -- bookingId (= correlationId)
    event_type   VARCHAR(64)  NOT NULL,                 -- = routing key
    payload      TEXT         NOT NULL,                 -- the full envelope as JSON
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ,
    attempts     INTEGER      NOT NULL DEFAULT 0,
    last_error   VARCHAR(1000)
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

-- Idempotent consumers: eventIds of booking.* events we have already handled.
CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);
