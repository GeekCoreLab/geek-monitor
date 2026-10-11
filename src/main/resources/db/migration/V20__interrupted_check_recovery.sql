ALTER TABLE check_execution
    ADD COLUMN execution_owner UUID;

CREATE INDEX check_execution_running_idx
    ON check_execution (execution_owner, scheduled_at)
    WHERE result_status = 'RUNNING';
