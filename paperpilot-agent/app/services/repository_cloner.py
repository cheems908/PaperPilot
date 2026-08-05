"""仓库克隆服务：初期为确定性 fake（T3-03 接入受控 Git 克隆）. 不依赖 FastAPI，可纯单测。"""
import time

from app.core.config import SimulateOptions
from app.core.errors import StageErrorCode, StageServiceError
from app.schemas.common import StageRequest, StageSuccessResponse
from app.schemas.repository import CloneOutput


class RepositoryCloner:
    def process(self, req: StageRequest, simulate: SimulateOptions | None = None) -> StageSuccessResponse:
        simulate = simulate or SimulateOptions()
        if simulate.failure:
            raise StageServiceError(StageErrorCode.STAGE_FAILED, "simulated clone failure", retryable=True)
        if simulate.delay_ms > 0:
            time.sleep(simulate.delay_ms / 1000.0)
        output = CloneOutput(
            repo="fake-repo",
            commit="fixed-commit-0000000000000000000000000000000000000000",
        )
        return StageSuccessResponse(output=output.model_dump(), workerVersion="fake-1.0.0")


repository_cloner = RepositoryCloner()
