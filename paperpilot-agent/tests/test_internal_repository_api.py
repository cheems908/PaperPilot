"""CLONE_REPOSITORY 内部接口契约测试（TestClient + 本地 git 仓库 + 重定向克隆）. """
import subprocess
from pathlib import Path

from fastapi.testclient import TestClient

from app.main import app
from app.services.repository_cloner import repository_cloner
from tests.test_repository_cloner import _init_local_repo

client = TestClient(app)


def _payload(url: str) -> dict:
    return {
        "schemaVersion": 1, "requestId": "req-1", "taskId": 7,
        "stageExecutionId": 34, "stage": "CLONE_REPOSITORY", "attempt": 1,
        "input": {"source": {"githubUrl": url}},
    }


def test_clone_endpoint_returns_commit_sha(tmp_path: Path, monkeypatch):
    repo = tmp_path / "remote"
    _init_local_repo(repo, {"code.py": b"def f():\n    pass\n"})

    monkeypatch.setattr(repository_cloner, "workspace_root", tmp_path / "ws")
    monkeypatch.setattr(repository_cloner, "_run_git", _redirect(repo, repository_cloner))

    r = client.post("/internal/v1/repositories/clone", json=_payload("https://github.com/paperpilot/patchtst"))

    assert r.status_code == 200, r.text
    out = r.json()["output"]
    assert len(out["commitSha"]) == 40
    assert out["canonicalUrl"] == "https://github.com/paperpilot/patchtst"
    assert out["workspaceRef"] == "task-7/stage-34"


def test_clone_endpoint_rejects_invalid_url(tmp_path: Path, monkeypatch):
    monkeypatch.setattr(repository_cloner, "workspace_root", tmp_path / "ws")

    r = client.post("/internal/v1/repositories/clone", json=_payload("git@github.com:owner/repo.git"))

    assert r.status_code == 400
    body = r.json()
    assert body["success"] is False
    assert body["errorCode"] == "INVALID_GITHUB_URL"
    assert body["retryable"] is False
    assert "Traceback" not in r.text


def _redirect(local_repo: Path, cloner):
    def run(*args, timeout=None):
        args = [str(local_repo) if a.startswith("https://github.com/") else a for a in args]
        return subprocess.run(["git", *args], capture_output=True, text=True, timeout=timeout, check=False)
    return run
