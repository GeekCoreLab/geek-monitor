ALTER TABLE monitor DROP CONSTRAINT monitor_type_check;
ALTER TABLE monitor ADD CONSTRAINT monitor_type_check
    CHECK (type IN ('HTTP', 'TCP', 'DNS', 'TLS', 'POSTGRESQL', 'MYSQL', 'REDIS', 'HEARTBEAT', 'METRIC_THRESHOLD'));

ALTER TABLE monitor DROP CONSTRAINT monitor_current_state_check;
ALTER TABLE monitor ADD CONSTRAINT monitor_current_state_check
    CHECK (current_state IN ('PENDING', 'UP', 'DEGRADED', 'DOWN', 'UNKNOWN'));

ALTER TABLE incident DROP CONSTRAINT incident_category_check;
ALTER TABLE incident ADD CONSTRAINT incident_category_check
    CHECK (category IN ('AVAILABILITY', 'TLS_CERTIFICATE', 'PERFORMANCE'));
