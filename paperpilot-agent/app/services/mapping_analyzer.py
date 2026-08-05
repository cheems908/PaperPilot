"""概念—代码映射服务：初期为确定性 fake（T3-05 接入规则版映射）. 不依赖 FastAPI，可纯单测。"""
import time

from app.core.config import SimulateOptions
from app.core.errors import StageErrorCode, StageServiceError
from app.schemas.common import StageRequest, StageSuccessResponse
from app.schemas.mapping import MappingOutput


class MappingAnalyzer:
    def process(self, req: StageRequest, simulate: SimulateOptions | None = None) -> StageSuccessResponse:
        simulate = simulate or SimulateOptions()
        if simulate.failure:
            raise StageServiceError(StageErrorCode.STAGE_FAILED, "simulated mapping failure", retryable=True)
        if simulate.delay_ms > 0:
            time.sleep(simulate.delay_ms / 1000.0)
        output = MappingOutput(conceptCount=2, mappingCount=2)
        return StageSuccessResponse(output=output.model_dump(), workerVersion="fake-1.0.0")


mapping_analyzer = MappingAnalyzer()
