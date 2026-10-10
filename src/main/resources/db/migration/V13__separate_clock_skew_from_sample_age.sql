ALTER TABLE agent_batch RENAME COLUMN clock_offset_ms TO receive_sample_delta_ms;
ALTER TABLE agent_batch ADD COLUMN client_clock_offset_ms BIGINT;
ALTER TABLE agent_batch ADD COLUMN clock_synced BOOLEAN NOT NULL DEFAULT false;

UPDATE agent_batch
SET receive_sample_delta_ms = (EXTRACT(EPOCH FROM (received_at - sampled_at)) * 1000)::BIGINT;
