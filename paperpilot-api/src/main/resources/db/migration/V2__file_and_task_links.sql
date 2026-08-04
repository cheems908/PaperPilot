-- V2__file_and_task_links.sql — 文件资源表 + 分析任务关联列
--
-- 设计要点（T1.3 接口开发）：
--   1. 文件资源（file）与论文业务实体（paper）分离。上传产生 file 行；
--      创建任务时先由 file 创建 paper 行，再把 file.id 记入 source_file_id、
--      paper.id 记入 paper_id，二者不混淆。
--   2. analysis_task 增加 source_file_id / paper_id / repository_id，均可空：
--      任务可能只关联论文、只关联仓库或两者皆有，合法组合在服务层校验。

-- ── 1. file 上传文件（本地磁盘存储，MVP 无 MinIO）───────────────────────
CREATE TABLE file (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    file_name    VARCHAR(512)  NOT NULL COMMENT '原始文件名',
    sha256       CHAR(64)      NOT NULL COMMENT '内容 SHA-256',
    size         BIGINT        NOT NULL COMMENT '文件字节数',
    storage_path VARCHAR(1024) NOT NULL COMMENT '本地存储路径',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version      INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    KEY idx_file_sha256 (sha256)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '上传文件';

-- ── 2. analysis_task 关联列（外键 + 索引）───────────────────────────────
ALTER TABLE analysis_task
    ADD COLUMN source_file_id BIGINT NULL COMMENT '来源上传文件(file.id)',
    ADD COLUMN paper_id       BIGINT NULL COMMENT '论文(paper.id)',
    ADD COLUMN repository_id  BIGINT NULL COMMENT '仓库(repository.id)',
    ADD KEY idx_task_source_file (source_file_id),
    ADD KEY idx_task_paper (paper_id),
    ADD KEY idx_task_repository (repository_id),
    ADD CONSTRAINT fk_task_source_file FOREIGN KEY (source_file_id) REFERENCES file (id),
    ADD CONSTRAINT fk_task_paper       FOREIGN KEY (paper_id)       REFERENCES paper (id),
    ADD CONSTRAINT fk_task_repository  FOREIGN KEY (repository_id)  REFERENCES repository (id);
