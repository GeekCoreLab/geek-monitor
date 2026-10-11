CREATE INDEX incident_resolved_retention_idx
    ON incident (resolved_at, id)
    WHERE status = 'RESOLVED' AND resolved_at IS NOT NULL;

CREATE INDEX notification_outbox_retention_idx
    ON notification_outbox (created_at, id)
    WHERE status IN ('DELIVERED', 'FAILED', 'CANCELLED');

CREATE INDEX audit_log_retention_idx
    ON audit_log (created_at, id);
