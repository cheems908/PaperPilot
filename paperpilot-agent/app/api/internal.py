"""内部阶段 API：四个阶段接口 + /internal/health（与 Java WorkerClient 契约一致）.

service 不依赖 FastAPI Request/Response；模拟开关由 API 层从配置注入。
"""
import logging

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from app.core.config import simulate_options
from app.core.errors import StageErrorCode, StageServiceError
from app.schemas.common import StageErrorResponse, StageRequest, StageSuccessResponse
from app.services import code_indexer, mapping_analyzer, paper_parser, repository_cloner

logger = logging.getLogger("paperpilot.agent.api")

router = APIRouter(prefix="/internal", tags=["internal"])


def _run_stage(stage: str, req: StageRequest, service) -> StageSuccessResponse:
    """调用阶段服务；失败统一日志（带 requestId/taskId/stageExecutionId）并抛可转换异常。"""
    try:
        return service.process(req, simulate_options())
    except StageServiceError as e:
        logger.error(
            "stage=%s failed requestId=%s taskId=%s stageExecutionId=%s errorCode=%s retryable=%s message=%s",
            stage, req.requestId, req.taskId, req.stageExecutionId, e.error_code, e.retryable, e.message,
        )
        raise
    except Exception as e:  # 意外异常：不向客户端返回堆栈，统一转稳定错误
        logger.exception(
            "stage=%s unexpected requestId=%s taskId=%s stageExecutionId=%s",
            stage, req.requestId, req.taskId, req.stageExecutionId,
        )
        raise StageServiceError(StageErrorCode.WORKER_ERROR, "internal error", retryable=True) from e


@router.get("/health", summary="统一健康检查")
def health():
    return {"status": "ok", "service": "paperpilot-agent"}


@router.post("/v1/papers/parse", response_model=StageSuccessResponse, summary="解析论文 PDF")
def parse_paper(req: StageRequest):
    return _run_stage("PARSE_PAPER", req, paper_parser.paper_parser)


@router.post("/v1/repositories/clone", response_model=StageSuccessResponse, summary="克隆 GitHub 仓库")
def clone_repository(req: StageRequest):
    return _run_stage("CLONE_REPOSITORY", req, repository_cloner.repository_cloner)


@router.post("/v1/repositories/index", response_model=StageSuccessResponse, summary="代码 AST 索引")
def index_code(req: StageRequest):
    return _run_stage("INDEX_CODE", req, code_indexer.code_indexer)


@router.post("/v1/mappings/generate", response_model=StageSuccessResponse, summary="生成概念—代码映射")
def generate_mappings(req: StageRequest):
    return _run_stage("MAP_CONCEPTS", req, mapping_analyzer.mapping_analyzer)
