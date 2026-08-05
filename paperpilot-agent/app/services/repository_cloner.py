"""受控 GitHub 仓库克隆：仅接受规范化 https://github.com/{owner}/{repo}，安全浅克隆并固定 commit SHA.

- git 一律用参数数组调用，不拼接 shell 命令；
- 目录由服务端按 task/stage 生成，克隆成功后原子重命名发布，失败清理无残留；
- 重复相同 stageExecutionId 复用已发布目录（损坏则安全重建）；
- workspaceRef 为逻辑引用（相对 workspace_root），不泄露宿主绝对路径；
- 限制：克隆超时、仓库总体积、文件数、单文件大小、总并发。
不运行仓库代码，不支持私有仓库 / Token / GitLab / submodule / LFS。
"""
import os
import re
import shutil
import subprocess
import threading
import time
import uuid
from pathlib import Path
from urllib.parse import urlparse

from app.core.config import SimulateOptions, settings
from app.core.errors import StageErrorCode, StageServiceError
from app.schemas.common import StageRequest, StageSuccessResponse
from app.schemas.repository import CloneOutput

_SHA40 = re.compile(r"^[0-9a-f]{40}$")
_OWNER_REPO = re.compile(r"^[A-Za-z0-9_.-]+$")


class RepositoryCloner:
    def __init__(self, workspace_root: str | None = None, clone_timeout_seconds: float | None = None,
                 max_repo_bytes: int | None = None, max_repo_files: int | None = None,
                 max_file_bytes: int | None = None, max_concurrent_clones: int | None = None):
        self.workspace_root = Path(workspace_root or settings.workspace_root).resolve()
        self.clone_timeout_seconds = clone_timeout_seconds or settings.clone_timeout_seconds
        self.max_repo_bytes = max_repo_bytes or settings.max_repo_bytes
        self.max_repo_files = max_repo_files or settings.max_repo_files
        self.max_file_bytes = max_file_bytes or settings.max_file_bytes
        self._semaphore = threading.BoundedSemaphore(max_concurrent_clones or settings.max_concurrent_clones)

    def process(self, req: StageRequest, simulate: SimulateOptions | None = None) -> StageSuccessResponse:
        simulate = simulate or SimulateOptions()
        if simulate.failure:
            raise StageServiceError(StageErrorCode.STAGE_FAILED, "simulated clone failure", retryable=True)
        if simulate.delay_ms > 0:
            time.sleep(simulate.delay_ms / 1000.0)

        github_url, branch = self._input_ref(req)
        canonical = self._validate_github_url(github_url)
        workspace_ref, commit_sha = self._clone(canonical, branch, req.taskId, req.stageExecutionId)
        output = CloneOutput(canonicalUrl=canonical, commitSha=commit_sha, workspaceRef=workspace_ref)
        return StageSuccessResponse(output=output.model_dump(), workerVersion="0.3.0-repo")

    # ── 输入与 URL 校验 ──────────────────────────────────────────────────

    def _input_ref(self, req: StageRequest) -> tuple[str, str | None]:
        raw = req.input if isinstance(req.input, dict) else {}
        source = raw.get("source") if isinstance(raw.get("source"), dict) else raw
        github_url = source.get("githubUrl")
        if not github_url or not isinstance(github_url, str):
            raise StageServiceError(StageErrorCode.INVALID_GITHUB_URL, "missing githubUrl",
                                    retryable=False, status_code=400)
        branch = source.get("branch")
        return github_url, (branch if isinstance(branch, str) and branch else None)

    def _validate_github_url(self, url: str) -> str:
        """仅接受 https://github.com/{owner}/{repo}，拒绝 SSH/file/任意 host/userinfo/端口/内网. """
        if url != url.strip():
            raise StageServiceError(StageErrorCode.INVALID_GITHUB_URL, "url must not have surrounding whitespace",
                                    retryable=False, status_code=400)
        parsed = urlparse(url)
        if parsed.scheme != "https":
            raise StageServiceError(StageErrorCode.INVALID_GITHUB_URL, f"scheme must be https: {url}",
                                    retryable=False, status_code=400)
        if parsed.hostname not in ("github.com", "www.github.com"):
            raise StageServiceError(StageErrorCode.INVALID_GITHUB_URL, f"host must be github.com: {url}",
                                    retryable=False, status_code=400)
        if parsed.username or parsed.password:
            raise StageServiceError(StageErrorCode.INVALID_GITHUB_URL, "userinfo is not allowed",
                                    retryable=False, status_code=400)
        if parsed.port is not None:
            raise StageServiceError(StageErrorCode.INVALID_GITHUB_URL, "port is not allowed",
                                    retryable=False, status_code=400)
        if parsed.query or parsed.fragment:
            raise StageServiceError(StageErrorCode.INVALID_GITHUB_URL, "query/fragment not allowed",
                                    retryable=False, status_code=400)
        path = parsed.path.rstrip("/")
        if path.endswith(".git"):
            path = path[:-4]
        parts = path.strip("/").split("/")
        if len(parts) != 2 or not all(_OWNER_REPO.fullmatch(p) for p in parts):
            raise StageServiceError(StageErrorCode.INVALID_GITHUB_URL, f"invalid owner/repo: {url}",
                                    retryable=False, status_code=400)
        return f"https://github.com/{parts[0]}/{parts[1]}"

    # ── 克隆 ─────────────────────────────────────────────────────────────

    def _clone(self, url: str, branch: str | None, task_id: int, stage_execution_id: int) -> tuple[str, str]:
        workspace_ref = f"task-{task_id}/stage-{stage_execution_id}"
        published = self.workspace_root / workspace_ref

        # 幂等：已发布则复用固定结果；损坏则安全重建
        if published.is_dir():
            commit = self._rev_parse(published)
            if commit:
                return workspace_ref, commit
            shutil.rmtree(published, ignore_errors=True)

        task_dir = published.parent
        temp = task_dir / f".{stage_execution_id}-{uuid.uuid4().hex[:8]}-tmp"
        try:
            with self._semaphore:
                temp.mkdir(parents=True)
                self._git_clone(url, branch, temp)
                self._check_limits(temp)
                commit = self._rev_parse(temp)
                if not commit:
                    raise StageServiceError(StageErrorCode.GITHUB_TEMPORARY_FAILURE,
                                            "rev-parse HEAD failed", retryable=True, status_code=500)
                os.replace(temp, published)
            return workspace_ref, commit
        except Exception:
            shutil.rmtree(temp, ignore_errors=True)
            raise

    def _git_clone(self, url: str, branch: str | None, target: Path) -> None:
        args = ["clone", "--depth", "1"]
        if branch:
            args += ["--branch", branch]
        args += [url, str(target)]
        try:
            proc = self._run_git(*args, timeout=self.clone_timeout_seconds)
        except subprocess.TimeoutExpired as e:
            raise StageServiceError(StageErrorCode.CLONE_TIMEOUT, f"clone timed out: {e}",
                                    retryable=True, status_code=504) from e
        if proc.returncode != 0:
            raise self._classify_clone_error(proc.stderr or proc.stdout)

    def _classify_clone_error(self, message: str) -> StageServiceError:
        s = message.lower()
        if "repository not found" in s or "does not appear to be a git repository" in s or "not exist" in s:
            return StageServiceError(StageErrorCode.REPOSITORY_NOT_FOUND, message.strip(),
                                     retryable=False, status_code=404)
        if "authentication" in s or "access denied" in s or "could not read" in s or "protocol" in s:
            return StageServiceError(StageErrorCode.UNSUPPORTED_REPOSITORY, message.strip(),
                                     retryable=False, status_code=422)
        return StageServiceError(StageErrorCode.GITHUB_TEMPORARY_FAILURE, message.strip(),
                                 retryable=True, status_code=502)

    def _check_limits(self, repo_dir: Path) -> None:
        file_count = 0
        total = 0
        for p in repo_dir.rglob("*"):
            if p.is_file():
                file_count += 1
                size = p.stat().st_size
                total += size
                if size > self.max_file_bytes:
                    raise StageServiceError(StageErrorCode.REPOSITORY_TOO_LARGE,
                                            f"file too large: {p.name}", retryable=False, status_code=413)
        if file_count > self.max_repo_files:
            raise StageServiceError(StageErrorCode.REPOSITORY_TOO_LARGE, "too many files",
                                    retryable=False, status_code=413)
        if total > self.max_repo_bytes:
            raise StageServiceError(StageErrorCode.REPOSITORY_TOO_LARGE, "repository too large",
                                    retryable=False, status_code=413)

    def _rev_parse(self, repo_dir: Path) -> str | None:
        proc = self._run_git("-C", str(repo_dir), "rev-parse", "HEAD")
        if proc.returncode != 0:
            return None
        sha = proc.stdout.strip()
        return sha if _SHA40.fullmatch(sha) else None

    def _run_git(self, *args, timeout: float | None = None) -> subprocess.CompletedProcess:
        """git 一律参数数组调用，不经过 shell；测试可覆写以重定向远端。"""
        return subprocess.run(["git", *args], capture_output=True, text=True,
                              timeout=timeout, check=False)

    # ── 受控 workspace 解析（INDEX_CODE 使用）────────────────────────────

    def resolve_workspace(self, workspace_ref: str) -> Path:
        """把逻辑 workspaceRef 解析为 workspace_root 内的绝对路径；拒绝逃逸。"""
        if not isinstance(workspace_ref, str) or not workspace_ref:
            raise StageServiceError(StageErrorCode.INVALID_WORKSPACE_REF, "empty workspaceRef",
                                    retryable=False, status_code=400)
        raw = Path(workspace_ref)
        if raw.is_absolute():
            raise StageServiceError(StageErrorCode.INVALID_WORKSPACE_REF, "absolute path not allowed",
                                    retryable=False, status_code=400)
        resolved = (self.workspace_root / raw).resolve()
        if not resolved.is_relative_to(self.workspace_root):
            raise StageServiceError(StageErrorCode.INVALID_WORKSPACE_REF, "workspaceRef escapes root",
                                    retryable=False, status_code=400)
        if not resolved.is_dir():
            raise StageServiceError(StageErrorCode.INVALID_WORKSPACE_REF, "workspace not found",
                                    retryable=False, status_code=404)
        return resolved


repository_cloner = RepositoryCloner()
