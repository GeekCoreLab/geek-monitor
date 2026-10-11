CREATE TABLE backup_status (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    filename VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 CHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-fA-F]{64}$'),
    completed_at TIMESTAMPTZ NOT NULL
);
