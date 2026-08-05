"""TEI XML 安全解析：拒绝 DOCTYPE/ENTITY（禁止外部实体与实体膨胀），提取论文结构. """
import xml.etree.ElementTree as ET

from app.schemas.paper import PaperBody, Section

_TEI_NS = "{http://www.tei-c.org/ns/1.0}"


class TeiParseError(ValueError):
    """TEI 结构非法或含被禁的 DTD/实体。"""


def parse_tei(xml_text: str) -> PaperBody:
    """安全解析 GROBID fulltext 返回的 TEI XML，返回结构化论文。"""
    if "<!DOCTYPE" in xml_text or "<!ENTITY" in xml_text:
        raise TeiParseError("TEI XML 含 DTD/实体声明，已拒绝（禁止外部实体）")
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as e:
        raise TeiParseError(f"invalid tei xml: {e}") from e

    title = _join(_find_texts(root, f"{_TEI_NS}teiHeader/{_TEI_NS}fileDesc/{_TEI_NS}titleStmt/{_TEI_NS}title"))
    authors = [
        a for a in (_join(_find_texts(root, f"{_TEI_NS}teiHeader/{_TEI_NS}fileDesc/{_TEI_NS}titleStmt/{_TEI_NS}author/{_TEI_NS}persName")) or "")
        .split(";")
        if a.strip()
    ]
    abstract = _join(_find_texts(root, f"{_TEI_NS}teiHeader/{_TEI_NS}profileDesc/{_TEI_NS}abstract"))
    sections = _extract_sections(root)

    return PaperBody(title=title or "unknown", abstract=abstract or None, authors=authors, sections=sections)


def _extract_sections(root: ET.Element) -> list:
    body = root.find(f"{_TEI_NS}text/{_TEI_NS}body")
    if body is None:
        return []
    sections: list = []
    for div in body.findall(f"{_TEI_NS}div"):
        head = div.find(f"{_TEI_NS}head")
        if head is None:
            continue
        paragraphs = [_join(list(p.itertext())) for p in div.iter(f"{_TEI_NS}p")]
        paragraphs = [p for p in paragraphs if p]
        page = _page_before(div, div.find(f"{_TEI_NS}p"))
        sections.append(Section(heading=_join(list(head.itertext())), level=1, page=page, paragraphs=paragraphs))
    return sections


def _page_before(container: ET.Element, target: ET.Element | None) -> int | None:
    """返回 target 之前最近一个 <pb n="数字"> 的页码；无法确定返回 None。"""
    if target is None:
        return None
    last: int | None = None
    for el in container.iter():
        if el is target:
            break
        if el.tag == f"{_TEI_NS}pb":
            n = el.get("n")
            if n and n.isdigit():
                last = int(n)
    return last


def _find_texts(root: ET.Element, path: str) -> list:
    found = root.findall(path)
    return [_join(list(el.itertext())) for el in found if el is not None]


def _join(parts) -> str:
    return " ".join(p.strip() for p in parts if p and p.strip()).strip()
