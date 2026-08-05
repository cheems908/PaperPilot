"""Python AST 代码索引：对固定 commit 的受控仓库提取可验证的文件/类/函数/方法/签名/行号.

- 通过 workspaceRef resolver 取得受控仓库目录并再次检查边界；
- 只解析 `.py`，忽略 .git/venv/data/weights/checkpoints/build/dist/缓存/生成目录；
- 用标准库 ast 提取 module/class/function/async function/method，生成 qualifiedName、
  signature、docstring、startLine/endLine、父符号；
- 文件路径统一为仓库相对 POSIX 路径；commitSha 来自 CLONE 输出；
- 单个语法错误文件记 warning 并继续，超过失败比例阈值才终止阶段；
- 排序输出固定，避免文件系统遍历顺序造成不确定响应。
不执行 import、不运行源码。
"""
import ast
import time
from pathlib import Path

from app.core.config import SimulateOptions, settings
from app.core.errors import StageErrorCode, StageServiceError
from app.schemas.common import StageRequest, StageSuccessResponse
from app.schemas.repository import FileSymbols, IndexOutput, Symbol

_IGNORED_DIRS = {
    ".git", ".hg", ".svn", "venv", ".venv", "env", "node_modules", "__pycache__",
    ".pytest_cache", ".mypy_cache", ".ruff_cache", "build", "dist", "data", "weights",
    "checkpoints", "cache", "logs", "outputs",
}
# 二进制/生成文件扩展名（即使以 .py 结尾也跳过，防御性）
_BINARY_SUFFIXES = {".pyc", ".pyo", ".pyd"}


class CodeIndexer:
    def __init__(self, workspace_root: str | None = None,
                 max_source_file_bytes: int | None = None,
                 max_parse_failure_ratio: float | None = None):
        self.workspace_root = Path(workspace_root or settings.workspace_root).resolve()
        self.max_source_file_bytes = max_source_file_bytes or settings.max_source_file_bytes
        self.max_parse_failure_ratio = max_parse_failure_ratio or settings.max_parse_failure_ratio

    def process(self, req: StageRequest, simulate: SimulateOptions | None = None) -> StageSuccessResponse:
        simulate = simulate or SimulateOptions()
        if simulate.failure:
            raise StageServiceError(StageErrorCode.STAGE_FAILED, "simulated index failure", retryable=True)
        if simulate.delay_ms > 0:
            time.sleep(simulate.delay_ms / 1000.0)

        workspace_ref, commit_sha = self._input_ref(req)
        repo_dir = self._resolve_workspace(workspace_ref)
        return self._index(repo_dir, workspace_ref, commit_sha)

    # ── 输入与受控解析 ────────────────────────────────────────────────────

    def _input_ref(self, req: StageRequest) -> tuple[str, str]:
        raw = req.input if isinstance(req.input, dict) else {}
        source = raw.get("source") if isinstance(raw.get("source"), dict) else raw
        workspace_ref = source.get("workspaceRef")
        commit_sha = source.get("commitSha")
        if not workspace_ref or not isinstance(workspace_ref, str) or not commit_sha:
            raise StageServiceError(StageErrorCode.INVALID_INDEX_INPUT,
                                    "missing workspaceRef/commitSha", retryable=False, status_code=400)
        return workspace_ref, commit_sha

    def _resolve_workspace(self, workspace_ref: str) -> Path:
        """把逻辑 workspaceRef 解析为 workspace_root 内的目录；再次检查边界（绝对路径/逃逸/不存在）。"""
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

    # ── 索引 ─────────────────────────────────────────────────────────────

    def _index(self, repo_dir: Path, workspace_ref: str, commit_sha: str) -> StageSuccessResponse:
        warnings: list[str] = []
        files: list[FileSymbols] = []
        total_files = 0
        error_files = 0
        symbol_count = 0

        for py_file in self._iter_py_files(repo_dir):
            rel = py_file.relative_to(repo_dir).as_posix()
            total_files += 1
            size = py_file.stat().st_size
            if size > self.max_source_file_bytes:
                warnings.append(f"FILE_TOO_LARGE_SKIPPED: {rel} ({size} bytes)")
                continue
            try:
                source = py_file.read_text(encoding="utf-8")
                symbols = _extract_symbols(source)
                files.append(FileSymbols(path=rel, symbols=symbols))
                symbol_count += len(symbols)
            except SyntaxError as e:
                error_files += 1
                warnings.append(f"SYNTAX_ERROR: {rel}:{e.lineno}")
            except (UnicodeDecodeError, ValueError) as e:
                error_files += 1
                warnings.append(f"PARSE_ERROR: {rel}: {e}")

        if total_files and error_files / total_files > self.max_parse_failure_ratio:
            raise StageServiceError(StageErrorCode.CODE_INDEX_FAILED,
                                    f"parse failure ratio {error_files}/{total_files} exceeds threshold",
                                    retryable=False, status_code=422)

        files.sort(key=lambda f: f.path)  # 排序输出固定，避免文件系统遍历顺序
        stats = {
            "fileCount": len(files),
            "symbolCount": symbol_count,
            "warningCount": len(warnings),
            "errorFileCount": error_files,
        }
        output = IndexOutput(repo=workspace_ref, commitSha=commit_sha, files=files,
                             warnings=warnings, stats=stats)
        return StageSuccessResponse(output=output.model_dump(), workerVersion="0.3.0-index")

    def _iter_py_files(self, repo_dir: Path):
        for p in sorted(repo_dir.rglob("*.py")):
            if not p.is_file() or p.suffix in _BINARY_SUFFIXES:
                continue
            parts = p.relative_to(repo_dir).parts
            if any(part in _IGNORED_DIRS for part in parts):
                continue
            yield p


def _extract_symbols(source: str) -> list[Symbol]:
    tree = ast.parse(source)
    symbols: list[Symbol] = []
    for stmt in tree.body:
        _walk_symbol(stmt, parent="", symbols=symbols, in_class=False)
    return symbols


def _walk_symbol(node, parent: str, symbols: list, in_class: bool) -> None:
    if isinstance(node, ast.ClassDef):
        qualified = f"{parent}.{node.name}" if parent else node.name
        symbols.append(Symbol(
            kind="class", name=node.name, qualifiedName=qualified,
            signature=f"class {node.name}",
            docstring=ast.get_docstring(node),
            startLine=node.lineno, endLine=node.end_lineno,
            parent=parent or None))
        for child in node.body:
            _walk_symbol(child, qualified, symbols, in_class=True)
    elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
        is_async = isinstance(node, ast.AsyncFunctionDef)
        kind = ("async_method" if is_async and in_class else
                "method" if in_class else
                "async_function" if is_async else "function")
        qualified = f"{parent}.{node.name}" if parent else node.name
        symbols.append(Symbol(
            kind=kind, name=node.name, qualifiedName=qualified,
            signature=_signature(node),
            docstring=ast.get_docstring(node),
            startLine=node.lineno, endLine=node.end_lineno,
            parent=parent or None))
        # 不递归进函数体（局部函数不作为符号）


def _signature(node) -> str:
    args = [a.arg for a in node.args.args]
    if node.args.vararg:
        args.append(f"*{node.args.vararg.arg}")
    if node.args.kwarg:
        args.append(f"**{node.args.kwarg.arg}")
    rendered = f"def {node.name}({', '.join(args)})"
    if node.returns is not None:
        rendered += f" -> {ast.unparse(node.returns)}"
    return rendered


code_indexer = CodeIndexer()
