-- payment-service, initial schema.

CREATE TABLE payments
(
    id                  BIGSERIAL PRIMARY KEY,

    -- The order this pays for. A plain id, not a foreign key: order-service owns orders, in a
    -- different database.
    order_key           VARCHAR(64)   NOT NULL,

    -- Olist's payment_sequential. An order can have several payment rows (a voucher split across
    -- three, a card plus a voucher), which is why order_key alone is not unique here.
    payment_sequential  INTEGER       NOT NULL DEFAULT 1,

    method              VARCHAR(24)   NOT NULL,
    installments        INTEGER       NOT NULL DEFAULT 1,
    amount              NUMERIC(12,2) NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    failure_reason      VARCHAR(255),

    -- THE MOST IMPORTANT COLUMN IN THIS SCHEMA.
    --
    -- A DERIVED key ("<order_key>:<sequential>"), not a UUID the caller supplies. The difference is
    -- the whole point:
    --
    --   caller-supplied  a client that forgets to send one gets a double charge; a client that
    --                    generates a fresh one per retry gets a double charge. Both are silent.
    --   derived          the key is a function of what is being paid for, so a retry of the same
    --                    request produces the same key by construction and the unique index refuses
    --                    it. There is nothing for a caller to get wrong.
    --
    -- This is what makes at-least-once delivery safe on a money path: a redelivered PaymentRequested
    -- is a duplicate-key violation, which the service treats as "already done" rather than as an
    -- error.
    idempotency_key     VARCHAR(96)   NOT NULL,

    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

ALTER TABLE payments ADD CONSTRAINT uk_payments_idempotency UNIQUE (idempotency_key);
CREATE INDEX idx_payments_order_key ON payments (order_key);
CREATE INDEX idx_payments_status ON payments (status) WHERE status <> 'CAPTURED';

ALTER TABLE payments ADD CONSTRAINT ck_payments_status
    CHECK (status IN ('CAPTURED', 'DECLINED', 'REFUNDED'));
ALTER TABLE payments ADD CONSTRAINT ck_payments_amount CHECK (amount >= 0);
