CREATE TABLE agent_enrollment (
    id UUID PRIMARY KEY,
    resource_id UUID NOT NULL REFERENCES monitored_resource(id),
    project_id UUID NOT NULL REFERENCES project(id),
    token_hash CHAR(64) NOT NULL UNIQUE,
    issued_by UUID NOT NULL REFERENCES app_user(id),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (expires_at > created_at)
);
CREATE INDEX agent_enrollment_resource_expiry_idx ON agent_enrollment (resource_id, expires_at DESC);

CREATE TABLE agent (
    id UUID PRIMARY KEY,
    resource_id UUID NOT NULL REFERENCES monitored_resource(id),
    credential_hash CHAR(64) NOT NULL UNIQUE,
    hostname VARCHAR(255) NOT NULL,
    operating_system VARCHAR(120) NOT NULL,
    agent_version VARCHAR(64) NOT NULL,
    config_version INTEGER NOT NULL DEFAULT 1 CHECK (config_version > 0),
    last_seen_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX agent_one_active_per_resource_idx ON agent (resource_id) WHERE revoked_at IS NULL;
CREATE INDEX agent_resource_idx ON agent (resource_id, created_at DESC);

CREATE TABLE agent_batch (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agent(id),
    boot_id UUID NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    sampled_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    config_version INTEGER NOT NULL CHECK (config_version > 0),
    clock_offset_ms BIGINT NOT NULL,
    trusted BOOLEAN NOT NULL,
    accepted_count INTEGER NOT NULL CHECK (accepted_count >= 0),
    UNIQUE (agent_id, boot_id, sequence)
);
CREATE INDEX agent_batch_agent_time_idx ON agent_batch (agent_id, sampled_at DESC);

CREATE TABLE metric_sample (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES agent_batch(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL REFERENCES agent(id),
    resource_id UUID NOT NULL REFERENCES monitored_resource(id),
    metric_name VARCHAR(80) NOT NULL,
    labels JSONB NOT NULL DEFAULT '{}'::jsonb,
    value DOUBLE PRECISION NOT NULL CHECK (value > '-Infinity'::double precision AND value < 'Infinity'::double precision),
    sampled_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    config_version INTEGER NOT NULL CHECK (config_version > 0),
    trusted BOOLEAN NOT NULL,
    UNIQUE (batch_id, metric_name, labels)
);
CREATE INDEX metric_sample_resource_series_time_idx ON metric_sample (resource_id, metric_name, sampled_at DESC);
CREATE INDEX metric_sample_sampled_at_idx ON metric_sample (sampled_at);
