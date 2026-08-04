# PaperPilot Agent Handoff

> 交接时间：2026-08-04（T0.1 → T1.3 已完成）。T1.1/T1.2 已提交（`ac18e84`）；T1.3 接口开发工作**未提交**。
> 技术边界：Spring Boot 管业务/任务状态机/RocketMQ 调度/Redis 进度/SSE；Python Worker 只执行单阶段分析，不改任务状态；MySQL 是任务状态最终事实来源。MVP 不实现用户系统、MinIO、ChromaDB、Docker Sandbox。

## 1. 架构与技术栈

```
paperpilot/  (git repo, branch: master)
├── paperpilot-api/        Spring Boot 3.3.5 · Java 17 · 端口 8080
│   ├── MyBatis-Plus 3.5.9 + MySQL 8 + Flyway + Druid
│   ├── RocketMQ 2.3.2 (namesrv:9876) + Redis 7 (进度/SSE) + Actuator
│   └── domain/ (enums/entity/状态机) + mapper/ + config/   ← T1.1/T1.2 新增
├── paperpilot-agent/      Python FastAPI + LangGraph (conda env: paperpilot) · 端口 8001
│   └── main.py 提供 GET /health、POST /api/analyze
├── paperpilot-frontend/   Vue 3 + Vite (rolldown-vite) · 未接后端
└── docker-compose.yml     mysql / redis / rocketmq-namesrv+borker / grobid（chromadb 已注释）
```

通信：Spring → RocketMQ 发异步任务 → Python 消费执行单阶段 → 回写 MySQL → Redis 推 SSE。

## 2. 已完成功能

| 阶段 | 内容 | 状态 |
|------|------|------|
| T0.1 | Spring Boot 4.0.0 → 3.3.5 降级，Java 17，依赖重组，删 Spring Security | ✅ |
| T0.2 | compose 精简为 5 服务，grobid 修复 | ✅ |
| T0.3 | 配置外置：env 占位符 + .env/.env.example + gitignore | ✅ |
| T1.1 | 状态机：`TaskStatus`(7) + `TaskStage`(6, MVP 前 4) + `TaskStateMachine` | ✅ |
| T1.2 | Flyway V1 建 8 表 + 8 实体 + 8 Mapper + 乐观锁拦截器 | ✅ |
| T1.3 | REST API：project/file/task 控制器 + DTO + 服务层 + V2 迁移（file 表、task 关联列）+ 内存 SSE | ✅ |

测试：`mvn clean test` → **27 tests, 0 failures**（状态机 5 + Flyway 迁移 4 + 持久化 1 + 服务集成 4 + 上下文 1 + 控制器 12）。

## 3. 当前仓库状态（全部未提交）

- 用户未提交改动（勿动）：`README.md`、`PaperPilot-实现与分析方案.md`、`参考.md`
- 分支 master，T1.1/T1.2 已提交（`ac18e84`）；T1.3 接口开发新增/改动见下节，**未提交**

## 4. 已修改/新增文件及关键点

**配置类**
- `paperpilot-api/pom.xml` — Spring Boot 3.3.5；Testcontainers **1.21.4**（勿回退 1.19.8，见 §9）；加 flyway-core/mysql、validation、actuator
- `paperpilot-api/src/main/resources/application.yml` — 全占位符 `${MYSQL_URL}`/`${MYSQL_PASSWORD}` 等；`spring.profiles.default: local`；`flyway.enabled: true`；`mybatis-plus.configuration.default-enum-type-handler: EnumTypeHandler`；`paperpilot.worker.base-url: ${AGENT_WORKER_URL:http://localhost:8001}`
- `application-local.yml`（gitignored）— 本地真实连接值（url/username/password）
- `.env`（gitignored）+ `.env.example` — 配置名文档，无真实密钥
- `.gitignore` — 新增 `**/application-local.yml`
- `docker-compose.yml` — chromadb 注释；`MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?...}`；grobid 加 `JAVA_OPTS: "-XX:-UseContainerSupport"`，健康检查用 `bash /dev/tcp`；rocketmq-namesrv 健康检查同样用 `/dev/tcp`

**T1.1/T1.2 Java（新增）**
- `domain/enums/TaskStatus.java`、`TaskStage.java`（含 `MVP_STAGES`）
- `domain/TaskStateMachine.java` — 唯一允许迁移集，非法抛 `IllegalStateException`
- `domain/entity/` 8 个：`Project/Paper/GitRepository/AnalysisTask/StageExecution/PaperConcept/CodeSymbol/ConceptCodeMapping`（均 `@TableName`+`@Version`+时间戳 `FieldStrategy.NEVER`）
- `mapper/` 8 个：`@Mapper extends BaseMapper<T>`（**不用 @MapperScan**，原因见 §5）
- `config/MybatisPlusConfig.java` — 仅 `OptimisticLockerInnerInterceptor`
- `resources/db/migration/V1__init_schema.sql` — 8 表 + 3 唯一索引 + FK
- 测试：`TaskStateMachineTest`、`FlywayMigrationTest`、`TaskStatusPersistenceTest`
- `paperpilot-agent/main.py` — 顶部加 `load_dotenv()`

**T1.3 接口开发（新增，未提交）**
- `resources/db/migration/V2__file_and_task_links.sql` — 建 `file` 表；`analysis_task` 加 `source_file_id`/`paper_id`/`repository_id`（可空 + 外键 + 索引）
- `domain/entity/File.java` + `mapper/FileMapper.java`；`AnalysisTask` 实体加 3 个关联字段
- `common/` — `ApiResponse{code,message,data}` 信封、`ErrorCode`、`ApiException`、`GlobalExceptionHandler`
- `dto/` — project/file/task 的请求/响应 record
- `service/` — `ProjectService`（级联删除）、`FileStorageService`（本地磁盘 + SHA-256）、`AnalysisTaskService`（状态机 + 乐观锁 + request_key 幂等 + file→paper 解析）、`StageExecutionService`（初始 4 阶段行）、`TaskEventService`（内存 SseEmitter，异步初始快照）、`TaskResultService`
- `controller/` — `ProjectController`/`FileController`/`TaskController`（create-task 返回 202，`/events` 为 SSE）
- `application.yml` — 加 `paperpilot.storage.local-dir`
- 测试：3 个 `@WebMvcTest` 控制器 + 3 个 Testcontainers 服务集成；`ApiApplicationTests` 改为 Testcontainers 提供 DataSource（T1.3 起上下文依赖 Mapper）

## 5. 已确认的技术决策（及原因）

1. **Spring Boot 3.3.5 + Java 17**：4.0.0 与 mybatis-plus-boot3-starter 有兼容风险；3.3.5 是验证过的组合。
2. **`@Mapper` 而非 `@MapperScan`**：测试 resources 的 application.yml 排除了 DataSource 自动配置，MyBatis 整体退避；用 `@Mapper` 注解可保证无 DataSource 时 context-load 测试仍通过。
3. **`src/test/resources/application.yml` 会覆盖主 application.yml**（classpath 优先）→ 测试只排除外部服务自动配置，不影响运行。
4. **`version` 乐观锁 + Java 状态机双层校验**：状态更新先过 `TaskStateMachine`，再靠 `@Version` 生成 `WHERE version=?`，实现"检查旧状态"。
5. **枚举存 `name()`**（如 `PENDING`），列用 VARCHAR；靠 `default-enum-type-handler` 配置。
6. **表 `repository` 实体类命名 `GitRepository`**：避免与 Spring Data `Repository` 混淆。
7. **code_symbol 唯一索引列长**：`8+64×4+512×4+128×4=2824B`，压进 InnoDB 3072B 上限，否则建索引失败。
8. **时间戳交给 MySQL**（`DEFAULT CURRENT_TIMESTAMP ON UPDATE`），实体 `FieldStrategy.NEVER`，不写 MetaObjectHandler。
9. **Flyway 迁移放 `src/main/resources/db/migration/`**，启动自动执行（本地 DB 已升到 v2）。

## 6. 未解决问题（风险清单）

1. ~~`default-enum-type-handler` 未在真实应用验证~~ — **已解决（T1.3）**：真实应用创建任务，status 写入/读回均为 `QUEUED`，`application.yml` 枚举 handler 绑定生效。
2. **RocketMQ 启动 5 条 `BeanPostProcessorChecker` 警告** — 2.3.2 已知问题，功能正常，勿恐慌。
3. **`commons-logging` 类路径冲突** — RocketMQ 传递依赖，spring-jcl 已桥接，日志有提示但无碍。
4. **新克隆需手工初始化** — 需 `cp .env.example .env` + 创建 `application-local.yml`，无自动化脚手架。
5. **孤儿容器 `paperpilot-chromadb`** — 已停止未删，保留 volume；`docker compose up -d --remove-orphans` 可清。
6. **分页拦截器未启用** — 3.5.9 已拆到 `mybatis-plus-jsqlparser` 模块，需分页时再加依赖。
7. **`stage_execution.snapshot` JSON 契约未定义** — T1.3 编排时定。
8. **`paperpilot.worker.base-url` 无消费方** — 仅文档化，Worker 集成时用 `@ConfigurationProperties`。
9. **所有工作未提交** — 建议下一阶段先 commit（README/参考.md 属用户改动，勿一并提交）。
10. **RocketMQ 调度 + Worker 集成未实现** — create-task 仅置 `QUEUED` 并建阶段行，未发 MQ 消息、无消费回写；为下一阶段核心工作。
11. **SSE 为单实例内存实现** — 无 Redis Pub/Sub 跨实例广播；多实例部署时事件只在被订阅实例上可见。
12. **前端未对齐新 API** — `App.vue` 仍用旧路径 `/api/papers/upload`、`/api/tasks`、`/api/tasks/{id}/progress`；需切 `/api/v1/...`，且 `taskEventsPolicy.js` 终态名（`COMPLETED`/`FAILED`）与后端（`SUCCEEDED`/`FAILED`/`CANCELLED`）不一致。
13. **cancel/retry 受状态机限制** — cancel 仅 `RUNNING→CANCELLED`（QUEUED 不可取消）；retry 仅 `WAITING_RETRY→RUNNING`（FAILED 为终态不可重试）。如需放宽需改 `TaskStateMachine` 及其测试。
14. **file 无下载/删除端点** — 上传只落盘写表，无 `GET /files/{id}` 下载，无孤儿文件清理策略。

## 7. 关键命令与验证结果

```bash
# 基础设施（5 服务全 healthy）
docker compose up -d mysql redis rocketmq-namesrv rocketmq-broker grobid

# Java 测试 + 启动
cd paperpilot-api && mvn clean test          # ✅ 27 tests, 0 failures（V1+V2 迁移）
mvn -q spring-boot:run &                     # ✅ Flyway 应用 V2（file 表 + task 关联列）
curl localhost:8080/actuator/health          # ✅ {"status":"UP"}

# T1.3 API 冒烟（真实 MySQL/Redis/RocketMQ）
curl -s -X POST localhost:8080/api/v1/projects \
  -H 'Content-Type: application/json' -d '{"name":"demo"}'      # → data.id
curl -s -X POST localhost:8080/api/v1/files/papers \
  -F 'file=@/tmp/paperpilot-test.pdf'                            # → data.fileId
curl -s -X POST localhost:8080/api/v1/projects/1/analysis-tasks \
  -H 'Content-Type: application/json' \
  -d '{"fileId":1,"githubUrl":"https://github.com/paperpilot/patchtst"}'  # → 202 data.status=QUEUED
curl -s localhost:8080/api/v1/tasks/1/stages                     # → 4 阶段行
curl -s localhost:8080/api/v1/tasks/1/result                     # → 任务结果
curl -N localhost:8080/api/v1/tasks/1/events                     # → SSE 连接 + 初始快照

# Python Worker（conda env: paperpilot）
source ~/miniconda3/etc/profile.d/conda.sh && conda activate paperpilot
cd paperpilot-agent && uvicorn main:app --host 127.0.0.1 --port 8001
curl localhost:8001/health                   # ✅ {"status":"ok","service":"paperpilot-agent"}

# 真实 MySQL 校验
docker exec paperpilot-mysql mysql -uroot -ppaperpilot123 paperpilot -e \
  "SELECT table_name FROM information_schema.tables WHERE table_schema='paperpilot' AND table_name<>'flyway_schema_history';"
# ✅ 8 表 + uk_task_request_key / uk_stage_task_stage_attempt / uk_code_symbol + version 列
```

## 8. 下一步任务（按优先级）

1. **提交 T1.3 工作**（排除 README.md / 参考.md / PaperPilot-实现与分析方案.md 用户文件）
2. **RocketMQ 调度**：`AnalysisTaskDispatcher` 生产者发单阶段消息 → 补 `paperpilot.worker.base-url` 消费方 → Python Worker 消费回写 stage/status（经状态机 + 乐观锁）
3. **Redis 进度 + Pub/Sub SSE**：`task:{id}:progress` 键 + 事件广播，替换内存 `TaskEventService`；补 `stage_execution.snapshot` JSON 契约
4. **前端切新 API**：`/api/v1/...` 路径 + `taskEventsPolicy.js` 终态名对齐后端
5. **file 下载端点 + 清理策略**：`GET /files/{id}`、超时/孤儿文件清理
6. **验证 §6.1 枚举 handler 真实绑定**：起真实应用跑一次写入（见 §7 curl 流程）
7. **清理**：`docker compose up -d --remove-orphans` 清 chromadb 孤儿

## 9. 避免重复尝试的失败方案

1. **Testcontainers 1.19.8 起不来**：`Could not find a valid Docker environment`（docker-java 与 Docker Desktop 29.x 不兼容，`/info` 返回 400）。**直接用 1.21.4+**。
2. **`PaginationInnerInterceptor` 加进 MybatisPlusConfig 编译失败**：3.5.9 把它拆到 `mybatis-plus-jsqlparser` 模块。别单独 import。
3. **grobid 健康检查用 `curl`**：容器里没 curl，永远 "starting"。用 `bash -c "exec 3<>/dev/tcp/127.0.0.1/8070"`。
4. **rocketmq-namesrv 健康检查用 `netstat`**：容器里没有。同样用 `/dev/tcp`。
5. **broker 命令加 `--autoCreateTopicEnable=true`**：`mqbroker` CLI 不认，直接打帮助并退出。用 `sh mqbroker -n rocketmq-namesrv:9876`。
6. **JDBC URL 用 `characterEncoding=utf8mb4`**：mysql-connector-j 8.3.0 报 `UnsupportedEncodingException`。用 `UTF-8`。
7. **grobid 崩溃 `CgroupInfo.getMountPoint() NPE`**：容器 JVM cgroup 检测缺陷，加 `JAVA_OPTS="-XX:-UseContainerSupport"`。
8. **`mvn spring-boot:run &` 后同命令 curl**：maven 输出刷屏导致超时。用 `mvn -q ... &` + 轮询循环。
9. **`pkill -f "spring-boot:run"` 清理端口**：exit 144 且误杀。用 `kill $(lsof -ti:8080)`。
