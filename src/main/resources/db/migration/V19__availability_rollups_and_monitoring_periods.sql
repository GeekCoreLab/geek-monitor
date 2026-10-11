ALTER TABLE check_execution
    ADD COLUMN interval_seconds INTEGER;

UPDATE check_execution execution
SET interval_seconds = monitor.interval_seconds
FROM monitor
WHERE monitor.id = execution.monitor_id;

ALTER TABLE check_execution
    ALTER COLUMN interval_seconds SET NOT NULL,
    ALTER COLUMN interval_seconds DROP DEFAULT,
    ADD CONSTRAINT check_execution_interval_seconds_check CHECK (interval_seconds BETWEEN 10 AND 86400);

CREATE TABLE monitor_active_period (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    monitor_id UUID NOT NULL REFERENCES monitor(id) ON DELETE CASCADE,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ,
    CHECK (ends_at IS NULL OR ends_at > starts_at)
);

CREATE UNIQUE INDEX monitor_active_period_open_idx
    ON monitor_active_period (monitor_id) WHERE ends_at IS NULL;
CREATE INDEX monitor_active_period_time_idx
    ON monitor_active_period (monitor_id, starts_at, ends_at);

INSERT INTO monitor_active_period (monitor_id, starts_at, ends_at)
SELECT id, created_at,
       CASE WHEN enabled AND deleted_at IS NULL THEN NULL ELSE COALESCE(deleted_at, updated_at) END
FROM monitor
WHERE created_at < CASE WHEN enabled AND deleted_at IS NULL THEN now() ELSE COALESCE(deleted_at, updated_at) END;

CREATE FUNCTION track_monitor_active_period() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.enabled AND NEW.deleted_at IS NULL THEN
            INSERT INTO monitor_active_period (monitor_id, starts_at) VALUES (NEW.id, NEW.created_at);
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.enabled IS DISTINCT FROM NEW.enabled OR OLD.deleted_at IS DISTINCT FROM NEW.deleted_at THEN
        UPDATE monitor_active_period
        SET ends_at = GREATEST(starts_at, now())
        WHERE monitor_id = NEW.id AND ends_at IS NULL;

        IF NEW.enabled AND NEW.deleted_at IS NULL THEN
            INSERT INTO monitor_active_period (monitor_id, starts_at) VALUES (NEW.id, now());
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER monitor_active_period_tracking
AFTER INSERT OR UPDATE OF enabled, deleted_at ON monitor
FOR EACH ROW EXECUTE FUNCTION track_monitor_active_period();

CREATE TABLE resource_pause_period (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    resource_id UUID NOT NULL REFERENCES monitored_resource(id) ON DELETE CASCADE,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ,
    CHECK (ends_at IS NULL OR ends_at > starts_at)
);

CREATE UNIQUE INDEX resource_pause_period_open_idx
    ON resource_pause_period (resource_id) WHERE ends_at IS NULL;
CREATE INDEX resource_pause_period_time_idx
    ON resource_pause_period (resource_id, starts_at, ends_at);

INSERT INTO resource_pause_period (resource_id, starts_at)
SELECT id, updated_at FROM monitored_resource WHERE paused;

CREATE FUNCTION track_resource_pause_period() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.paused THEN
            INSERT INTO resource_pause_period (resource_id, starts_at) VALUES (NEW.id, NEW.created_at);
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.paused IS DISTINCT FROM NEW.paused THEN
        IF OLD.paused THEN
            UPDATE resource_pause_period
            SET ends_at = GREATEST(starts_at, now())
            WHERE resource_id = NEW.id AND ends_at IS NULL;
        END IF;
        IF NEW.paused THEN
            INSERT INTO resource_pause_period (resource_id, starts_at) VALUES (NEW.id, now());
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER resource_pause_period_tracking
AFTER INSERT OR UPDATE OF paused ON monitored_resource
FOR EACH ROW EXECUTE FUNCTION track_resource_pause_period();

CREATE TABLE availability_rollup (
    monitor_id UUID NOT NULL REFERENCES monitor(id) ON DELETE CASCADE,
    bucket_at TIMESTAMPTZ NOT NULL,
    scheduled_seconds INTEGER NOT NULL CHECK (scheduled_seconds BETWEEN 0 AND 3600),
    up_seconds INTEGER NOT NULL CHECK (up_seconds >= 0),
    degraded_seconds INTEGER NOT NULL CHECK (degraded_seconds >= 0 AND degraded_seconds <= up_seconds),
    down_seconds INTEGER NOT NULL CHECK (down_seconds >= 0),
    unknown_seconds INTEGER NOT NULL CHECK (unknown_seconds >= 0),
    excluded_seconds INTEGER NOT NULL CHECK (excluded_seconds >= 0),
    PRIMARY KEY (monitor_id, bucket_at),
    CHECK (up_seconds + down_seconds + unknown_seconds + excluded_seconds <= scheduled_seconds)
);

CREATE INDEX availability_rollup_retention_idx ON availability_rollup (bucket_at);

CREATE TABLE availability_rollup_state (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    last_bucket_at TIMESTAMPTZ NOT NULL
);

INSERT INTO availability_rollup_state (id, last_bucket_at)
VALUES (1, date_bin(INTERVAL '1 hour', now() - INTERVAL '7 days', TIMESTAMPTZ '1970-01-01 00:00:00+00') - INTERVAL '1 hour');
