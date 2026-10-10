CREATE TABLE database_metric_sample (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    monitor_id UUID NOT NULL REFERENCES monitor(id),
    resource_id UUID NOT NULL REFERENCES monitored_resource(id),
    metric_name VARCHAR(80) NOT NULL,
    labels JSONB NOT NULL DEFAULT '{}'::jsonb,
    value DOUBLE PRECISION NOT NULL CHECK (value > '-Infinity'::double precision AND value < 'Infinity'::double precision),
    sampled_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (monitor_id, metric_name, labels, sampled_at)
);

CREATE INDEX database_metric_sample_resource_series_time_idx
    ON database_metric_sample (resource_id, metric_name, sampled_at DESC);
CREATE INDEX database_metric_sample_sampled_at_idx ON database_metric_sample (sampled_at);
