import pytest

from app.services.concept_extractor import ConceptExtractor, canonical_term, stable_concept_id


SHA = "a" * 64
PAPER = {
    "title": "Patch-based Time-series Forecasting",
    "abstract": "We introduce multi-head self-attention (MHSA) with channel-independent Transformer encoding.",
    "sections": [
        {"heading": "Instance Normalization", "page": 4, "paragraphs": [
            "Instance normalization restores statistics after forecasting.",
            "The instance normalization operation is shared across channels.",
        ]},
        {"heading": "Transformer Encoder", "page": 5, "paragraphs": [
            "We use scaled dot-product and residual attention for patch representations.",
            "A learnable positional encoding is added after linear patch projection.",
        ]},
        {"heading": "Loss Function", "page": 6, "paragraphs": [
            "The mean squared error objective averages forecasting errors.",
        ]},
    ],
}


def test_extracts_title_abstract_heading_body_and_compound_phrases():
    concepts, _ = ConceptExtractor().extract(PAPER, SHA)
    terms = {canonical_term(c.term) for c in concepts}
    assert "patch based time series forecasting" in terms
    assert "multi head self attention" in terms
    assert "instance normalization" in terms
    assert "scaled dot product and residual attention" in terms
    assert "learnable positional encoding" in terms
    assert {c.source for c in concepts} >= {"title", "abstract", "heading", "paragraph"}


def test_mentions_merge_and_preserve_original_evidence():
    concepts, _ = ConceptExtractor().extract(PAPER, SHA)
    concept = next(c for c in concepts if canonical_term(c.term) == "instance normalization")
    assert len(concept.mentions) >= 2
    assert all(m.evidenceText for m in concept.mentions)
    assert concept.term == "Instance Normalization"


def test_ids_and_order_are_deterministic_and_not_benchmark_ids():
    extractor = ConceptExtractor()
    first = extractor.extract(PAPER, SHA)[0]
    second = extractor.extract(PAPER, SHA)[0]
    assert [c.model_dump() for c in first] == [c.model_dump() for c in second]
    assert all(c.conceptId.startswith("pc_") and len(c.conceptId) == 27 for c in first)
    assert not any(c.conceptId.startswith("PT-") for c in first)


def test_stable_id_uses_primary_anchor_and_validates_sha():
    concept = ConceptExtractor().extract(PAPER, SHA)[0][0]
    assert stable_concept_id(SHA, concept.term, concept.mentions[0]) == concept.conceptId
    with pytest.raises(ValueError):
        ConceptExtractor().extract(PAPER, "bad")


def test_subphrases_are_suppressed_and_limit_is_reported():
    concepts, warnings = ConceptExtractor(max_concepts=3).extract(PAPER, SHA)
    assert len(concepts) == 3
    assert warnings and warnings[0].startswith("CONCEPT_LIMIT_APPLIED")
    canonical = [canonical_term(c.term) for c in concepts]
    assert len(canonical) == len(set(canonical))
