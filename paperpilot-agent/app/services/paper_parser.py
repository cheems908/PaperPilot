"""论文解析服务：初期为确定性 fake（T3-02 接入 GROBID/PyMuPDF 真实实现）.

不依赖 FastAPI Request/Response，可纯单元测试。
"""
import time

from app.core.config import SimulateOptions
from app.core.errors import StageErrorCode, StageServiceError
from app.schemas.common import StageRequest, StageSuccessResponse
from app.schemas.paper import PaperParseOutput


class PaperParser:
    def process(self, req: StageRequest, simulate: SimulateOptions | None = None) -> StageSuccessResponse:
        simulate = simulate or SimulateOptions()
        if simulate.failure:
            raise StageServiceError(StageErrorCode.STAGE_FAILED, "simulated parse failure", retryable=True)
        if simulate.delay_ms > 0:
            time.sleep(simulate.delay_ms / 1000.0)
        output = PaperParseOutput(title="fake-paper", sections=3, authors=["fake author"])
        return StageSuccessResponse(output=output.model_dump(), workerVersion="fake-1.0.0")


paper_parser = PaperParser()
