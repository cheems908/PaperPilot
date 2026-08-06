"""PaperPilot Agent 阶段工具服务入口.

内部阶段 API（/internal/v1/*）是 Java WorkerClient 调用的正式契约；
旧整链入口 ``/api/analyze`` 保留但标记 deprecated（Java 不再调用）。
主流程不依赖 LangGraph；``agents/`` 包仅被旧接口使用，暂不删除。
"""
import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.api.internal import router as internal_router
from app.core.errors import StageErrorCode, StageServiceError
from app.schemas.common import StageErrorResponse

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("paperpilot.agent")

app = FastAPI(title="PaperPilot Agent Worker", version="0.1.0")

app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

app.include_router(internal_router)


@app.exception_handler(RequestValidationError)
async def handle_validation_error(request: Request, exc: RequestValidationError):
    """校验错误也使用稳定 Worker 错误体，日志只记录字段位置与类型。"""
    details = [{"loc": list(error["loc"]), "type": error["type"]} for error in exc.errors()]
    logger.warning("invalid request on %s: %s", request.url.path, details)
    body = StageErrorResponse(
        errorCode=StageErrorCode.BAD_REQUEST,
        retryable=False,
        message="request validation failed",
    )
    return JSONResponse(status_code=422, content=body.model_dump())


@app.exception_handler(StageServiceError)
async def handle_stage_error(request: Request, exc: StageServiceError):
    """统一阶段错误：稳定 errorCode + retryable + message，不返回堆栈。"""
    body = StageErrorResponse(errorCode=exc.error_code, retryable=exc.retryable, message=exc.message)
    return JSONResponse(status_code=exc.status_code, content=body.model_dump())


@app.exception_handler(Exception)
async def handle_unexpected(request: Request, exc: Exception):
    """兜底：任何意外异常都不向客户端泄露堆栈。"""
    logger.exception("unexpected error on %s", request.url.path)
    body = StageErrorResponse(errorCode=StageErrorCode.WORKER_ERROR, retryable=True, message="internal error")
    return JSONResponse(status_code=500, content=body.model_dump())


@app.get("/health", tags=["compat"])
async def health():
    """健康检查（与 /internal/health 等效）。"""
    return {"status": "ok", "service": "paperpilot-agent"}


@app.post("/api/analyze", deprecated=True, tags=["legacy"])
def analyze(paper_url: str, github_url: str = None):
    """旧版整链分析入口（已弃用）：Java 不再调用，仅保留兼容。

    依赖 LangGraph 的完整流水线；主流程（内部阶段 API）已不依赖 LangGraph，
    故此处惰性导入 agents.pipeline。
    """
    try:
        from agents.pipeline import run_analysis_pipeline
    except Exception as e:
        logger.warning("legacy /api/analyze 不可用: %s", e)
        return JSONResponse(status_code=503, content={"message": "legacy pipeline unavailable"})
    logger.info("legacy /api/analyze called paper_url=%s", paper_url)
    try:
        return run_analysis_pipeline(paper_url, github_url)
    except Exception as e:
        logger.exception("legacy analyze failed")
        return JSONResponse(status_code=500, content={"message": str(e)})
