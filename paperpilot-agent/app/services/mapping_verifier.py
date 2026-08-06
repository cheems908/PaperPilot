"""MappingVerifier 抽象：LLM Top-K 验证与幻觉抑制.

- LLM 只返回 candidateId / verificationScore / reason / decision；
- Pydantic 严格校验（extra=forbid）拒绝额外路径/行号等幻觉字段；
- 候选集之外的 candidateId 一律拒绝（LLM 无法创造代码位置）；
- 限流/临时失败（retryable=True）可重试；非法 schema/幻觉（retryable=False）稳定错误。
"""
import hashlib
import json
from typing import Dict, List, Protocol

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.core.config import settings


class CandidateForVerification(BaseModel):
    candidateId: str
    term: str
    codeSummary: str
    evidence: str


class VerificationResult(BaseModel):
    model_config = ConfigDict(extra="forbid")  # 拒绝路径/行号等幻觉字段
    candidateId: str
    verificationScore: float = Field(ge=0, le=1)
    reason: str = ""
    decision: str  # verified / rejected


class VerificationError(RuntimeError):
    """验证失败：retryable=True 可重试（限流/临时），False 为稳定错误（非法 schema/幻觉）。"""

    def __init__(self, message: str, retryable: bool):
        super().__init__(message)
        self.retryable = retryable


class MappingVerifier(Protocol):
    def verify(self, candidates: List[CandidateForVerification]) -> List[VerificationResult]: ...


class DeterministicMappingVerifier:
    """确定性 fake：按 candidateId 哈希给分（0.5~0.99），测试完全确定。"""

    def verify(self, candidates: List[CandidateForVerification]) -> List[VerificationResult]:
        results: List[VerificationResult] = []
        for c in candidates:
            h = int(hashlib.sha256(c.candidateId.encode("utf-8")).hexdigest(), 16)
            score = round(0.5 + (h % 50) / 100.0, 4)
            results.append(VerificationResult(
                candidateId=c.candidateId, verificationScore=score,
                reason="deterministic", decision="verified" if score >= 0.7 else "rejected"))
        return results


class _LlmResultList(BaseModel):
    model_config = ConfigDict(extra="forbid")  # 拒绝幻觉字段
    results: List[VerificationResult]


class OpenAiMappingVerifier:
    """OpenAI 兼容 chat/completions 的 LLM 验证器；严格解析并拒绝幻觉。"""

    def __init__(self, base_url: str, api_key: str, model: str,
                 timeout_seconds: float = 30.0, prompt_version: str = "1"):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout_seconds = timeout_seconds
        self.prompt_version = prompt_version

    def verify(self, candidates: List[CandidateForVerification]) -> List[VerificationResult]:
        allowed = {c.candidateId for c in candidates}
        prompt = self._build_prompt(candidates)
        try:
            raw = self._call_llm(prompt)
        except httpx.HTTPError as e:
            raise VerificationError(f"llm unavailable: {e}", retryable=True) from e
        return self._parse(raw, allowed)

    def _build_prompt(self, candidates: List[CandidateForVerification]) -> str:
        lines = [
            "Verify whether each code candidate supports the paper concept. "
            "Return STRICT JSON {\"results\":[{\"candidateId\":\"...\",\"verificationScore\":0.0,"
            "\"reason\":\"...\",\"decision\":\"verified|rejected\"}]}.",
            "You may NOT add any field other than candidateId/verificationScore/reason/decision, "
            "and you may NOT invent candidateId outside this list.",
        ]
        for c in candidates:
            lines.append(f"- candidateId={c.candidateId} term={c.term} code={c.codeSummary}")
        return "\n".join(lines)

    def _call_llm(self, prompt: str) -> str:
        url = f"{self.base_url}/chat/completions"
        headers = {"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"}
        payload = {
            "model": self.model,
            "messages": [{"role": "user", "content": prompt}],
            "response_format": {"type": "json_object"},
        }
        resp = httpx.post(url, headers=headers, json=payload, timeout=self.timeout_seconds)
        if resp.status_code == 429 or resp.status_code >= 500:
            raise VerificationError(f"llm http {resp.status_code}", retryable=True)
        resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"]

    def _parse(self, raw: str, allowed: set[str]) -> List[VerificationResult]:
        try:
            data = json.loads(raw)
        except json.JSONDecodeError as e:
            raise VerificationError(f"invalid llm json: {e}", retryable=False) from e
        try:
            parsed = _LlmResultList.model_validate(data)  # extra=forbid → 幻觉字段被拒
        except ValidationError as e:
            raise VerificationError(f"invalid llm schema: {e}", retryable=False) from e
        for r in parsed.results:
            if r.candidateId not in allowed:
                raise VerificationError(f"hallucinated candidateId: {r.candidateId}", retryable=False)
        return parsed.results


def default_verifier() -> MappingVerifier:
    """按配置选择验证器：llm_base_url 为空 → 确定性 fake；否则真实 LLM。"""
    if not settings.llm_base_url:
        return DeterministicMappingVerifier()
    return OpenAiMappingVerifier(
        base_url=settings.llm_base_url, api_key=settings.llm_api_key,
        model=settings.llm_model, timeout_seconds=settings.llm_timeout_seconds,
        prompt_version=settings.llm_prompt_version)
