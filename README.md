# PaperPilot

面向深度学习论文复现流程的多 Agent 智能辅助平台。

> 输入论文 PDF + GitHub 链接 → Paper Agent 理解论文 → Code Agent 分析仓库 → Mapping Agent 建立关联 → Env Agent 生成运行环境

## 架构（Java 主控 + Python 工具层）

```
┌─────────────────────────────────────────────┐
│     Spring Boot (Java) — 主控引擎 (8080)     │
│                                              │
│  Auth · StateMachine · LangChain4j · API    │
│  RocketMQ Consumer · Redisson · SSE         │
│                     │                        │
│         HTTP REST (内部调用)                  │
│                     │                        │
│  Python Worker (8001) — 工具微服务           │
│  PDF解析 · AST解析 · Embedding生成           │
│                                              │
│  MySQL · Redis · RocketMQ · GROBID · ChromaDB│
└─────────────────────────────────────────────┘
```

## 项目结构

```
paperpilot/
├── docker-compose.yml          # 基础设施（MySQL, Redis, RocketMQ, GROBID, ChromaDB, MinIO）
├── paperpilot-api/             # Spring Boot 后端 (Java)
│   ├── pom.xml
│   └── src/main/java/com/paperpilot/api/
├── paperpilot-agent/           # Python Agent Worker（工具微服务）
│   ├── pyproject.toml
│   ├── main.py                 # FastAPI 入口
│   └── agents/                 # PDF解析 / AST分析 / Embedding
├── paperpilot-frontend/        # Vue 3 前端
│   ├── package.json
│   ├── vite.config.js
│   └── src/
│       ├── App.vue             # 主组件（输入 → 进度 → 结果）
│       ├── api.js              # API 信封解包
│       ├── taskEvents.js       # SSE 连接池
│       └── style.css           # 暗色主题
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

### 5. 启动前端

```bash
cd ~/projects/paperpilot/paperpilot-frontend
npm install
npm run dev
```

打开 http://localhost:5173

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

| 层面 | 技术 | 所在侧 |
|------|------|--------|
| 后端框架 | Spring Boot 4.0 + MyBatis-Plus | Java |
| LLM 编排 | **LangChain4j** + 自实现 StateMachine | **Java** |
| 消息队列 | RocketMQ 5.3 (Producer + Consumer) | **Java** |
| 缓存/锁/限流 | Redis 7 + Redisson | Java |
| 数据库 | MySQL 8 + Flyway 迁移 | Java |
| 文件存储 | MinIO | Java |
| Agent 状态机 | Java Stage 枚举 + Redis 持久化 | **Java** |
| PDF 解析 | GROBID / PyMuPDF | Python 微服务 |
| 代码分析 | tree-sitter + ast-grep | Python 微服务 |
| Embedding | BAAI/bge-small-zh | Python 微服务 |
| 向量检索 | ChromaDB (REST API) | Java HTTP Client |
| LLM | DeepSeek (OpenAI-compatible API) | Java LangChain4j |

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
