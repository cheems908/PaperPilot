"""规则 + 语义召回 + LLM 验证的概念—代码映射.

- 概念抽取：标题、章节标题、段落显著术语（保留 section/page/evidenceText/paragraphId）；
- 规则召回：符号名/qualifiedName/signature/docstring 分项分数；
- 语义召回：EmbeddingProvider 对概念与受控代码摘要求余弦相似度；
- 合并规则+语义候选，只把 Top-K 发送给 LLM 验证；
- 统一评分：0.35 semantic + 0.25 symbol + 0.20 keyword + 0.20 verification，缺失项默认 0；
- 状态：高分且验证通过 → VERIFIED；中间 → NEEDS_REVIEW；低分 → REJECTED（阈值配置化）；
- LLM 限流/临时失败 → 降级（degraded=true，不标 VERIFIED）；非法 schema/幻觉 → 稳定错误。
不引入 ChromaDB/向量库；MVP 用内存确定性 hash 嵌入。
"""
import re
import time
from collections import Counter
from dataclasses import dataclass, field

from app.core.config import SimulateOptions, settings
from app.core.errors import StageErrorCode, StageServiceError
from app.schemas.common import StageRequest, StageSuccessResponse
from app.schemas.mapping import Concept, MappingCandidate, MappingOutput
from app.services.embeddings import EmbeddingProvider, cosine, hash_embedding_provider
from app.services.mapping_verifier import (
    CandidateForVerification,
    MappingVerifier,
    VerificationError,
    default_verifier,
)

_STOPWORDS = {
    "a", "an", "the", "of", "for", "in", "on", "to", "and", "or", "with", "based",
    "using", "via", "from", "at", "by", "as", "is", "are", "was", "were", "we", "our",
    "this", "that", "it", "its", "can", "will", "be", "not", "over", "under", "into",
    "than", "but", "they", "their", "has", "have", "had", "which", "such", "each",
}
_SYNONYMS = {
    "prediction": "forecast",
    "forecasting": "forecast",
    "predict": "forecast",
    "patch": "patching",
}


def _split_words(name: str) -> list[str]:
    parts = re.split(r"[_\s]+", name)
    words: list[str] = []
    for p in parts:
        words.extend(re.split(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", p))
    return [w.lower() for w in words if w]


def _tokens(text: str | None) -> list[str]:
    if not text:
        return []
    words = re.findall(r"[a-z][a-z0-9]*", text.lower())
    return [_SYNONYMS.get(w, w) for w in words if w not in _STOPWORDS and len(w) > 1]


def _clean_term(text: str) -> str:
    return re.sub(r"\s+", " ", text.strip())


def _symbol_summary(sym: dict) -> str:
    return " ".join(p for p in [sym.get("qualifiedName") or sym.get("name") or "",
                                sym.get("signature") or "", sym.get("docstring") or ""] if p)


def _candidate_id(sym: dict) -> str:
    return f"{sym.get('qualifiedName') or sym.get('name')}@{sym.get('filePath')}:{sym.get('startLine')}"


@dataclass
class _Candidate:
    candidate_id: str
    symbol: dict
    term_tokens: list
    symbol_score: float = 0.0
    keyword_score: float = 0.0
    doc_score: float = 0.0
    semantic_score: float = 0.0
    matched_tokens: list = field(default_factory=list)
    code_evidence: str = ""
    verification_score: float = 0.0
    verification_reason: str = ""
    verified: bool = False
    degraded: bool = False

    @property
    def pre_total(self) -> float:
        return 0.35 * self.semantic_score + 0.25 * self.symbol_score + 0.20 * self.keyword_score

    @property
    def total(self) -> float:
        return self.pre_total + 0.20 * self.verification_score


class MappingAnalyzer:
    def __init__(self, top_k: int | None = None, high_threshold: float | None = None,
                 low_threshold: float | None = None,
                 embedding_provider: EmbeddingProvider | None = None,
                 verifier: MappingVerifier | None = None):
        self.top_k = top_k or settings.mapping_top_k
        self.high_threshold = high_threshold if high_threshold is not None else settings.mapping_high_threshold
        self.low_threshold = low_threshold if low_threshold is not None else settings.mapping_low_threshold
        self.embedding_provider = embedding_provider or hash_embedding_provider
        self.verifier = verifier or default_verifier()

    def process(self, req: StageRequest, simulate: SimulateOptions | None = None) -> StageSuccessResponse:
        simulate = simulate or SimulateOptions()
        if simulate.failure:
            raise StageServiceError(StageErrorCode.STAGE_FAILED, "simulated mapping failure", retryable=True)
        if simulate.delay_ms > 0:
            time.sleep(simulate.delay_ms / 1000.0)

        raw = req.input if isinstance(req.input, dict) else {}
        paper = raw.get("paper")
        symbols = raw.get("symbols")
        if not isinstance(paper, dict) or not isinstance(symbols, list) or not symbols:
            raise StageServiceError(StageErrorCode.INVALID_MAPPING_INPUT,
                                    "missing paper/symbols", retryable=False, status_code=400)
        commit_sha = raw.get("commitSha", "")

        concepts = _dedup_concepts(_extract_concepts(paper))
        degraded = False
        for concept in concepts:
            concept.candidates = self._score_concept(concept, symbols)
            degraded |= any(c.degraded for c in concept.candidates)

        stats = {
            "conceptCount": len(concepts),
            "candidateCount": sum(len(c.candidates) for c in concepts),
            "verifiedCount": sum(1 for c in concepts for cand in c.candidates if cand.status == "VERIFIED"),
            "needsReviewCount": sum(1 for c in concepts for cand in c.candidates if cand.status == "NEEDS_REVIEW"),
            "rejectedCount": sum(1 for c in concepts for cand in c.candidates if cand.status == "REJECTED"),
        }
        output = MappingOutput(commitSha=commit_sha, concepts=concepts, stats=stats, degraded=degraded)
        return StageSuccessResponse(output=output.model_dump(), workerVersion="0.3.0-mapping")

    # ── 单概念打分流水线 ─────────────────────────────────────────────────

    def _score_concept(self, concept: Concept, symbols: list) -> list[MappingCandidate]:
        term_tokens = _tokens(concept.term)
        if not term_tokens:
            return []
        rule = _rule_candidates(term_tokens, symbols)
        semantic_scores = _semantic_scores(concept.term, symbols, self.embedding_provider)
        merged = _merge(rule, semantic_scores, symbols, term_tokens)
        if not merged:
            return []
        merged.sort(key=lambda c: -c.pre_total)
        top = merged[:self.top_k]

        try:
            verifications = _verify(self.verifier, concept.term, top)
            degraded = False
        except VerificationError as e:
            if not e.retryable:
                raise StageServiceError(StageErrorCode.MAPPING_VERIFICATION_FAILED,
                                        f"verification failed: {e}", retryable=False, status_code=502) from e
            verifications = {}
            degraded = True

        results: list[MappingCandidate] = []
        for c in top:
            v = verifications.get(c.candidate_id)
            if not degraded and v:
                c.verification_score = v.verificationScore
                c.verification_reason = v.reason
                c.verified = v.decision == "verified"
            c.degraded = degraded
            results.append(_to_output(c, self.high_threshold, self.low_threshold))
        results.sort(key=lambda out: (-out.totalScore, out.symbolRef.get("qualifiedName") or ""))
        return results


# ── 规则召回 / 语义召回 / 合并 ───────────────────────────────────────────

def _rule_candidates(term_tokens: list, symbols: list) -> list[_Candidate]:
    candidates: list[_Candidate] = []
    for sym in symbols:
        name_tokens = _tokens(sym.get("name")) + [_SYNONYMS.get(w, w) for w in _split_words(sym.get("name", ""))]
        qualified_tokens = _tokens(sym.get("qualifiedName"))
        signature_tokens = _tokens(sym.get("signature"))
        doc_tokens = _tokens(sym.get("docstring"))
        symbol_score = _overlap(term_tokens, name_tokens)
        keyword_score = 0.5 * _overlap(term_tokens, qualified_tokens) + 0.5 * _overlap(term_tokens, signature_tokens)
        doc_score = _overlap(term_tokens, doc_tokens)
        if symbol_score == 0 and keyword_score == 0 and doc_score == 0:
            continue
        matched = [t for t in term_tokens
                   if t in name_tokens or t in qualified_tokens or t in signature_tokens or t in doc_tokens]
        candidates.append(_Candidate(
            candidate_id=_candidate_id(sym), symbol=sym, term_tokens=term_tokens,
            symbol_score=symbol_score, keyword_score=keyword_score, doc_score=doc_score,
            matched_tokens=matched,
            code_evidence=str(sym.get("signature") or sym.get("docstring") or sym.get("name", ""))[:200]))
    return candidates


def _semantic_scores(term: str, symbols: list, provider: EmbeddingProvider) -> dict[str, float]:
    summaries = [_symbol_summary(s) for s in symbols]
    term_vec = provider.embed([term])[0]
    summary_vecs = provider.embed(summaries)
    return {_candidate_id(s): cosine(term_vec, sv) for s, sv in zip(symbols, summary_vecs)}


def _merge(rule: list[_Candidate], semantic_scores: dict[str, float],
           symbols: list, term_tokens: list) -> list[_Candidate]:
    by_id = {c.candidate_id: c for c in rule}
    for sym in symbols:
        cid = _candidate_id(sym)
        sem = semantic_scores.get(cid, 0.0)
        if cid in by_id:
            by_id[cid].semantic_score = sem
        elif sem > 0:
            by_id[cid] = _Candidate(
                candidate_id=cid, symbol=sym, term_tokens=term_tokens, semantic_score=sem,
                code_evidence=str(sym.get("signature") or sym.get("docstring") or sym.get("name", ""))[:200])
    return list(by_id.values())


def _verify(verifier: MappingVerifier, term: str, candidates: list[_Candidate]) -> dict[str, object]:
    items = [CandidateForVerification(candidateId=c.candidate_id, term=term,
                                      codeSummary=_symbol_summary(c.symbol), evidence="") for c in candidates]
    return {r.candidateId: r for r in verifier.verify(items)}


def _to_output(c: _Candidate, high: float, low: float) -> MappingCandidate:
    return MappingCandidate(
        symbolRef={"filePath": c.symbol.get("filePath"), "qualifiedName": c.symbol.get("qualifiedName"),
                   "name": c.symbol.get("name"), "startLine": c.symbol.get("startLine"),
                   "commitSha": c.symbol.get("commitSha")},
        semanticScore=round(c.semantic_score, 4), symbolScore=round(c.symbol_score, 4),
        keywordScore=round(c.keyword_score, 4), verificationScore=round(c.verification_score, 4),
        totalScore=round(c.total, 4),
        status=_status(c, high, low), degraded=c.degraded,
        matchedTokens=c.matched_tokens, codeEvidence=c.code_evidence,
        verificationReason=c.verification_reason)


def _status(c: _Candidate, high: float, low: float) -> str:
    if c.degraded:
        return "NEEDS_REVIEW" if c.total >= low else "REJECTED"  # 无验证不标 VERIFIED
    if c.total >= high and c.verified:
        return "VERIFIED"
    if c.total >= low:
        return "NEEDS_REVIEW"
    return "REJECTED"


# ── 概念抽取 ─────────────────────────────────────────────────────────────

def _extract_concepts(paper: dict) -> list[Concept]:
    concepts: list[Concept] = []
    title = paper.get("title")
    if title:
        concepts.append(Concept(term=_clean_term(title), source="title",
                                evidenceText=str(title)[:300]))
    for si, sec in enumerate(paper.get("sections") or []):
        heading = sec.get("heading")
        page = sec.get("page")
        paragraphs = sec.get("paragraphs") or []
        for pi, para in enumerate(paragraphs):
            evidence = str(para)[:300]
            para_id = f"{si + 1}.{pi + 1}"
            if heading and pi == 0:
                concepts.append(Concept(term=_clean_term(str(heading)), source="heading",
                                        section=_clean_term(str(heading)), page=page,
                                        evidenceText=evidence, paragraphId=para_id))
            for term in _significant_terms(para):
                concepts.append(Concept(term=term, source="paragraph",
                                        section=_clean_term(str(heading)) if heading else None,
                                        page=page, evidenceText=evidence, paragraphId=para_id))
    return concepts


def _significant_terms(text: str, limit: int = 5) -> list[str]:
    tokens = _tokens(text)
    if len(tokens) < 4:
        return []
    freq = Counter(tokens)
    terms = [t for t, c in freq.items() if c >= 2]
    bigrams = Counter(" ".join(tokens[i:i + 2]) for i in range(len(tokens) - 1))
    terms += [b for b, c in bigrams.items() if c >= 2]
    seen, out = set(), []
    for t in terms:
        if t not in seen:
            seen.add(t)
            out.append(t)
    return out[:limit]


def _dedup_concepts(concepts: list[Concept]) -> list[Concept]:
    seen: set[str] = set()
    out: list[Concept] = []
    for c in concepts:
        key = c.term.lower()
        if key not in seen:
            seen.add(key)
            out.append(c)
    return out


def _overlap(term_tokens: list[str], sym_tokens: list[str]) -> float:
    if not term_tokens:
        return 0.0
    matched = len(set(term_tokens) & set(sym_tokens))
    return matched / len(term_tokens)


mapping_analyzer = MappingAnalyzer()
