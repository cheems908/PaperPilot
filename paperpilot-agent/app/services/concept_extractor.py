"""Deterministic compound concept extraction with stable production identities."""

from __future__ import annotations

import hashlib
import re
import unicodedata
from collections import defaultdict
from dataclasses import dataclass

from app.schemas.mapping import Concept, ConceptMention


EXTRACTOR_VERSION = "compound-rule-v1"
MAX_CONCEPTS = 64
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_WORD = r"[A-Za-z][A-Za-z0-9]*(?:[-–—][A-Za-z0-9]+)*"
_COMPOUND_PATTERNS = [re.compile(pattern, re.IGNORECASE) for pattern in (
    r"\b(?:time[- ]series|subseries[- ]level)\s+patch(?:ing|es)\b",
    r"\bchannel[- ]independen(?:t|ce)(?:\s+[A-Za-z-]+){0,3}\s+transformer\b",
    r"\b(?:learnable\s+)?(?:position|positional)\s+encoding\b",
    r"\b(?:linear|patch)\s+projection\b",
    r"\b(?:vanilla\s+)?transformer\s+encoder\b",
    r"\bmulti[- ]head\s+(?:self[- ])?attention\b",
    r"\bscaled\s+dot[- ]product\s+and\s+residual\s+attention\b",
    r"\b(?:scaled\s+dot[- ]product|residual)\s+attention\b",
    r"\b(?:flatten(?:ing)?(?:\s+layer)?(?:\s+with|\s+and)?\s+linear|linear\s+forecasting)\s+head\b",
    r"\binstance\s+(?:normalization|denormalization)\b",
    r"\bmasked(?:\s+patch)?\s+(?:pre[- ]training|pre[- ]trained\s+representation|reconstruction)\b",
    r"\b(?:mse|mean\s+squared\s+error)(?:\s+forecasting)?\s+(?:loss|objective)\b",
    r"\b(?:series\s+)?decomposition(?:\s+wrapper)?\b",
    r"\bself[- ]supervised\s+(?:pre[- ]training|representation\s+learning)\b",
)]
_DEFINITION = re.compile(rf"\b((?:{_WORD}\s+){{1,5}}{_WORD})\s*\(([A-Z][A-Z0-9-]{{1,10}})\)")
_LEADING = {"a", "an", "the", "this", "that", "these", "those", "our", "their", "each", "with",
            "using", "for", "from", "into", "we", "is", "are", "be", "to", "of", "and", "or",
            "introduce", "introduces", "use", "uses", "apply", "applies"}
_GENERIC = {"related work", "model structure", "conclusion and future work", "introduction",
            "ablation study", "representation learning"}


def canonical_term(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).casefold()
    value = re.sub(r"[-–—]+", " ", value)
    value = re.sub(r"[^a-z0-9]+", " ", value)
    return " ".join(value.split())


def stable_concept_id(paper_sha256: str, term: str, mention: ConceptMention) -> str:
    if not _SHA256.fullmatch(paper_sha256):
        raise ValueError("paperSha256 must be 64 lowercase hex characters")
    anchor = f"{mention.section or ''}|{mention.page if mention.page is not None else ''}|{mention.paragraphId}"
    payload = f"{paper_sha256}\n{canonical_term(term)}\n{anchor}".encode()
    return "pc_" + hashlib.sha256(payload).hexdigest()[:24]


@dataclass(frozen=True)
class _LocatedText:
    text: str
    source: str
    section: str | None
    page: int | None
    paragraph_id: str
    order: int


class ConceptExtractor:
    def __init__(self, max_concepts: int = MAX_CONCEPTS):
        self.max_concepts = max_concepts

    def extract(self, paper: dict, paper_sha256: str) -> tuple[list[Concept], list[str]]:
        if not _SHA256.fullmatch(paper_sha256 or ""):
            raise ValueError("paperSha256 must be 64 lowercase hex characters")
        located = self._located_text(paper)
        occurrences: dict[str, list[tuple[str, _LocatedText]]] = defaultdict(list)
        aliases: dict[str, set[str]] = defaultdict(set)
        for item in located:
            for phrase, phrase_aliases in self._phrases(item):
                key = canonical_term(phrase)
                if 2 <= len(key.split()) <= 6 and key not in _GENERIC:
                    occurrences[key].append((phrase, item))
                    aliases[key].update(phrase_aliases)

        ranked = sorted(occurrences, key=lambda key: (-self._score(key, occurrences[key]), key))
        ranked = self._suppress_subphrases(ranked, occurrences)[:self.max_concepts]
        warnings = []
        if len(occurrences) > self.max_concepts:
            warnings.append(f"CONCEPT_LIMIT_APPLIED: retained={self.max_concepts} dropped={len(occurrences)-self.max_concepts}")
        concepts = [self._concept(key, occurrences[key], aliases[key], paper_sha256) for key in ranked]
        return concepts, warnings

    def _located_text(self, paper: dict) -> list[_LocatedText]:
        result: list[_LocatedText] = []
        order = 0
        title = str(paper.get("title") or "").strip()
        if title:
            result.append(_LocatedText(title, "title", None, None, "title", order)); order += 1
        abstract = str(paper.get("abstract") or "").strip()
        if abstract:
            result.append(_LocatedText(abstract, "abstract", "Abstract", None, "abstract", order)); order += 1
        for si, section in enumerate(paper.get("sections") or [], 1):
            heading = str(section.get("heading") or "").strip()
            page = section.get("page")
            if heading:
                result.append(_LocatedText(heading, "heading", heading, page, f"{si}.0", order)); order += 1
            for pi, paragraph in enumerate(section.get("paragraphs") or [], 1):
                text = str(paragraph).strip()
                if text:
                    result.append(_LocatedText(text, "paragraph", heading or None, page, f"{si}.{pi}", order)); order += 1
        return result

    def _phrases(self, item: _LocatedText) -> list[tuple[str, set[str]]]:
        found: list[tuple[str, set[str]]] = []
        cleaned = re.sub(r"^(?:[A-Z]?\.?\d+(?:\.\d+)*\.?\s+)", "", item.text).strip()
        words = re.findall(_WORD, cleaned)
        if item.source in {"title", "heading"} and 2 <= len(words) <= 6:
            found.append((" ".join(words), set()))
        for pattern in _COMPOUND_PATTERNS:
            for match in pattern.finditer(cleaned):
                phrase = self._trim(match.group(0))
                if phrase:
                    found.append((phrase, self._surface_aliases(phrase)))
        for match in _DEFINITION.finditer(cleaned):
            phrase, acronym = self._trim(match.group(1)), match.group(2)
            if phrase:
                found.append((phrase, {acronym}))
        return found

    def _trim(self, phrase: str) -> str:
        words = phrase.strip(" ,.;:()[]").split()
        while words and canonical_term(words[0]) in _LEADING:
            words.pop(0)
        return " ".join(words[-6:])

    def _surface_aliases(self, phrase: str) -> set[str]:
        aliases = set()
        spaced = re.sub(r"[-–—]+", " ", phrase)
        if spaced != phrase:
            aliases.add(spaced)
        return aliases

    def _score(self, key: str, values: list[tuple[str, _LocatedText]]) -> int:
        sources = {item.source for _, item in values}
        return len(values) * 4 + len(key.split()) + (5 if "heading" in sources else 0) + (2 if "abstract" in sources else 0)

    def _suppress_subphrases(self, ranked: list[str], occurrences) -> list[str]:
        kept: list[str] = []
        for key in ranked:
            tokens = key.split()
            if any(len(tokens) < len(other.split()) and f" {key} " in f" {other} " for other in kept):
                continue
            kept.append(key)
        return kept

    def _concept(self, key: str, values: list[tuple[str, _LocatedText]], alias_values: set[str],
                 paper_sha256: str) -> Concept:
        unique: dict[tuple, tuple[str, _LocatedText]] = {}
        for phrase, item in values:
            unique[(item.section, item.page, item.paragraph_id)] = (phrase, item)
        ordered = sorted(unique.values(), key=lambda value: value[1].order)
        surface, primary = ordered[0]
        mentions = [ConceptMention(section=item.section, page=item.page, paragraphId=item.paragraph_id,
                                   evidenceText=item.text[:500]) for _, item in ordered]
        aliases = sorted({alias for alias in alias_values if canonical_term(alias) != key}, key=canonical_term)
        return Concept(conceptId=stable_concept_id(paper_sha256, surface, mentions[0]), term=surface,
                       aliases=aliases, extractorVersion=EXTRACTOR_VERSION, mentions=mentions,
                       source=primary.source, section=primary.section, page=primary.page,
                       evidenceText=primary.text[:500], paragraphId=primary.paragraph_id)


concept_extractor = ConceptExtractor()
