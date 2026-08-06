"""Pydantic Settings：模拟参数仅测试环境启用（环境变量 PAPERPILOT_SIMULATE_*），生产默认关闭."""
from dataclasses import dataclass

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "paperpilot-agent"
    simulate_failure: bool = False
    simulate_delay_ms: int = 0

    # 论文资源契约（T3-02）：storagePath 为相对 storage_root 的逻辑路径
    storage_root: str = "./data/papers"
    max_pdf_bytes: int = 50 * 1024 * 1024  # 50MB

    # GROBID 客户端
    grobid_url: str = "http://localhost:8070"
    grobid_timeout_seconds: float = 30.0
    grobid_max_retries: int = 2

    # 仓库克隆（T3-03）
    workspace_root: str = "./data/workspaces"
    clone_timeout_seconds: float = 60.0
    max_repo_bytes: int = 200 * 1024 * 1024  # 200MB
    max_repo_files: int = 10000
    max_file_bytes: int = 20 * 1024 * 1024  # 20MB
    max_concurrent_clones: int = 2

    # 代码索引（T3-04）
    max_source_file_bytes: int = 2 * 1024 * 1024  # 单文件 2MB
    max_parse_failure_ratio: float = 0.2  # 语法错误占比超过该阈值才终止阶段

    # 概念—代码映射（T3-05/T3-06）
    mapping_top_k: int = 5
    mapping_high_threshold: float = 0.7  # 高分 + 验证通过 → VERIFIED
    mapping_low_threshold: float = 0.3  # 低于该总分 → REJECTED

    # Embedding 召回与 LLM 验证（T3-06）
    embedding_dim: int = 64
    llm_base_url: str = ""  # 空 → 使用确定性 fake verifier（测试）
    llm_api_key: str = ""
    llm_model: str = "gpt-4o-mini"
    llm_timeout_seconds: float = 30.0
    llm_prompt_version: str = "1"

    model_config = SettingsConfigDict(env_prefix="PAPERPILOT_", env_file=".env", extra="ignore")


@dataclass(frozen=True)
class SimulateOptions:
    """测试用模拟开关（缺省关闭）。"""

    failure: bool = False
    delay_ms: int = 0


settings = Settings()


def simulate_options() -> SimulateOptions:
    """从配置读取当前模拟开关（供 API 层注入服务，服务本身不读全局配置）。"""
    return SimulateOptions(failure=settings.simulate_failure, delay_ms=settings.simulate_delay_ms)
