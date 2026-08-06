# paperpilot-agent

阶段工具服务：把单阶段分析暴露为独立的内部 HTTP API，供 Java 编排器调用。

## 结构与契约

- `app/main.py` — FastAPI 入口（`uvicorn app.main:app`）。
- `app/api/internal.py` — 四个内部阶段接口 + `/internal/health`（Java WorkerClient 调用的正式契约）。
- `app/schemas/` — 请求 / 成功响应 / 错误响应模型（均含 schemaVersion，与 Java `WorkerStageRequest` / `WorkerStageResponse` 兼容）。
- `app/services/` — 阶段服务（初期为确定性 fake，T3-02 起逐步接入真实实现）；不依赖 FastAPI，可纯单测。
- `app/core/` — Pydantic Settings 与统一异常（稳定 errorCode + retryable + message，不返回堆栈）。
- `app/clients/grobid_client.py` — GROBID 客户端占位（T3-02 实现）。

## 内部阶段接口

| 阶段 | HTTP 路径 |
|---|---|
| PARSE_PAPER | `POST /internal/v1/papers/parse` |
| CLONE_REPOSITORY | `POST /internal/v1/repositories/clone` |
| INDEX_CODE | `POST /internal/v1/repositories/index` |
| MAP_CONCEPTS | `POST /internal/v1/mappings/generate` |
| 健康检查 | `GET /internal/health` |

请求模型见 `app/schemas/common.py`（schemaVersion/requestId/taskId/stageExecutionId/stage/attempt/input）。
错误响应统一为 `{schemaVersion, success:false, errorCode, retryable, message}`，不返回 Python 堆栈。

## 模拟参数（仅测试环境）

- `PAPERPILOT_SIMULATE_FAILURE=true` — 阶段服务抛可重试错误（HTTP 500）。
- `PAPERPILOT_SIMULATE_DELAY_MS=500` — 阶段服务延迟响应。

生产配置默认关闭（Pydantic Settings 缺省值）。

## 旧接口兼容策略

- 旧整链入口 `POST /api/analyze`（LangGraph 单体流水线）保留但标记 **deprecated**，
  Java 不再调用；`GET /health` 与 `GET /internal/health` 等效。
- 主流程（内部阶段 API）不依赖 LangGraph；`agents/` 包仅被旧接口使用，暂不删除。
- 启动入口由 `main:app` 改为 `app.main:app`。

## 开发

项目固定使用 Python 3.11 和 `pyproject.toml` 中的精确依赖版本。重新建立环境时执行：

```bash
conda create -n paperpilot python=3.11 -y
conda run -n paperpilot python -m pip install -e ".[dev]"
conda run -n paperpilot python -m pip check
```

测试客户端显式使用 uvloop 后端，避免部分 Linux/Python 组合中默认 asyncio
跨线程唤醒阻塞。`httpx2` 尚未作为稳定基线引入；升级 FastAPI/Starlette 时必须先运行全量测试。

```bash
source ~/miniconda3/etc/profile.d/conda.sh && conda activate paperpilot
uvicorn app.main:app --host 127.0.0.1 --port 8001
pytest -q
```
