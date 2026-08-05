"""论文解析阶段 schema."""
from typing import List

from pydantic import BaseModel, Field


class PaperParseOutput(BaseModel):
    title: str
    sections: int = 0
    authors: List[str] = Field(default_factory=list)
