ALTER TABLE notification_channel
    ADD COLUMN rate_limit_per_minute INTEGER NOT NULL DEFAULT 60
        CHECK (rate_limit_per_minute BETWEEN 1 AND 600),
    ADD COLUMN next_delivery_at TIMESTAMPTZ;
