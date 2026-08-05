"""论文解析服务单测：GROBID TEI 路径、PyMuPDF 降级、路径安全、sha256/大小/魔数、错误码."""
from pathlib import Path

import fitz
import pytest

from app.clients.grobid_client import GrobidUnavailableError
from app.core.errors import StageServiceError
from app.schemas.common import StageRequest
from app.services.paper_parser import PaperParser

FIXTURES = Path(__file__).parent / "fixtures"
TEI_FIXTURE = (FIXTURES / "patchtst-sample.tei.xml").read_text(encoding="utf-8")


class _StubGrobid:
    def __init__(self, tei=None, error=None):
        self.tei = tei
        self.error = error

    def process_fulltext(self, pdf_path):
        if self.error:
            raise self.error
        return self.tei


def _make_pdf(path: Path, text: str = "PatchTST Abstract\n1 Introduction\nTime series matter.\n") -> None:
    doc = fitz.open()
    page = doc.new_page()
    page.insert_text((72, 72), text)
    doc.save(str(path))
    doc.close()


def _sha256(path: Path) -> str:
    import hashlib
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _parser(storage_root: Path, grobid) -> PaperParser:
    return PaperParser(grobid_client=grobid, storage_root=str(storage_root), max_pdf_bytes=5 * 1024 * 1024)


def _req(storage_path: str, sha256: str | None = None) -> StageRequest:
    source = {"fileId": 3, "storagePath": storage_path}
    if sha256:
        source["sha256"] = sha256
    return StageRequest(taskId=7, stageExecutionId=34, stage="PARSE_PAPER", attempt=1, input={"source": source})


def test_grobid_path_returns_structured_output(tmp_path: Path):
    pdf = tmp_path / "p.pdf"
    _make_pdf(pdf)
    parser = _parser(tmp_path, _StubGrobid(tei=TEI_FIXTURE))

    resp = parser.process(_req(pdf.name, _sha256(pdf)))

    out = resp.output
    assert out["parser"]["name"] == "grobid"
    assert out["parser"]["fallbackUsed"] is False
    assert out["paper"]["title"] == "A Time Series is Worth 64 Words"
    assert out["paper"]["abstract"] and "forecasting" in out["paper"]["abstract"]
    assert [s["heading"] for s in out["paper"]["sections"]] == ["1 Introduction", "2 Method", "3 Experiments"]
    assert out["stats"]["sectionCount"] == 3
    assert out["stats"]["paragraphCount"] >= 3
    # 重复调用结构一致
    again = parser.process(_req(pdf.name, _sha256(pdf)))
    assert resp.output == again.output


def test_grobid_unavailable_falls_back_to_pymupdf(tmp_path: Path):
    pdf = tmp_path / "p.pdf"
    _make_pdf(pdf, "My Paper Title\n1 Introduction\nTime series matter.\n2 Method\nWe split into patches.\n")
    parser = _parser(tmp_path, _StubGrobid(error=GrobidUnavailableError("down")))

    resp = parser.process(_req(pdf.name, _sha256(pdf)))

    out = resp.output
    assert out["parser"]["name"] == "pymupdf"
    assert out["parser"]["fallbackUsed"] is True
    assert out["warnings"] and any("GROBID_UNAVAILABLE" in w for w in out["warnings"])
    assert out["paper"]["title"] == "My Paper Title"
    assert any(s["heading"].startswith("1 Introduction") for s in out["paper"]["sections"])


def test_damaged_pdf_returns_invalid_pdf_non_retryable(tmp_path: Path):
    # 损坏 PDF：通过魔数检查但无法被 PyMuPDF 打开（GROBID 不可用 → 降级 → 打开失败 → INVALID_PDF）
    bad = tmp_path / "bad.pdf"
    bad.write_bytes(b"%PDF-1.7 garbage not a real pdf")
    parser = _parser(tmp_path, _StubGrobid(error=GrobidUnavailableError("down")))

    with pytest.raises(StageServiceError) as exc:
        parser.process(_req(bad.name, _sha256(bad)))
    assert exc.value.error_code == "INVALID_PDF"
    assert exc.value.retryable is False


def test_not_a_pdf_magic_rejected(tmp_path: Path):
    not_pdf = tmp_path / "f.pdf"
    not_pdf.write_bytes(b"PK\x03\x04 not a pdf")
    parser = _parser(tmp_path, _StubGrobid(tei=TEI_FIXTURE))

    with pytest.raises(StageServiceError) as exc:
        parser.process(_req(not_pdf.name, _sha256(not_pdf)))
    assert exc.value.error_code == "INVALID_PDF"
    assert exc.value.retryable is False


def test_path_traversal_rejected(tmp_path: Path):
    outside = tmp_path.parent / "outside.pdf"
    _make_pdf(outside)
    parser = _parser(tmp_path, _StubGrobid(tei=TEI_FIXTURE))

    with pytest.raises(StageServiceError) as exc:
        parser.process(_req("../outside.pdf"))
    assert exc.value.error_code == "PATH_OUTSIDE_STORAGE_ROOT"
    assert exc.value.retryable is False


def test_absolute_path_rejected(tmp_path: Path):
    pdf = tmp_path / "p.pdf"
    _make_pdf(pdf)
    parser = _parser(tmp_path, _StubGrobid(tei=TEI_FIXTURE))

    with pytest.raises(StageServiceError) as exc:
        parser.process(_req(str(pdf)))
    assert exc.value.error_code == "PATH_OUTSIDE_STORAGE_ROOT"


def test_symlink_escape_rejected(tmp_path: Path):
    outside = tmp_path.parent / "secret.pdf"
    _make_pdf(outside)
    link = tmp_path / "link.pdf"
    link.symlink_to(outside)
    parser = _parser(tmp_path, _StubGrobid(tei=TEI_FIXTURE))

    with pytest.raises(StageServiceError) as exc:
        parser.process(_req(link.name))
    assert exc.value.error_code == "PATH_OUTSIDE_STORAGE_ROOT"


def test_sha256_mismatch_rejected(tmp_path: Path):
    pdf = tmp_path / "p.pdf"
    _make_pdf(pdf)
    parser = _parser(tmp_path, _StubGrobid(tei=TEI_FIXTURE))

    with pytest.raises(StageServiceError) as exc:
        parser.process(_req(pdf.name, "0" * 64))
    assert exc.value.error_code == "FILE_HASH_MISMATCH"
    assert exc.value.retryable is False


def test_file_not_found(tmp_path: Path):
    parser = _parser(tmp_path, _StubGrobid(tei=TEI_FIXTURE))
    with pytest.raises(StageServiceError) as exc:
        parser.process(_req("missing.pdf"))
    assert exc.value.error_code == "FILE_NOT_FOUND"


def test_missing_storage_path_is_invalid_input(tmp_path: Path):
    parser = _parser(tmp_path, _StubGrobid(tei=TEI_FIXTURE))
    with pytest.raises(StageServiceError) as exc:
        parser.process(StageRequest(taskId=7, stageExecutionId=34, stage="PARSE_PAPER", attempt=1, input={}))
    assert exc.value.error_code == "INVALID_PAPER_INPUT"


def test_tei_with_doctype_rejected():
    from app.core.tei import TeiParseError, parse_tei
    evil = ('<?xml version="1.0"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>'
            '<TEI xmlns="http://www.tei-c.org/ns/1.0"><teiHeader/></TEI>')
    with pytest.raises(TeiParseError):
        parse_tei(evil)


def test_grobid_bad_tei_is_parse_failed(tmp_path: Path):
    pdf = tmp_path / "p.pdf"
    _make_pdf(pdf)
    parser = _parser(tmp_path, _StubGrobid(tei="<not-tei>"))
    with pytest.raises(StageServiceError) as exc:
        parser.process(_req(pdf.name, _sha256(pdf)))
    assert exc.value.error_code == "PAPER_PARSE_FAILED"
    assert exc.value.retryable is False
