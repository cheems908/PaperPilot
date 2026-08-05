"""受控仓库克隆单测：URL 校验、安全克隆（本地 git 仓库替代网络）、限制、清理与幂等."""
import subprocess
from pathlib import Path

import pytest

from app.core.errors import StageServiceError
from app.schemas.common import StageRequest
from app.services.repository_cloner import RepositoryCloner


def _init_local_repo(path: Path, files: dict[str, bytes]) -> None:
    path.mkdir(parents=True)
    for name, content in files.items():
        (path / name).parent.mkdir(parents=True, exist_ok=True)
        (path / name).write_bytes(content)
    subprocess.run(["git", "-C", str(path), "init", "-q"], check=True)
    subprocess.run(["git", "-C", str(path), "add", "-A"], check=True)
    subprocess.run(["git", "-C", str(path), "-c", "user.email=t@t", "-c", "user.name=t",
                    "commit", "-q", "-m", "c"], check=True)


class _RedirectCloner(RepositoryCloner):
    """把 github.com 远端重定向到本地仓库，测试真实 git 克隆流程（不访问网络）。"""

    def __init__(self, local_repo: Path, **kwargs):
        super().__init__(**kwargs)
        self.local_repo = str(local_repo)
        self.clone_calls = 0

    def _run_git(self, *args, timeout=None):
        if args and args[0] == "clone":
            self.clone_calls += 1
        args = [self.local_repo if a.startswith("https://github.com/") else a for a in args]
        return subprocess.run(["git", *args], capture_output=True, text=True,
                              timeout=timeout, check=False)


def _req(url: str, branch: str | None = None) -> StageRequest:
    source = {"githubUrl": url}
    if branch:
        source["branch"] = branch
    return StageRequest(taskId=7, stageExecutionId=34, stage="CLONE_REPOSITORY", attempt=1,
                        input={"source": source})


def _cloner(tmp_path: Path, local_repo: Path, **kwargs) -> _RedirectCloner:
    kwargs.setdefault("workspace_root", str(tmp_path / "ws"))
    return _RedirectCloner(local_repo, **kwargs)


# ── URL 校验 ─────────────────────────────────────────────────────────────

@pytest.mark.parametrize("url,expected", [
    ("https://github.com/paperpilot/patchtst", "https://github.com/paperpilot/patchtst"),
    ("https://github.com/paperpilot/patchtst.git", "https://github.com/paperpilot/patchtst"),
    ("https://www.github.com/owner/repo", "https://github.com/owner/repo"),
])
def test_valid_urls_normalized(tmp_path: Path, url, expected):
    repo = tmp_path / "local"
    _init_local_repo(repo, {"a.txt": b"a"})
    cloner = _cloner(tmp_path, repo)
    assert cloner._validate_github_url(url) == expected


@pytest.mark.parametrize("url", [
    "git@github.com:owner/repo.git",      # SSH shorthand
    "ssh://git@github.com/owner/repo",    # SSH scheme
    "file:///etc/passwd",                 # local file
    "https://10.0.0.1/owner/repo",        # 内网 IP
    "https://localhost/owner/repo",       # localhost
    "https://gitlab.com/owner/repo",      # 非 github host
    "https://github.com:8080/owner/repo",  # 端口
    "https://user:pass@github.com/owner/repo",  # userinfo
    "https://github.com/owner/repo?x=1",  # query
    "https://github.com/owner",           # 缺 repo
    "https://github.com/owner/repo/extra",  # 多余路径段
    "https://github.com/o wner/repo",     # 非法字符
])
def test_invalid_urls_rejected(tmp_path: Path, url):
    repo = tmp_path / "local"
    _init_local_repo(repo, {"a.txt": b"a"})
    cloner = _cloner(tmp_path, repo)
    with pytest.raises(StageServiceError) as exc:
        cloner._validate_github_url(url)
    assert exc.value.error_code == "INVALID_GITHUB_URL"
    assert exc.value.retryable is False


# ── 合法克隆 ─────────────────────────────────────────────────────────────

def test_legal_repo_returns_40_char_sha(tmp_path: Path):
    repo = tmp_path / "remote"
    _init_local_repo(repo, {"code.py": b"def f():\n    pass\n"})
    cloner = _cloner(tmp_path, repo)

    resp = cloner.process(_req("https://github.com/paperpilot/patchtst"))

    out = resp.output
    assert out["canonicalUrl"] == "https://github.com/paperpilot/patchtst"
    assert len(out["commitSha"]) == 40
    assert int(out["commitSha"], 16) >= 0  # 合法 40 位 hex
    assert out["workspaceRef"] == "task-7/stage-34"


def test_repeated_clone_is_idempotent_and_reuses(tmp_path: Path):
    repo = tmp_path / "remote"
    _init_local_repo(repo, {"a.py": b"print(1)\n"})
    cloner = _cloner(tmp_path, repo)

    first = cloner.process(_req("https://github.com/paperpilot/patchtst"))
    second = cloner.process(_req("https://github.com/paperpilot/patchtst"))

    assert first.output["commitSha"] == second.output["commitSha"]
    assert first.output["workspaceRef"] == second.output["workspaceRef"]
    assert cloner.clone_calls == 1  # 第二次复用已发布目录，未重新克隆


def test_failure_leaves_no_workspace_residue(tmp_path: Path):
    missing = tmp_path / "does-not-exist"
    cloner = _cloner(tmp_path, missing)
    with pytest.raises(StageServiceError) as exc:
        cloner.process(_req("https://github.com/paperpilot/patchtst"))
    assert exc.value.error_code == "REPOSITORY_NOT_FOUND"
    assert exc.value.retryable is False
    # 无残留工作目录（含临时目录）
    ws = tmp_path / "ws"
    leftover = list(ws.rglob("*-tmp*")) if ws.exists() else []
    assert leftover == []


# ── 限制 ─────────────────────────────────────────────────────────────────

def test_repo_size_limit(tmp_path: Path):
    repo = tmp_path / "remote"
    _init_local_repo(repo, {"big.bin": b"x" * 300})
    cloner = _cloner(tmp_path, repo, max_repo_bytes=100)
    with pytest.raises(StageServiceError) as exc:
        cloner.process(_req("https://github.com/paperpilot/patchtst"))
    assert exc.value.error_code == "REPOSITORY_TOO_LARGE"
    assert exc.value.retryable is False


def test_file_count_limit(tmp_path: Path):
    repo = tmp_path / "remote"
    _init_local_repo(repo, {f"f{i}.py": b"x" for i in range(5)})
    cloner = _cloner(tmp_path, repo, max_repo_files=3)
    with pytest.raises(StageServiceError) as exc:
        cloner.process(_req("https://github.com/paperpilot/patchtst"))
    assert exc.value.error_code == "REPOSITORY_TOO_LARGE"


def test_single_file_size_limit(tmp_path: Path):
    repo = tmp_path / "remote"
    _init_local_repo(repo, {"huge.bin": b"x" * 500})
    cloner = _cloner(tmp_path, repo, max_file_bytes=100)
    with pytest.raises(StageServiceError) as exc:
        cloner.process(_req("https://github.com/paperpilot/patchtst"))
    assert exc.value.error_code == "REPOSITORY_TOO_LARGE"


def test_clone_timeout_classified_retryable(tmp_path: Path):
    repo = tmp_path / "remote"
    _init_local_repo(repo, {"a.py": b"x"})

    class _TimeoutCloner(_RedirectCloner):
        def _run_git(self, *args, timeout=None):
            if args and args[0] == "clone":
                raise subprocess.TimeoutExpired(cmd=args, timeout=timeout)
            return super()._run_git(*args, timeout=timeout)

    cloner = _TimeoutCloner(str(repo), workspace_root=str(tmp_path / "ws"))
    with pytest.raises(StageServiceError) as exc:
        cloner.process(_req("https://github.com/paperpilot/patchtst"))
    assert exc.value.error_code == "CLONE_TIMEOUT"
    assert exc.value.retryable is True


# ── workspace resolver ───────────────────────────────────────────────────

def test_resolve_workspace_resolves_published_and_rejects_escape(tmp_path: Path):
    repo = tmp_path / "remote"
    _init_local_repo(repo, {"a.py": b"x"})
    cloner = _cloner(tmp_path, repo)
    cloner.process(_req("https://github.com/paperpilot/patchtst"))

    resolved = cloner.resolve_workspace("task-7/stage-34")
    assert resolved.is_dir()
    assert resolved.is_relative_to(cloner.workspace_root)

    with pytest.raises(StageServiceError) as exc:
        cloner.resolve_workspace("../escape")
    assert exc.value.error_code == "INVALID_WORKSPACE_REF"
    with pytest.raises(StageServiceError) as exc:
        cloner.resolve_workspace("/abs/path")
    assert exc.value.error_code == "INVALID_WORKSPACE_REF"
    with pytest.raises(StageServiceError) as exc:
        cloner.resolve_workspace("task-7/stage-9999")
    assert exc.value.error_code == "INVALID_WORKSPACE_REF"
