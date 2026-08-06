"""阶段服务通用契约单测（不启动 HTTP）：simulate.failure 短路 + simulate.delay.

注：四个阶段（PARSE/CLONE/INDEX/MAP）均已接入真实实现，各自的确定性/行为在
test_paper_parser / test_repository_cloner / test_code_indexer / test_mapping_analyzer_rules 覆盖。
"""
import time

import pytest

from app.core.config import SimulateOptions
from app.core.errors import StageServiceError
from app.schemas.common import StageRequest
from app.services.code_indexer import code_indexer
from app.services.mapping_analyzer import mapping_analyzer
from app.services.paper_parser import paper_parser
from app.services.repository_cloner import repository_cloner


def _req(stage: str = "PARSE_PAPER") -> StageRequest:
    return StageRequest(taskId=7, stageExecutionId=34, stage=stage, attempt=1)


@pytest.mark.parametrize("service", [
    paper_parser, repository_cloner, code_indexer, mapping_analyzer,
])
def test_simulate_failure_short_circuits_before_input(service):
    with pytest.raises(StageServiceError) as exc:
        service.process(_req(service.__class__.__name__), SimulateOptions(failure=True))
    assert exc.value.error_code == "STAGE_FAILED"
    assert exc.value.retryable is True


def test_simulate_delay_is_respected():
    start = time.monotonic()
    with pytest.raises(StageServiceError):  # 延迟在输入校验前执行，随后因缺输入报错
        mapping_analyzer.process(_req("MAP_CONCEPTS"), SimulateOptions(delay_ms=300))
    assert time.monotonic() - start >= 0.25
