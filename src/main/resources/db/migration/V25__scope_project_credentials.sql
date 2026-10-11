ALTER TABLE project_credential
    ADD COLUMN allowed_origins JSONB NOT NULL DEFAULT '[]'::jsonb;
