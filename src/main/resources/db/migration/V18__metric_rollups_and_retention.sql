CREATE TABLE metric_rollup (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    resource_id UUID NOT NULL REFERENCES monitored_resource(id),
    metric_name VARCHAR(80) NOT NULL,
    labels JSONB NOT NULL DEFAULT '{}'::jsonb,
    resolution_seconds INTEGER NOT NULL CHECK (resolution_seconds IN (300, 3600)),
    bucket_at TIMESTAMPTZ NOT NULL,
    sample_count BIGINT NOT NULL CHECK (sample_count > 0),
    sum_value DOUBLE PRECISION NOT NULL CHECK (sum_value > '-Infinity'::double precision AND sum_value < 'Infinity'::double precision),
    min_value DOUBLE PRECISION NOT NULL CHECK (min_value > '-Infinity'::double precision AND min_value < 'Infinity'::double precision),
    max_value DOUBLE PRECISION NOT NULL CHECK (max_value > '-Infinity'::double precision AND max_value < 'Infinity'::double precision),
    CHECK (min_value <= max_value),
    UNIQUE (resource_id, metric_name, labels, resolution_seconds, bucket_at)
);

CREATE INDEX metric_rollup_series_time_idx
    ON metric_rollup (resource_id, metric_name, resolution_seconds, bucket_at DESC);
CREATE INDEX metric_rollup_retention_idx
    ON metric_rollup (resolution_seconds, bucket_at);
CREATE INDEX check_execution_retention_idx ON check_execution (scheduled_at);
CREATE INDEX agent_batch_retention_idx ON agent_batch (sampled_at);
