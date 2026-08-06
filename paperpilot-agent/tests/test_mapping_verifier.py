"""MappingVerifier 测试：确定性 fake 稳定、合法 schema、幻觉拒绝、额外字段拒绝、非法 JSON、限流可重试."""
import pytest

from app.services.mapping_verifier import (
    CandidateForVerification,
    DeterministicMappingVerifier,
    OpenAiMappingVerifier,
    VerificationError,
)


def _candidates():
    return [
        CandidateForVerification(candidateId="PatchTST@model.py:5", term="patching",
                                 codeSummary="class PatchTST", evidence=""),
        CandidateForVerification(candidateId="PatchTSTHead@model.py:20", term="patching",
                                 codeSummary="class PatchTSTHead", evidence=""),
    ]


def _verifier(call_llm):
    v = OpenAiMappingVerifier(base_url="http://fake", api_key="k", model="m")
    v._call_llm = call_llm  # 注入假 LLM 响应
    return v


def test_deterministic_verifier_is_stable():
    v = DeterministicMappingVerifier()
    a = v.verify(_candidates())
    b = v.verify(_candidates())
    assert a == b  # 完全确定
    assert all(0 <= r.verificationScore <= 1 for r in a)
    assert all(r.decision in ("verified", "rejected") for r in a)


def test_llm_valid_schema_parsed():
    raw = '{"results":[{"candidateId":"PatchTST@model.py:5","verificationScore":0.9,"reason":"ok","decision":"verified"},' \
          '{"candidateId":"PatchTSTHead@model.py:20","verificationScore":0.4,"reason":"weak","decision":"rejected"}]}'
    results = _verifier(lambda prompt: raw).verify(_candidates())
    assert len(results) == 2
    by_id = {r.candidateId: r for r in results}
    assert by_id["PatchTST@model.py:5"].verificationScore == 0.9
    assert by_id["PatchTST@model.py:5"].decision == "verified"


def test_hallucinated_candidate_rejected():
    raw = '{"results":[{"candidateId":"NOT_IN_SET@x.py:1","verificationScore":0.9,"reason":"x","decision":"verified"}]}'
    with pytest.raises(VerificationError) as exc:
        _verifier(lambda prompt: raw).verify(_candidates())
    assert exc.value.retryable is False
    assert "hallucinated" in str(exc.value)


def test_extra_fields_rejected():
    # LLM 尝试追加 lineNumber/路径等幻觉字段 → extra=forbid 拒绝
    raw = '{"results":[{"candidateId":"PatchTST@model.py:5","verificationScore":0.9,"reason":"x","decision":"verified","lineNumber":999}]}'
    with pytest.raises(VerificationError) as exc:
        _verifier(lambda prompt: raw).verify(_candidates())
    assert exc.value.retryable is False
    assert "schema" in str(exc.value)


def test_invalid_json_rejected():
    with pytest.raises(VerificationError) as exc:
        _verifier(lambda prompt: "{not json").verify(_candidates())
    assert exc.value.retryable is False


def test_score_out_of_range_rejected():
    raw = '{"results":[{"candidateId":"PatchTST@model.py:5","verificationScore":2.0,"reason":"x","decision":"verified"}]}'
    with pytest.raises(VerificationError) as exc:
        _verifier(lambda prompt: raw).verify(_candidates())
    assert exc.value.retryable is False


def test_rate_limit_is_retryable():
    def unavailable(prompt):
        raise VerificationError("llm http 429", retryable=True)

    with pytest.raises(VerificationError) as exc:
        _verifier(unavailable).verify(_candidates())
    assert exc.value.retryable is True
