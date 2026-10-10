ALTER TABLE incident
    ADD COLUMN category VARCHAR(24) NOT NULL DEFAULT 'AVAILABILITY'
        CHECK (category IN ('AVAILABILITY', 'TLS_CERTIFICATE'));

DROP INDEX incident_one_active_per_monitor_idx;
CREATE UNIQUE INDEX incident_one_active_per_monitor_category_idx
    ON incident (monitor_id, category) WHERE status IN ('OPEN', 'ACKNOWLEDGED');

ALTER TABLE incident_event DROP CONSTRAINT incident_event_event_type_check;
ALTER TABLE incident_event ADD CONSTRAINT incident_event_event_type_check
    CHECK (event_type IN ('OPENED', 'ACKNOWLEDGED', 'RESOLVED', 'ESCALATED', 'NOTE'));

ALTER TABLE notification_outbox DROP CONSTRAINT notification_outbox_event_type_check;
ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_event_type_check
    CHECK (event_type IN ('OPENED', 'ESCALATED', 'RESOLVED', 'TEST'));

CREATE TABLE tls_certificate_alert_state (
    monitor_id UUID PRIMARY KEY REFERENCES monitor(id) ON DELETE CASCADE,
    incident_id UUID NOT NULL UNIQUE REFERENCES incident(id) ON DELETE CASCADE,
    certificate_expires_at TIMESTAMPTZ NOT NULL,
    threshold_days INTEGER NOT NULL CHECK (threshold_days IN (7, 14, 30)),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
