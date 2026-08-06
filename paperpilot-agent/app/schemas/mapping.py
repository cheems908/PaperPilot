"""概念—代码映射阶段 schema：含语义/符号/关键词/验证分项分数、统一总分与状态. """
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class MappingCandidate(BaseModel):
    symbolRef: Dict[str, Any]  # {filePath, qualifiedName, name, startLine, commitSha}
    semanticScore: float = 0.0
    symbolScore: float = 0.0
    keywordScore: float = 0.0
    documentationScore: float = 0.0
    verificationScore: float = 0.0
    totalScore: float = 0.0
    status: str  # VERIFIED / NEEDS_REVIEW / REJECTED
    degraded: bool = False
    matchedTokens: List[str] = Field(default_factory=list)
    codeEvidence: str = ""
    verificationReason: str = ""


class ConceptMention(BaseModel):
    section: Optional[str] = None
    page: Optional[int] = None
    paragraphId: str
    evidenceText: str


class Concept(BaseModel):
    conceptId: str
    term: str
    aliases: List[str] = Field(default_factory=list)
    extractorVersion: str = "compound-rule-v1"
    mentions: List[ConceptMention] = Field(default_factory=list)
    source: str  # title / heading / paragraph
    section: Optional[str] = None
    page: Optional[int] = None
    evidenceText: str
    paragraphId: Optional[str] = None
    decision: str = "MAPPED"  # MAPPED / ABSTAINED
    abstentionReason: Optional[str] = None
    candidates: List[MappingCandidate] = Field(default_factory=list)


class MappingOutput(BaseModel):
    commitSha: str = ""
    concepts: List[Concept] = Field(default_factory=list)
    stats: Dict[str, int] = Field(default_factory=dict)
    degraded: bool = False
    warnings: List[str] = Field(default_factory=list)
