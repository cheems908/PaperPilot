"""仓库克隆 / 代码索引阶段 schema."""
from pydantic import BaseModel


class CloneOutput(BaseModel):
    repo: str
    commit: str


class IndexOutput(BaseModel):
    repo: str
    symbolCount: int = 0
