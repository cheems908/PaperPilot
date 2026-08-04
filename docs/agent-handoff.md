# PaperPilot Agent Handoff

> 交接时间：2026-08-04（T0.1 → T1.2 已完成）。所有工作均**未提交**。
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

测试：`mvn clean test` → **11 tests, 0 failures**（状态机 5 + Flyway 迁移 4 + 持久化 1 + context 1）。

## 3. 当前仓库状态（全部未提交）

- 用户未提交改动（勿动）：`README.md`、`PaperPilot-实现与分析方案.md`、`参考.md`
- 本系列会话改动：见下节
- 分支 master，最近提交 `8c47b92` / `b8ecd02`

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

## 5. 已确认的技术决策（及原因）

1. **Spring Boot 3.3.5 + Java 17**：4.0.0 与 mybatis-plus-boot3-starter 有兼容风险；3.3.5 是验证过的组合。
2. **`@Mapper` 而非 `@MapperScan`**：测试 resources 的 application.yml 排除了 DataSource 自动配置，MyBatis 整体退避；用 `@Mapper` 注解可保证无 DataSource 时 context-load 测试仍通过。
3. **`src/test/resources/application.yml` 会覆盖主 application.yml**（classpath 优先）→ 测试只排除外部服务自动配置，不影响运行。
4. **`version` 乐观锁 + Java 状态机双层校验**：状态更新先过 `TaskStateMachine`，再靠 `@Version` 生成 `WHERE version=?`，实现"检查旧状态"。
5. **枚举存 `name()`**（如 `PENDING`），列用 VARCHAR；靠 `default-enum-type-handler` 配置。
6. **表 `repository` 实体类命名 `GitRepository`**：避免与 Spring Data `Repository` 混淆。
7. **code_symbol 唯一索引列长**：`8+64×4+512×4+128×4=2824B`，压进 InnoDB 3072B 上限，否则建索引失败。
8. **时间戳交给 MySQL**（`DEFAULT CURRENT_TIMESTAMP ON UPDATE`），实体 `FieldStrategy.NEVER`，不写 MetaObjectHandler。
9. **Flyway 迁移放 `src/main/resources/db/migration/`**，启动自动执行（本地 DB 已升到 v1）。

## 6. 未解决问题（风险清单）

1. **`default-enum-type-handler` 未在真实应用验证** — `TaskStatusPersistenceTest` 是手工构建 SqlSessionFactory 并显式设置该 handler 才通过的；application.yml 同名属性是否被 Spring Boot 正确绑定未证实。**下一步优先验证**。
2. **RocketMQ 启动 5 条 `BeanPostProcessorChecker` 警告** — 2.3.2 已知问题，功能正常，勿恐慌。
3. **`commons-logging` 类路径冲突** — RocketMQ 传递依赖，spring-jcl 已桥接，日志有提示但无碍。
4. **新克隆需手工初始化** — 需 `cp .env.example .env` + 创建 `application-local.yml`，无自动化脚手架。
5. **孤儿容器 `paperpilot-chromadb`** — 已停止未删，保留 volume；`docker compose up -d --remove-orphans` 可清。
6. **分页拦截器未启用** — 3.5.9 已拆到 `mybatis-plus-jsqlparser` 模块，需分页时再加依赖。
7. **`stage_execution.snapshot` JSON 契约未定义** — T1.3 编排时定。
8. **`paperpilot.worker.base-url` 无消费方** — 仅文档化，Worker 集成时用 `@ConfigurationProperties`。
9. **所有工作未提交** — 建议下一阶段先 commit（README/参考.md 属用户改动，勿一并提交）。

## 7. 关键命令与验证结果

```bash
# 基础设施（5 服务全 healthy）
docker compose up -d mysql redis rocketmq-namesrv rocketmq-broker grobid

# Java 测试 + 启动
cd paperpilot-api && mvn clean test          # ✅ 11 tests, 0 failures
mvn -q spring-boot:run &                     # ✅ 启动 4.8s，Flyway 应用 v1
curl localhost:8080/actuator/health          # ✅ {"status":"UP"}

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

1. **提交当前工作**（排除 README.md / 参考.md / PaperPilot-实现与分析方案.md 用户文件）
2. **验证 §6.1**：写一个 `@SpringBootTest`（带 DataSource）或用真实 DB 跑一次 Mapper 写入，确认 application.yml 的枚举 handler 绑定生效；若不生效改方案（如给枚举加 `@EnumValue`）
3. **T1.3 任务编排服务层**：`AnalysisTaskService`（状态机 + 乐观锁更新）、`StageExecutionService`（attempt 递增、快照）、`request_key` 幂等、`stage_execution.snapshot` JSON 契约
4. **T1.4 REST API**：project/paper/repository/task 的创建与查询端点
5. **Worker 集成**：Spring 经 RocketMQ 发单阶段任务 → Python 消费 → 回写；补 `paperpilot.worker.base-url` 消费方
6. **清理**：`docker compose up -d --remove-orphans` 清 chromadb 孤儿

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
