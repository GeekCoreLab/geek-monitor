ALTER TABLE notification_outbox
    ADD COLUMN silence_deferred_until TIMESTAMPTZ;
