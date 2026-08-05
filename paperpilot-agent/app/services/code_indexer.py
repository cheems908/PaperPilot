"""代码索引服务：初期为确定性 fake（T3-04 接入 AST 索引）. 不依赖 FastAPI，可纯单测。"""
import time

from app.core.config import SimulateOptions
from app.core.errors import StageErrorCode, StageServiceError
from app.schemas.common import StageRequest, StageSuccessResponse
from app.schemas.repository import IndexOutput


class CodeIndexer:
    def process(self, req: StageRequest, simulate: SimulateOptions | None = None) -> StageSuccessResponse:
        simulate = simulate or SimulateOptions()
        if simulate.failure:
            raise StageServiceError(StageErrorCode.STAGE_FAILED, "simulated index failure", retryable=True)
        if simulate.delay_ms > 0:
            time.sleep(simulate.delay_ms / 1000.0)
        output = IndexOutput(repo="fake-repo", symbolCount=5)
        return StageSuccessResponse(output=output.model_dump(), workerVersion="fake-1.0.0")


code_indexer = CodeIndexer()
