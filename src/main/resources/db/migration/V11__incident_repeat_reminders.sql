ALTER TABLE monitor
    ADD COLUMN reminder_interval_minutes INTEGER NOT NULL DEFAULT 30
        CHECK (reminder_interval_minutes BETWEEN 0 AND 1440);

ALTER TABLE incident
    ADD COLUMN last_notified_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE incident_event DROP CONSTRAINT incident_event_event_type_check;
ALTER TABLE incident_event ADD CONSTRAINT incident_event_event_type_check
    CHECK (event_type IN ('OPENED', 'ACKNOWLEDGED', 'RESOLVED', 'ESCALATED', 'REMINDER', 'NOTE'));

ALTER TABLE notification_outbox DROP CONSTRAINT notification_outbox_event_type_check;
ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_event_type_check
    CHECK (event_type IN ('OPENED', 'ESCALATED', 'REMINDER', 'RESOLVED', 'TEST'));

CREATE INDEX incident_reminder_due_idx
    ON incident (last_notified_at) WHERE status IN ('OPEN', 'ACKNOWLEDGED');
