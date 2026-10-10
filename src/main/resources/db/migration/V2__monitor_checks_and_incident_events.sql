ALTER TABLE monitor
    ADD COLUMN critical BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN consecutive_successes INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN current_state VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (current_state IN ('PENDING', 'UP', 'DOWN', 'UNKNOWN')),
    ADD COLUMN last_result_at TIMESTAMPTZ,
    ADD COLUMN deleted_at TIMESTAMPTZ;

DROP INDEX monitor_due_idx;
CREATE INDEX monitor_due_idx ON monitor (next_run_at) WHERE enabled AND deleted_at IS NULL;

ALTER TABLE incident ADD COLUMN acknowledgement_note VARCHAR(2000);

CREATE TABLE incident_event (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incident(id) ON DELETE CASCADE,
    actor_user_id UUID REFERENCES app_user(id),
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('OPENED', 'ACKNOWLEDGED', 'RESOLVED', 'NOTE')),
    note VARCHAR(2000),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX incident_event_history_idx ON incident_event (incident_id, created_at);

CREATE UNIQUE INDEX incident_one_active_per_monitor_idx
    ON incident (monitor_id) WHERE status IN ('OPEN', 'ACKNOWLEDGED');
