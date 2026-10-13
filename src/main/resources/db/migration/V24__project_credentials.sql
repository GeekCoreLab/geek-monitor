CREATE TABLE project_credential (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    encrypted_headers JSONB NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    created_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, name)
);

CREATE INDEX project_credential_project_idx
    ON project_credential (project_id, updated_at DESC);

ALTER TABLE monitor
    ADD COLUMN credential_id UUID REFERENCES project_credential(id) ON DELETE RESTRICT;

CREATE INDEX monitor_credential_idx ON monitor (credential_id) WHERE credential_id IS NOT NULL;
