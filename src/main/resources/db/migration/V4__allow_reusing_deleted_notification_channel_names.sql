ALTER TABLE notification_channel
    DROP CONSTRAINT notification_channel_project_id_name_key;

CREATE UNIQUE INDEX notification_channel_project_name_uq
    ON notification_channel (project_id, name) WHERE deleted_at IS NULL;
