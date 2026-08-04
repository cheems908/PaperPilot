-- V3__stage_execution_snapshot_contracts.sql — 冻结阶段执行快照契约
--
-- 目标：统一阶段状态枚举、输入/输出/错误快照与执行时间字段，避免 Java/Python/DB 各定义一套。
--
-- 兼容策略：
--   1. status 列语义由 TaskStatus 收窄为 StageExecutionStatus（多 SKIPPED、无 QUEUED），
--      列类型与默认值不变，仅更新注释。阶段行只会被置为 PENDING（见
--      StageExecutionService.createInitialStages），不存在 QUEUED 行，无需数据回填。
--   2. 旧 snapshot / error_message 列仍被 StageExecutionService / TaskResultService
--      消费（StageResponse.snapshot、result 聚合），保留不删；新契约数据写入
--      input_snapshot / output_snapshot / error_snapshot，二者并存。
--   3. 快照内容一律用 Jackson DTO（StageInputSnapshot / StageOutputSnapshot /
--      StageErrorSnapshot）序列化，业务代码不手拼 JSON。

ALTER TABLE stage_execution
    MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        COMMENT '阶段状态（StageExecutionStatus）',
    ADD COLUMN input_snapshot  TEXT     NULL COMMENT '阶段输入快照（StageInputSnapshot JSON）' AFTER error_message,
    ADD COLUMN output_snapshot TEXT     NULL COMMENT '阶段输出快照（StageOutputSnapshot JSON）' AFTER input_snapshot,
    ADD COLUMN error_snapshot  TEXT     NULL COMMENT '阶段错误快照（StageErrorSnapshot JSON）' AFTER output_snapshot,
    ADD COLUMN started_at      DATETIME NULL COMMENT '阶段开始执行时间' AFTER error_snapshot,
    ADD COLUMN finished_at     DATETIME NULL COMMENT '阶段结束时间' AFTER started_at,
    ADD COLUMN next_retry_at   DATETIME NULL COMMENT '下次重试调度时间' AFTER finished_at,
    ADD COLUMN heartbeat_at    DATETIME NULL COMMENT '最近心跳时间' AFTER next_retry_at,
    ADD KEY idx_stage_status (status),
    ADD KEY idx_stage_next_retry (next_retry_at);
