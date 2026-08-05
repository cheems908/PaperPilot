"""AST 代码索引单测：真实符号提取、语法错误容错、忽略目录/超大/二进制、排序确定性."""
import shutil
from pathlib import Path

import pytest

from app.core.errors import StageServiceError
from app.schemas.common import StageRequest
from app.services.code_indexer import CodeIndexer

FIXTURES = Path(__file__).parent / "fixtures" / "repos"


def _workspace(tmp_path: Path, repo_name: str) -> Path:
    """把 fixture 仓库复制到 workspace_root/task-7/stage-34（满足受控 resolver 的已发布目录）。"""
    ws = tmp_path / "ws"
    target = ws / "task-7" / "stage-34"
    shutil.copytree(FIXTURES / repo_name, target)
    return ws


def _req(workspace_ref: str = "task-7/stage-34", commit_sha: str = "a" * 40) -> StageRequest:
    return StageRequest(taskId=7, stageExecutionId=34, stage="INDEX_CODE", attempt=1,
                        input={"source": {"workspaceRef": workspace_ref, "commitSha": commit_sha}})


def _indexer(ws: Path, **kwargs) -> CodeIndexer:
    kwargs.setdefault("workspace_root", str(ws))
    return CodeIndexer(**kwargs)


def _index(ws: Path, **kwargs):
    return _indexer(ws, **kwargs).process(_req())


def test_patchtst_sample_extracts_model_and_forward(tmp_path: Path):
    ws = _workspace(tmp_path, "patchtst-sample")

    resp = _index(ws)

    out = resp.output
    assert out["commitSha"] == "a" * 40
    assert out["stats"]["fileCount"] == 2  # model.py + utils.py
    files = {f["path"]: f for f in out["files"]}
    assert set(files) == {"model.py", "utils.py"}

    model_symbols = {s["qualifiedName"]: s for s in files["model.py"]["symbols"]}
    assert "PatchTST" in model_symbols and model_symbols["PatchTST"]["kind"] == "class"
    assert "PatchTST.forward" in model_symbols
    forward = model_symbols["PatchTST.forward"]
    assert forward["kind"] == "method"
    assert forward["signature"] == "def forward(self, x) -> torch.Tensor"
    assert forward["docstring"] == "Forward pass of PatchTST."
    assert forward["startLine"] > 0 and forward["endLine"] >= forward["startLine"]
    assert forward["parent"] == "PatchTST"
    assert "PatchTST.__init__" in model_symbols
    assert "train_model" in model_symbols and model_symbols["train_model"]["kind"] == "function"

    # async function 提取
    utils_symbols = {s["qualifiedName"]: s for s in files["utils.py"]["symbols"]}
    assert utils_symbols["fetch_data"]["kind"] == "async_function"


def test_ignored_dirs_and_binary_not_parsed(tmp_path: Path):
    ws = _workspace(tmp_path, "patchtst-sample")
    # 加入应被忽略的目录与二进制/非 py 文件
    (ws / "task-7" / "stage-34" / "venv").mkdir(parents=True)
    (ws / "task-7" / "stage-34" / "venv" / "lib.py").write_text("x = 1\n")
    (ws / "task-7" / "stage-34" / "data").mkdir()
    (ws / "task-7" / "stage-34" / "data" / "gen.py").write_text("y = 2\n")
    (ws / "task-7" / "stage-34" / "__pycache__").mkdir()
    (ws / "task-7" / "stage-34" / "readme.md").write_text("docs")

    resp = _index(ws)

    paths = {f["path"] for f in resp.output["files"]}
    assert paths == {"model.py", "utils.py"}  # venv/data/__pycache__ 未解析


def test_oversized_source_file_skipped_with_warning(tmp_path: Path):
    ws = _workspace(tmp_path, "patchtst-sample")
    big = ws / "task-7" / "stage-34" / "huge.py"
    big.write_text("# " + "x" * 2000 + "\n")

    resp = _index(ws, max_source_file_bytes=1000)

    paths = {f["path"] for f in resp.output["files"]}
    assert "huge.py" not in paths
    assert any("FILE_TOO_LARGE_SKIPPED" in w for w in resp.output["warnings"])


def test_syntax_error_warns_and_continues(tmp_path: Path):
    ws = _workspace(tmp_path, "broken")

    resp = _index(ws)

    paths = {f["path"] for f in resp.output["files"]}
    assert "bad.py" not in paths
    assert "good1.py" in paths
    assert any("SYNTAX_ERROR" in w for w in resp.output["warnings"])
    assert resp.output["stats"]["errorFileCount"] == 1


def test_failure_ratio_aborts_stage(tmp_path: Path):
    ws = _workspace(tmp_path, "broken")
    # 追加更多坏文件，把失败比例拉高到阈值以上
    repo = ws / "task-7" / "stage-34"
    for i in range(6):
        (repo / f"bad{i}.py").write_text("def broken(:\n    pass\n")

    with pytest.raises(StageServiceError) as exc:
        _index(ws, max_parse_failure_ratio=0.2)
    assert exc.value.error_code == "CODE_INDEX_FAILED"
    assert exc.value.retryable is False


def test_output_sorted_and_deterministic(tmp_path: Path):
    ws = _workspace(tmp_path, "patchtst-sample")

    a = _index(ws)
    b = _index(ws)

    assert a.output == b.output  # 重复执行结构一致
    paths = [f["path"] for f in a.output["files"]]
    assert paths == sorted(paths)  # 排序输出固定


def test_missing_input_rejected(tmp_path: Path):
    indexer = _indexer(tmp_path)
    req = StageRequest(taskId=7, stageExecutionId=34, stage="INDEX_CODE", attempt=1, input={})
    with pytest.raises(StageServiceError) as exc:
        indexer.process(req)
    assert exc.value.error_code == "INVALID_INDEX_INPUT"


def test_workspace_escape_rejected(tmp_path: Path):
    indexer = _indexer(tmp_path)
    with pytest.raises(StageServiceError) as exc:
        indexer.process(_req(workspace_ref="../escape"))
    assert exc.value.error_code == "INVALID_WORKSPACE_REF"
