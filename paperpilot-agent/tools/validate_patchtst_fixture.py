"""校验 PatchTST 基准元数据、gold schema 和固定 commit 源码证据。"""

from __future__ import annotations

import argparse
import ast
import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator


FIXTURE_DIR = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "patchtst"


class FixtureValidationError(ValueError):
    """基准 fixture 不完整或证据与固定源码不一致。"""


def _load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _symbols(path: Path) -> dict[str, tuple[int, int]]:
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    result: dict[str, tuple[int, int]] = {}
    for node in tree.body:
        if isinstance(node, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            result[node.name] = (node.lineno, node.end_lineno or node.lineno)
            if isinstance(node, ast.ClassDef):
                for child in node.body:
                    if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)):
                        result[f"{node.name}.{child.name}"] = (
                            child.lineno,
                            child.end_lineno or child.lineno,
                        )
    return result


def validate_fixture(fixture_dir: Path = FIXTURE_DIR, repo: Path | None = None) -> dict[str, int]:
    schema = _load_json(fixture_dir / "gold.schema.json")
    gold = _load_json(fixture_dir / "gold.json")
    metadata = _load_json(fixture_dir / "benchmark.json")
    manifest = _load_json(fixture_dir / "source_manifest.json")

    errors = sorted(Draft202012Validator(schema).iter_errors(gold), key=lambda e: list(e.path))
    if errors:
        details = "; ".join(f"{list(error.path)}: {error.message}" for error in errors)
        raise FixtureValidationError(f"gold schema 校验失败: {details}")

    if gold["paper"]["sha256"] != metadata["paper"]["sha256"]:
        raise FixtureValidationError("gold 与 benchmark 的 PDF SHA-256 不一致")
    if gold["repository"]["commitSha"] != metadata["repository"]["commitSha"]:
        raise FixtureValidationError("gold 与 benchmark 的 repository commit 不一致")
    if manifest["commitSha"] != metadata["repository"]["commitSha"]:
        raise FixtureValidationError("source manifest commit 与 benchmark 不一致")

    files = manifest["files"]
    mapping_count = 0
    uncertain_count = 0
    for concept in gold["concepts"]:
        uncertain_count += concept["certainty"] != "CONFIRMED"
        if concept["certainty"] == "NO_EXPLICIT_IMPLEMENTATION" and concept["mappings"]:
            raise FixtureValidationError(f"{concept['id']} 声明无明确实现但包含源码映射")
        for mapping in concept["mappings"]:
            mapping_count += 1
            path = mapping["filePath"]
            symbol = mapping["qualifiedName"]
            if path not in files:
                raise FixtureValidationError(f"manifest 缺少文件: {path}")
            expected = files[path]["symbols"].get(symbol)
            if expected is None:
                raise FixtureValidationError(f"manifest 缺少符号: {path}:{symbol}")
            if [mapping["startLine"], mapping["endLine"]] != expected:
                raise FixtureValidationError(f"gold 行号与 manifest 不一致: {path}:{symbol}")

    if repo is not None:
        _validate_repository(repo.resolve(), metadata, files)

    return {
        "concepts": len(gold["concepts"]),
        "mappings": mapping_count,
        "uncertainConcepts": uncertain_count,
        "files": len(files),
    }


def _validate_repository(repo: Path, metadata: dict[str, Any], files: dict[str, Any]) -> None:
    if not (repo / ".git").exists():
        raise FixtureValidationError(f"不是 Git 仓库: {repo}")
    head = subprocess.run(
        ["git", "-C", str(repo), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    expected_commit = metadata["repository"]["commitSha"]
    if head != expected_commit:
        raise FixtureValidationError(f"仓库 commit 不匹配: expected={expected_commit}, actual={head}")

    for relative, expected_file in files.items():
        path = (repo / relative).resolve()
        if repo not in path.parents or not path.is_file():
            raise FixtureValidationError(f"源码文件不存在或越界: {relative}")
        if _sha256(path) != expected_file["sha256"]:
            raise FixtureValidationError(f"源码文件 checksum 不一致: {relative}")
        actual_symbols = _symbols(path)
        for name, lines in expected_file["symbols"].items():
            if actual_symbols.get(name) != tuple(lines):
                raise FixtureValidationError(
                    f"源码符号/行号不一致: {relative}:{name}, expected={lines}, actual={actual_symbols.get(name)}"
                )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, default=FIXTURE_DIR)
    parser.add_argument("--repo", type=Path, help="可选：固定 commit 的 PatchTST Git 仓库")
    args = parser.parse_args()
    summary = validate_fixture(args.fixture, args.repo)
    print(json.dumps({"valid": True, **summary}, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
