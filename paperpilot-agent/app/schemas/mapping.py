"""概念—代码映射阶段 schema."""
from pydantic import BaseModel


class MappingOutput(BaseModel):
    conceptCount: int = 0
    mappingCount: int = 0
