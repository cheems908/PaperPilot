"""阶段服务纯单测（不启动 HTTP）：确定性输出、重复调用一致、模拟故障抛统一异常.

注：PARSE_PAPER 已接入真实解析（见 test_paper_parser.py）；此处覆盖其余三个确定性服务。
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


def _req(stage: str = "CLONE_REPOSITORY") -> StageRequest:
    return StageRequest(taskId=7, stageExecutionId=34, stage=stage, attempt=1)


def test_three_fake_services_deterministic():
    for service, stage in [
        (repository_cloner, "CLONE_REPOSITORY"),
        (code_indexer, "INDEX_CODE"),
        (mapping_analyzer, "MAP_CONCEPTS"),
    ]:
        r1 = service.process(_req(stage))
        r2 = service.process(_req(stage))
        assert r1.success is True
        assert r1.output is not None
        assert r1 == r2


def test_simulate_failure_raises_stage_error():
    with pytest.raises(StageServiceError) as exc:
        repository_cloner.process(_req(), SimulateOptions(failure=True))
    assert exc.value.error_code == "STAGE_FAILED"
    assert exc.value.retryable is True


def test_simulate_failure_on_paper_parser_short_circuits_before_input():
    # paper_parser 的 simulate.failure 在资源校验前触发
    with pytest.raises(StageServiceError) as exc:
        paper_parser.process(_req("PARSE_PAPER"), SimulateOptions(failure=True))
    assert exc.value.error_code == "STAGE_FAILED"


def test_simulate_delay_is_respected():
    start = time.monotonic()
    repository_cloner.process(_req(), SimulateOptions(delay_ms=300))
    assert time.monotonic() - start >= 0.25
