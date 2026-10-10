CREATE TABLE installation_state (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    initialized BOOLEAN NOT NULL DEFAULT FALSE,
    initialized_at TIMESTAMPTZ
);

INSERT INTO installation_state (id, initialized) VALUES (1, FALSE);

CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role VARCHAR(32) NOT NULL CHECK (role IN ('ADMIN', 'OPERATOR', 'READ_ONLY')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    disabled_at TIMESTAMPTZ
);

CREATE TABLE auth_session (
    token_hash CHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX auth_session_user_expiry_idx ON auth_session (user_id, expires_at);

CREATE TABLE project (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE project_membership (
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL CHECK (role IN ('ADMIN', 'OPERATOR', 'READ_ONLY')),
    PRIMARY KEY (project_id, user_id)
);

CREATE TABLE monitored_resource (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id),
    name VARCHAR(160) NOT NULL,
    type VARCHAR(32) NOT NULL CHECK (type IN ('HOST', 'WEBSITE', 'DATABASE', 'SERVICE', 'HEARTBEAT')),
    environment VARCHAR(80) NOT NULL DEFAULT 'production',
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    owner_email VARCHAR(320),
    health_status VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (health_status IN ('PENDING', 'UP', 'DEGRADED', 'DOWN', 'UNKNOWN')),
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX monitored_resource_project_idx ON monitored_resource (project_id, created_at DESC) WHERE deleted_at IS NULL;

CREATE TABLE monitor (
    id UUID PRIMARY KEY,
    resource_id UUID NOT NULL REFERENCES monitored_resource(id),
    name VARCHAR(160) NOT NULL,
    type VARCHAR(24) NOT NULL CHECK (type IN ('HTTP', 'TCP', 'DNS', 'TLS', 'POSTGRESQL', 'MYSQL', 'REDIS', 'HEARTBEAT')),
    config JSONB NOT NULL,
    interval_seconds INTEGER NOT NULL CHECK (interval_seconds >= 10),
    timeout_seconds INTEGER NOT NULL CHECK (timeout_seconds > 0),
    failure_threshold INTEGER NOT NULL DEFAULT 3 CHECK (failure_threshold > 0),
    recovery_threshold INTEGER NOT NULL DEFAULT 2 CHECK (recovery_threshold > 0),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version INTEGER NOT NULL DEFAULT 1,
    next_run_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX monitor_due_idx ON monitor (next_run_at) WHERE enabled;

CREATE TABLE check_execution (
    id UUID PRIMARY KEY,
    monitor_id UUID NOT NULL REFERENCES monitor(id),
    scheduled_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    result_status VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
    error_code VARCHAR(48),
    error_summary VARCHAR(500),
    duration_ms BIGINT,
    http_status INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX check_execution_monitor_time_idx ON check_execution (monitor_id, scheduled_at DESC);

CREATE TABLE incident (
    id UUID PRIMARY KEY,
    deduplication_key VARCHAR(500) NOT NULL UNIQUE,
    monitor_id UUID NOT NULL REFERENCES monitor(id),
    resource_id UUID NOT NULL REFERENCES monitored_resource(id),
    severity VARCHAR(16) NOT NULL DEFAULT 'WARNING',
    status VARCHAR(16) NOT NULL CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    title VARCHAR(240) NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    acknowledged_by UUID REFERENCES app_user(id),
    acknowledged_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    occurrence_count INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX incident_status_opened_idx ON incident (status, opened_at DESC);

CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    actor_user_id UUID REFERENCES app_user(id),
    action VARCHAR(120) NOT NULL,
    subject_type VARCHAR(64) NOT NULL,
    subject_id VARCHAR(120),
    summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
