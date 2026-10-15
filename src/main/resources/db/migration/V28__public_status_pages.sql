ALTER TABLE monitored_resource ADD COLUMN public_enabled BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX monitored_resource_public_project_idx ON monitored_resource(project_id) WHERE public_enabled AND deleted_at IS NULL;
