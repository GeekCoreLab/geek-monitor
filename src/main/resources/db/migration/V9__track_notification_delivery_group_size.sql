ALTER TABLE notification_delivery
    ADD COLUMN group_count INTEGER NOT NULL DEFAULT 1
        CHECK (group_count BETWEEN 1 AND 50);
