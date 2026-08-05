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
