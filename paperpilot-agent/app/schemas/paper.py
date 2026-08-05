"""论文解析阶段 schema：结构化论文模型（页码无法可靠确定时返回 null，不猜测）. """
from typing import Dict, List, Optional

from pydantic import BaseModel, Field


class Section(BaseModel):
    heading: str
    level: int = 1
    page: Optional[int] = None
    paragraphs: List[str] = Field(default_factory=list)


class PaperBody(BaseModel):
    title: str
    abstract: Optional[str] = None
    authors: List[str] = Field(default_factory=list)
    sections: List[Section] = Field(default_factory=list)


class PaperParserInfo(BaseModel):
    name: str
    version: str
    fallbackUsed: bool = False


class PaperParseOutput(BaseModel):
    paper: PaperBody
    parser: PaperParserInfo
    warnings: List[str] = Field(default_factory=list)
    stats: Dict[str, int] = Field(default_factory=dict)
