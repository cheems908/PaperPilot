"""
PaperPilot 假 Worker（测试替身）— 提供四个内部阶段接口的固定确定性响应.

仅用于集成测试；生产 Worker（main.py）不挂载本应用。
模拟参数 ``simulate.delayMs`` / ``simulate.failure`` 仅测试环境支持，
缺省无延迟、无故障（生产配置默认关闭）。

启动: uvicorn fake_worker:app --port 8001
"""
import asyncio

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

app = FastAPI(title="PaperPilot Fake Worker", version="0.1.0")

# 每个阶段的固定确定性输出（不依赖 GROBID / Git / AST / LLM）
DETERMINISTIC_OUTPUT = {
    "PARSE_PAPER": {"sections": 3, "title": "fake-paper"},
    "CLONE_REPOSITORY": {
        "commit": "fixed-commit-0000000000000000000000000000000000000000",
        "repo": "fake-repo",
    },
    "INDEX_CODE": {"symbols": 5},
    "MAP_CONCEPTS": {"mappings": 2},
}


def _stage_response(stage: str) -> dict:
    return {
        "schemaVersion": 1,
        "success": True,
        "output": DETERMINISTIC_OUTPUT.get(stage, {"stage": stage}),
        "artifacts": [],
        "metrics": {},
        "workerVersion": "fake-1.0.0",
    }


async def _handle_stage(stage: str, request: Request):
    # simulate.* 仅测试环境支持；缺省无延迟/无故障
    delay_ms = request.query_params.get("simulate.delayMs")
    if delay_ms is not None:
        await asyncio.sleep(int(delay_ms) / 1000.0)
    if request.query_params.get("simulate.failure") == "true":
        return JSONResponse(status_code=500, content={"message": "simulated failure"})
    return _stage_response(stage)


@app.get("/internal/health")
async def internal_health():
    """统一健康检查."""
    return {"status": "ok", "service": "paperpilot-fake-worker"}


@app.get("/health")
async def legacy_health():
    """旧健康检查兼容."""
    return {"status": "ok", "service": "paperpilot-fake-worker"}


@app.post("/internal/v1/papers/parse")
async def parse_paper(request: Request):
    return await _handle_stage("PARSE_PAPER", request)


@app.post("/internal/v1/repositories/clone")
async def clone_repository(request: Request):
    return await _handle_stage("CLONE_REPOSITORY", request)


@app.post("/internal/v1/repositories/index")
async def index_code(request: Request):
    return await _handle_stage("INDEX_CODE", request)


@app.post("/internal/v1/mappings/generate")
async def generate_mappings(request: Request):
    return await _handle_stage("MAP_CONCEPTS", request)
