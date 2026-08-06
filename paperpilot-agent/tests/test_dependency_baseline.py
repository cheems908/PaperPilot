"""T5-00 Python 运行环境和 TestClient 可复现基线。"""

from importlib.metadata import PackageNotFoundError, version

from fastapi import FastAPI
from fastapi.testclient import TestClient


EXPECTED_VERSIONS = {
    "fastapi": "0.115.14",
    "starlette": "0.46.2",
    "httpx": "0.28.1",
    "anyio": "4.8.0",
    "pytest": "8.3.5",
    "pytest-asyncio": "0.25.3",
    "uvloop": "0.22.1",
}


def test_runtime_dependencies_are_pinned_and_httpx2_is_not_mixed_in():
    assert {package: version(package) for package in EXPECTED_VERSIONS} == EXPECTED_VERSIONS
    try:
        version("httpx2")
    except PackageNotFoundError:
        pass
    else:
        raise AssertionError("httpx2 不应与当前 Starlette/httpx 基线混装")


def test_testclient_uvloop_backend_completes_request():
    app = FastAPI()

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    client = TestClient(app, backend_options={"use_uvloop": True})
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
