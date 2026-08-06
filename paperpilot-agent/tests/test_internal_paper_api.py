"""PARSE_PAPER 内部接口契约测试（TestClient + 临时 storage root + stub GROBID）. """
import hashlib
from pathlib import Path

import fitz
from fastapi.testclient import TestClient

from app.clients.grobid_client import GrobidUnavailableError
from app.main import app
from app.services.paper_parser import paper_parser
from tests.test_paper_parser import _StubGrobid, TEI_FIXTURE

client = TestClient(app, backend_options={"use_uvloop": True})


def _make_pdf(path: Path) -> None:
    doc = fitz.open()
    page = doc.new_page()
    page.insert_text((72, 72), "PatchTST Abstract\n1 Introduction\nTime series matter.\n")
    doc.save(str(path))
    doc.close()


def test_parse_endpoint_returns_structured_result(tmp_path: Path, monkeypatch):
    pdf = tmp_path / "p.pdf"
    _make_pdf(pdf)
    sha = hashlib.sha256(pdf.read_bytes()).hexdigest()

    monkeypatch.setattr(paper_parser, "storage_root", tmp_path)
    monkeypatch.setattr(paper_parser, "grobid_client", _StubGrobid(tei=TEI_FIXTURE))

    r = client.post("/internal/v1/papers/parse", json={
        "schemaVersion": 1, "requestId": "req-1", "taskId": 7,
        "stageExecutionId": 34, "stage": "PARSE_PAPER", "attempt": 1,
        "input": {"source": {"fileId": 3, "storagePath": pdf.name, "sha256": sha}},
    })

    assert r.status_code == 200, r.text
    body = r.json()
    assert body["schemaVersion"] == 1
    assert body["success"] is True
    assert body["output"]["parser"]["name"] == "grobid"
    assert body["output"]["paper"]["title"] == "A Time Series is Worth 64 Words"


def test_parse_endpoint_path_escape_returns_uniform_error(tmp_path: Path, monkeypatch):
    outside = tmp_path.parent / "secret.pdf"
    _make_pdf(outside)
    monkeypatch.setattr(paper_parser, "storage_root", tmp_path)
    monkeypatch.setattr(paper_parser, "grobid_client", _StubGrobid(tei=TEI_FIXTURE))

    r = client.post("/internal/v1/papers/parse", json={
        "schemaVersion": 1, "requestId": "req-1", "taskId": 7,
        "stageExecutionId": 34, "stage": "PARSE_PAPER", "attempt": 1,
        "input": {"source": {"fileId": 3, "storagePath": "../secret.pdf"}},
    })

    assert r.status_code == 400
    body = r.json()
    assert body["success"] is False
    assert body["errorCode"] == "PATH_OUTSIDE_STORAGE_ROOT"
    assert body["retryable"] is False
    assert "Traceback" not in r.text


def test_parse_endpoint_fallback_succeeds_with_warning(tmp_path: Path, monkeypatch):
    pdf = tmp_path / "p.pdf"
    _make_pdf(pdf)
    sha = hashlib.sha256(pdf.read_bytes()).hexdigest()
    monkeypatch.setattr(paper_parser, "storage_root", tmp_path)
    monkeypatch.setattr(paper_parser, "grobid_client", _StubGrobid(error=GrobidUnavailableError("down")))

    r = client.post("/internal/v1/papers/parse", json={
        "schemaVersion": 1, "requestId": "req-1", "taskId": 7,
        "stageExecutionId": 34, "stage": "PARSE_PAPER", "attempt": 1,
        "input": {"source": {"fileId": 3, "storagePath": pdf.name, "sha256": sha}},
    })

    assert r.status_code == 200, r.text
    out = r.json()["output"]
    assert out["parser"]["name"] == "pymupdf"
    assert out["parser"]["fallbackUsed"] is True
    assert any("GROBID_UNAVAILABLE" in w for w in out["warnings"])
