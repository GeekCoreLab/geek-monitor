CREATE TABLE notification_channel (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id),
    name VARCHAR(120) NOT NULL,
    channel_type VARCHAR(16) NOT NULL CHECK (channel_type IN ('EMAIL', 'WEBHOOK')),
    config_ciphertext TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version INTEGER NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, name)
);
CREATE INDEX notification_channel_project_idx
    ON notification_channel (project_id, created_at DESC) WHERE deleted_at IS NULL;

CREATE TABLE notification_outbox (
    id UUID PRIMARY KEY,
    channel_id UUID NOT NULL REFERENCES notification_channel(id),
    incident_id UUID REFERENCES incident(id) ON DELETE SET NULL,
    incident_event_id UUID REFERENCES incident_event(id) ON DELETE SET NULL,
    requested_by UUID REFERENCES app_user(id),
    event_type VARCHAR(24) NOT NULL CHECK (event_type IN ('OPENED', 'RESOLVED', 'TEST')),
    idempotency_key VARCHAR(200) NOT NULL UNIQUE,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'DELIVERED', 'FAILED', 'CANCELLED')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    last_http_status INTEGER,
    last_error_code VARCHAR(48),
    last_error_summary VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX notification_outbox_due_idx
    ON notification_outbox (next_attempt_at, created_at) WHERE status = 'PENDING';
CREATE INDEX notification_outbox_channel_idx
    ON notification_outbox (channel_id, created_at DESC);

CREATE TABLE notification_delivery (
    id UUID PRIMARY KEY,
    outbox_id UUID NOT NULL REFERENCES notification_outbox(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DELIVERED', 'RETRYING', 'FAILED', 'CANCELLED')),
    http_status INTEGER,
    error_code VARCHAR(48),
    error_summary VARCHAR(500),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (outbox_id, attempt_number)
);
CREATE INDEX notification_delivery_outbox_time_idx
    ON notification_delivery (outbox_id, attempt_number DESC);
