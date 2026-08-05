"""pytest 根 conftest：确保 app/ 与 agents/ 可导入（rootdir 加入 sys.path）。"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
