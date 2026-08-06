"""内部 API 通用契约（TestClient）：健康检查、统一错误、OpenAPI、模拟参数.

四个阶段接口的具体契约分别在 test_internal_paper_api / test_internal_repository_api 覆盖。
"""
from fastapi.testclient import TestClient

from app.core.config import settings
from app.main import app

client = TestClient(app)

INTERNAL_PATHS = [
    "/internal/health",
    "/internal/v1/papers/parse",
    "/internal/v1/repositories/clone",
    "/internal/v1/repositories/index",
    "/internal/v1/mappings/generate",
]


def _payload(stage: str) -> dict:
    return {
        "schemaVersion": 1, "requestId": "req-1", "taskId": 7,
        "stageExecutionId": 34, "stage": stage, "attempt": 1, "input": {},
    }


def test_internal_health():
    r = client.get("/internal/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


def test_legacy_health_still_works():
    assert client.get("/health").status_code == 200


def test_simulate_failure_returns_uniform_error_without_stack():
    settings.simulate_failure = True
    try:
        r = client.post("/internal/v1/papers/parse", json=_payload("PARSE_PAPER"))
        assert r.status_code == 500
        body = r.json()
        assert body["success"] is False
        assert body["errorCode"] == "STAGE_FAILED"
        assert body["retryable"] is True
        assert "Traceback" not in r.text
    finally:
        settings.simulate_failure = False


def test_missing_required_field_is_422():
    r = client.post("/internal/v1/papers/parse", json={"schemaVersion": 1, "stage": "PARSE_PAPER"})
    assert r.status_code == 422


def test_openapi_has_internal_paths_and_deprecated_analyze():
    schema = client.get("/openapi.json").json()
    paths = schema["paths"]
    for path in INTERNAL_PATHS:
        assert path in paths
    assert paths["/api/analyze"]["post"].get("deprecated") is True
