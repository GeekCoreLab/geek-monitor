\set ON_ERROR_STOP on
\timing on

DO $$
BEGIN
    IF current_database() <> 'geek_monitor_benchmark' THEN
        RAISE EXCEPTION 'refusing to load benchmark data into database %', current_database();
    END IF;
    IF EXISTS (SELECT 1 FROM agent WHERE hostname LIKE 'capacity-benchmark-%') THEN
        RAISE EXCEPTION 'capacity benchmark data already exists in this database';
    END IF;
END;
$$;

SELECT id AS project_id FROM project ORDER BY created_at LIMIT 1 \gset
SELECT now() AS sample_anchor \gset

INSERT INTO monitored_resource (id, project_id, name, type, environment)
SELECT ('30000000-0000-0000-0000-' || lpad(host_no::text, 12, '0'))::uuid,
       :'project_id'::uuid,
       'capacity-benchmark-host-' || lpad(host_no::text, 3, '0'),
       'HOST', 'benchmark'
FROM generate_series(1, 100) AS hosts(host_no);

INSERT INTO agent (id, resource_id, credential_hash, hostname, operating_system, agent_version, config_version, last_seen_at)
SELECT ('40000000-0000-0000-0000-' || lpad(host_no::text, 12, '0'))::uuid,
       ('30000000-0000-0000-0000-' || lpad(host_no::text, 12, '0'))::uuid,
       md5('capacity-benchmark-token-a-' || host_no) || md5('capacity-benchmark-token-b-' || host_no),
       'capacity-benchmark-' || lpad(host_no::text, 3, '0'),
       'linux/amd64', 'benchmark', 1, now()
FROM generate_series(1, 100) AS hosts(host_no);

INSERT INTO agent_batch (id, agent_id, boot_id, sequence, sampled_at, config_version,
                         receive_sample_delta_ms, client_clock_offset_ms, clock_synced, trusted, accepted_count)
SELECT md5(a.id::text || ':capacity:' || sequence_no)::uuid,
       a.id,
       md5(a.id::text || ':capacity-boot')::uuid,
       sequence_no,
       :'sample_anchor'::timestamptz - INTERVAL '24 hours' + (sequence_no - 1) * INTERVAL '30 seconds',
       1, 0, 0, TRUE, TRUE, 50
FROM agent a
CROSS JOIN generate_series(1, 2880) AS batches(sequence_no)
WHERE a.hostname LIKE 'capacity-benchmark-%';

WITH series AS (
    SELECT metric_no,
           CASE
               WHEN metric_no = 1 THEN 'cpu_usage_percent'
               WHEN metric_no = 2 THEN 'load_1m'
               WHEN metric_no = 3 THEN 'memory_used_percent'
               WHEN metric_no = 4 THEN 'memory_available_bytes'
               WHEN metric_no = 5 THEN 'swap_used_percent'
               WHEN metric_no BETWEEN 6 AND 17 THEN 'disk_used_percent'
               WHEN metric_no BETWEEN 18 AND 29 THEN 'disk_inode_used_percent'
               WHEN metric_no BETWEEN 30 AND 40 THEN 'network_receive_bytes_per_second'
               ELSE 'network_transmit_bytes_per_second'
           END AS metric_name,
           CASE
               WHEN metric_no BETWEEN 6 AND 17 THEN jsonb_build_object('mountpoint', '/mnt/disk' || (metric_no - 5))
               WHEN metric_no BETWEEN 18 AND 29 THEN jsonb_build_object('mountpoint', '/mnt/disk' || (metric_no - 17))
               WHEN metric_no BETWEEN 30 AND 40 THEN jsonb_build_object('interface', 'eth' || (metric_no - 29))
               WHEN metric_no BETWEEN 41 AND 50 THEN jsonb_build_object('interface', 'eth' || (metric_no - 40))
               ELSE '{}'::jsonb
           END AS labels
    FROM generate_series(1, 50) AS metrics(metric_no)
)
INSERT INTO metric_sample (batch_id, agent_id, resource_id, metric_name, labels, value, sampled_at, config_version, trusted)
SELECT b.id, a.id, a.resource_id, s.metric_name, s.labels,
       CASE
           WHEN s.metric_name = 'memory_available_bytes' THEN 5000000000 + (b.sequence % 100) * 1000000
           WHEN s.metric_name IN ('cpu_usage_percent', 'memory_used_percent', 'swap_used_percent', 'disk_used_percent', 'disk_inode_used_percent')
               THEN 20 + s.metric_no + (b.sequence % 100) / 10.0
           WHEN s.metric_name = 'load_1m' THEN 0.5 + (b.sequence % 100) / 100.0
           ELSE s.metric_no * 100 + b.sequence % 100
       END,
       b.sampled_at, b.config_version, TRUE
FROM agent_batch b
JOIN agent a ON a.id = b.agent_id
CROSS JOIN series s
WHERE a.hostname LIKE 'capacity-benchmark-%';

ANALYZE metric_sample;
ANALYZE agent_batch;

SELECT count(*) AS metric_samples,
       count(DISTINCT resource_id) AS hosts,
       count(DISTINCT (resource_id, metric_name, labels)) AS series,
       min(sampled_at) AS first_sample,
       max(sampled_at) AS last_sample
FROM metric_sample
WHERE agent_id IN (SELECT id FROM agent WHERE hostname LIKE 'capacity-benchmark-%');
