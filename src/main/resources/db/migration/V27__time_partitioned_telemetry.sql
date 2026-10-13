-- The source tables are swapped transactionally. Existing rows are copied into
-- month partitions before the old tables are dropped, so a failed migration
-- rolls back to the original schema and data.
CREATE FUNCTION create_monthly_time_partitions(
    partitioned_table REGCLASS,
    partition_prefix TEXT,
    source_table REGCLASS,
    time_column TEXT
) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    oldest_value TIMESTAMPTZ;
    newest_value TIMESTAMPTZ;
    month_start DATE;
    month_end_exclusive DATE;
    current_month DATE;
    month_count INTEGER;
    lower_bound TIMESTAMPTZ;
    upper_bound TIMESTAMPTZ;
    partition_name TEXT;
BEGIN
    EXECUTE format('SELECT min(%I), max(%I) FROM %s', time_column, time_column, source_table)
       INTO oldest_value, newest_value;

    current_month := date_trunc('month', now() AT TIME ZONE 'UTC')::DATE;
    month_start := LEAST(
        date_trunc('month', COALESCE(oldest_value, now()) AT TIME ZONE 'UTC')::DATE,
        (current_month - INTERVAL '1 month')::DATE
    );
    month_end_exclusive := (
        date_trunc('month', GREATEST(COALESCE(newest_value, now()), now()) AT TIME ZONE 'UTC')
        + INTERVAL '4 months'
    )::DATE;

    month_count := (
        (EXTRACT(YEAR FROM month_end_exclusive) - EXTRACT(YEAR FROM month_start)) * 12
        + EXTRACT(MONTH FROM month_end_exclusive) - EXTRACT(MONTH FROM month_start)
    )::INTEGER;
    IF month_count > 1200 THEN
        RAISE EXCEPTION 'Refusing to create % monthly partitions for %. Clean up or migrate the unusually wide retained history first.',
            month_count, source_table;
    END IF;

    WHILE month_start < month_end_exclusive LOOP
        lower_bound := month_start::TIMESTAMP AT TIME ZONE 'UTC';
        upper_bound := (month_start + INTERVAL '1 month')::TIMESTAMP AT TIME ZONE 'UTC';
        partition_name := partition_prefix || '_' || to_char(month_start, 'YYYYMM');
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
            partition_name, partitioned_table, lower_bound, upper_bound
        );
        month_start := (month_start + INTERVAL '1 month')::DATE;
    END LOOP;

    EXECUTE format('CREATE TABLE %I PARTITION OF %s DEFAULT', partition_prefix || '_default', partitioned_table);
END;
$$;

CREATE TABLE check_execution_identity_registry (
    id UUID PRIMARY KEY,
    scheduled_at TIMESTAMPTZ NOT NULL
);

CREATE FUNCTION register_check_execution_identity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    inserted_rows INTEGER;
BEGIN
    INSERT INTO check_execution_identity_registry(id, scheduled_at)
    VALUES (NEW.id, NEW.scheduled_at)
    ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS inserted_rows = ROW_COUNT;
    IF inserted_rows = 0 THEN
        RAISE EXCEPTION 'check execution id % already exists', NEW.id USING ERRCODE = '23505';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION unregister_check_execution_identity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM check_execution_identity_registry
    WHERE id = OLD.id AND scheduled_at = OLD.scheduled_at;
    RETURN OLD;
END;
$$;

CREATE FUNCTION guard_check_execution_identity_update() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.scheduled_at IS DISTINCT FROM OLD.scheduled_at THEN
        RAISE EXCEPTION 'check execution identity and scheduled time are immutable';
    END IF;
    RETURN NEW;
END;
$$;

ALTER TABLE check_execution RENAME TO check_execution_v26;
CREATE TABLE check_execution_partitioned (
    LIKE check_execution_v26 INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS
) PARTITION BY RANGE (scheduled_at);
SELECT create_monthly_time_partitions(
    'check_execution_partitioned'::REGCLASS, 'check_execution', 'check_execution_v26'::REGCLASS, 'scheduled_at'
);
INSERT INTO check_execution_partitioned SELECT * FROM check_execution_v26;
INSERT INTO check_execution_identity_registry(id, scheduled_at)
    SELECT id, scheduled_at FROM check_execution_partitioned;
ALTER TABLE check_execution_partitioned ADD CONSTRAINT check_execution_partitioned_pkey PRIMARY KEY (id, scheduled_at);
ALTER TABLE check_execution_partitioned ADD CONSTRAINT check_execution_partitioned_monitor_fkey
    FOREIGN KEY (monitor_id) REFERENCES monitor(id);
ALTER TABLE check_execution_partitioned ADD CONSTRAINT check_execution_partitioned_identity_fkey
    FOREIGN KEY (id) REFERENCES check_execution_identity_registry(id) ON DELETE CASCADE;
CREATE TRIGGER check_execution_identity_insert
    BEFORE INSERT ON check_execution_partitioned
    FOR EACH ROW EXECUTE FUNCTION register_check_execution_identity();
CREATE TRIGGER check_execution_identity_delete
    AFTER DELETE ON check_execution_partitioned
    FOR EACH ROW EXECUTE FUNCTION unregister_check_execution_identity();
CREATE TRIGGER check_execution_identity_update_guard
    BEFORE UPDATE OF id, scheduled_at ON check_execution_partitioned
    FOR EACH ROW EXECUTE FUNCTION guard_check_execution_identity_update();
DROP TABLE check_execution_v26;
ALTER TABLE check_execution_partitioned RENAME TO check_execution;
CREATE INDEX check_execution_monitor_time_partitioned_idx ON check_execution (monitor_id, scheduled_at DESC);
CREATE INDEX check_execution_retention_partitioned_idx ON check_execution (scheduled_at);
CREATE INDEX check_execution_running_partitioned_idx ON check_execution (execution_owner, scheduled_at)
    WHERE result_status = 'RUNNING';
CREATE INDEX check_execution_status_stream_partitioned_idx ON check_execution (finished_at, id);

ALTER TABLE metric_sample RENAME TO metric_sample_v26;
CREATE TABLE metric_sample_partitioned (
    LIKE metric_sample_v26 INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS
) PARTITION BY RANGE (sampled_at);
SELECT create_monthly_time_partitions(
    'metric_sample_partitioned'::REGCLASS, 'metric_sample', 'metric_sample_v26'::REGCLASS, 'sampled_at'
);
INSERT INTO metric_sample_partitioned OVERRIDING SYSTEM VALUE SELECT * FROM metric_sample_v26;
SELECT setval(
    pg_get_serial_sequence('metric_sample_partitioned', 'id'),
    COALESCE((SELECT max(id) FROM metric_sample_partitioned), 1),
    EXISTS (SELECT 1 FROM metric_sample_partitioned)
);
ALTER TABLE metric_sample_partitioned ADD CONSTRAINT metric_sample_partitioned_pkey PRIMARY KEY (id, sampled_at);
ALTER TABLE metric_sample_partitioned ADD CONSTRAINT metric_sample_partitioned_batch_metric_key
    UNIQUE (batch_id, metric_name, labels, sampled_at);
ALTER TABLE metric_sample_partitioned ADD CONSTRAINT metric_sample_partitioned_batch_fkey
    FOREIGN KEY (batch_id) REFERENCES agent_batch(id) ON DELETE CASCADE;
ALTER TABLE metric_sample_partitioned ADD CONSTRAINT metric_sample_partitioned_agent_fkey
    FOREIGN KEY (agent_id) REFERENCES agent(id);
ALTER TABLE metric_sample_partitioned ADD CONSTRAINT metric_sample_partitioned_resource_fkey
    FOREIGN KEY (resource_id) REFERENCES monitored_resource(id);
DROP TABLE metric_sample_v26;
ALTER TABLE metric_sample_partitioned RENAME TO metric_sample;
CREATE INDEX metric_sample_resource_series_partitioned_idx
    ON metric_sample (resource_id, metric_name, sampled_at DESC);
CREATE INDEX metric_sample_sampled_at_partitioned_idx ON metric_sample (sampled_at);

ALTER TABLE database_metric_sample RENAME TO database_metric_sample_v26;
CREATE TABLE database_metric_sample_partitioned (
    LIKE database_metric_sample_v26 INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS
) PARTITION BY RANGE (sampled_at);
SELECT create_monthly_time_partitions(
    'database_metric_sample_partitioned'::REGCLASS, 'database_metric_sample',
    'database_metric_sample_v26'::REGCLASS, 'sampled_at'
);
INSERT INTO database_metric_sample_partitioned OVERRIDING SYSTEM VALUE SELECT * FROM database_metric_sample_v26;
SELECT setval(
    pg_get_serial_sequence('database_metric_sample_partitioned', 'id'),
    COALESCE((SELECT max(id) FROM database_metric_sample_partitioned), 1),
    EXISTS (SELECT 1 FROM database_metric_sample_partitioned)
);
ALTER TABLE database_metric_sample_partitioned ADD CONSTRAINT database_metric_sample_partitioned_pkey
    PRIMARY KEY (id, sampled_at);
ALTER TABLE database_metric_sample_partitioned ADD CONSTRAINT database_metric_sample_partitioned_series_key
    UNIQUE (monitor_id, metric_name, labels, sampled_at);
ALTER TABLE database_metric_sample_partitioned ADD CONSTRAINT database_metric_sample_partitioned_monitor_fkey
    FOREIGN KEY (monitor_id) REFERENCES monitor(id);
ALTER TABLE database_metric_sample_partitioned ADD CONSTRAINT database_metric_sample_partitioned_resource_fkey
    FOREIGN KEY (resource_id) REFERENCES monitored_resource(id);
DROP TABLE database_metric_sample_v26;
ALTER TABLE database_metric_sample_partitioned RENAME TO database_metric_sample;
CREATE INDEX database_metric_sample_resource_series_partitioned_idx
    ON database_metric_sample (resource_id, metric_name, sampled_at DESC);
CREATE INDEX database_metric_sample_sampled_at_partitioned_idx ON database_metric_sample (sampled_at);

ALTER TABLE metric_rollup RENAME TO metric_rollup_v26;
CREATE TABLE metric_rollup_partitioned (
    LIKE metric_rollup_v26 INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS
) PARTITION BY RANGE (bucket_at);
SELECT create_monthly_time_partitions(
    'metric_rollup_partitioned'::REGCLASS, 'metric_rollup', 'metric_rollup_v26'::REGCLASS, 'bucket_at'
);
INSERT INTO metric_rollup_partitioned OVERRIDING SYSTEM VALUE SELECT * FROM metric_rollup_v26;
SELECT setval(
    pg_get_serial_sequence('metric_rollup_partitioned', 'id'),
    COALESCE((SELECT max(id) FROM metric_rollup_partitioned), 1),
    EXISTS (SELECT 1 FROM metric_rollup_partitioned)
);
ALTER TABLE metric_rollup_partitioned ADD CONSTRAINT metric_rollup_partitioned_pkey PRIMARY KEY (id, bucket_at);
ALTER TABLE metric_rollup_partitioned ADD CONSTRAINT metric_rollup_partitioned_series_key
    UNIQUE (resource_id, metric_name, labels, resolution_seconds, bucket_at);
ALTER TABLE metric_rollup_partitioned ADD CONSTRAINT metric_rollup_partitioned_resource_fkey
    FOREIGN KEY (resource_id) REFERENCES monitored_resource(id);
DROP TABLE metric_rollup_v26;
ALTER TABLE metric_rollup_partitioned RENAME TO metric_rollup;
CREATE INDEX metric_rollup_series_partitioned_idx
    ON metric_rollup (resource_id, metric_name, resolution_seconds, bucket_at DESC);
CREATE INDEX metric_rollup_retention_partitioned_idx ON metric_rollup (resolution_seconds, bucket_at);

ALTER TABLE availability_rollup RENAME TO availability_rollup_v26;
CREATE TABLE availability_rollup_partitioned (
    LIKE availability_rollup_v26 INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS
) PARTITION BY RANGE (bucket_at);
SELECT create_monthly_time_partitions(
    'availability_rollup_partitioned'::REGCLASS, 'availability_rollup',
    'availability_rollup_v26'::REGCLASS, 'bucket_at'
);
INSERT INTO availability_rollup_partitioned SELECT * FROM availability_rollup_v26;
ALTER TABLE availability_rollup_partitioned ADD CONSTRAINT availability_rollup_partitioned_pkey
    PRIMARY KEY (monitor_id, bucket_at);
ALTER TABLE availability_rollup_partitioned ADD CONSTRAINT availability_rollup_partitioned_monitor_fkey
    FOREIGN KEY (monitor_id) REFERENCES monitor(id) ON DELETE CASCADE;
DROP TABLE availability_rollup_v26;
ALTER TABLE availability_rollup_partitioned RENAME TO availability_rollup;
CREATE INDEX availability_rollup_retention_partitioned_idx ON availability_rollup (bucket_at);

DROP FUNCTION create_monthly_time_partitions(REGCLASS, TEXT, REGCLASS, TEXT);
