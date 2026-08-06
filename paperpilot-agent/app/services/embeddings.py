"""Embedding 抽象：语义召回的嵌入提供方.

MVP 不引入 ChromaDB/真实嵌入模型；默认使用确定性 hash 嵌入（测试完全确定），
真实提供方后续可替换实现 EmbeddingProvider。
"""
import hashlib
import math
from typing import List, Protocol

from app.core.config import settings


class EmbeddingProvider(Protocol):
    def embed(self, texts: List[str]) -> List[List[float]]: ...


class HashEmbeddingProvider:
    """确定性 hash 嵌入：按 token 的 SHA-256 哈希累加符号向量并归一化。"""

    def __init__(self, dim: int | None = None):
        self.dim = dim or settings.embedding_dim

    def embed(self, texts: List[str]) -> List[List[float]]:
        return [self._embed_one(t) for t in texts]

    def _embed_one(self, text: str) -> List[float]:
        from app.services.mapping_analyzer import _tokens
        vec = [0.0] * self.dim
        for token in _tokens(text):
            digest = hashlib.sha256(token.encode("utf-8")).digest()
            idx = int.from_bytes(digest[:4], "big") % self.dim
            sign = 1.0 if digest[4] % 2 == 0 else -1.0
            vec[idx] += sign
        norm = math.sqrt(sum(x * x for x in vec))
        if norm == 0:
            return vec
        return [x / norm for x in vec]


def cosine(a: List[float], b: List[float]) -> float:
    """余弦相似度（向量已归一化时为点积）。"""
    if not a or not b or len(a) != len(b):
        return 0.0
    return sum(x * y for x, y in zip(a, b))


hash_embedding_provider = HashEmbeddingProvider()
