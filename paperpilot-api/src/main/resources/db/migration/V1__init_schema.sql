-- V1__init_schema.sql — PaperPilot 初始 schema
-- 所有表均含 id / created_at / updated_at / version（version 用于乐观锁，
-- 任务状态更新在 Java 侧经状态机校验后，再通过 version 做并发检查）。

-- ── 1. project 论文复现项目 ────────────────────────────────────────────
CREATE TABLE project (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(128) NOT NULL COMMENT '项目名称',
    description TEXT         NULL COMMENT '项目描述',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '论文复现项目';

-- ── 2. paper PDF 及解析状态 ────────────────────────────────────────────
CREATE TABLE paper (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    project_id   BIGINT        NOT NULL COMMENT '所属项目',
    title        VARCHAR(512)  NOT NULL COMMENT '论文标题',
    pdf_url      VARCHAR(1024) NOT NULL COMMENT 'PDF 地址',
    parse_status VARCHAR(32)   NOT NULL DEFAULT 'NOT_PARSED' COMMENT '解析状态',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version      INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_paper_project (project_id),
    CONSTRAINT fk_paper_project FOREIGN KEY (project_id) REFERENCES project (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '论文 PDF 及解析状态';

-- ── 3. repository GitHub 仓库 ──────────────────────────────────────────
CREATE TABLE repository (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    project_id  BIGINT        NOT NULL COMMENT '所属项目',
    github_url  VARCHAR(1024) NOT NULL COMMENT 'GitHub URL',
    branch      VARCHAR(255)  NOT NULL DEFAULT 'main' COMMENT '分支',
    commit_sha  VARCHAR(64)   NULL COMMENT 'commit SHA',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version     INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_repo_project (project_id),
    CONSTRAINT fk_repo_project FOREIGN KEY (project_id) REFERENCES project (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'GitHub 仓库';

-- ── 4. analysis_task 总任务状态 ────────────────────────────────────────
CREATE TABLE analysis_task (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    project_id  BIGINT       NOT NULL COMMENT '所属项目',
    request_key VARCHAR(128) NOT NULL COMMENT '幂等请求键（唯一）',
    status      VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '任务总状态（TaskStatus）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_request_key (request_key),
    KEY idx_task_project (project_id),
    KEY idx_task_status (status),
    CONSTRAINT fk_task_project FOREIGN KEY (project_id) REFERENCES project (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '分析总任务';

-- ── 5. stage_execution 每阶段执行、重试和快照 ───────────────────────────
CREATE TABLE stage_execution (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    task_id       BIGINT      NOT NULL COMMENT '所属任务',
    stage         VARCHAR(64) NOT NULL COMMENT '阶段（TaskStage）',
    attempt       INT         NOT NULL DEFAULT 1 COMMENT '尝试次数（从 1 开始）',
    status        VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '阶段状态（TaskStatus）',
    snapshot      TEXT        NULL COMMENT '阶段快照（JSON）',
    error_message TEXT        NULL COMMENT '失败原因',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version       INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stage_task_stage_attempt (task_id, stage, attempt),
    KEY idx_stage_task_status (task_id, status),
    CONSTRAINT fk_stage_task FOREIGN KEY (task_id) REFERENCES analysis_task (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '阶段执行';

-- ── 6. paper_concept 论文概念及原文证据 ────────────────────────────────
CREATE TABLE paper_concept (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    paper_id          BIGINT       NOT NULL COMMENT '所属论文',
    concept_name      VARCHAR(512) NOT NULL COMMENT '概念名称',
    evidence_text     TEXT         NULL COMMENT '原文证据',
    evidence_location VARCHAR(255) NULL COMMENT '证据位置（页码/段落）',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version           INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_concept_paper (paper_id),
    CONSTRAINT fk_concept_paper FOREIGN KEY (paper_id) REFERENCES paper (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '论文概念';

-- ── 7. code_symbol 代码符号 ────────────────────────────────────────────
-- 唯一索引列长合计：8 + 64*4 + 512*4 + 128*4 = 2824B < InnoDB 3072B 上限。
CREATE TABLE code_symbol (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    repository_id BIGINT       NOT NULL COMMENT '所属仓库',
    commit_sha    VARCHAR(64)  NOT NULL COMMENT 'commit SHA',
    file_path     VARCHAR(512) NOT NULL COMMENT '文件路径',
    symbol_name   VARCHAR(128) NOT NULL COMMENT '符号名（类/函数/变量）',
    symbol_type   VARCHAR(32)  NULL COMMENT '符号类型（class/function/var）',
    line_number   INT          NULL COMMENT '行号',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version       INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code_symbol (repository_id, commit_sha, file_path, symbol_name),
    KEY idx_symbol_repo (repository_id),
    CONSTRAINT fk_symbol_repo FOREIGN KEY (repository_id) REFERENCES repository (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '代码符号';

-- ── 8. concept_code_mapping 概念—代码映射结果 ──────────────────────────
CREATE TABLE concept_code_mapping (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    concept_id     BIGINT       NOT NULL COMMENT '概念（paper_concept.id）',
    code_symbol_id BIGINT       NOT NULL COMMENT '代码符号（code_symbol.id）',
    confidence     DECIMAL(5,4) NULL COMMENT '置信度 0~1',
    notes          TEXT         NULL COMMENT '映射说明',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version        INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_mapping_concept (concept_id),
    KEY idx_mapping_symbol (code_symbol_id),
    CONSTRAINT fk_mapping_concept FOREIGN KEY (concept_id) REFERENCES paper_concept (id),
    CONSTRAINT fk_mapping_symbol FOREIGN KEY (code_symbol_id) REFERENCES code_symbol (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '概念-代码映射';
