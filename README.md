# PaperPilot

面向深度学习论文复现流程的多 Agent 智能辅助平台。

> 输入论文 PDF + GitHub 链接 → Paper Agent 理解论文 → Code Agent 分析仓库 → Mapping Agent 建立关联 → Env Agent 生成运行环境

## 架构

```
Web UI (未来)
    │
Spring Boot Gateway (8080)
    │
RocketMQ ──── Agent Worker Cluster (Python, 8001)
    │              │
MySQL + Redis    LangGraph (Paper → Code → Mapping → Env)
    │
Docker Sandbox (ChromaDB, GROBID)
```

## 项目结构

```
paperpilot/
├── docker-compose.yml          # 基础设施（MySQL, Redis, RocketMQ, GROBID, ChromaDB）
├── paperpilot-api/             # Spring Boot 后端
│   ├── pom.xml
│   └── src/main/java/com/paperpilot/api/
├── paperpilot-agent/           # Python Agent Worker
│   ├── pyproject.toml
│   ├── main.py                 # FastAPI 入口
│   └── agents/                 # LangGraph Agent 定义
│       ├── paper_agent.py      # 论文理解
│       ├── code_agent.py       # 代码分析
│       ├── mapping_agent.py    # 概念映射
│       ├── env_agent.py        # 环境生成
│       └── debug_agent.py      # 错误诊断（阶段4）
├── paperpilot-frontend/        # 前端（后面再加）
└── README.md
```

## 快速开始

### 1. 启动基础设施

```bash
cd ~/projects/paperpilot
docker compose up -d
```

服务端口：

| 服务 | 端口 |
|------|------|
| MySQL 8.x | 3306 |
| Redis 7 | 6379 |
| RocketMQ NameServer | 9876 |
| RocketMQ Broker | 10911 |
| GROBID | 8070 |
| ChromaDB | 8000 |

### 2. 启动 Spring Boot 后端

```bash
cd ~/projects/paperpilot/paperpilot-api
mvn spring-boot:run
```

健康检查：http://localhost:8080/actuator/health

### 3. 启动 Python Agent Worker

```bash
cd ~/projects/paperpilot/paperpilot-agent
conda activate paperpilot
pip install -e .
uvicorn main:app --reload --port 8001
```

健康检查：http://localhost:8001/health

### 4. 配置 LLM API

编辑 `paperpilot-agent/.env`：

```env
OPENAI_API_BASE=https://api.deepseek.com/v1
OPENAI_API_KEY=sk-your-key-here
OPENAI_MODEL=deepseek-chat
```

## API 概览

| Method | Path | 说明 |
|--------|------|------|
| GET | `/actuator/health` | Spring Boot 健康检查 |
| POST | `/api/tasks` | 提交分析任务 |
| GET | `/api/tasks/{id}` | 查询任务状态 |
| GET | `/api/tasks/{id}/result` | 获取分析结果 |
| GET | `/api/tasks/{id}/progress` | SSE 实时进度 |
| GET | `/health` | Agent Worker 健康检查 (8001) |
| POST | `/api/analyze` | 触发分析流水线 (8001) |

## 技术栈

| 层面 | 技术 |
|------|------|
| 后端框架 | Spring Boot 4.0 + MyBatis-Plus |
| 消息队列 | RocketMQ 5.3 |
| 缓存 | Redis 7 |
| 数据库 | MySQL 8 |
| Agent 编排 | LangGraph |
| PDF 解析 | GROBID / PyMuPDF |
| 代码分析 | tree-sitter + ast-grep |
| 向量检索 | ChromaDB |
| LLM | OpenAI-compatible API |

## 分阶段路线

| 阶段 | 目标 | 时间 |
|------|------|------|
| 0 | 环境搭建 ✅ | 2-3 天 |
| 1 | MVP：论文理解 + 代码分析 + 概念映射 + 环境生成 | 1-2 周 |
| 2 | 用户系统 + 任务管理 + 实时进度 | 2-3 周 |
| 3 | Docker Sandbox 自动运行 | 1-2 周 |
| 4 | Debug Agent 错误诊断 | 1-2 周 |
| 5 | 复现路线生成器 | 1 周 |

## License

MIT
