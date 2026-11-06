CREATE TABLE IF NOT EXISTS query_logs (
    id              BIGSERIAL PRIMARY KEY,
    request_id      VARCHAR(36) NOT NULL,
    query           TEXT NOT NULL,
    generated_sql   TEXT,
    status          VARCHAR(16) NOT NULL DEFAULT 'running',
    error_message   TEXT,
    result_rows     INT,
    duration_ms     INT,
    created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS query_node_logs (
    id              BIGSERIAL PRIMARY KEY,
    query_log_id    BIGINT NOT NULL REFERENCES query_logs(id),
    node_name       VARCHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    duration_ms     INT,
    created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_query_logs_created_at ON query_logs(created_at);
CREATE INDEX idx_query_logs_status ON query_logs(status);
CREATE INDEX idx_node_logs_query_id ON query_node_logs(query_log_id);
