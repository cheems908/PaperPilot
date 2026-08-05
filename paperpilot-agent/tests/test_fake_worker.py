"""假 Worker 的固定确定性响应契约测试."""
import time

from fastapi.testclient import TestClient

from fake_worker import app

client = TestClient(app)

STAGE_ENDPOINTS = [
    ("/internal/v1/papers/parse", "PARSE_PAPER"),
    ("/internal/v1/repositories/clone", "CLONE_REPOSITORY"),
    ("/internal/v1/repositories/index", "INDEX_CODE"),
    ("/internal/v1/mappings/generate", "MAP_CONCEPTS"),
]


def test_internal_health():
    r = client.get("/internal/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


def test_legacy_health_still_works():
    assert client.get("/health").status_code == 200


def test_four_stage_endpoints_return_valid_response():
    for path, stage in STAGE_ENDPOINTS:
        r = client.post(path, json={"schemaVersion": 1, "stage": stage})
        assert r.status_code == 200
        body = r.json()
        assert body["schemaVersion"] == 1
        assert body["success"] is True
        assert body["output"] is not None
        assert body["artifacts"] == []
        assert body["workerVersion"] == "fake-1.0.0"


def test_simulate_failure_only_when_enabled():
    # 未开启 simulate.failure → 正常成功
    ok = client.post("/internal/v1/papers/parse", json={})
    assert ok.status_code == 200
    assert ok.json()["success"] is True
    # 开启 → 模拟故障（500）
    failed = client.post("/internal/v1/papers/parse?simulate.failure=true", json={})
    assert failed.status_code == 500


def test_simulate_delay_only_when_enabled():
    start = time.monotonic()
    client.post("/internal/v1/papers/parse?simulate.delayMs=300", json={})
    assert time.monotonic() - start >= 0.25
