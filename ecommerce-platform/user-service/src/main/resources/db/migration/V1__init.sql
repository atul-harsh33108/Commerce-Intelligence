-- user-service, initial schema.

CREATE TABLE users
(
    id                  BIGSERIAL PRIMARY KEY,
    username            VARCHAR(100) NOT NULL,
    email               VARCHAR(200) NOT NULL,
    -- 100 rather than 60. A bcrypt digest is exactly 60 characters, and sizing the column to 60 is
    -- the change that breaks the day somebody switches to argon2id (97+ characters) - a migration
    -- that has to run before the algorithm change, on a table nobody wants to lock.
    password            VARCHAR(100) NOT NULL,
    role                VARCHAR(20)  NOT NULL DEFAULT 'USER',
    customer_key        VARCHAR(64),
    customer_unique_key VARCHAR(64),
    city                VARCHAR(100),
    state               VARCHAR(4),
    zip_prefix          VARCHAR(12),
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE users ADD CONSTRAINT uk_users_username UNIQUE (username);
ALTER TABLE users ADD CONSTRAINT uk_users_email UNIQUE (email);

-- NULLABLE and UNIQUE together, which is exactly what is wanted: Postgres treats NULLs as distinct,
-- so every registered user (customer_key IS NULL) coexists, while the Olist loader gets the
-- conflict target its ON CONFLICT DO NOTHING needs.
ALTER TABLE users ADD CONSTRAINT uk_users_customer_key UNIQUE (customer_key);

-- Login is by username, and it is the only query on the hot path here.
CREATE INDEX idx_users_username ON users (username);

-- The warehouse's dim_customer is built from these two. Indexed because the CDC snapshot phase and
-- any re-derivation scan by them.
CREATE INDEX idx_users_customer_unique_key ON users (customer_unique_key);
CREATE INDEX idx_users_state ON users (state);

-- A role that is not in the enum would fail Hibernate's mapping at read time, far from the write
-- that caused it. The database is the right place to refuse it.
ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'));
