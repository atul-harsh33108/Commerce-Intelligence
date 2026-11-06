-- The agent's metadata catalogue, and the credential it reads the warehouse with.
--
-- Two separate concerns in one file because they are created together and are useless apart: the
-- agent needs somewhere to look up what the schema MEANS, and it needs an account that cannot do
-- anything but read.

-- --------------------------------------------------------------------------------------------
-- `meta` - the schema the NL->SQL agent retrieves over.
--
-- These four tables are the agent's contract, and they are the reason Step 9 is configuration rather
-- than a rewrite: the agent is metadata-driven, so pointing it at a new warehouse means populating
-- these, not editing Python.
--
-- WHY THE AGENT NEEDS THIS AT ALL, rather than reading information_schema: column names are not
-- descriptions. `fact_order_items.line_total` tells a model nothing about whether it includes
-- freight, and `dim_customer.customer_key` versus `customer_unique_key` is exactly the distinction a
-- model will get wrong every time without being told. The metadata is what turns a schema into
-- something retrievable by meaning - the aliases in particular, because a user asks about "revenue"
-- and the column is called `line_total`.
-- --------------------------------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS meta DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE meta;

CREATE TABLE IF NOT EXISTS table_info
(
    id          VARCHAR(64) PRIMARY KEY COMMENT 'Stable id, used as the Qdrant point id',
    name        VARCHAR(128) COMMENT 'Table name as it appears in SQL',
    role        VARCHAR(32) COMMENT 'fact | dim',
    description TEXT COMMENT 'What the table holds, in the words a business user would use'
);

CREATE TABLE IF NOT EXISTS column_info
(
    id          VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(128),
    type        VARCHAR(64),
    role        VARCHAR(32) COMMENT 'primary_key | foreign_key | dimension | measure',
    examples    JSON COMMENT 'Real values, so the model sees the format it must match',
    description TEXT,
    -- THE FIELD THAT DOES THE WORK. A user asks for "revenue"; the column is `line_total`. Aliases
    -- are what the embedding actually retrieves on, so a missing alias is a question the agent
    -- cannot answer no matter how good the model is.
    alias       JSON,
    table_id    VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS metric_info
(
    id               VARCHAR(64) PRIMARY KEY,
    name             VARCHAR(128),
    description      TEXT,
    -- Which columns a metric is computed from. This is what lets the agent answer "GMV" without the
    -- model having to invent the formula - the definition is data, not a prompt.
    relevant_columns JSON,
    alias            JSON
);

CREATE TABLE IF NOT EXISTS column_metric
(
    column_id VARCHAR(64),
    metric_id VARCHAR(64),
    PRIMARY KEY (column_id, metric_id)
);


-- --------------------------------------------------------------------------------------------
-- warehouse_ro - the agent's credential (D12).
--
-- SELECT on dw.* and NOTHING ELSE. No INSERT, no UPDATE, no DELETE, no CREATE TEMPORARY TABLES, no
-- access to `meta`, and no account on any OLTP database.
--
-- THIS IS THE BOUNDARY, and the other two layers are defence in depth:
--
--   1. the GRANT       a DROP TABLE that gets past everything else fails at the database
--   2. the validator   the agent refuses anything that is not a single SELECT
--   3. the gateway     the only route to the agent, so the request carries a real user's JWT
--
-- Being precise about which is which matters: a prompt-level guardrail is a mitigation, a GRANT is a
-- boundary. An LLM generates a SQL string, and a SQL string from a language model is untrusted input
-- however good the model is.
--
-- `%` rather than a specific host because the agent's container IP is assigned by Docker and changes
-- on every recreate. In a real deployment this is a specific host or a network policy; here the
-- database is not published outside the Compose network.
-- --------------------------------------------------------------------------------------------
CREATE USER IF NOT EXISTS 'warehouse_ro'@'%' IDENTIFIED BY 'warehouse_ro';
GRANT SELECT ON dw.* TO 'warehouse_ro'@'%';

-- The metadata is read by the indexing job, not by generated SQL, so the read-only user does not get
-- it. If the agent could SELECT from `meta` it could be talked into describing its own guardrails.
GRANT SELECT ON meta.* TO 'warehouse'@'%';
GRANT ALL PRIVILEGES ON meta.* TO 'warehouse'@'%';
GRANT ALL PRIVILEGES ON dw.* TO 'warehouse'@'%';

FLUSH PRIVILEGES;
