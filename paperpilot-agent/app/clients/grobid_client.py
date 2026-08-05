"""GROBID 客户端：受控 base URL、连接/读取超时与有限重试，调用 fulltext 接口返回 TEI XML.

连接/超时/服务错误统一抛 :class:`GrobidUnavailableError`，由论文解析服务降级到 PyMuPDF。
"""
from pathlib import Path

import httpx

GROBID_FULLTEXT_PATH = "/api/processFulltextDocument"


class GrobidUnavailableError(RuntimeError):
    """GROBID 连接或服务错误（可重试，触发 PyMuPDF 降级）。"""


class GrobidClient:
    def __init__(self, base_url: str, timeout_seconds: float = 30.0, max_retries: int = 2):
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds
        self.max_retries = max_retries

    def process_fulltext(self, pdf_path: str) -> str:
        """调用 GROBID fulltext 接口，返回 TEI XML；失败抛 :class:`GrobidUnavailableError`。"""
        url = f"{self.base_url}{GROBID_FULLTEXT_PATH}"
        last_error: GrobidUnavailableError | None = None
        for _ in range(self.max_retries + 1):
            try:
                with open(pdf_path, "rb") as f:
                    files = {"input": (Path(pdf_path).name, f, "application/pdf")}
                    resp = httpx.post(url, files=files, timeout=self.timeout_seconds)
                if resp.status_code >= 400:
                    last_error = GrobidUnavailableError(f"grobid http {resp.status_code}")
                    continue
                if not resp.text.strip():
                    last_error = GrobidUnavailableError("grobid empty response")
                    continue
                return resp.text
            except (httpx.HTTPError, OSError) as e:
                last_error = GrobidUnavailableError(f"grobid unavailable: {e}")
        raise last_error
