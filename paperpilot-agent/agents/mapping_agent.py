"""
Mapping Agent — 论文概念 ↔ 代码模块关联.

核心思路: 双向 Embedding + 语义匹配

    论文方法概念描述 → embedding
    代码函数/类的 docstring + 签名 → embedding
        ↓
    cosine similarity 匹配
        ↓
    LLM 验证映射合理性 + 生成说明
"""

import logging

log = logging.getLogger(__name__)


class MappingAgent:
    """论文-代码映射 Agent — embedding 匹配 + LLM 验证."""

    async def run(self, paper_result: dict, code_result: dict) -> dict:
        """
        建立论文概念与代码模块的映射关系.

        Args:
            paper_result: PaperAgent 输出的结构化 JSON
            code_result: CodeAgent 输出的代码结构分析

        Returns:
            {
                "mappings": [
                    {
                        "paper_concept": "Patch Embedding",
                        "confidence": 0.92,
                        "code_location": "models/PatchTST.py → class PatchEmbedding",
                        "explanation": "...",
                        "key_evidence": [...]
                    }
                ]
            }
        """
        # TODO: 实现 embedding 匹配 + LLM 验证
        log.info("MappingAgent running: %d concepts vs %d modules",
                 len(paper_result.get("method", [])),
                 len(code_result.get("core_modules", [])))
        return {"status": "stub", "message": "MappingAgent not yet implemented"}
