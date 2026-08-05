"""阶段服务纯单测（不启动 HTTP）：确定性输出、重复调用一致、模拟故障抛统一异常."""
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


def test_paper_parser_returns_deterministic_output():
    a = paper_parser.process(_req())
    b = paper_parser.process(_req())
    assert a.success is True
    assert a.output == {"title": "fake-paper", "sections": 3, "authors": ["fake author"]}
    assert a.workerVersion == "fake-1.0.0"
    assert a == b  # 同一输入重复调用结构一致


def test_all_four_services_deterministic():
    for service, stage in [
        (paper_parser, "PARSE_PAPER"),
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
        paper_parser.process(_req(), SimulateOptions(failure=True))
    assert exc.value.error_code == "STAGE_FAILED"
    assert exc.value.retryable is True


def test_simulate_delay_is_respected():
    import time

    start = time.monotonic()
    paper_parser.process(_req(), SimulateOptions(delay_ms=300))
    assert time.monotonic() - start >= 0.25
