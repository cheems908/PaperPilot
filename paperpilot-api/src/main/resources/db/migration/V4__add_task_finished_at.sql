-- T2-05：任务终态记录完成时间（最后阶段成功后写入）
ALTER TABLE analysis_task
    ADD COLUMN finished_at DATETIME NULL COMMENT '任务完成时间' AFTER updated_at;
