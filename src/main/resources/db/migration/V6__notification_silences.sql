CREATE TABLE notification_silence (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id),
    resource_id UUID REFERENCES monitored_resource(id),
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    canceled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at)
);

CREATE INDEX notification_silence_project_time_idx
    ON notification_silence (project_id, starts_at, ends_at)
    WHERE canceled_at IS NULL;
CREATE INDEX notification_silence_resource_time_idx
    ON notification_silence (resource_id, starts_at, ends_at)
    WHERE canceled_at IS NULL AND resource_id IS NOT NULL;
