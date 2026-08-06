"""统一评分公式、缺失项处理与阈值/状态边界测试."""
from app.schemas.common import StageRequest
from app.services.mapping_analyzer import MappingAnalyzer, _Candidate, _status
from app.services.mapping_verifier import VerificationError, VerificationResult

PAPER = {
    "title": "A Time Series is Worth 64 Words",
    "sections": [
        {"heading": "Time-series Patching", "page": 4,
         "paragraphs": ["Patching segments the input into patches."]},
    ],
}
SYMBOLS = [
    {"name": "PatchTST", "qualifiedName": "PatchTST", "signature": "class PatchTST(nn.Module)",
     "docstring": "patch-based model", "startLine": 5, "filePath": "model.py", "commitSha": "a" * 40},
]


def _mk(sem=0.0, sym=0.0, kw=0.0, ver=0.0, verified=False, degraded=False) -> _Candidate:
    c = _Candidate(candidate_id="x@f:1", symbol={"name": "x", "qualifiedName": "x",
                                                 "filePath": "f", "startLine": 1}, term_tokens=["x"])
    c.semantic_score, c.symbol_score, c.keyword_score, c.verification_score = sem, sym, kw, ver
    c.verified, c.degraded = verified, degraded
    return c


def test_total_formula_weights():
    c = _mk(sem=1.0, sym=0.5, kw=0.3, ver=0.8)
    assert round(c.total, 4) == round(0.35 * 1.0 + 0.25 * 0.5 + 0.20 * 0.3 + 0.20 * 0.8, 4)


def test_missing_verification_defaults_to_zero():
    c = _mk(sem=1.0)
    assert round(c.total, 4) == round(0.35 * 1.0, 4)  # 缺验证 → 该项为 0，不报错


def test_status_boundaries():
    # 高分 + 验证通过 → VERIFIED
    assert _status(_mk(sem=1.0, sym=1.0, ver=1.0, verified=True), 0.7, 0.3) == "VERIFIED"
    # 高分但验证未通过 → 不 VERIFIED（NEEDS_REVIEW）
    assert _status(_mk(sem=1.0, sym=1.0, ver=1.0, verified=False), 0.7, 0.3) == "NEEDS_REVIEW"
    # 中间区间 → NEEDS_REVIEW
    assert _status(_mk(sym=1.0, ver=1.0, verified=True), 0.7, 0.3) == "NEEDS_REVIEW"
    # 低分 → REJECTED
    assert _status(_mk(sem=0.2), 0.7, 0.3) == "REJECTED"
    # 降级：无验证不标 VERIFIED（即使规则分数高）
    assert _status(_mk(sem=1.0, sym=1.0, verified=True, degraded=True), 0.7, 0.3) == "NEEDS_REVIEW"


class _FixedEmbedding:
    """固定向量：任何文本相同 → 余弦恒为 1.0（可预测）。"""

    def embed(self, texts):
        return [[1.0, 0.0] for _ in texts]


class _FixedVerifier:
    def __init__(self, score=1.0, decision="verified"):
        self.score, self.decision = score, decision

    def verify(self, candidates):
        return [VerificationResult(candidateId=c.candidateId, verificationScore=self.score,
                                   reason="fixed", decision=self.decision) for c in candidates]


class _FailingVerifier:
    def verify(self, candidates):
        raise VerificationError("llm unavailable", retryable=True)


def _req():
    return StageRequest(taskId=7, stageExecutionId=34, stage="MAP_CONCEPTS", attempt=1,
                        input={"paper": PAPER, "symbols": SYMBOLS, "commitSha": "a" * 40,
                               "paperSha256": "b" * 64})


def test_analyzer_marks_verified_with_fixed_fakes():
    analyzer = MappingAnalyzer(high_threshold=0.6, low_threshold=0.3,
                               embedding_provider=_FixedEmbedding(), verifier=_FixedVerifier())
    resp = analyzer.process(_req())
    out = resp.output
    assert out["degraded"] is False
    assert out["stats"]["verifiedCount"] >= 1
    patching = next(c for c in out["concepts"] if "patching" in c["term"].lower())
    top = patching["candidates"][0]
    assert top["totalScore"] == round(0.35 * top["semanticScore"]
                                      + 0.25 * top["symbolScore"]
                                      + 0.20 * top["keywordScore"]
                                      + 0.20 * top["verificationScore"], 4)
    assert top["status"] == "VERIFIED"
    assert top["verificationScore"] == 1.0


def test_analyzer_degrades_when_llm_unavailable():
    analyzer = MappingAnalyzer(high_threshold=0.7, low_threshold=0.3,
                               embedding_provider=_FixedEmbedding(), verifier=_FailingVerifier())
    resp = analyzer.process(_req())
    out = resp.output
    assert out["degraded"] is True
    # 降级：不标 VERIFIED
    assert out["stats"]["verifiedCount"] == 0
    for concept in out["concepts"]:
        for cand in concept["candidates"]:
            assert cand["degraded"] is True
            assert cand["status"] != "VERIFIED"


def test_analyzer_rejects_non_retryable_verification():
    class _BadVerifier:
        def verify(self, candidates):
            raise VerificationError("hallucinated", retryable=False)

    analyzer = MappingAnalyzer(embedding_provider=_FixedEmbedding(), verifier=_BadVerifier())
    import pytest
    from app.core.errors import StageServiceError
    with pytest.raises(StageServiceError) as exc:
        analyzer.process(_req())
    assert exc.value.error_code == "MAPPING_VERIFICATION_FAILED"
    assert exc.value.retryable is False
