import copy
import hashlib
import json
from pathlib import Path

import fitz
import pytest

from tools.validate_patchtst_fixture import FIXTURE_DIR, FixtureValidationError, validate_fixture


def test_fixture_schema_manifest_and_boundary_cases_are_valid():
    summary = validate_fixture()

    assert summary == {"concepts": 12, "mappings": 14, "uncertainConcepts": 3, "files": 6}


def test_gold_ids_are_unique_and_boundary_cases_are_present():
    gold = json.loads((FIXTURE_DIR / "gold.json").read_text(encoding="utf-8"))
    ids = [item["id"] for item in gold["concepts"]]
    certainties = {item["certainty"] for item in gold["concepts"]}

    assert len(ids) == len(set(ids))
    assert {"CONFIRMED", "AUXILIARY", "LOW_CONFIDENCE", "NO_EXPLICIT_IMPLEMENTATION"} <= certainties
    assert any(len(item["mappings"]) > 1 for item in gold["concepts"])
    assert any(not item["mappings"] for item in gold["concepts"])


def test_validator_rejects_line_number_drift(tmp_path: Path):
    for name in ("benchmark.json", "gold.schema.json", "gold.json", "source_manifest.json"):
        (tmp_path / name).write_bytes((FIXTURE_DIR / name).read_bytes())
    gold = json.loads((tmp_path / "gold.json").read_text(encoding="utf-8"))
    changed = copy.deepcopy(gold)
    changed["concepts"][0]["mappings"][0]["startLine"] += 1
    (tmp_path / "gold.json").write_text(json.dumps(changed), encoding="utf-8")

    with pytest.raises(FixtureValidationError, match="行号"):
        validate_fixture(tmp_path)


def test_local_pdf_matches_frozen_checksum_and_title_when_available():
    pdf = Path(__file__).resolve().parents[1] / "data" / "papers" / "PatchTST.pdf"
    if not pdf.exists():
        pytest.skip("local PatchTST.pdf is not present")
    metadata = json.loads((FIXTURE_DIR / "benchmark.json").read_text(encoding="utf-8"))["paper"]

    assert pdf.stat().st_size == metadata["sizeBytes"]
    assert hashlib.sha256(pdf.read_bytes()).hexdigest() == metadata["sha256"]
    document = fitz.open(pdf)
    assert document.page_count == metadata["pageCount"]
    first_page = document[0].get_text()
    assert "Published as a conference paper at ICLR 2023" in first_page
    assert "A TIME SERIES IS WORTH 64 WORDS" in first_page
