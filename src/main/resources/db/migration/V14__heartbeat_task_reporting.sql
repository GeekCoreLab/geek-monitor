CREATE TABLE heartbeat_task (
    monitor_id UUID PRIMARY KEY REFERENCES monitor(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    last_received_at TIMESTAMPTZ,
    last_run_at TIMESTAMPTZ,
    last_run_id UUID,
    last_status VARCHAR(12) CHECK (last_status IN ('SUCCESS', 'FAILURE')),
    last_summary VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE heartbeat_report (
    monitor_id UUID NOT NULL REFERENCES heartbeat_task(monitor_id) ON DELETE CASCADE,
    run_id UUID NOT NULL,
    run_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('SUCCESS', 'FAILURE')),
    summary VARCHAR(500),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (monitor_id, run_id)
);
CREATE INDEX heartbeat_report_received_idx ON heartbeat_report (monitor_id, received_at DESC);
