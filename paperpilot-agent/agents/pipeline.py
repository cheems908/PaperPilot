"""
LangGraph 分析流水线 — 编排 Paper/Code/Mapping/Env 四个 Agent.

流水线结构 (StateGraph):

    PaperAgent ──→ CodeAgent ──→ MappingAgent ──→ EnvAgent
                       ↑                              │
                       └── (无 GitHub URL 时跳过) ─────┘

状态定义参考 design doc 第三节。
"""

import logging
from typing import TypedDict

log = logging.getLogger(__name__)


class AnalysisState(TypedDict):
    task_id: str
    paper_url: str
    github_url: str | None
    stage: str              # 当前阶段
    progress: int           # 0-100
    paper_result: dict | None
    code_result: dict | None
    mapping_result: dict | None
    env_result: dict | None
    errors: list[str]


async def run_analysis_pipeline(paper_url: str, github_url: str | None = None) -> dict:
    """
    执行完整分析流水线（MVP 阶段：串行调用）.

    后续将改为 LangGraph StateGraph 编译执行。
    """
    state: AnalysisState = {
        "task_id": "placeholder",
        "paper_url": paper_url,
        "github_url": github_url,
        "stage": "PAPER_ANALYSIS",
        "progress": 0,
        "paper_result": None,
        "code_result": None,
        "mapping_result": None,
        "env_result": None,
        "errors": [],
    }

    log.info("Starting analysis pipeline for paper: %s", paper_url)

    # Stage 1: Paper Understanding
    state["stage"] = "PAPER_ANALYSIS"
    state["progress"] = 25
    # TODO: 调用 PaperAgent 解析 PDF → 结构化 JSON
    state["paper_result"] = {"status": "stub", "message": "PaperAgent not yet implemented"}

    # Stage 2: Code Repository Analysis (if github_url provided)
    if github_url:
        state["stage"] = "CODE_ANALYSIS"
        state["progress"] = 50
        # TODO: 调用 CodeAgent 克隆 + AST 分析
        state["code_result"] = {"status": "stub", "message": "CodeAgent not yet implemented"}

    # Stage 3: Paper-Code Mapping
    state["stage"] = "MAPPING"
    state["progress"] = 75
    # TODO: 调用 MappingAgent 做 embedding 匹配
    state["mapping_result"] = {"status": "stub", "message": "MappingAgent not yet implemented"}

    # Stage 4: Environment Setup
    state["stage"] = "ENV_SETUP"
    state["progress"] = 100
    # TODO: 调用 EnvAgent 生成 Dockerfile
    state["env_result"] = {"status": "stub", "message": "EnvAgent not yet implemented"}

    state["stage"] = "COMPLETED"
    return dict(state)
