-- order-service, initial schema.
--
-- This is the largest table on the platform (~100K orders, ~113K items) and the one the CDC pipeline
-- in Step 8 captures, so its shape is chosen with both the transactional and the analytical reader in
-- mind - which mostly means: keep the natural keys, and index what the warehouse's incremental
-- extraction will scan.

CREATE TABLE orders
(
    id                    BIGSERIAL PRIMARY KEY,

    -- The Olist order_id for historical rows; a generated UUID for orders placed here. One column,
    -- because every downstream consumer (payment, inventory, the warehouse) refers to an order by
    -- this value and must not have to know which population it came from.
    order_key             VARCHAR(64)  NOT NULL,

    -- The Olist customer_id. NOT a foreign key: user-service owns users, in a different database
    -- (D5 in the capstone, database-per-service here). A cross-service reference is a plain id, and
    -- referential integrity across it is the application's job, not Postgres's.
    customer_key          VARCHAR(64),

    -- The authenticated user's id from the `uid` claim, for orders placed through the API. Null for
    -- the historical population, which predates any account on this platform.
    user_id               BIGINT,

    status                VARCHAR(24)  NOT NULL,

    -- Set when a saga compensates, so a CANCELED row can say WHY. An Olist cancellation has none.
    failure_reason        VARCHAR(255),

    total_amount          NUMERIC(12,2) NOT NULL DEFAULT 0,

    -- Olist's fulfilment timestamps. Kept even though this platform ships nothing: they are what
    -- makes "how many orders arrived after the estimate" answerable, which is one of the questions
    -- the NL->SQL agent is demonstrated on.
    purchased_at          TIMESTAMPTZ,
    approved_at           TIMESTAMPTZ,
    delivered_carrier_at  TIMESTAMPTZ,
    delivered_customer_at TIMESTAMPTZ,
    estimated_delivery_at TIMESTAMPTZ,

    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE orders ADD CONSTRAINT uk_orders_order_key UNIQUE (order_key);

-- "My orders", the only query a customer makes.
CREATE INDEX idx_orders_customer_key ON orders (customer_key);
CREATE INDEX idx_orders_user_id ON orders (user_id) WHERE user_id IS NOT NULL;

-- PARTIAL, and this is the index that matters operationally. The saga recovery job asks "which
-- orders have been in flight too long?" every 30 seconds, against a table where 99.99% of rows are
-- in a terminal state. A full index on `status` would be 100K entries to answer a question about
-- three of them; this one holds only the in-flight rows and stays tiny forever.
CREATE INDEX idx_orders_in_flight ON orders (status, created_at)
    WHERE status IN ('PENDING', 'AWAITING_PAYMENT');

-- The warehouse's fact table is grained by order date. Step 8's incremental extraction scans this.
CREATE INDEX idx_orders_purchased_at ON orders (purchased_at);


CREATE TABLE order_items
(
    id                BIGSERIAL PRIMARY KEY,

    -- A REAL foreign key, because both sides are owned by this service. The contrast with
    -- customer_key above is the whole point: inside a service boundary, use the database's integrity
    -- guarantees; across one, do not pretend you can.
    order_id          BIGINT       NOT NULL REFERENCES orders (id) ON DELETE CASCADE,

    -- Olist's order_item_id: 1-based within the order. (order_id, line_no) is the natural key, which
    -- is what makes the loader's ON CONFLICT work.
    line_no           INTEGER      NOT NULL,

    product_key       VARCHAR(64)  NOT NULL,
    seller_key        VARCHAR(64),

    -- Always 1 for historical rows: Olist has no quantity column, and two of the same product on one
    -- order appear as two rows. Orders placed through the API can carry a real quantity.
    quantity          INTEGER      NOT NULL DEFAULT 1,

    unit_price        NUMERIC(12,2) NOT NULL,
    freight_amount    NUMERIC(12,2) NOT NULL DEFAULT 0,
    shipping_limit_at TIMESTAMPTZ
);

ALTER TABLE order_items ADD CONSTRAINT uk_order_items_order_line UNIQUE (order_id, line_no);
CREATE INDEX idx_order_items_product_key ON order_items (product_key);
ALTER TABLE order_items ADD CONSTRAINT ck_order_items_quantity CHECK (quantity > 0);
