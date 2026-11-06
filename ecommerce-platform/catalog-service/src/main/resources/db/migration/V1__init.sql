-- catalog-service, initial schema.
--
-- Written to hold the Olist product dataset without loss: every column the source has is either
-- stored or deliberately dropped with a reason, and the natural key is preserved so a re-load is
-- idempotent rather than duplicating.

CREATE TABLE products
(
    -- BIGSERIAL, not `GENERATED ALWAYS AS IDENTITY`. Both work with GenerationType.IDENTITY; the
    -- difference is that ALWAYS forbids an explicit value, and the Olist loader inserts through
    -- plain SQL where letting the sequence assign is simpler than overriding it.
    id          BIGSERIAL      PRIMARY KEY,
    product_key VARCHAR(64)    NOT NULL,
    name        VARCHAR(200)   NOT NULL,
    category_key VARCHAR(100),
    category    VARCHAR(100),
    price       NUMERIC(12, 2),
    weight_g    INTEGER,
    length_cm   INTEGER,
    height_cm   INTEGER,
    width_cm    INTEGER,
    photos_qty  INTEGER,
    active      BOOLEAN        NOT NULL DEFAULT TRUE,
    version     BIGINT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- THE CONSTRAINT THE LOADER DEPENDS ON.
--
-- `ON CONFLICT (product_key) DO NOTHING` needs a unique index to conflict against, and without it
-- the loader silently inserts a second copy of every row on a re-run instead of failing. A
-- correctness guarantee that lives in the schema rather than in the script that happens to use it.
ALTER TABLE products ADD CONSTRAINT uk_products_product_key UNIQUE (product_key);

-- Category is the only genuinely selective predicate this table has: Olist products have no names,
-- so "show me health_beauty" is the query the browse page actually issues. Partial on `active`
-- because the browse path never wants the ~600 products that never sold and have no price.
CREATE INDEX idx_products_category_active ON products (category) WHERE active;

-- Supports ORDER BY price with a category filter, which is the second-most-common browse query.
CREATE INDEX idx_products_category_price ON products (category, price);
