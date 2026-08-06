"""Deterministic benchmark evaluation for paper-to-code mappings."""

from __future__ import annotations

import json
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any


KS = (1, 3, 5)
RANKING_CERTAINTY = "CONFIRMED"
ABSTENTION_CERTAINTY = "NO_EXPLICIT_IMPLEMENTATION"


def _normalise_term(value: str) -> str:
    return " ".join(re.findall(r"[a-z0-9]+", value.casefold()))


def _symbol_key(candidate: dict[str, Any], default_commit: str) -> str | None:
    ref = candidate.get("symbolRef") if isinstance(candidate.get("symbolRef"), dict) else candidate
    path = ref.get("filePath")
    name = ref.get("qualifiedName") or ref.get("symbolName")
    if not path or not name:
        return None
    return f"{ref.get('commitSha') or default_commit}|{path}|{name}"


def _gold_symbol_key(mapping: dict[str, Any], commit: str) -> str:
    return f"{commit}|{mapping['filePath']}|{mapping['qualifiedName']}"


def _concepts(result: dict[str, Any]) -> list[dict[str, Any]]:
    """Accept the canonical export, worker MappingOutput, or Java task result."""
    if isinstance(result.get("concepts"), list):
        return result["concepts"]
    if isinstance(result.get("mappings"), list):
        return result["mappings"]
    nested = result.get("result")
    if isinstance(nested, dict):
        mapping = nested.get("MAP_CONCEPTS")
        if isinstance(mapping, str):
            mapping = json.loads(mapping)
        if isinstance(mapping, dict) and isinstance(mapping.get("concepts"), list):
            return mapping["concepts"]
    return []


def _candidates(concept: dict[str, Any]) -> list[dict[str, Any]]:
    candidates = concept.get("candidates")
    return candidates if isinstance(candidates, list) else []


def _find_prediction(gold_concept: dict[str, Any], predictions: list[dict[str, Any]]) -> dict[str, Any] | None:
    concept_id = gold_concept["id"]
    for prediction in predictions:
        if prediction.get("conceptId") == concept_id or prediction.get("id") == concept_id:
            return prediction
    target = _normalise_term(gold_concept["concept"])
    for prediction in predictions:
        term = prediction.get("term") or prediction.get("concept") or prediction.get("conceptName") or ""
        if _normalise_term(str(term)) == target:
            return prediction
    return None


def _has_paper_evidence(concept: dict[str, Any]) -> bool:
    return bool((concept.get("evidenceText") or concept.get("evidence"))
                and (concept.get("section") or concept.get("source"))
                and (concept.get("page") is not None or concept.get("paragraphId")))


def _has_code_evidence(candidate: dict[str, Any], default_commit: str) -> bool:
    ref = candidate.get("symbolRef") if isinstance(candidate.get("symbolRef"), dict) else candidate
    return bool((ref.get("commitSha") or default_commit) and ref.get("filePath")
                and (ref.get("qualifiedName") or ref.get("symbolName"))
                and isinstance(ref.get("startLine"), int) and ref["startLine"] > 0)


def _error_category(gold_concept: dict[str, Any], prediction: dict[str, Any] | None,
                    predicted_keys: list[str], relevant: set[str]) -> str:
    if prediction is None:
        return "TERM_OR_CONCEPT_EXTRACTION"
    if not predicted_keys:
        return "RETRIEVAL_EMPTY"
    if len(relevant) > 1 and relevant.intersection(predicted_keys):
        return "MULTI_FILE_OR_MULTI_SYMBOL_PARTIAL"
    relevant_paths = {key.split("|", 2)[1] for key in relevant}
    predicted_paths = {key.split("|", 2)[1] for key in predicted_keys}
    if relevant_paths.intersection(predicted_paths):
        return "RIGHT_FILE_WRONG_SYMBOL"
    if any(not _has_code_evidence(candidate, "") for candidate in _candidates(prediction)):
        return "INDEX_OR_CODE_EVIDENCE_MISSING"
    return "SEMANTIC_OR_RULE_RETRIEVAL"


def evaluate(gold: dict[str, Any], result: dict[str, Any], label: str | None = None) -> dict[str, Any]:
    """Evaluate one deterministic system export against a gold document."""
    predictions = _concepts(result)
    commit = gold["repository"]["commitSha"]
    confirmed = [c for c in gold["concepts"] if c["certainty"] == RANKING_CERTAINTY]
    errors: list[dict[str, Any]] = []
    relevant_hits = {k: 0 for k in KS}
    precision_sum = {k: 0.0 for k in KS}
    recall_sum = {k: 0.0 for k in KS}
    reciprocal_rank_sum = 0.0

    matched_predictions: list[dict[str, Any]] = []
    for concept in confirmed:
        prediction = _find_prediction(concept, predictions)
        if prediction is not None:
            matched_predictions.append(prediction)
        candidates = _candidates(prediction or {})
        predicted_keys = [key for candidate in candidates if (key := _symbol_key(candidate, commit))]
        relevant = {_gold_symbol_key(mapping, commit) for mapping in concept["mappings"]}
        for k in KS:
            hits = len(relevant.intersection(predicted_keys[:k]))
            relevant_hits[k] += hits
            precision_sum[k] += hits / k
            recall_sum[k] += hits / len(relevant)
        rank = next((i for i, key in enumerate(predicted_keys, 1) if key in relevant), None)
        reciprocal_rank_sum += 1 / rank if rank else 0.0
        if not relevant.issubset(set(predicted_keys[:5])):
            errors.append({
                "conceptId": concept["id"],
                "concept": concept["concept"],
                "type": "FALSE_NEGATIVE",
                "category": _error_category(concept, prediction, predicted_keys, relevant),
                "missing": sorted(relevant.difference(predicted_keys[:5])),
            })
        for key in predicted_keys[:5]:
            if key not in relevant:
                errors.append({"conceptId": concept["id"], "concept": concept["concept"],
                               "type": "FALSE_POSITIVE", "category": "UNRELATED_TOP_K_SYMBOL",
                               "symbol": key})

    # Evidence/status quality describes the complete system export. Ranking still uses only
    # gold-aligned CONFIRMED concepts, so an extraction miss cannot hide malformed evidence.
    evaluated_candidates = [candidate for concept in predictions for candidate in _candidates(concept)]
    complete = sum(_has_paper_evidence(concept) and _has_code_evidence(candidate, commit)
                   for concept in predictions for candidate in _candidates(concept))
    needs_review = sum(candidate.get("status") == "NEEDS_REVIEW" for candidate in evaluated_candidates)
    abstention_cases = [c for c in gold["concepts"] if c["certainty"] == ABSTENTION_CERTAINTY]
    abstained = sum(not _candidates(_find_prediction(c, predictions) or {}) for c in abstention_cases)
    metadata = result.get("metadata") if isinstance(result.get("metadata"), dict) else {}
    durations = metadata.get("stageDurationsMs") or result.get("stageDurationsMs") or {}
    duration_values = [float(v) for v in durations.values()] if isinstance(durations, dict) else []
    denominator = len(confirmed)
    metrics: dict[str, Any] = {}
    for k in KS:
        metrics[f"precisionAt{k}"] = round(precision_sum[k] / denominator, 6) if denominator else 0.0
        metrics[f"recallAt{k}"] = round(recall_sum[k] / denominator, 6) if denominator else 0.0
    metrics.update({
        "mrr": round(reciprocal_rank_sum / denominator, 6) if denominator else 0.0,
        "evidenceCompleteness": round(complete / len(evaluated_candidates), 6) if evaluated_candidates else 0.0,
        "needsReviewRatio": round(needs_review / len(evaluated_candidates), 6) if evaluated_candidates else 0.0,
        "abstentionAccuracy": round(abstained / len(abstention_cases), 6) if abstention_cases else None,
        "averageStageDurationMs": round(sum(duration_values) / len(duration_values), 3) if duration_values else None,
        "llmTokens": metadata.get("llmTokens", result.get("llmTokens")),
    })
    return {
        "schemaVersion": 1,
        "benchmarkId": gold["benchmarkId"],
        "label": label or metadata.get("label") or result.get("label") or "result",
        "configuration": {
            key: metadata.get(key) for key in ("mode", "seed", "modelVersion", "promptVersion", "parameters")
        },
        "denominators": {
            "confirmedConcepts": denominator,
            "confirmedRelevantSymbols": sum(len(c["mappings"]) for c in confirmed),
            "matchedConfirmedConcepts": len(matched_predictions),
            "evaluatedCandidates": len(evaluated_candidates),
            "abstentionCases": len(abstention_cases),
            "stageDurations": len(duration_values),
        },
        "metricDefinitions": {
            "precisionAtK": "macro mean of relevant symbols in top K divided by K over CONFIRMED concepts",
            "recallAtK": "macro mean of relevant symbols in top K divided by all gold symbols over CONFIRMED concepts",
            "mrr": "mean reciprocal rank of the first relevant symbol over CONFIRMED concepts",
            "evidenceCompleteness": "candidates with paper location/text and commit/path/symbol/startLine divided by evaluated candidates",
            "needsReviewRatio": "NEEDS_REVIEW candidates divided by evaluated candidates",
            "abstentionAccuracy": "NO_EXPLICIT_IMPLEMENTATION concepts with no candidates divided by abstention cases",
        },
        "metrics": metrics,
        "errorSummary": dict(sorted(Counter(error["category"] for error in errors).items())),
        "errors": errors,
    }


def render_markdown(evaluations: list[dict[str, Any]]) -> str:
    lines = ["# PatchTST 映射质量报告", "", "## 指标", "",
             "| 版本 | P@1 | P@3 | P@5 | R@1 | R@3 | R@5 | MRR | 证据完整率 | NEEDS_REVIEW | 弃答准确率 |",
             "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for evaluation in evaluations:
        m = evaluation["metrics"]
        fmt = lambda value: "N/A" if value is None else f"{value:.4f}"
        lines.append(f"| {evaluation['label']} | {fmt(m['precisionAt1'])} | {fmt(m['precisionAt3'])} | "
                     f"{fmt(m['precisionAt5'])} | {fmt(m['recallAt1'])} | {fmt(m['recallAt3'])} | "
                     f"{fmt(m['recallAt5'])} | {fmt(m['mrr'])} | {fmt(m['evidenceCompleteness'])} | "
                     f"{fmt(m['needsReviewRatio'])} | {fmt(m['abstentionAccuracy'])} |")
    lines += ["", "## 口径", "",
              "主排名指标仅使用 `CONFIRMED` 概念；P@K、R@K 为逐概念宏平均，MRR 取首个正确符号。",
              "`AUXILIARY` 与 `LOW_CONFIDENCE` 不进入主指标；`NO_EXPLICIT_IMPLEMENTATION` 仅进入弃答准确率。",
              "证据完整要求论文定位与文本，以及固定 commit、文件、符号和起始行全部存在。", "",
              "## 执行元数据", ""]
    for evaluation in evaluations:
        m = evaluation["metrics"]
        config = evaluation["configuration"]
        lines.append(f"- **{evaluation['label']}**：mode={config.get('mode') or 'N/A'}，"
                     f"model={config.get('modelVersion') or 'N/A'}，promptVersion="
                     f"{config.get('promptVersion') or 'N/A'}，平均阶段耗时="
                     f"{m['averageStageDurationMs'] if m['averageStageDurationMs'] is not None else 'N/A'} ms，"
                     f"LLM tokens={m['llmTokens'] if m['llmTokens'] is not None else 'N/A'}")
    lines += ["",
              "## 错误分析", ""]
    for evaluation in evaluations:
        lines.append(f"### {evaluation['label']}")
        lines.append("")
        if evaluation["errorSummary"]:
            for category, count in evaluation["errorSummary"].items():
                lines.append(f"- `{category}`：{count}")
            lines.append("")
            lines.append("失败样例（按 conceptId 稳定排序，完整列表见 JSON）：")
            lines.append("")
            for error in sorted(evaluation["errors"], key=lambda item: (item["conceptId"], item["type"]))[:5]:
                detail = error.get("symbol") or ", ".join(error.get("missing", []))
                lines.append(f"- `{error['conceptId']}` {error['concept']}：{error['type']} / "
                             f"{error['category']}；{detail}")
        else:
            lines.append("- 未发现 Top-5 false positive/negative。")
        lines.append("")
    lines += ["## 下一步优化", "",
              "- 让概念抽取输出稳定 conceptId 或基准别名，降低术语差异导致的概念对齐失败。",
              "- 对一对多和跨文件实现增加多样性召回，避免 Top-K 被同模块近义符号占满。",
              "- 将索引中的 docstring、父符号与调用关系用于重排，同时保持路径和行号只能来自 AST。",
              "- LLM 评估必须记录模型、promptVersion、参数和 token；缺失时报告 N/A，不作推测。", ""]
    return "\n".join(lines)


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))
