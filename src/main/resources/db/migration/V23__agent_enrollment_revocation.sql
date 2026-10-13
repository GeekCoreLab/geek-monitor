ALTER TABLE agent_enrollment ADD COLUMN revoked_at TIMESTAMPTZ;

CREATE INDEX agent_enrollment_pending_idx
    ON agent_enrollment (resource_id, created_at DESC)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;
