# agents/__init__.py
from .paper_agent import PaperAgent
from .code_agent import CodeAgent
from .mapping_agent import MappingAgent
from .env_agent import EnvAgent
from .debug_agent import DebugAgent

__all__ = [
    "PaperAgent",
    "CodeAgent",
    "MappingAgent",
    "EnvAgent",
    "DebugAgent",
]
