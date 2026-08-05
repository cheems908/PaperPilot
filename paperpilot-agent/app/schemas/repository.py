"""仓库克隆 / 代码索引阶段 schema."""
from pydantic import BaseModel


class CloneOutput(BaseModel):
    canonicalUrl: str
    commitSha: str
    workspaceRef: str


class IndexOutput(BaseModel):
    repo: str
    symbolCount: int = 0
