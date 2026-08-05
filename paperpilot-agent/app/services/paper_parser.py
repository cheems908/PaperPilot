"""论文解析服务：安全解析受控本地 PDF（GROBID TEI → PyMuPDF 降级），返回确定性论文结构.

资源契约（T3-02）：input 提供 fileId + 相对 storagePath + sha256；
路径在 storage_root 内解析，拒绝绝对路径 / `..` / 符号链接逃逸，
并校验大小、PDF 魔数与 SHA-256。不调用 LLM 提炼概念。
"""
import hashlib
import time
from pathlib import Path

from app.clients.grobid_client import GrobidClient, GrobidUnavailableError
from app.core.config import SimulateOptions, settings
from app.core.errors import StageErrorCode, StageServiceError
from app.core.tei import TeiParseError, parse_tei
from app.schemas.common import StageRequest, StageSuccessResponse
from app.schemas.paper import PaperBody, PaperParseOutput, PaperParserInfo, Section

_PDF_MAGIC = b"%PDF-"
_FALLBACK_VERSION = "pymupdf-1.28"


def _sha256_hex(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class PaperParser:
    def __init__(self, grobid_client: GrobidClient | None = None,
                 storage_root: str | None = None, max_pdf_bytes: int | None = None):
        self.grobid_client = grobid_client or GrobidClient(
            base_url=settings.grobid_url,
            timeout_seconds=settings.grobid_timeout_seconds,
            max_retries=settings.grobid_max_retries,
        )
        self.storage_root = Path(storage_root or settings.storage_root).resolve()
        self.max_pdf_bytes = max_pdf_bytes or settings.max_pdf_bytes

    def process(self, req: StageRequest, simulate: SimulateOptions | None = None) -> StageSuccessResponse:
        simulate = simulate or SimulateOptions()
        if simulate.failure:
            raise StageServiceError(StageErrorCode.STAGE_FAILED, "simulated parse failure", retryable=True)
        if simulate.delay_ms > 0:
            time.sleep(simulate.delay_ms / 1000.0)

        storage_path, sha256 = self._source_ref(req)
        pdf_path = self._resolve_pdf(storage_path)
        self._validate_pdf(pdf_path, sha256)
        return self._parse(pdf_path)

    # ── 资源契约 ──────────────────────────────────────────────────────────

    def _source_ref(self, req: StageRequest) -> tuple[str, str | None]:
        raw = req.input if isinstance(req.input, dict) else {}
        source = raw.get("source") if isinstance(raw.get("source"), dict) else raw
        storage_path = source.get("storagePath")
        if not storage_path or not isinstance(storage_path, str):
            raise StageServiceError(
                StageErrorCode.INVALID_PAPER_INPUT, "missing storagePath", retryable=False, status_code=400)
        sha256 = source.get("sha256")
        return storage_path, (sha256 if isinstance(sha256, str) else None)

    def _resolve_pdf(self, storage_path: str) -> Path:
        raw = Path(storage_path)
        if raw.is_absolute():
            raise StageServiceError(
                StageErrorCode.PATH_OUTSIDE_STORAGE_ROOT, "absolute path not allowed",
                retryable=False, status_code=400)
        resolved = (self.storage_root / raw).resolve()
        if not resolved.is_relative_to(self.storage_root):
            raise StageServiceError(
                StageErrorCode.PATH_OUTSIDE_STORAGE_ROOT,
                "path escapes storage root", retryable=False, status_code=400)
        if not resolved.exists():
            raise StageServiceError(
                StageErrorCode.FILE_NOT_FOUND, f"file not found: {storage_path}",
                retryable=False, status_code=404)
        if not resolved.is_file():
            raise StageServiceError(
                StageErrorCode.INVALID_PDF, "not a regular file", retryable=False, status_code=400)
        return resolved

    def _validate_pdf(self, pdf_path: Path, sha256: str | None) -> None:
        if pdf_path.stat().st_size > self.max_pdf_bytes:
            raise StageServiceError(
                StageErrorCode.INVALID_PDF, "pdf exceeds size limit", retryable=False, status_code=400)
        if sha256 and _sha256_hex(pdf_path).lower() != sha256.lower():
            raise StageServiceError(
                StageErrorCode.FILE_HASH_MISMATCH, "sha256 mismatch", retryable=False, status_code=409)
        with open(pdf_path, "rb") as f:
            if f.read(len(_PDF_MAGIC)) != _PDF_MAGIC:
                raise StageServiceError(
                    StageErrorCode.INVALID_PDF, "not a valid PDF (magic mismatch)",
                    retryable=False, status_code=400)

    # ── 解析（GROBID → PyMuPDF 降级）─────────────────────────────────────

    def _parse(self, pdf_path: Path) -> StageSuccessResponse:
        warnings: list[str] = []
        try:
            tei = self.grobid_client.process_fulltext(str(pdf_path))
            body = parse_tei(tei)
            return self._response(body, "grobid", "0.8.1", fallback_used=False, warnings=warnings)
        except GrobidUnavailableError as e:
            warnings.append(f"GROBID_UNAVAILABLE: {e}")
        except TeiParseError as e:
            raise StageServiceError(
                StageErrorCode.PAPER_PARSE_FAILED, f"tei parse failed: {e}",
                retryable=False, status_code=500) from e
        return self._fallback(pdf_path, warnings)

    def _fallback(self, pdf_path: Path, warnings: list[str]) -> StageSuccessResponse:
        try:
            import fitz
        except Exception as e:  # pragma: no cover - 仅当 PyMuPDF 未安装
            raise StageServiceError(
                StageErrorCode.GROBID_UNAVAILABLE, "pymupdf unavailable", retryable=True,
                status_code=503) from e
        try:
            doc = fitz.open(str(pdf_path))
        except Exception as e:
            raise StageServiceError(
                StageErrorCode.INVALID_PDF, f"unreadable pdf: {e}", retryable=False, status_code=400) from e
        try:
            body = _extract_with_pymupdf(doc)
        except Exception as e:
            raise StageServiceError(
                StageErrorCode.PAPER_PARSE_FAILED, f"pymupdf extraction failed: {e}",
                retryable=False, status_code=500) from e
        return self._response(body, "pymupdf", _FALLBACK_VERSION, fallback_used=True, warnings=warnings)

    def _response(self, body: PaperBody, name: str, version: str,
                  fallback_used: bool, warnings: list[str]) -> StageSuccessResponse:
        output = PaperParseOutput(
            paper=body,
            parser=PaperParserInfo(name=name, version=version, fallbackUsed=fallback_used),
            warnings=warnings,
            stats={
                "sectionCount": len(body.sections),
                "paragraphCount": sum(len(s.paragraphs) for s in body.sections),
            },
        )
        return StageSuccessResponse(output=output.model_dump(), workerVersion="0.3.0-paper")


def _extract_with_pymupdf(doc) -> PaperBody:
    """PyMuPDF 降级提取：标题来自元数据或首行；章节用启发式标题识别；页码不可靠返回 null。"""
    lines: list[str] = []
    for page in doc:
        for line in page.get_text().splitlines():
            stripped = line.strip()
            if stripped:
                lines.append(stripped)
    if not lines:
        return PaperBody(title="unknown")

    title = ""
    meta = doc.metadata or {}
    if meta.get("title"):
        title = meta["title"].strip()
    if not title:
        title = lines[0]

    sections: list[Section] = []
    current_heading: str | None = None
    current_paragraphs: list[str] = []
    for line in lines[1:]:
        if _is_heading(line):
            if current_heading:
                sections.append(Section(heading=current_heading, paragraphs=current_paragraphs))
            current_heading = line
            current_paragraphs = []
        elif current_heading is not None:
            current_paragraphs.append(line)
    if current_heading:
        sections.append(Section(heading=current_heading, paragraphs=current_paragraphs))

    return PaperBody(title=title, sections=sections)


def _is_heading(line: str) -> bool:
    """启发式章节标题：较短、不以句号结尾、不以常见行内词开头。"""
    if len(line) > 90 or line.endswith("."):
        return False
    if line[0].islower():
        return False
    return True


paper_parser = PaperParser()
