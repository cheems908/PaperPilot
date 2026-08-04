"""
PaperPilot Agent Worker — FastAPI + LangGraph 多 Agent 协同引擎

启动方式:
    uvicorn main:app --reload --port 8001
"""

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from agents.pipeline import run_analysis_pipeline

log = logging.getLogger("paperpilot.agent")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期：启动时初始化，关闭时清理资源."""
    log.setLevel(logging.INFO)
    log.info("PaperPilot Agent Worker starting...")
    # TODO: 初始化 ChromaDB 连接、预热 Embedding 模型
    yield
    log.info("PaperPilot Agent Worker shutting down...")


app = FastAPI(
    title="PaperPilot Agent Worker",
    version="0.1.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
async def health():
    """健康检查."""
    return {"status": "ok", "service": "paperpilot-agent"}


@app.post("/api/analyze")
async def analyze(paper_url: str, github_url: str = None):
    """
    触发完整分析流水线.

    POST /api/analyze
    {
        "paper_url": "https://arxiv.org/pdf/2211.14730",
        "github_url": "https://github.com/yuqinie98/PatchTST"
    }
    """
    log.info("Received analysis request: paper=%s, repo=%s", paper_url, github_url)
    result = await run_analysis_pipeline(paper_url, github_url)
    return result
