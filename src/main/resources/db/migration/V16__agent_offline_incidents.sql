ALTER TABLE monitor DROP CONSTRAINT monitor_type_check;
ALTER TABLE monitor ADD CONSTRAINT monitor_type_check
    CHECK (type IN ('HTTP', 'TCP', 'DNS', 'TLS', 'POSTGRESQL', 'MYSQL', 'REDIS', 'HEARTBEAT', 'METRIC_THRESHOLD', 'AGENT_OFFLINE'));

ALTER TABLE incident DROP CONSTRAINT incident_category_check;
ALTER TABLE incident ADD CONSTRAINT incident_category_check
    CHECK (category IN ('AVAILABILITY', 'TLS_CERTIFICATE', 'PERFORMANCE', 'COLLECTION'));

INSERT INTO monitor (
    id, resource_id, name, type, config, interval_seconds, timeout_seconds,
    failure_threshold, recovery_threshold, critical, enabled, reminder_interval_minutes, next_run_at
)
SELECT gen_random_uuid(), a.resource_id, 'Agent 采集状态', 'AGENT_OFFLINE',
       jsonb_build_object('agentId', a.id, 'resourceId', a.resource_id),
       10, 1, 1, 1, FALSE, TRUE, 30,
       GREATEST(now(), COALESCE(a.last_seen_at, a.created_at) + INTERVAL '90 seconds')
FROM agent a
WHERE a.revoked_at IS NULL;
