"""内部阶段 API 契约测试（TestClient）：四接口 schema、重复一致性、统一错误、OpenAPI."""
from fastapi.testclient import TestClient

from app.core.config import settings
from app.main import app

client = TestClient(app)

# PARSE_PAPER 已接入真实解析（见 test_internal_paper_api.py），此处覆盖其余三个确定性接口
STAGE_ENDPOINTS = [
    ("/internal/v1/repositories/clone", "CLONE_REPOSITORY"),
    ("/internal/v1/repositories/index", "INDEX_CODE"),
    ("/internal/v1/mappings/generate", "MAP_CONCEPTS"),
]


def _payload(stage: str) -> dict:
    return {
        "schemaVersion": 1,
        "requestId": "req-1",
        "taskId": 7,
        "stageExecutionId": 34,
        "stage": stage,
        "attempt": 1,
        "input": {},
    }


def test_internal_health():
    r = client.get("/internal/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


def test_legacy_health_still_works():
    assert client.get("/health").status_code == 200


def test_four_stage_endpoints_return_valid_schema():
    for path, stage in STAGE_ENDPOINTS:
        r = client.post(path, json=_payload(stage))
        assert r.status_code == 200, r.text
        body = r.json()
        assert body["schemaVersion"] == 1
        assert body["success"] is True
        assert body["output"] is not None
        assert body["artifacts"] == []
        assert body["workerVersion"] == "fake-1.0.0"


def test_same_input_repeated_is_identical():
    payload = _payload("CLONE_REPOSITORY")
    a = client.post("/internal/v1/repositories/clone", json=payload)
    b = client.post("/internal/v1/repositories/clone", json=payload)
    assert a.status_code == 200
    assert a.json() == b.json()


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
    for path, _ in STAGE_ENDPOINTS:
        assert path in paths
    assert "/internal/health" in paths
    # 旧接口保留但标记 deprecated
    assert paths["/api/analyze"]["post"].get("deprecated") is True
