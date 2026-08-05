"""阶段服务纯单测（不启动 HTTP）：确定性输出、重复调用一致、模拟故障抛统一异常.

注：PARSE_PAPER（test_paper_parser.py）、CLONE_REPOSITORY（test_repository_cloner.py）、
INDEX_CODE（test_code_indexer.py）已接入真实实现；此处覆盖最后剩余确定性服务 MAP_CONCEPTS。
"""
import time

import pytest

from app.core.config import SimulateOptions
from app.core.errors import StageServiceError
from app.schemas.common import StageRequest
from app.services.mapping_analyzer import mapping_analyzer
from app.services.paper_parser import paper_parser


def _req(stage: str = "MAP_CONCEPTS") -> StageRequest:
    return StageRequest(taskId=7, stageExecutionId=34, stage=stage, attempt=1)


def test_mapping_service_deterministic():
    r1 = mapping_analyzer.process(_req())
    r2 = mapping_analyzer.process(_req())
    assert r1.success is True
    assert r1.output is not None
    assert r1 == r2


def test_simulate_failure_raises_stage_error():
    with pytest.raises(StageServiceError) as exc:
        mapping_analyzer.process(_req(), SimulateOptions(failure=True))
    assert exc.value.error_code == "STAGE_FAILED"
    assert exc.value.retryable is True


def test_simulate_failure_on_paper_parser_short_circuits_before_input():
    with pytest.raises(StageServiceError) as exc:
        paper_parser.process(_req("PARSE_PAPER"), SimulateOptions(failure=True))
    assert exc.value.error_code == "STAGE_FAILED"


def test_simulate_delay_is_respected():
    start = time.monotonic()
    mapping_analyzer.process(_req(), SimulateOptions(delay_ms=300))
    assert time.monotonic() - start >= 0.25
