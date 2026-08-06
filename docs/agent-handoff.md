# PaperPilot Agent Handoff

> 交接时间：2026-08-06（T1.4 → T4.02 已完成，全部已提交；工作树干净）。
> 技术边界：Spring Boot 管业务数据/任务状态机/RocketMQ 生产与消费/阶段编排/Redis 进度与 PubSub/SSE；
> Python Worker 只执行单阶段分析（HTTP），不消费 MQ、不改任务状态；MySQL 是任务状态最终事实来源。
> 开发卡在 `docs/dev_cards/`；公共约束见 `docs/dev_cards/00-公共约束.md`；踩坑记录见 `docs/踩坑.md`。

## 1. 架构与技术栈

```
paperpilot/  (git repo, branch: master)
├── paperpilot-api/        Spring Boot 3.3.5 · Java 17 · 端口 8080
│   ├── MyBatis-Plus 3.5.9 + MySQL 8 + Flyway + Redis(Spring Data) + RocketMQ 2.3.2 + Actuator
│   ├── controller/ task|project|file     → REST + SSE(/api/v1/tasks/{id}/events)
│   ├── service/    状态机/编排/进度/事件/结果     ← 业务核心
│   ├── progress/   TaskProgressService + TaskEventService(PubSub/SSE)
│   ├── worker/     WorkerClient(HTTP 调 Python) + 错误码透传
│   ├── mq/         StageMessageConsumer(消费者) + Producer/Dispatcher
│   ├── dto/        task|snapshot|worker|indexer|mapping|progress 契约
│   └── resources/db/migration/  V1~V5
├── paperpilot-agent/      Python FastAPI（conda env: paperpilot）· 端口 8001
│   └── app/  main.py + api/internal.py(4 阶段接口) + schemas/ + services/(真实解析/克隆/索引/映射)
│        + clients/grobid_client.py + core/(config|errors)
└── paperpilot-frontend/   Vue 3 + Vite · 未接后端（T6 处理）
```

通信链路：`创建任务 → AFTER_COMMIT 派发首阶段 MQ → Java 消费 → 幂等编排(短事务抢占→HTTP 调 Python Worker→结果落库) → 推进下一阶段 → 终态`；
进度/事件经 Redis（进度快照 + Pub/Sub）实时推给 SSE 前端。

## 2. 已完成功能（按卡）

| 阶段 | 内容 | 提交 |
|------|------|------|
| T1.1/T1.2 | 状态机（TaskStatus/TaskStage/TaskStateMachine）+ Flyway V1 8 表 + 8 实体/Mapper + 乐观锁 | `ac18e84` |
| T1.3 | REST API（project/file/task）+ V2 迁移 + 内存 SSE | `8de23e9` 起 |
| T1.4-02 | 阶段快照契约冻结（StageSnapshotContract/schemaVersion=1 + V3）+ StageExecutionStatus | `b7d5172` |
| T1.4-03 | 取消/重试状态迁移对齐（终态仅 SUCCEEDED/CANCELLED）+ ILLEGAL_TASK_TRANSITION | `701f38d` |
| T1.4-04 | requestId 全链路（过滤器 + MDC + ApiResponse.requestId + 日志） | `3c88c52` |
| T2-01 | RocketMQ 阶段消息契约（StageTaskMessage schemaVersion=1 + 校验）+ paperpilot.mq 配置 | `a4e2e0d` |
| T2-02~04 | 事务提交后派发首阶段 + Java WorkerClient(错误分类) + 幂等编排器(条件更新抢占) | `d730a6c`（合并） |
| T2-05/06 | 消费推进(NextStageResolver/StageProgressionService) + 假 Worker 全链路集成 | `045f419`（合并） |
| T3-01 | 重构 Python 为 app 包（4 内部接口 + 统一错误 + deprecated /api/analyze） | `0d8f4c2` |
| T3-02 | GROBID 解析 + TEI 安全解析 + PyMuPDF 降级 + PDF 路径/sha256 安全 | `8b4b752` |
| T3-03 | 受控 GitHub 克隆（URL/SSRF 校验 + 参数数组 git + 限制 + 原子发布） | `0f9a261` |
| T3-04 | AST 代码索引（符号/签名/行号）+ code_symbol 幂等 upsert | `c529266` |
| T3-05/06 | 规则映射 + Embedding/LLM 验证（幻觉抑制 + 统一评分 + 状态） | `1abc835`（合并） |
| T4-01/02 | Redis 进度模型 + Redis Pub/Sub + SSE snapshot 重连 + heartbeat | `9d608d9`（合并） |

## 3. 当前仓库状态

- 工作树**干净**（全部已提交）。
- 基线：`mvn clean test` → **136 tests, 0 failures**；`pytest -q`（agent）→ **78 passed**。
- Flyway 到 V5；本地 MySQL/Redis/RocketMQ 由 `docker-compose.yml` 提供。

## 4. 关键实现与决策

1. **状态迁移唯一状态机**：`TaskStateMachine` 收敛全部迁移（终态仅 SUCCEEDED/CANCELLED；FAILED 可人工重试回 QUEUED）；非法抛稳定业务码 `ILLEGAL_TASK_TRANSITION`。
2. **幂等**：执行权靠条件 UPDATE（`WHERE status IN (PENDING,WAITING_RETRY) AND version=?`）+ `@Version` 乐观锁；code_symbol / concept_code_mapping / paper_concept 用唯一键 + `ON DUPLICATE KEY UPDATE`。
3. **错误码透传**：Python 统一错误 `{errorCode,retryable,message}` → `HttpWorkerClient` 解析 4xx/5xx 错误体 → `WorkerException` 保留远端 errorCode/retryable → 错误快照写远端码（如 INVALID_PDF）。
4. **MySQL 优先，Redis 只补充**：进度快照 best-effort 写 Redis（失败仅告警）；查询 MySQL 终态优先；SSE 首条事件为 snapshot（MySQL+Redis），重连靠快照恢复。
5. **规则 + 语义 + LLM 验证**：统一评分 `0.35 semantic + 0.25 symbol + 0.20 keyword + 0.20 verification`；LLM 只回 candidateId/score/reason/decision（`extra=forbid` 拒幻觉路径/行号）；LLM 不可用降级（degraded，不标 VERIFIED）。
6. **Flyway 已应用迁移不可改**：V1~V5 均追加式。
7. 测试用 Testcontainers（MySQL/Redis）真实验证并发/持久化/跨实例 Pub/Sub。

## 5. 未解决问题 / 下一步（风险清单）

**T4 剩余**
- **T4-03 自动重试与错误分类**：编排器已形成结构化错误快照（StageErrorSnapshot 含 retryable），但任务失败一律走 FAILED；需实现按错误分类的三档退避、最大 attempt、`WAITING_RETRY→QUEUED` 自动回队。
- **T4-04 服务重启恢复与取消竞态**：编排器对 RUNNING 阶段无进程内中止；重启后 RUNNING 阶段恢复、取消与执行竞态需处理。

**T3 遗留（延后项）**
- **T3-02C**：Java 把完整解析结果持久化到 `paper` 表 + snapshot 只存摘要（防 TEXT 溢出）。
- **真实阶段输入接线**：Java 端需把 repo URL / workspaceRef / paper 结构组装进 CLONE/INDEX/MAP 输入（当前与 T2-05 推进守卫 `input_snapshot IS NULL` 冲突，需统一设计）。
- **Java 状态对齐**：`TaskResultService` per-candidate 状态仍按 confidence 派生 `CANDIDATE`，与 Python `VERIFIED/NEEDS_REVIEW/REJECTED` 未对齐。

**后续阶段**
- **T5**：PatchTST 真实 PDF/仓库/固定 commit 端到端基准 + 映射质量指标（需真实 GROBID/GitHub/LLM）。
- **T6**：前端接新 API；`taskEventsPolicy.js` 的终态判定需对齐新事件模型（`event.type` 取代 `event.state`）。
- **T7**：Outbox 关闭 DB/MQ 一致性窗口、DLQ、MinIO、Redis 配额、Docker 沙箱、可观测性。

**已知技术债**
- SSE 快照为异步发送，极端并发下 Redis 事件可能先于快照（重连靠快照恢复，非序保证）。
- 真实 LLM/Embedding 未进自动化（确定性 fake）；需 `PAPERPILOT_LLM_BASE_URL`/key。
- 进度写入为 read-then-write（非原子）；严格化可用 Lua CAS。

## 6. 关键命令

```bash
# 基础设施
docker compose up -d mysql redis rocketmq-namesrv rocketmq-broker grobid

# Java 全量测试
cd paperpilot-api && mvn clean test

# 定向测试（按卡）
mvn -Dtest='*StageOrchestrator*Test,*Concurrency*Test' test
mvn -Dtest='*TaskProgress*Test,*Redis*IntegrationTest' test
mvn -Dtest='*TaskEvent*Test,*Sse*Test' test

# Python Worker
cd paperpilot-agent && source ~/miniconda3/etc/profile.d/conda.sh && conda activate paperpilot
python -m pytest -q
uvicorn app.main:app --host 127.0.0.1 --port 8001
curl -s localhost:8001/internal/health
```

## 7. 避免重复踩坑

详见 `docs/踩坑.md`（T2-03 超时分类、JdkClientHttpRequestFactory API、stale class、SimpleApplicationEventPublisher 不存在、LambdaUpdateWrapper 需 TableInfo 缓存等）。

## 8. 2026-08-06 T5 阶段更新

- T5-01 已冻结 PatchTST PDF SHA256、仓库 commit 和 12 条人工 gold（9 CONFIRMED、1 AUXILIARY、
  1 LOW_CONFIDENCE、1 NO_EXPLICIT_IMPLEMENTATION）。
- T5-02 已用真实 GROBID、GitHub 固定 commit、MySQL/Redis/RocketMQ 连续完成两次四阶段任务，
  并通过 Java 重启后的结果与 SSE 恢复校验。
- T5-03 已增加确定性 evaluator、规则版/增强版基准生成器以及 JSON/Markdown 固化报告。
  Python 全量基线为 **89 passed**；最近 Java 全量基线为 **169 tests, 0 failures**。
- 当前质量结论：两版 P@K/R@K/MRR 均为 0，主因是论文解析后的概念抽取退化为单词级术语，
  无法与 9 个 CONFIRMED 复合概念形成稳定匹配键；代码/论文候选证据完整率为 99.29%。
  T5 工程闭环已形成，但映射质量尚不具备对外宣称条件，下一步应优先修复概念抽取与概念 ID 对齐。
