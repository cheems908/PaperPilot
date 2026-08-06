-- T4-04：恢复扫描只按状态、超时字段和 id 读取有限批次。
ALTER TABLE analysis_task
    ADD KEY idx_task_status_updated_id (status, updated_at, id);

ALTER TABLE stage_execution
    ADD KEY idx_stage_status_updated_id (status, updated_at, id),
    ADD KEY idx_stage_status_heartbeat_id (status, heartbeat_at, id),
    ADD KEY idx_stage_status_retry_id (status, next_retry_at, id);
