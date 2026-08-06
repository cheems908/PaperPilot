"""规则版概念—代码映射单测：非 stub 候选、论文+代码双证据、稳定排序、NEEDS_REVIEW 不标 VERIFIED."""
import pytest

from app.core.errors import StageServiceError
from app.schemas.common import StageRequest
from app.services.mapping_analyzer import MappingAnalyzer

PAPER = {
    "title": "A Time Series is Worth 64 Words",
    "abstract": "Long-term time series forecasting with patching and channel independence.",
    "sections": [
        {"heading": "Channel Independence", "page": 3,
         "paragraphs": [
             "Channel independence splits the multivariate time series into univariate channels.",
             "The model applies channel independence to each series.",
         ]},
        {"heading": "Time-series Patching", "page": 4,
         "paragraphs": [
             "Patching segments the input into patches.",
         ]},
    ],
}

SYMBOLS = [
    {"name": "PatchTST", "qualifiedName": "PatchTST", "signature": "class PatchTST(nn.Module)",
     "docstring": "PatchTST: a patch-based time series forecasting model with channel independence.",
     "startLine": 5, "filePath": "model.py", "commitSha": "a" * 40},
    {"name": "PatchTSTHead", "qualifiedName": "PatchTSTHead",
     "signature": "class PatchTSTHead(nn.Module)",
     "docstring": "Forecasting head producing predictions from patched representations.",
     "startLine": 20, "filePath": "model.py", "commitSha": "a" * 40},
    {"name": "forward", "qualifiedName": "PatchTST.forward", "signature": "def forward(self, x)",
     "docstring": "Forward pass with channel independence.", "startLine": 12,
     "filePath": "model.py", "commitSha": "a" * 40},
]


def _req() -> StageRequest:
    return StageRequest(taskId=7, stageExecutionId=34, stage="MAP_CONCEPTS", attempt=1,
                        input={"paper": PAPER, "symbols": SYMBOLS, "commitSha": "a" * 40,
                               "paperSha256": "b" * 64})


def test_produces_non_stub_candidates():
    resp = MappingAnalyzer().process(_req())
    out = resp.output
    assert out["stats"]["candidateCount"] >= 2  # 至少若干非 stub 候选
    assert out["stats"]["conceptCount"] >= 2


def test_each_mapping_has_paper_and_code_evidence():
    resp = MappingAnalyzer().process(_req())
    for concept in resp.output["concepts"]:
        assert concept["evidenceText"], "论文证据缺失"
        for cand in concept["candidates"]:
            ref = cand["symbolRef"]
            assert ref["filePath"] and ref["qualifiedName"] and ref["startLine"], "代码坐标缺失"
            assert cand["codeEvidence"], "代码证据缺失"
            # 规则命中 token 或语义分数至少其一
            assert cand["matchedTokens"] or cand["semanticScore"] > 0


def test_patching_matches_patchtst():
    resp = MappingAnalyzer().process(_req())
    patching = next(c for c in resp.output["concepts"] if "patching" in c["term"].lower())
    # PatchTST 经 camel 拆分 + 同义词（patch→patching）命中符号名
    top = patching["candidates"][0]
    assert top["symbolRef"]["qualifiedName"] == "PatchTST"
    assert top["totalScore"] > 0


def test_channel_independence_has_evidence():
    resp = MappingAnalyzer().process(_req())
    ci = next(c for c in resp.output["concepts"] if c["term"].lower() == "channel independence")
    assert ci["section"] == "Channel Independence"
    assert ci["page"] == 3
    assert ci["paragraphId"]
    assert any(c["totalScore"] > 0 for c in ci["candidates"])


def test_statuses_within_expected_set():
    resp = MappingAnalyzer().process(_req())
    statuses = {c["status"] for concept in resp.output["concepts"] for c in concept["candidates"]}
    assert statuses and statuses <= {"VERIFIED", "NEEDS_REVIEW", "REJECTED"}
    # 确定性 verifier 不会全部 VERIFIED
    assert resp.output["stats"]["verifiedCount"] < resp.output["stats"]["candidateCount"]


def test_stable_order_and_scores():
    a = MappingAnalyzer().process(_req())
    b = MappingAnalyzer().process(_req())
    assert a.output == b.output  # 同一输入稳定排序与分数
    for concept in a.output["concepts"]:
        totals = [c["totalScore"] for c in concept["candidates"]]
        assert totals == sorted(totals, reverse=True)  # Top-K 总分降序


def test_missing_input_rejected():
    req = StageRequest(taskId=7, stageExecutionId=34, stage="MAP_CONCEPTS", attempt=1, input={})
    with pytest.raises(StageServiceError) as exc:
        MappingAnalyzer().process(req)
    assert exc.value.error_code == "INVALID_MAPPING_INPUT"
