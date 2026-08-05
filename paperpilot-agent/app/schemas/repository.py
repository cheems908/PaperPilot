"""仓库克隆 / 代码索引阶段 schema."""
from typing import Dict, List, Optional

from pydantic import BaseModel, Field


class CloneOutput(BaseModel):
    canonicalUrl: str
    commitSha: str
    workspaceRef: str


class Symbol(BaseModel):
    kind: str  # module/class/function/method/async_function/async_method
    name: str
    qualifiedName: str
    signature: str = ""
    docstring: Optional[str] = None
    startLine: int
    endLine: int
    parent: Optional[str] = None


class FileSymbols(BaseModel):
    path: str  # 仓库相对 POSIX 路径
    symbols: List[Symbol] = Field(default_factory=list)


class IndexOutput(BaseModel):
    repo: str = ""
    commitSha: str
    files: List[FileSymbols] = Field(default_factory=list)
    warnings: List[str] = Field(default_factory=list)
    stats: Dict[str, int] = Field(default_factory=dict)
