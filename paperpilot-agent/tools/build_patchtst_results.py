"""Build reproducible rule/enhanced PatchTST result exports from real PDF and AST index."""

from __future__ import annotations

import argparse
import json
import random
import subprocess
import time
from pathlib import Path

from app.schemas.common import StageRequest
from app.services.code_indexer import CodeIndexer
from app.services.mapping_analyzer import MappingAnalyzer
from app.services.mapping_verifier import DeterministicMappingVerifier, VerificationResult
from app.services.paper_parser import PaperParser


class RuleOnlyVerifier:
    """Ablation verifier: keeps the same Top-K recall but contributes no verification score."""

    def verify(self, candidates):
        return [VerificationResult(candidateId=c.candidateId, verificationScore=0.0,
                                   reason="rule-only-ablation", decision="rejected") for c in candidates]


def _request(stage: str, payload: dict) -> StageRequest:
    return StageRequest(taskId=1, stageExecutionId=1, stage=stage, attempt=1, input=payload)


def _timed(callable_):
    started = time.perf_counter()
    value = callable_()
    return value, round((time.perf_counter() - started) * 1000, 3)


def _commit(repo: Path) -> str:
    return subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"], check=True,
                          capture_output=True, text=True).stdout.strip()


def _symbols(index_output: dict, commit: str) -> list[dict]:
    flattened = []
    for file in index_output["files"]:
        for symbol in file["symbols"]:
            flattened.append({**symbol, "filePath": file["path"], "commitSha": commit})
    return flattened


def _export(label: str, mode: str, model: str, prompt: str | None, output: dict,
            durations: dict[str, float], llm_tokens: int | None) -> dict:
    return {
        "schemaVersion": 1,
        "label": label,
        "metadata": {
            "label": label, "mode": mode, "seed": 0, "modelVersion": model,
            "promptVersion": prompt, "parameters": {"topK": 5, "embedding": "sha256-hash-64"},
            "stageDurationsMs": durations, "llmTokens": llm_tokens,
        },
        "commitSha": output["commitSha"], "concepts": output["concepts"],
        "stats": output["stats"], "degraded": output["degraded"],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--paper", type=Path, required=True)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--out-dir", type=Path, default=Path("build/benchmark"))
    args = parser.parse_args()
    random.seed(0)
    paper, repo = args.paper.resolve(), args.repo.resolve()
    actual_commit = _commit(repo)
    if actual_commit != args.commit:
        raise SystemExit(f"repository commit mismatch: expected {args.commit}, got {actual_commit}")

    parsed, parse_ms = _timed(lambda: PaperParser(storage_root=str(paper.parent)).process(
        _request("PARSE_PAPER", {"storagePath": paper.name})).output)
    indexed, index_ms = _timed(lambda: CodeIndexer(workspace_root=str(repo.parent))._index(
        repo, repo.name, actual_commit).output)
    mapping_input = {"paper": parsed["paper"], "symbols": _symbols(indexed, actual_commit),
                     "commitSha": actual_commit}

    rule, rule_ms = _timed(lambda: MappingAnalyzer(verifier=RuleOnlyVerifier()).process(
        _request("MAP_CONCEPTS", mapping_input)).output)
    enhanced, enhanced_ms = _timed(lambda: MappingAnalyzer(verifier=DeterministicMappingVerifier()).process(
        _request("MAP_CONCEPTS", mapping_input)).output)
    args.out_dir.mkdir(parents=True, exist_ok=True)
    common = {"PARSE_PAPER": parse_ms, "INDEX_CODE": index_ms}
    outputs = {
        "rule-result.json": _export("规则版", "rules+hash-embedding", "none", None, rule,
                                    {**common, "MAP_CONCEPTS": rule_ms}, 0),
        "enhanced-result.json": _export("增强版（确定性验证器）", "rules+hash-embedding+verification",
                                        "deterministic-sha256-v1", "1", enhanced,
                                        {**common, "MAP_CONCEPTS": enhanced_ms}, 0),
    }
    for name, value in outputs.items():
        (args.out_dir / name).write_text(
            json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({name: value["stats"] for name, value in outputs.items()}, ensure_ascii=False))


if __name__ == "__main__":
    main()
