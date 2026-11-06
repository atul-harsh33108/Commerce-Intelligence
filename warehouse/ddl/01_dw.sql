-- The analytics warehouse: a star schema, in MySQL 8.
--
-- ============================================================================================
-- WHY THIS IS SHAPED THE OPPOSITE WAY TO THE OLTP DATABASES
-- ============================================================================================
--
-- The service databases are normalised. `orders` does not repeat the customer's city on every row,
-- because if it did, correcting one customer's city would mean updating every order they ever placed
-- - and any row the update missed would disagree with the others. That is an update anomaly, and
-- avoiding it is what normalisation is for.
--
-- This schema deliberately does the opposite. `dim_customer` repeats the city on every customer row,
-- `fact_order_items` carries a foreign key to it, and nothing here is in third normal form. Three
-- reasons, in the order they matter:
--
--   1. THE WORKLOAD IS DIFFERENT. OLTP writes one row and reads a handful by primary key. OLAP reads
--      millions of rows and writes almost none. "Revenue by category by month" touches the whole
--      fact table; a normalised schema would make that six joins instead of two.
--
--   2. UPDATE ANOMALIES ARE NOT A RISK HERE, because nothing updates in place. The warehouse is
--      loaded from a change stream; a customer moving city produces a new version of a dimension
--      row, not an edit scattered across a million facts.
--
--   3. THE QUERIES ARE WRITTEN BY AN LLM (Step 9). A star schema is the shape a text-to-SQL model
--      handles best - one fact table, dimensions one join away, no bridge tables and no
--      snowflaking - and that is a real engineering consideration rather than a concession.
--
-- Same data, opposite trade-off. Being able to say which one applies where is the point.
--
-- ============================================================================================
-- GRAIN
-- ============================================================================================
--
-- One row in `fact_order_items` = one line of one order. Declaring the grain first is the single
-- most important step in dimensional modelling: every measure in the table must be true at that
-- grain, and every dimension must apply to it. Putting an order-level total on a line-level fact is
-- how a warehouse starts double-counting revenue on multi-line orders.

CREATE DATABASE IF NOT EXISTS dw DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE dw;

-- --------------------------------------------------------------------------------------------
-- dim_date
--
-- A ROW PER DAY, PRE-GENERATED. The obvious alternative is to call DATE_FORMAT() on the fact's
-- timestamp in every query, and it is worse in three ways: the function call defeats any index on
-- the date column, "which quarter is this?" gets re-implemented in every query that needs it, and
-- fiscal calendars, holidays and week-numbering conventions have nowhere to live.
--
-- date_id is yyyyMMdd as an INT rather than a surrogate sequence. It is the one place a "smart key"
-- is standard practice in dimensional modelling, because it makes fact-table partitioning by date
-- range trivial and a human can read a fact row without a join.
-- --------------------------------------------------------------------------------------------
CREATE TABLE dim_date
(
    date_id      INT         NOT NULL COMMENT 'yyyyMMdd',
    full_date    DATE        NOT NULL,
    year         SMALLINT    NOT NULL,
    quarter      TINYINT     NOT NULL,
    month        TINYINT     NOT NULL,
    month_name   VARCHAR(12) NOT NULL,
    day          TINYINT     NOT NULL,
    day_of_week  TINYINT     NOT NULL COMMENT '1=Monday .. 7=Sunday',
    day_name     VARCHAR(12) NOT NULL,
    week_of_year TINYINT     NOT NULL,
    is_weekend   TINYINT(1)  NOT NULL,
    -- `month_key`, NOT `year_month`. YEAR_MONTH is a reserved interval unit in MySQL 8, so the
    -- obvious name is a syntax error at CREATE TABLE - with the parser pointing at the line AFTER
    -- it, which is how it cost a container restart to find.
    month_key    VARCHAR(7)  NOT NULL COMMENT 'yyyy-MM, so grouping by month needs no DATE_FORMAT',
    PRIMARY KEY (date_id),
    KEY idx_dim_date_full (full_date),
    KEY idx_dim_date_month (month_key)
) COMMENT 'Calendar dimension: one row per day.';


-- --------------------------------------------------------------------------------------------
-- dim_product
--
-- A SURROGATE KEY (product_id) plus the source's natural key (product_key). The surrogate exists so
-- the fact table stores a 4-byte int rather than a 32-character hash 118,000 times, and so that a
-- future slowly-changing-dimension version can hold two rows for one product_key.
--
-- This is a TYPE 1 dimension today: a change overwrites. That means historical facts report the
-- product's CURRENT category, not the category it had when it sold - which is wrong for
-- "how did the electronics category perform in 2017" if products have been recategorised since.
-- Type 2 (a new row per version, with valid_from/valid_to) is the fix, and it is not built here.
-- Stated rather than left to be discovered.
-- --------------------------------------------------------------------------------------------
CREATE TABLE dim_product
(
    product_id       INT AUTO_INCREMENT,
    product_key      VARCHAR(64)    NOT NULL COMMENT 'Olist product_id: the natural key',
    product_name     VARCHAR(200),
    category         VARCHAR(100)   COMMENT 'English category name',
    category_pt      VARCHAR(100)   COMMENT 'Original Portuguese category',
    unit_price       DECIMAL(12, 2) COMMENT 'Current catalogue price; the fact carries the sold price',
    weight_g         INT,
    photos_qty       INT,
    is_active        TINYINT(1)     NOT NULL DEFAULT 1,
    updated_at       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (product_id),
    UNIQUE KEY uk_dim_product_key (product_key),
    KEY idx_dim_product_category (category)
) COMMENT 'Product dimension. Type 1: changes overwrite.';


-- --------------------------------------------------------------------------------------------
-- dim_customer
--
-- BOTH Olist customer identifiers are carried, and the distinction is not pedantry:
--   customer_key         unique per ORDER   - 99,441 of them
--   customer_unique_key  unique per PERSON  - ~96,096 of them
-- Counting distinct customers on the wrong one inflates the answer by 3% and makes every
-- repeat-purchase metric exactly zero.
-- --------------------------------------------------------------------------------------------
CREATE TABLE dim_customer
(
    customer_id         INT AUTO_INCREMENT,
    customer_key        VARCHAR(64) NOT NULL COMMENT 'Olist customer_id: one per order',
    customer_unique_key VARCHAR(64) COMMENT 'Olist customer_unique_id: one per person',
    city                VARCHAR(100),
    state               VARCHAR(4)  COMMENT 'Brazilian federative unit, e.g. SP',
    zip_prefix          VARCHAR(12),
    updated_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (customer_id),
    UNIQUE KEY uk_dim_customer_key (customer_key),
    KEY idx_dim_customer_person (customer_unique_key),
    KEY idx_dim_customer_state (state)
) COMMENT 'Customer dimension. Type 1.';


-- --------------------------------------------------------------------------------------------
-- dim_geography
--
-- Split out of dim_customer rather than left as columns on it, because geography is a question
-- people ask INDEPENDENTLY of customers ("revenue by region") and because the state -> region
-- mapping is reference data that belongs in one place. The cost is one more join; the benefit is
-- that "which region is MG in" has a single answer.
-- --------------------------------------------------------------------------------------------
CREATE TABLE dim_geography
(
    geography_id INT AUTO_INCREMENT,
    state_code   VARCHAR(4)   NOT NULL,
    state_name   VARCHAR(60)  NOT NULL,
    region       VARCHAR(20)  NOT NULL COMMENT 'Southeast, South, Northeast, North, Central-West',
    PRIMARY KEY (geography_id),
    UNIQUE KEY uk_dim_geography_state (state_code)
) COMMENT 'Brazilian states and their regions.';


-- --------------------------------------------------------------------------------------------
-- fact_order_items
--
-- Grain: one order line. Measures are additive at that grain, which is what makes SUM() correct
-- without qualification.
--
-- ORDER STATUS IS A DEGENERATE DIMENSION - a dimension attribute stored on the fact rather than in
-- its own table, because a lookup table of eleven strings would be a join that buys nothing.
--
-- delivery_days and is_late are PRE-COMPUTED. They could be derived in each query from the
-- timestamps, and are not, for the same reason dim_date exists: a derivation repeated in fifty
-- queries is fifty chances to define "late" differently, and the LLM in Step 9 will define it
-- differently every time it is asked.
-- --------------------------------------------------------------------------------------------
CREATE TABLE fact_order_items
(
    order_item_sk    BIGINT AUTO_INCREMENT,

    -- The natural key of the grain, kept so the sink can upsert idempotently and so a fact row can
    -- be traced back to the OLTP row it came from.
    order_key        VARCHAR(64)    NOT NULL,
    line_no          INT            NOT NULL,

    -- Dimension foreign keys.
    date_id          INT            NOT NULL,
    product_id       INT,
    customer_id      INT,
    geography_id     INT,

    -- Degenerate dimensions: attributes of the fact itself.
    order_status     VARCHAR(24)    NOT NULL,
    seller_key       VARCHAR(64),

    -- Measures. Additive at this grain.
    quantity         INT            NOT NULL DEFAULT 1,
    unit_price       DECIMAL(12, 2) NOT NULL,
    freight_amount   DECIMAL(12, 2) NOT NULL DEFAULT 0,
    line_revenue     DECIMAL(12, 2) NOT NULL COMMENT 'quantity * unit_price',
    line_total       DECIMAL(12, 2) NOT NULL COMMENT 'line_revenue + freight_amount',

    -- Pre-computed delivery facts.
    delivery_days    INT            COMMENT 'purchase -> customer delivery, NULL if undelivered',
    estimated_days   INT            COMMENT 'purchase -> estimated delivery',
    is_late          TINYINT(1)     COMMENT '1 when delivered after the estimate, NULL if undelivered',

    purchased_at     DATETIME,
    ingested_at      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP
                                    COMMENT 'When the CDC sink wrote this row - used to measure lag',

    PRIMARY KEY (order_item_sk),

    -- THE CONSTRAINT THE SINK DEPENDS ON. Every write is an upsert on (order_key, line_no), so a
    -- redelivered change event updates the row rather than creating a second one. Kafka is
    -- at-least-once; without this, a consumer restart duplicates revenue.
    UNIQUE KEY uk_fact_order_line (order_key, line_no),

    KEY idx_fact_date (date_id),
    KEY idx_fact_product (product_id),
    KEY idx_fact_customer (customer_id),
    KEY idx_fact_geography (geography_id),
    KEY idx_fact_status (order_status),
    -- Covering the most common query shape: filter by date range, group by product.
    KEY idx_fact_date_product (date_id, product_id)

    -- NO FOREIGN KEY CONSTRAINTS, and that is deliberate rather than lazy.
    --
    -- A fact row can legitimately arrive before its dimension row: the CDC streams are independent
    -- topics with independent consumer lag, so an order for a product created two seconds ago can
    -- land first. Enforcing referential integrity would make the sink fail and retry until the
    -- dimension caught up, converting a millisecond of skew into a stalled partition.
    --
    -- Warehouses handle this with a "late arriving dimension" pattern: insert a stub dimension row
    -- and let it be filled in later. The sink does exactly that (see cdc_sink.py), and the integrity
    -- guarantee lives in the sink rather than in the schema. The trade is that a bug in the sink can
    -- produce an orphan; the check query for that is in warehouse/README.md.
) COMMENT 'Fact table. Grain: one order line.';
