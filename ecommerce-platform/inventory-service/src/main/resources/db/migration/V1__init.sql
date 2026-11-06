-- inventory-service, initial schema.
--
-- Three tables, and each exists for a reason the others cannot serve:
--   stock_items       the counter. One row per product, contended.
--   reservations      the saga's memory. What was taken, for which order, and whether it came back.
--   processed_events  the consumer-side idempotency guard (D10).

CREATE TABLE stock_items
(
    id                BIGSERIAL PRIMARY KEY,
    product_key       VARCHAR(64) NOT NULL,
    quantity_on_hand  INTEGER     NOT NULL,
    -- RESERVED IS TRACKED SEPARATELY FROM ON-HAND, and this is the modelling decision that matters.
    --
    -- The naive version decrements on_hand when stock is reserved. Then a cancelled order has to
    -- increment it back, and any failure between the two makes the number wrong permanently with no
    -- way to detect it. Keeping the reservation as its own quantity means available = on_hand -
    -- reserved is DERIVED, the reservations table is the audit trail, and a lost release shows up as
    -- a reservation row that is too old rather than as a stock level nobody can explain.
    quantity_reserved INTEGER     NOT NULL DEFAULT 0,
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE stock_items ADD CONSTRAINT uk_stock_items_product_key UNIQUE (product_key);

-- THE DATABASE IS THE FINAL WORD ON OVERSELLING.
--
-- Optimistic locking (the `version` column) makes concurrent updates fail loudly, and application
-- code checks availability before reserving. Both can be bypassed - by a bad migration, by a manual
-- UPDATE, by a bug in a code path added next year. These two constraints cannot: the transaction
-- that would take stock below zero, or reserve more than exists, is rejected by Postgres.
--
-- Defence in depth with a clear hierarchy: the application check produces a good error message, the
-- optimistic lock produces a retry, and the constraint produces a guarantee.
ALTER TABLE stock_items ADD CONSTRAINT ck_stock_non_negative CHECK (quantity_on_hand >= 0);
ALTER TABLE stock_items ADD CONSTRAINT ck_reserved_within_stock
    CHECK (quantity_reserved >= 0 AND quantity_reserved <= quantity_on_hand);


CREATE TABLE reservations
(
    id          BIGSERIAL PRIMARY KEY,
    order_key   VARCHAR(64) NOT NULL,
    product_key VARCHAR(64) NOT NULL,
    quantity    INTEGER     NOT NULL,
    -- RESERVED -> CONFIRMED (payment succeeded, stock is consumed)
    --          -> RELEASED  (compensation: the saga rolled back)
    status      VARCHAR(20) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One reservation per (order, product). The natural key again: it makes a redelivered OrderPlaced
-- for the same order a no-op at the database level rather than a second reservation.
ALTER TABLE reservations ADD CONSTRAINT uk_reservations_order_product UNIQUE (order_key, product_key);
CREATE INDEX idx_reservations_order_key ON reservations (order_key);
-- Supports the sweeper that looks for reservations stuck in RESERVED past their deadline.
CREATE INDEX idx_reservations_status_created ON reservations (status, created_at);
ALTER TABLE reservations ADD CONSTRAINT ck_reservations_status
    CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED'));


-- The consumer-side idempotency guard, and it is a TABLE rather than an in-memory set on purpose.
--
-- Kafka delivers at least once: a consumer that processes a record and dies before committing its
-- offset sees the record again. An in-memory guard is lost on restart while the effect it guards -
-- stock taken out of a warehouse - is not, so exactly the failure that causes redelivery is the one
-- that erases the defence against it.
--
-- The rule this is an instance of: AN IDEMPOTENCY GUARD MUST BE AT LEAST AS DURABLE AS THE EFFECT IT
-- GUARDS. Written in the same transaction as the reservation, so either both happen or neither does.
CREATE TABLE processed_events
(
    event_id     VARCHAR(100) PRIMARY KEY,
    event_type   VARCHAR(80) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Retention: this table grows forever otherwise. Not enforced here - a partitioned table or a
-- scheduled DELETE is the production answer, and both are larger than this project needs. The index
-- is what a retention job would use.
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
