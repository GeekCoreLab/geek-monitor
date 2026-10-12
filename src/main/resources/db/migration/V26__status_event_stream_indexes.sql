CREATE INDEX check_execution_status_stream_idx
    ON check_execution (finished_at, id)
    WHERE finished_at IS NOT NULL;

CREATE INDEX incident_event_status_stream_idx
    ON incident_event (created_at, id);
