"""统一请求 / 成功响应 / 错误响应模型（与 Java WorkerStageRequest/Response 兼容，均含 schemaVersion）."""
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field

SCHEMA_VERSION = 1


class StageRequest(BaseModel):
    """阶段执行请求：JSON 字段与 Java {@code WorkerStageRequest} 一一对应。"""

    schemaVersion: int = Field(default=SCHEMA_VERSION)
    requestId: Optional[str] = None
    taskId: int
    stageExecutionId: int
    stage: str
    attempt: int = Field(gt=0)
    input: Optional[Dict[str, Any]] = None


class StageSuccessResponse(BaseModel):
    """阶段成功响应：JSON 字段与 Java {@code WorkerStageResponse} 一致。"""

    schemaVersion: int = Field(default=SCHEMA_VERSION)
    success: bool = True
    output: Optional[Dict[str, Any]] = None
    artifacts: List[Dict[str, Any]] = Field(default_factory=list)
    metrics: Dict[str, Any] = Field(default_factory=dict)
    workerVersion: str = "fake-1.0.0"


class StageErrorResponse(BaseModel):
    """统一错误响应：稳定 errorCode + retryable + message，不含堆栈。"""

    schemaVersion: int = Field(default=SCHEMA_VERSION)
    success: bool = False
    errorCode: str
    retryable: bool
    message: str
