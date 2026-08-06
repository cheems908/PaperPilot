-- T5-02：真实论文正文和 AST 符号会超过 TEXT 的 64 KiB，阶段快照改为 MEDIUMTEXT。
ALTER TABLE stage_execution
    MODIFY COLUMN input_snapshot  MEDIUMTEXT NULL COMMENT '阶段输入快照 JSON（最大 16 MiB）',
    MODIFY COLUMN output_snapshot MEDIUMTEXT NULL COMMENT '阶段输出快照 JSON（最大 16 MiB）',
    MODIFY COLUMN error_snapshot  TEXT       NULL COMMENT '阶段错误快照 JSON';
