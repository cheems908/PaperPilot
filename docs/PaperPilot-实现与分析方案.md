# PaperPilot：面向科研复现流程的多Agent智能辅助平台

## 一、项目定位

### 1.1 核心定位

> **针对 GitHub 有开源代码的深度学习论文，实现从论文理解到环境运行的辅助 Agent**

不是"全自动 Devin for Papers"（容易陷入幻觉、代码理解困难、工程量爆炸），而是"论文复现助手"——提升效率，不承诺 100% 自动化。

### 1.2 与普通项目的区别

| 普通学生做的 | 本项目 |
|---|---|
| PDF上传 → GPT总结 → 输出摘要 | 论文 → 方法理解 → Repo分析 → 环境生成 → 运行诊断 |
| 单一的 LLM 调用 | **AI Agent + 软件工程 + 科研基础设施** |

### 1.3 评价矩阵

| 维度 | 评价 |
|---|---|
| 创新性 | ★★★★★ |
| 后端展示价值 | ★★★★★ |
| Agent 价值 | ★★★★★ |
| 个人匹配度 | ★★★★★ |
| 实现难度 | ★★★★☆ |

### 1.4 项目优势

1. **自己每天都能用**（科研/读论文）
2. **技术栈跨度合理**（Spring Boot + MQ + Redis + Agent）
3. **面试故事非常完整**（为什么做 → 遇到什么问题 → 如何设计系统）

---

## 二、核心设计原则

### 2.1 不要过度承诺

| ❌ 不要承诺 | ✅ 改为 |
|---|---|
| 精确定位公式对应代码行 | 建立论文概念与代码模块级映射 |
| 自动复现任何论文 | 针对有开源代码的 DL 论文做辅助分析 |
| 全自动端到端 | 人机协作，Agent 建议 + 用户确认 |

**为什么"公式→代码行"不可行？**

```
论文 Equation (5):  L = L_cls + λL_reg

实际代码可能经过：
  - 函数封装
  - 类继承
  - 多文件调用
  - 参数重命名

LLM 很容易胡编。
```

**改为模块级映射即可行：**

```
论文概念: Patch Embedding
    ↓
对应代码: models/patchtst.py → class PatchEmbedding
核心功能:
  1. segment input
  2. projection
  3. positional encoding
```

### 2.2 Agent 拆分原则

不要一个大 Agent，做 Multi-Agent：

| Agent | 职责 | 输入 | 输出 |
|---|---|---|---|
| Paper Understanding Agent | 论文结构化理解 | PDF | 结构化 JSON（标题、问题域、方法、创新点） |
| Code Repository Agent | 代码仓库分析 | GitHub URL | 代码结构图、核心模块说明 |
| Mapping Agent | 论文-代码关联 | 论文概念 + 代码结构 | 概念↔模块映射表 |
| Environment Agent | 环境构建 | repo 文件 | Dockerfile、运行步骤 |
| Debug Agent | 运行错误诊断 | 错误日志 + 环境 + 源码 | 根因分析 + 修复建议 |

---

## 三、系统架构

### 3.1 总体架构图（Java 主控 + Python 工具层）

```
┌─────────────────────────────────────────────────────────┐
│                  Spring Boot (Java) — 主控引擎            │
│                                                          │
│  ┌──────────┐  ┌──────────────┐  ┌───────────────────┐  │
│  │ 用户认证  │  │ 任务状态机    │  │ LLM 编排          │  │
│  │ Spring   │  │ (Java 实现   │  │ (LangChain4j      │  │
│  │ Security │  │  StateGraph) │  │  替代 LangGraph)  │  │
│  └──────────┘  └──────────────┘  └───────────────────┘  │
│  ┌──────────┐  ┌──────────────┐  ┌───────────────────┐  │
│  │ REST API │  │ RocketMQ     │  │ ChromaDB          │  │
│  │ + SSE    │  │ Producer +   │  │ HTTP Client       │  │
│  │          │  │ Consumer     │  │ (Java 侧直调)      │  │
│  └──────────┘  └──────────────┘  └───────────────────┘  │
│                       │                                  │
│              HTTP REST (内部调用)                         │
│                       │                                  │
│  ┌────────────────────┴───────────────────────────────┐  │
│  │         Python Worker (轻量工具微服务)                │  │
│  │  ┌──────────┐  ┌──────────┐  ┌─────────────────┐   │  │
│  │  │ AST 解析  │  │ Embedding│  │ PDF 解析        │   │  │
│  │  │tree-sitter│  │ 生成     │  │ GROBID/PyMuPDF  │   │  │
│  │  └──────────┘  └──────────┘  └─────────────────┘   │  │
│  └────────────────────────────────────────────────────┘  │
│                                                          │
│  基础设施: MySQL + Redis + MinIO + Qdrant + Docker Sandbox │
└─────────────────────────────────────────────────────────┘
```

**核心设计原则：Java 做"大脑"（编排、调度、状态、LLM 调用、API），Python 只做"手"（AST 解析、Embedding 生成、PDF 解析等 Java 生态不擅长的工具任务）。**

**为什么这样划分？** 参考同目录下的 DOVideo-AI 项目——一个 76 文件纯 Java 的 Spring Boot 项目，其 Agent 编排、LLM 调用、RocketMQ 消费、SSE 推送全部在 Java 侧完成，**零 Python 依赖**。这证明了 LangChain4j + Spring Boot 完全可以替代 LangGraph + FastAPI。对找 Java 实习而言，Java 占比 > 70% 才有说服力。

### 3.2 Agent 编排：Java StateGraph（替代 LangGraph）

Agent 之间的协同逻辑不再依赖 Python LangGraph，而是由 Java 侧的状态机管理：

```java
// 状态定义（Java Record，替代 Python TypedDict）
public record AnalysisState(
    String taskId,
    String paperUrl,
    String githubUrl,
    Stage stage,           // 当前阶段（枚举）
    int progress,          // 0-100
    PaperResult paperResult,
    CodeResult codeResult,
    MappingResult mappingResult,
    EnvResult envResult,
    List<String> errors
) {}

// 状态流转（Java 枚举 + 转换逻辑，替代 LangGraph StateGraph）
enum Stage {
    PENDING,
    PAPER_ANALYSIS,    // → 调用 Python PDF 解析 Worker
    CODE_ANALYSIS,     // → 调用 Python AST 解析 Worker
    MAPPING,           // → Java 侧做 Embedding 匹配 + LLM 验证
    ENV_SETUP,         // → Java 侧调用 LLM 生成 Dockerfile
    COMPLETED,
    FAILED
}
```

**状态机执行流程：**
```
PENDING → PAPER_ANALYSIS → CODE_ANALYSIS → MAPPING → ENV_SETUP → COMPLETED
              │                  ↑              │
              │                  └── 无 GitHub URL 时跳过
              │
              └── 失败 → FAILED（可重试）
```

> 不需要引入 Spring StateMachine（太重），一个 `AnalysisStateMachine` Service + Redis 状态存储即可。DOVideo-AI 也是这样做的（用 `TaskStage` 枚举 + `AnalysisStatusService`）。

### 3.3 异步任务流（Java 主导）

```
用户 POST /api/tasks
    │
    ▼
Spring Boot Controller (AnalysisController)
    │
    ▼
AnalysisDispatchService（幂等去重 + Redisson 限流）
    │
    ▼
RocketMQ Producer ──→ topic: paper-analysis
    │
    ▼
RocketMQ Consumer (Java 侧 @RocketMQMessageListener)   ← 关键变化
    │
    ▼
AnalysisStateMachine（Java 状态机编排）
    │
    ├──→ Stage 1: HTTP 调用 Python Worker → GROBID PDF 解析
    ├──→ Stage 2: HTTP 调用 Python Worker → tree-sitter AST 解析
    ├──→ Stage 3: Java LangChain4j → LLM 做论文-代码映射
    └──→ Stage 4: Java LangChain4j → LLM 生成 Dockerfile
    │
    ▼
结果写入 MySQL + Redis（进度更新）
    │
    ▼
SSE 实时推送进度给前端
```

### 3.3 异步任务流

```
用户 POST /api/tasks
    │
    ▼
Spring Boot Controller
    │
    ▼
RocketMQ Producer → topic: paper-analysis
    │
    ▼
RocketMQ Consumer (Python Agent Worker)
    │
    ▼
LangGraph StateGraph 执行
    │
    ▼
结果写入 MySQL + Redis（进度更新）
    │
    ▼
前端轮询 Redis → 实时展示进度
```

---

## 四、Agent 详细设计

### 4.1 Paper Understanding Agent

**流程：**

```
PDF → GROBID (Docker) → TEI-XML
    → 提取章节结构（title, abstract, method, experiments）
    → LLM 总结 → 结构化 JSON
```

**输出格式：**

```json
{
  "title": "PatchTST: A Time Series is Worth 64 Words",
  "problem": "time series forecasting",
  "innovation": [
    "Patching: segment time series into subseries-level patches",
    "Channel Independence: each channel has its own model"
  ],
  "method": [
    {
      "name": "Patch Embedding",
      "description": "Segment input into patches and project to embeddings",
      "context": "Transformer encoder input"
    },
    {
      "name": "Channel Independence",
      "description": "Each variate processed independently by shared model",
      "context": "Multivariate forecasting strategy"
    }
  ],
  "architecture": "Transformer encoder with patch embedding",
  "dataset": ["ETTh1", "ETTh2", "ETTm1", "Weather", "ILI", "Traffic"]
}
```

**降级方案：** 如果 GROBID Docker 不稳定 → PyMuPDF (fitz) 直接提取文本 + LLM 分段。

### 4.2 Code Repository Agent

**流程：**

```
git clone repo
    ↓
tree-sitter-analyzer → 项目结构摘要（PageRank 排序的关键文件）
    ↓
ast-grep → 结构化搜索核心模块（class/function/forward/loss）
    ↓
embedding → ChromaDB 存储
    ↓
LLM → 代码结构说明 + 核心模块功能描述
```

**技术选择：**

| 工具 | 用途 | 理由 |
|---|---|---|
| tree-sitter-analyzer | 项目结构摘要 | MCP Server，自动生成 token 优化后的代码地图 |
| ast-grep | 模式匹配 | 比手写 tree-sitter query 快 10 倍，支持 YAML 规则 |
| ChromaDB | 代码块向量存储 | 零配置，自带 embedding function |

**输出示例：**

```
项目结构：
├── models/
│   └── PatchTST.py          ★ 核心模型 (PageRank: 0.95)
│       ├── class PatchTST_backbone
│       │   └── forward()    - 主前向传播
│       ├── class PatchEmbedding
│       │   └── forward()    - Patch分割+投影+位置编码
│       └── class TSTiEncoder
│           └── forward()    - Transformer编码器
├── data/
│   └── data_loader.py       ★ 数据加载 (PageRank: 0.72)
└── scripts/
    └── train.py             ★ 训练入口 (PageRank: 0.88)
```

**PageRank 策略（参考 Aider repo-map）：**

- 源文件作为图的节点
- import 关系作为边
- PageRank 分数排序 → 取 top-N 适配 token 预算（默认 4096 tokens）

### 4.3 Mapping Agent

**核心思路：双向 Embedding + 语义匹配**

```
论文方法概念描述 → embedding (via bge-small-zh / all-MiniLM-L6-v2)
代码函数/类的 docstring + 签名 → embedding
    ↓
cosine similarity 匹配
    ↓
LLM 验证映射合理性 + 生成说明
```

**输出格式：**

```json
{
  "mappings": [
    {
      "paper_concept": "Patch Embedding",
      "confidence": 0.92,
      "code_location": "models/PatchTST.py → class PatchEmbedding",
      "explanation": "论文描述的 patch 分割、投影和位置编码三个步骤与该类的 __init__ 和 forward 方法完全对应",
      "key_evidence": [
        "论文: 'split into N patches of length P' → 代码: self.seq_len, self.patch_len 参数",
        "论文: 'project to D dimensions' → 代码: self.W_P = nn.Linear(patch_len, d_model)"
      ]
    },
    {
      "paper_concept": "Channel Independence",
      "confidence": 0.87,
      "code_location": "models/PatchTST.py → class PatchTST_backbone.forward()",
      "explanation": "该策略在 forward 方法中通过对每个 variate 独立应用模型来实现",
      "key_evidence": [
        "论文: 'each channel shares the same model' → 代码: 对每个 variate 循环使用同一个 backbone"
      ]
    }
  ]
}
```

**防幻觉机制：**

- 每个 Agent 输出都有结构化 JSON Schema 约束
- Mapping Agent 匹配结果经过第二轮 LLM 验证（"这个映射是否合理？为什么？"）
- 标记置信度，低置信度映射标注为 `[uncertain]`

### 4.4 Environment Agent

**流程：**

```
解析 requirements.txt / environment.yml / README.md
    ↓
LLM 提取依赖 + 安装步骤
    ↓
生成 Dockerfile + docker-compose.yml + 运行脚本
```

**输出示例：**

```dockerfile
FROM pytorch/pytorch:2.1.0-cuda12.1-cudnn8-runtime

WORKDIR /workspace

# 系统依赖
RUN apt-get update && apt-get install -y git

# Python 依赖
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 代码
COPY . .

# 默认命令
CMD ["python", "scripts/train.py"]
```

### 4.5 Debug Agent（最大亮点）

**MVP 阶段仅覆盖三类错误：**

| 错误类型 | 诊断逻辑 | 实现难度 |
|---|---|---|
| `ModuleNotFoundError` | 比对 requirements.txt vs pip list，输出缺失包名 | 低 |
| `CUDA out of memory` | 读错误信息里的显存需求，建议减 batch_size | 中 |
| `RuntimeError: shape mismatch` | AST 定位模型定义的 tensor 维度，与报错维度对比 | 中高 |

这三类覆盖论文复现中约 80% 的常见报错。

**诊断流程：**

```
错误日志 → LLM 分析根因 → 搜索代码定位问题行 → 生成修复建议
```

**交互示例：**

```
用户提交运行，遇到错误：
  RuntimeError: CUDA out of memory. Tried to allocate 2.00 GiB
  (GPU 0; 11.00 GiB total capacity; 9.50 GiB already allocated)

Agent 诊断输出：
  原因: batch_size=64 过于激进
  显存需求估算: ~24GB（当前 GPU 仅 12GB）
  解决方案:
    1. batch_size 降至 16（预计显存 ~8GB）
    2. 启用 gradient_accumulation_steps=4（保持等效 batch size）
  修改位置: scripts/train.py:42
    - batch_size = 64
    + batch_size = 16
    + gradient_accumulation_steps = 4
```

---

## 五、技术选型总表

| 层面 | 技术 | 所在侧 | 理由 |
|---|---|---|---|
| **后端框架** | Spring Boot 4.x | Java | 主要技术栈，Java 实习核心竞争力 |
| **ORM** | MyBatis-Plus | Java | 与 MySQL 配合成熟，DOVideo-AI 同款 |
| **LLM 编排** | **LangChain4j** (替代 LangGraph) | **Java** | Java 生态的 LLM 框架，支持 OpenAI-compatible API；DOVideo-AI 已证明可行 |
| **消息队列** | RocketMQ | **Java** Consumer | RocketMQ 的 Java Starter 最成熟，`@RocketMQMessageListener` 开箱即用 |
| **Agent 状态机** | 自实现 StateMachine Service | **Java** | 替代 Python LangGraph StateGraph；Spring StateMachine 太重，枚举+Redis 足够 |
| **缓存/锁/限流** | Redis + Redisson | Java | Agent 进度、任务状态、LLM 结果缓存、分布式锁、限流 |
| **数据库** | MySQL 8.x | Java | 论文/任务/用户持久化 |
| **文件存储** | MinIO | Java | 论文 PDF、代码仓库文件存储 |
| **向量数据库** | ChromaDB (REST API) | **Java HTTP Client 直调** | ChromaDB 自带 REST API，Java 侧直接 HTTP 调，无需 Python 中间层 |
| **LLM API** | OpenAI-compatible API | **Java** (LangChain4j) | 灵活切换（deepseek / GPT-4 / 本地模型） |
| **PDF 解析** | GROBID (Docker) / PyMuPDF | **Python** 微服务 | GROBID 是独立 Docker 服务，但客户端逻辑复杂，保留 Python |
| **代码 AST 解析** | tree-sitter + ast-grep | **Python** 微服务 | tree-sitter Java binding 不成熟，保留 Python 做工具层 |
| **Embedding 生成** | BAAI/bge-small-zh 等 | **Python** 微服务 或 调 API | 纯 ML 任务，Python 最合适；也可直接用 SiliconFlow Embedding API |
| **沙箱执行** | Docker Sandbox | Python 或独立服务 | 代码运行隔离环境 |
| **前端** | Vue 3 + Vite | 前端 | SPA，DOVideo-AI 同款 |
| **部署** | Docker Compose | — | 一键启动所有服务 |

**核心变化（对比原方案）：**

| 原方案 | 新方案 | 理由 |
|---|---|---|
| Python LangGraph 编排 Agent | **Java 自实现 StateMachine** | Java 侧管理所有状态转换，面试展示分布式编排能力 |
| Python LangChain 调用 LLM | **Java LangChain4j** | LangChain4j 是成熟的 Java LLM 框架，DOVideo-AI 已集成 |
| Python RocketMQ Consumer | **Java @RocketMQMessageListener** | RocketMQ Spring Boot Starter 的最佳实践 |
| Python ChromaDB Client | **Java HTTP Client 直调** | ChromaDB 本身就是 REST API，无需 Python 中间层 |
| 所有逻辑在 Python | **Java 70%+ / Python 30%-** | Java 做编排/LLM/API/MQ，Python 仅做 AST/Embedding/PDF |

### 为什么 ChromaDB 而不是 Milvus？

- 个人项目、单机部署、论文-代码映射场景不需要 Milvus 的分布式能力
- ChromaDB 零配置启动（`pip install chromadb`）
- 自带 embedding function，不需要单独调 embedding API
- 足够支撑 10 万级文档块（一篇论文 + 一个中型 repo ≈ 2000-5000 chunks）

### 为什么 RocketMQ 而不是 Kafka？

- RocketMQ 与 Spring Boot 集成更原生（Apache 顶级项目，`rocketmq-spring-boot-starter`）
- 事务消息支持更好
- 本项目场景（任务异步化）不需要 Kafka 的流处理能力
- 如果本地调试复杂，MVP 阶段可先用 Redis List（`BLPOP`）做简易队列

---

## 六、数据库设计

### 6.1 MySQL 核心表

```sql
-- 论文表
CREATE TABLE paper (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    arxiv_url VARCHAR(500),
    local_pdf_path VARCHAR(500),
    parsed_json JSON COMMENT 'Paper Agent 输出的结构化 JSON',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 代码仓库表
CREATE TABLE repository (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT,
    github_url VARCHAR(500) NOT NULL,
    commit_hash VARCHAR(64),
    local_path VARCHAR(500),
    structure_json JSON COMMENT 'Code Agent 输出的结构分析 JSON',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (paper_id) REFERENCES paper(id)
);

-- 任务表
CREATE TABLE task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    paper_id BIGINT,
    repository_id BIGINT,
    status ENUM('PENDING', 'PAPER_ANALYSIS', 'CODE_ANALYSIS',
                'MAPPING', 'ENV_SETUP', 'RUNNING',
                'DEBUGGING', 'COMPLETED', 'FAILED') DEFAULT 'PENDING',
    progress INT DEFAULT 0 COMMENT '0-100',
    result_json JSON COMMENT '完整分析结果',
    error_message TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (paper_id) REFERENCES paper(id),
    FOREIGN KEY (repository_id) REFERENCES repository(id)
);

-- 论文-代码映射表
CREATE TABLE paper_code_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    paper_concept VARCHAR(300) NOT NULL,
    code_location VARCHAR(500) NOT NULL,
    confidence DECIMAL(3,2) DEFAULT 0.00,
    explanation TEXT,
    evidence_json JSON,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (task_id) REFERENCES task(id)
);
```

### 6.2 Redis 数据结构

```
# Agent 进度（实时更新，前端轮询/SSE 读取）
task:{task_id}:progress → {
    "stage": "CODE_ANALYSIS",
    "stage_cn": "代码分析中",
    "percent": 60,
    "message": "正在解析 models/PatchTST.py..."
}

# LLM 结果缓存（同一篇论文不重复解析）
paper:{paper_id}:summary → { ... }
paper:{paper_id}:hash → "md5_of_pdf"

# 任务状态锁（防止重复消费）
task:{task_id}:lock → "consumer_id"
```

---

## 七、分阶段执行路线图

### 阶段 0：环境搭建（2-3 天）

**目标：所有基础设施跑通**

```
□ Docker Compose 编排：
    - GROBID (端口 8070)
    - MySQL 8.x (端口 3306)
    - Redis (端口 6379)
    - RocketMQ NameServer (端口 9876)
    - RocketMQ Broker (端口 10911)
    - ChromaDB (端口 8000)

□ Spring Boot 项目骨架：
    - 多模块结构：paperpilot-common / paperpilot-api / paperpilot-agent
    - MyBatis-Plus + MySQL 建表
    - RocketMQ spring-boot-starter 配置
    - 基础 API：健康检查

□ Python Agent 项目骨架：
    - FastAPI (端口 8001)
    - LangGraph 依赖
    - GROBID client 测试（调 localhost:8070 解析一篇论文）
    - OpenAI-compatible API 调通
```

### 阶段 1：MVP — 论文+代码理解助手（1-2 周）

**这是最重要的阶段。完成后即是一个完整可演示的最小闭环。**

**功能：**
- 输入：论文 PDF + GitHub URL
- 输出：论文总结 + 代码结构分析 + 论文概念↔代码映射 + 运行步骤

**Agent 流水线：**

```
用户 POST /api/tasks
    ↓
Spring Boot → RocketMQ
    ↓
Python Agent Worker (LangGraph)
    ↓
Node 1: PaperAgent → GROBID 解析 + LLM 总结
Node 2: CodeAgent  → git clone + AST 分析 + LLM 说明
Node 3: MappingAgent → embedding 匹配 + LLM 验证
Node 4: EnvAgent  → 依赖分析 + Dockerfile 生成
    ↓
结果写入 MySQL + Redis
    ↓
前端展示
```

**Spring Boot API：**

```
POST   /api/tasks              # 提交分析任务 → 返回 task_id
GET    /api/tasks/{id}          # 查询任务状态（从 Redis 读）
GET    /api/tasks/{id}/result   # 获取分析结果（从 MySQL 读）
GET    /api/tasks/{id}/progress # SSE 实时进度推送
```

**交付物：**
- Spring Boot 三个 API + RocketMQ 异步消息链路
- Python Agent 四个 Node 的 LangGraph 流水线
- 一篇 PatchTST 论文 + GitHub repo 跑通全流程
- 前端：论文总结 + 代码结构 + 映射表 + 运行步骤

### 阶段 2：异步化 + 完整后端（2-3 周）

**目标：后端能力拉满**

```
□ 用户系统：
    - 注册/登录 (Spring Security + JWT)
    - 用户-项目关联

□ 任务系统完善：
    - 完整状态机：
      PENDING → PAPER_ANALYSIS → CODE_ANALYSIS
             → MAPPING → ENV_SETUP → COMPLETED / FAILED
    - 失败重试 + 死信队列（RocketMQ DLQ）
    - 任务历史列表 + 详情 + 分页

□ Redis 实时进度：
    - task:{id}:progress 实时写入
    - 前端 SSE 实时更新进度条动画

□ 项目系统：
    - Project CRUD（论文 + repo → 分析结果绑定）
    - 历史记录搜索（Elasticsearch 可选，MySQL LIKE 够用）

□ 可观测性：
    - RocketMQ 消费监控
    - Agent 执行耗时统计
```

### 阶段 3：Docker Sandbox 自动运行（1-2 周）

**目标：不止输出"怎么运行"，而是直接帮用户跑起来**

```
□ Environment Agent 增强：
    - 自动生成 Dockerfile
    - 调用 python-sandbox REST API 创建容器
    - 容器内：pip install → 运行示例脚本 → 捕获 stdout/stderr

□ 运行结果反馈：
    - 成功 → 展示输出 + 运行时间
    - 失败 → 自动进入 Debug Agent
```

### 阶段 4：Debug Agent（1-2 周）

**目标：项目最大亮点**

```
□ 错误分类器：
    - ModuleNotFoundError → 依赖诊断
    - CUDA OOM → 显存诊断
    - Shape mismatch → 维度诊断

□ 诊断流程：
    错误日志 → LLM 分析根因 → 代码搜索定位 → 修复建议

□ 交互式修复（optional）：
    用户确认修复方案 → Agent 修改代码 → 重新运行
```

### 阶段 5：复现路线生成器（1 周）

**个人化特色功能，结合 AIS 项目经历：**

```
□ 基于论文方法生成分步复现计划：
    Day 1: 理解核心机制（如 Patch）
    Day 2: 运行官方代码
    Day 3: 替换数据集
    Day 4: 修改模型参数
    Day 5: 复现实验表格

□ 个性化迁移建议：
    论文方法 → 你的 AIS 轨迹预测场景 → 修改建议
    （输入维度调整、输出目标修改、数据预处理适配）
```

---

## 八、时间估算

| 阶段 | 时间 | 累计 | 可演示内容 |
|---|---|---|---|
| 阶段 0：环境搭建 | 2-3 天 | ~3 天 | Docker Compose 一键启动所有服务 |
| **阶段 1：MVP 闭环** | **1-2 周** | **~2 周** | **完整论文+代码理解+映射+环境，可演示** |
| 阶段 2：完整后端 | 2-3 周 | ~5 周 | 用户系统 + 任务管理 + 实时进度 |
| 阶段 3：Docker Sandbox | 1-2 周 | ~7 周 | 自动运行 repo |
| 阶段 4：Debug Agent | 1-2 周 | ~9 周 | 错误自动诊断修复 |
| 阶段 5：复现路线 | 1 周 | ~10 周 | 个性化复现计划生成 |

**总计约 10 周。** 如果时间紧，阶段 3+4 可合并（边跑边修），压缩到 8 周。

---

## 九、风险与缓解

| 风险 | 概率 | 缓解方案 |
|---|---|---|
| GROBID Docker 启动复杂，PDF 解析不稳定 | 中 | 降级方案：PyMuPDF 直接提取文本 + LLM 分段 |
| LLM API 费用高（反复调 GPT-4） | 中 | ① 中间步骤用本地小模型 ② 仅关键步骤调 GPT-4 ③ Redis 缓存：同一篇论文不重复解析 |
| 代码 repo 结构差异大，AST 覆盖不全 | 中 | 不承诺全自动，解析失败标记 `[parse_error]` 但不阻断流程 |
| RocketMQ 本地调试复杂 | 低 | Docker Compose 一键启动；MVP 阶段可用 Redis List 做简易队列 |
| 时间不够 | 高 | **死守阶段 1 交付。** 阶段 1 完成就是完整可演示项目 |
| LLM 输出不稳定/幻觉 | 中 | 结构化 JSON Schema 约束 + 第二轮 LLM 验证 + 置信度标注 |

---

## 十、简历文案与面试故事

### 10.1 一句话版本

> **PaperPilot**：面向深度学习论文复现流程的多 Agent 智能辅助平台。基于 Spring Boot + RocketMQ + Redisson 构建分布式异步任务调度引擎，使用 **Java 状态机 + LangChain4j** 编排 Paper/Code/Mapping/Env 四类 Agent 协同工作；集成 GROBID 结构化论文解析、tree-sitter AST 代码分析，实现论文方法与源码模块级关联；通过 Docker 沙箱完成实验环境自动构建与运行错误诊断。

### 10.2 STAR 面试故事

**Situation（背景）：**

> 我在做 AIS 时间序列预测研究时，需要复现 PatchTST、TimesNet 等论文。每次复现都要先读论文理解方法，再读 GitHub 代码找对应实现，然后配环境、改 bug，整个过程重复且低效。我发现这不是我一个人的问题——大多数做深度学习研究的同学都有同样的痛点。

**Task（目标）：**

> 我决定做一个工具，把"论文阅读 → 代码理解 → 环境配置 → 错误诊断"这个流程自动化。但不是做一个简单的 PDF 总结器——而是要真正理解论文方法和代码模块之间的对应关系，并且能帮用户把代码跑起来。

**Action（行动）：**

> 第一，系统架构上，我选用了 Spring Boot + RocketMQ + Redis + Redisson 构建分布式异步任务引擎。因为分析一篇论文可能需要 5-10 分钟（涉及 LLM 调用、代码克隆、AST 解析），同步返回不现实。RocketMQ 做任务削峰，Redisson 做分布式锁和限流，Redis 存实时进度，前端通过 SSE 展示流水线状态。
>
> 第二，Agent 设计上，我没有用 Python 的 LangGraph 全栈方案，而是在 Java 侧自实现了 Agent 状态机。因为我发现 LangGraph 本质就是一个 StateGraph，用 Java 枚举 + Redis 状态存储完全可以替代，而且更可控——每个 Node 的状态变更都有明确的输入输出和错误处理，中间状态持久化到 Redis，可以随时恢复。LLM 调用全部通过 LangChain4j 在 Java 侧完成，Python 只作为轻量工具微服务，处理 AST 解析和 Embedding 生成这类 Java 生态不擅长的 ML 任务。
>
> 第三，代码分析上，我没有简单地把所有代码扔给 LLM（token 爆炸 + 幻觉风险），而是先用 tree-sitter 做 AST 解析提取函数/类签名和调用关系，然后用 Embedding 做论文概念和代码模块的语义匹配。这样 LLM 只处理最关键的部分。整个工程参考了 DOVideo-AI 的成熟架构模式（分布式锁、Checkpoint 双层存储、死信队列、SSE 推送），但 Agent 领域完全不同。

**Result（结果）：**

> 我用自己做过的 PatchTST 和 TimesNet 论文验证了整个流程：系统能准确识别论文中的 Patch Embedding、Channel Independence 等核心概念，并映射到代码中的对应类和函数；自动生成了可运行的 Docker 环境；对故意的 CUDA OOM 错误给出了正确的诊断建议。整个流程用户只需要粘贴论文链接和 GitHub 链接。

### 10.3 可能被追问的问题

| 问题 | 回答方向 |
|---|---|
| **"为什么用 RocketMQ 而不是 Kafka/RabbitMQ？"** | RocketMQ 与 Spring Boot 集成更原生（Apache 顶级项目，阿里出品），事务消息支持更好，且我的场景（任务异步化）不需要 Kafka 的流处理能力。RocketMQ 的 DLQ 对失败重试的支持也更直接。 |
| **"Agent 之间的状态怎么管理的？"** | LangGraph 的 StateGraph + TypedDict，每个 Node 读写共享 State。状态变更通过 Redis 同步给前端，实现实时进度展示。LangGraph 的 conditional edge 处理分支逻辑（如代码仓库不存在时跳过 Code Agent）。 |
| **"论文概念和代码模块怎么对应的？"** | 双向 embedding 匹配：论文概念描述 → embedding，代码函数/类的 docstring + 签名 → embedding，cosine similarity 匹配后由 LLM 验证。不追求 100% 精确，标记置信度。 |
| **"怎么避免 LLM 幻觉？"** | 三层防护：① 每个 Agent 输出有结构化 JSON Schema 约束 ② Mapping Agent 匹配结果经第二轮 LLM 独立验证 ③ 所有映射标记置信度，低置信度标注 `[uncertain]` |
| **"如果代码仓库很大怎么办？"** | 使用 PageRank 算法筛选核心文件（类似 Aider 的 repo-map 做法），只分析 top-N 重要文件，token 预算控制在 4096 以内。非核心文件如 utils、tests 只记录路径不深度分析。 |
| **"为什么不用 Python 包办所有？"** | ① Spring Boot 是 Java 后端实习的核心竞争力展示 ② RocketMQ 的 Java 集成最成熟 ③ 异步任务引擎 + 用户系统 + 权限管理这些 Python（FastAPI/Django）也能做但 Java 生态更规范 ④ Python 侧只负责 Agent 逻辑，各司其职。 |

---

## 十一、关键设计决策记录

### 决策 1：Spring Boot 负责什么 vs Python 负责什么（重新划分）

> **原方案将 Agent 编排、LLM 调用、RocketMQ 消费全放在 Python 侧，导致 Java 退化为"薄网关"——只做 CRUD 和消息转发。**
> **对找 Java 实习来说，Java 占比不足 20% 的项目没有说服力。**
>
> 以下为重新划分后的边界，参考 DOVideo-AI（76 文件纯 Java）的成功实践：

| Spring Boot（Java）— 主控层 (~70%+) | Python Agent Worker — 工具层 (~30%-) |
|---|---|
| 用户认证与权限 (Spring Security + JWT) | PDF 解析（GROBID client / PyMuPDF fallback） |
| **任务状态机编排（Java StateMachine Service）** | **代码仓库克隆 + AST 解析（tree-sitter）** |
| **RocketMQ Producer + Consumer（@RocketMQMessageListener）** | **Embedding 生成（bge-small-zh 等模型推理）** |
| **LLM 调用与 Agent 编排（LangChain4j）** | Docker 沙箱调用 |
| **向量检索（ChromaDB HTTP Client 直调）** | 仅负责 Java 生态不擅长的 ML/解析工具任务 |
| REST API + SSE 实时推送 | — |
| 数据库 CRUD + Flyway 迁移 | — |
| Redis 缓存/分布式锁/限流（Redisson） | — |
| MinIO 文件存储 | — |
| Checkpoint 持久化 + 任务恢复 | — |

**划分原则：**
1. 凡是 Java 生态有成熟方案的，用 Java（LLM 编排 → LangChain4j，MQ → RocketMQ Starter，向量检索 → HTTP Client）
2. Python 只做 Java binding 不成熟的 ML/工具任务（AST 解析、Embedding 模型推理、PDF 精细化解析）
3. Java 是主控层（发起调用、管理状态），Python 是被调用的工具层（接收请求、返回结果）

### 决策 2：MVP 阶段不做的事情

- ❌ 用户登录系统（阶段 2 再做）
- ❌ Docker Sandbox 实际执行（阶段 3 再做）
- ❌ Debug Agent（阶段 4 再做）
- ❌ 代码自动修改（只建议，不修改）
- ❌ 多论文对比分析
- ❌ 论文推荐

### 决策 3：错误处理策略

```
┌──────────────────────────────────────────┐
│           Agent 执行层级                   │
├──────────────────────────────────────────┤
│  L1: 致命错误 → 任务标记 FAILED           │
│      - PDF 完全无法解析                    │
│      - Git clone 失败（URL 不存在）         │
│      - RocketMQ 消息丢失                   │
├──────────────────────────────────────────┤
│  L2: 可恢复错误 → 标记 warning + 继续      │
│      - 某个代码文件 AST 解析失败            │
│      - 某个章节提取不完整                   │
│      - LLM 返回格式不符（重试 3 次）        │
├──────────────────────────────────────────┤
│  L3: 降级 → 使用备用方案                   │
│      - GROBID 挂了 → PyMuPDF              │
│      - GPT-4 太贵 → deepseek-v3           │
│      - ChromaDB 挂了 → 跳过映射步骤        │
└──────────────────────────────────────────┘
```

---

## 十二、DOVideo-AI 参考架构分析

### 12.1 为什么参考 DOVideo-AI

DOVideo-AI 是本机同目录下的另一个项目，定位为"长视频内容理解的 Video Agent"。它在架构上与本项目高度相似（LLM Agent + 异步任务 + 向量检索 + Checkpoint），但 **76 个 Java 文件、0 个 Python 文件**，证明了 Agent 类项目完全可以全 Java 实现。

### 12.2 DOVideo-AI 的技术栈（与本项目对比）

| 维度 | DOVideo-AI | PaperPilot（原方案） | PaperPilot（新方案） |
|---|---|---|---|
| 语言 | Java 21 + Vue 3 JS | Java 17 + Python 3.10+ | **Java 17+ + Python 3.10+** |
| 框架 | Spring Boot 3.5.9 | Spring Boot 4.0.0 | Spring Boot 4.0.0 |
| Web 服务器 | Undertow | Tomcat (默认) | Undertow |
| ORM | MyBatis-Plus 3.5.9 | MyBatis-Plus 3.5.9 | MyBatis-Plus 3.5.9 ✅ 一致 |
| DB 迁移 | Flyway | 无 | **Flyway**（借鉴） |
| LLM 框架 | LangChain4j 1.16.3 | LangGraph + LangChain (Python) | **LangChain4j**（借鉴） |
| 消息队列 | RocketMQ 5.3.4 | RocketMQ 5.3.0 | RocketMQ 5.3 ✅ 一致 |
| MQ Consumer | Java @RocketMQMessageListener | 设计在 Python | **Java**（借鉴） |
| 缓存/锁 | Redis + Redisson 3.52 | Redis (无 Redisson) | **Redis + Redisson**（借鉴） |
| 向量数据库 | Qdrant v1.18.2 | ChromaDB | ChromaDB（可替换为 Qdrant） |
| 对象存储 | MinIO | 无 | **MinIO**（借鉴） |
| Agent 模式 | Planner-Executor-Critic Loop | Paper-Code-Mapping-Env Pipeline | **StateMachine + LangChain4j** |
| SSE 推送 | Redis Pub/Sub + SseEmitter | 设计有，未实现 | Redis Pub/Sub + SseEmitter（借鉴） |
| 限流 | Redisson RRateLimiter | 无 | **Redisson RRateLimiter**（借鉴） |
| Checkpoint | MySQL + Redis 双层 | 设计有，未实现 | MySQL + Redis 双层（借鉴） |
| 错误处理 | 死信队列 + 失败任务台账 | 三级降级策略 | 死信队列 + 三级降级 |
| Python 占比 | **0%** | **~95%** | **< 30%** |

### 12.3 DOVideo-AI 值得借鉴的架构模式

以下模式可以直接移植到 PaperPilot：

**1. AnalysisDispatchService — 幂等异步提交**
```java
// Redis SET NX 去重 → Redisson 限流 → RocketMQ 发布 → 返回 202 Accepted
public SubmissionResult submit(MediaFile media, String goal, AgentFeedback feedback, AnalysisMode mode) {
    if (!redisTemplate.opsForValue().setIfAbsent(activeKey, "1", Duration.ofHours(6))) {
        return DUPLICATE;  // 相同内容+目标已在处理中
    }
    if (!rateLimiter.tryAcquire()) return RATE_LIMITED;
    rocketMQTemplate.convertAndSend("paper-analysis", new AnalysisTaskMsg(...));
    return ACCEPTED;
}
```

**2. AgentLoopService — 受控 Agent 循环**
```java
// Planner → Executor → Critic 闭环，带预算约束
AgentState run(Long mediaId, VideoContext ctx, ModeProfile profile) {
    for (int round = 0; round < maxRounds; round++) {
        AgentPlan plan = plan(ctx, profile, criticState);
        AnalysisResult result = execute(plan, ctx, profile);
        Critique critique = crit(result, ctx, profile);
        if (critique.passed()) return new AgentState(goal, plan, result, critique, round);
        criticState = criticState.refine(critique);
    }
    throw new BudgetExceededException();
}
```

**3. AiService.resolveContext — 内容级上下文复用**
```java
// 同一视频被不同用户/目标提交时，ASR/OCR 只跑一次，其余复用
VideoContext resolveContext(MediaFile media, String goal, String traceId) {
    // 1. 查自身 checkpoint
    // 2. 查内容级 Redis 归属索引 → 跨用户复用
    // 3. Redisson 上下文锁 → 单消费者构建，其余等待
}
```

**4. TaskEventService — Redis Pub/Sub SSE 推送**
```java
// Redis pub/sub → in-memory SseEmitter fan-out
public SseEmitter subscribe(Long mediaId, String type, String goal) {
    SseEmitter emitter = new SseEmitter(TimeUnit.HOURS.toMillis(2));
    subscribers.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(emitter);
    emitter.onCompletion(() -> subscribers.get(key).remove(emitter));
    return emitter;
}
```

**5. VideoAnalysisConsumer — MQ 消费可靠性**（直接映射到 PaperPilot 的 AnalysisTaskConsumer）
```java
@Component
@RocketMQMessageListener(topic = "paper-analysis", consumerGroup = "paperpilot-consumer")
public class AnalysisTaskConsumer implements RocketMQListener<AnalysisTaskMsg> {
    // Redisson 锁 + 重试计数 + 永久失败检测 + 死信队列
}
```

---

## 十三、Java 化改造路线图

### 13.1 改造总览

| 阶段 | 内容 | 预计时间 | Java 占比变化 |
|---|---|---|---|
| **Phase A** | 基础设施对齐 DOVideo-AI | 2-3 天 | 5% → 20% |
| **Phase B** | RocketMQ Consumer 移到 Java | 1-2 天 | 20% → 35% |
| **Phase C** | LangChain4j 替代 Python LangChain | 2-3 天 | 35% → 55% |
| **Phase D** | Java 状态机 + Agent 编排 | 3-5 天 | 55% → 70% |
| **Phase E** | Checkpoint + Redisson + SSE | 3-5 天 | 70% → 80%+ |
| **Phase F** | Python 降级为工具层 | 2-3 天 | 稳定在 75%+ |

总耗时估计：**2-3 周**（可在阶段 1 MVP 开发中同步完成）

### 13.2 Phase A：基础设施对齐

**目标：** pom.xml 和 docker-compose.yml 与 DOVideo-AI 对齐

```
□ pom.xml 新增依赖：
    - Redisson（spring-boot-starter 已有，加 redisson-spring-boot-starter）
    - Flyway（core + mysql，DB 版本管理）
    - MinIO（8.5.7，论文 PDF 存储）
    - Undertow（替换 Tomcat，高性能）
    - LangChain4j（langchain4j-open-ai，LLM 调用）

□ docker-compose.yml 新增服务：
    - MinIO（端口 9000/9001，论文 PDF + 代码文件存储）

□ 创建 Flyway 迁移脚本（基于设计文档 §六 的表结构）
```

### 13.3 Phase B：RocketMQ Consumer 移到 Java

**这是最关键的一步。** RocketMQ 的 Spring Boot Starter 在 Java 侧最成熟，Consumer 放 Python 是本项目最大的架构问题。

```
□ 创建 AnalysisTaskMsg（RocketMQ 消息体 Record）
□ 创建 AnalysisTaskConsumer（@RocketMQMessageListener）
    - 消费 topic: paper-analysis
    - Redisson 分布式锁防重复消费
    - 重试次数追踪（maxReconsumeTimes=3）
    - 永久失败 → 死信队列 + failed_analysis_tasks 表
□ 创建 AnalysisDispatchService
    - Redis SET NX 幂等去重
    - Redisson RRateLimiter 用户级 + 全局限流
    - RocketMQTemplate.convertAndSend 发布任务
    - 返回 202 Accepted
□ 更新 AnalysisController：POST /api/tasks → DispatchService → MQ
```

### 13.4 Phase C：LangChain4j 替代 Python LangChain

**LLM 调用全部移到 Java 侧。** DeepSeek API 兼容 OpenAI 格式，LangChain4j 直接可用。

```
□ 创建 PaperPilotAiService（类似 DOVideo-AI 的 DeepSeekUtils）
    - ChatLanguageModel bean（OpenAiChatModel，指向 DeepSeek API）
    - 各 Agent 的 Prompt 模板（Paper/Code/Mapping/Env）
    - 结构化 JSON 输出 + 重试
    - SYSTEM_POLICY 防止 prompt injection
□ 各 Agent 逻辑在 Java 侧实现：
    - PaperAnalysisService：调 Python PDF 解析 → LLM 总结
    - CodeAnalysisService：调 Python AST 解析 → LLM 说明
    - MappingService：Embedding 匹配 + LLM 验证
    - EnvGenerationService：LLM 生成 Dockerfile
□ 删除 paperpilot-agent 中的 LLM 调用代码
```

### 13.5 Phase D：Java 状态机 + Agent 编排

```
□ 创建 AnalysisStateMachine
    - Stage 枚举（PENDING → PAPER → CODE → MAPPING → ENV → COMPLETED）
    - State 记录（taskId, stage, progress, results, errors）
    - 条件分支（无 GitHub URL → 跳过 CODE_ANALYSIS）
    - Redis 状态持久化（task:{id}:state）
□ 创建 AnalysisOrchestrator
    - 从 MQ Consumer 接收任务
    - 按 Stage 顺序执行
    - 每个 Stage 可重试（L2 错误）
    - 致命错误 → 任务标记 FAILED（L1 错误）
    - 降级策略（L3：GROBID 挂了 → PyMuPDF）
□ Python Worker 改为被调用的工具服务：
    - POST /analyze/pdf → 解析 PDF 返回结构化文本
    - POST /analyze/code → 克隆仓库 + AST 分析返回结构 JSON
    - POST /embedding → 文本转向量
```

### 13.6 Phase E：Checkpoint + Redisson + SSE

```
□ 创建 AgentCheckpointService（MySQL + Redis 双层）
    - MySQL agent_checkpoints 表（恢复真源）
    - Redis Hash 7 天热缓存
    - per-task: task:{id}:plan / :criticState / :result / :stage
□ 创建 TaskEventService（Redis Pub/Sub → SSE）
    - SseEmitter 订阅管理
    - Stage 变更 → Redis publish → 内存 fan-out → SSE emit
    - 断线重连 + 终态补发
□ Redisson 集成：
    - 分布式锁（防重复消费）
    - RRateLimiter（用户 5/min + 全局 30/min）
    - 上下文内容锁（同一 PDF 不重复解析）
```

### 13.7 Phase F：Python 降级为工具层

```
□ paperpilot-agent 精简为三个独立 Endpoint：
    - POST /tools/pdf-parse    → GROBID/PyMuPDF PDF 解析
    - POST /tools/code-analyze  → tree-sitter AST 分析
    - POST /tools/embedding     → 文本 Embedding 生成

□ Python 侧移除：
    - LangGraph / LangChain 依赖
    - RocketMQ 消费逻辑
    - Agent 编排逻辑
    - 状态管理逻辑

□ Python 侧保留：
    - FastAPI 框架（轻量 HTTP 服务）
    - tree-sitter + ast-grep（代码解析）
    - PyMuPDF（PDF 降级解析）
    - sentence-transformers（Embedding 模型推理）
```

---

## 十四、面试话术调整

### 14.1 为什么用 Java + Python 混合架构（新话术）

> "这个项目的核心是一个分布式的多 Agent 编排引擎。我用 Java/Spring Boot 做为主控层——任务调度、状态机管理、LLM 编排都在 Java 侧完成。Python 只作为轻量的工具微服务，处理个别 Java 生态不擅长的工作，比如 AST 解析和 Embedding 模型推理。
>
> 具体来说，LangChain4j 负责所有 LLM 调用，RocketMQ 的 Producer 和 Consumer 都在 Java 侧，Agent 之间的状态流转由 Java 状态机管理。Python Worker 通过 REST API 被 Java 侧调用，只做 PDF 解析和代码 AST 分析这类工具任务。
>
> 这种架构就像一个微服务系统：Java 是大脑和调度中心，Python 是执行特定任务的手。这比全部用 Python 写更能体现分布式系统的设计能力。"

### 14.2 可能被追问的问题（更新）

| 问题 | 回答方向 |
|---|---|
| **"为什么不用 Python 全栈？"** | Python 全栈（FastAPI+Django）也能做，但 Spring Boot + RocketMQ + Redisson 的异步任务引擎更能展示后端工程能力。Java 实习面试官更关注你对 Spring 生态、MQ、分布式锁、状态管理的理解，而不是 Python ML 能力。 |
| **"LangChain4j 和 LangGraph 有什么区别？"** | LangChain4j 是 LangChain 的 Java 移植，专注于 LLM 集成（Chat Model、Tool Calling、Structured Output），不是 Agent 编排框架。Agent 编排我用 Java 自实现的状态机管理，比 LangGraph 更可控——每个 Node 的状态变更都有明确的输入输出和错误处理，中间状态持久化到 Redis，可以随时恢复。这是我的主动设计选择，而不是因为不会用 LangGraph。 |
| **"Python 侧为什么还要保留？"** | tree-sitter 的 Java binding 不成熟（解析能力弱、语言覆盖率低）；sentence-transformers 是纯 Python ML 生态，没有 Java 等价替代。让合适的技术做合适的事——Python 做 ML 工具层，Java 做工程编排层，各司其职。 |
| **"如果 ChromaDB 挂了怎么办？"** | 映射步骤标记为降级，只输出 Paper 和 Code 的独立结果，跳过概念映射。前端展示时标注 `[映射步骤跳过: ChromaDB不可用]`。任务不算失败。 |

---

## 十五、前端设计（Vue 3 SPA）

### 15.1 技术选型

| 层面 | 选型 | 理由 |
|------|------|------|
| 框架 | **Vue 3.5** (Composition API) | 与 DOVideo-AI 同款，生态成熟 |
| 构建 | **Vite** (rolldown-vite 7.2) | DOVideo-AI 已验证，开发体验好 |
| Markdown | **marked** | 渲染 LLM 输出的 Markdown 格式分析报告 |
| UI 组件 | **无组件库，纯手写 CSS** | 保持与 DOVideo-AI 一致的项目风格，代码完全可控 |
| 路由 | **无 Vue Router**（单页条件渲染） | 本项目仅一个主页面（输入→进度→结果），不需要路由 |
| API 封装 | `api.js` 统一信封解包 | 后端 `{code, message, data}` 统一响应，前端集中解包 |
| SSE | `taskEvents.js` 连接池 | 指数退避重连、终端状态自动释放（DOVideo-AI 同款） |

### 15.2 页面结构

```
┌────────────────────────────────────────────┐
│  Header: Logo "PaperPilot" + 副标题         │
├────────────────────────────────────────────┤
│                                            │
│  [状态 1: 输入区]                           │
│  ┌──────────────────────────────────────┐  │
│  │  上传论文 PDF (拖拽/点击)              │  │
│  │  GitHub URL 输入框                     │  │
│  │  分析模式选择 (完整/仅论文)             │  │
│  │  [开始分析]                            │  │
│  └──────────────────────────────────────┘  │
│                                            │
│  [状态 2: 进度区]                           │
│  ┌──────────────────────────────────────┐  │
│  │  论文理解 → 代码分析 → 概念映射 → 环境  │  │
│  │  ⬤──────⬤──────◉──────⬤              │  │
│  │  ████████████░░░░░░░░░░ 60%          │  │
│  │  正在分析代码结构...                   │  │
│  └──────────────────────────────────────┘  │
│                                            │
│  [状态 3: 结果区]                           │
│  ┌──────────────────────────────────────┐  │
│  │  [📄论文总结] [💻代码结构] [🔗映射] [🐳环境] │
│  │  ─────────────────────────────────── │  │
│  │  Tab 内容区                           │  │
│  └──────────────────────────────────────┘  │
└────────────────────────────────────────────┘
```

### 15.3 组件树与数据流

```
App.vue (单文件组件，无子组件拆分)
├── 响应式状态 (ref/reactive)
│   ├── pdfFile          — 上传的 PDF File 对象
│   ├── githubUrl        — GitHub 仓库链接
│   ├── analysisMode     — 分析模式
│   ├── taskState        — 当前任务进度 {stage, progress, message}
│   └── result           — 分析结果 {paper, code, mapping, env}
│
├── API 调用 (api.js)
│   ├── POST /api/papers/upload     — FormData 上传 PDF
│   ├── POST /api/tasks             — JSON 提交分析任务 → 返回 taskId
│   ├── SSE  /api/tasks/{id}/progress — 实时进度
│   └── GET  /api/tasks/{id}/result  — 获取完整结果
│
└── SSE 连接池 (taskEvents.js)
    └── 指数退避重连 (1s → 2s → 4s → ... → 15s max)
    └── COMPLETED/FAILED 终态自动释放
```

### 15.4 关键设计决策

**1. 为什么单文件组件（不拆分子组件）？**

MVP 阶段的页面状态清晰（输入→进度→结果），拆分子组件带来的 props/emit 传递复杂度超过收益。DOVideo-AI 的 App.vue 也是 1731 行单文件。后续如果增加路由页面（如历史任务列表），再引入 Vue Router + 组件拆分。

**2. 为什么不需要 Vue Router？**

本项目只有三个页面状态（输入→进度→结果），用 `v-if` 条件渲染即可。DOVideo-AI 同样采用此策略——用侧边栏面板切换而非路由跳转。

**3. API 信封解包模式**

```js
// 后端统一响应: { code: 0, message: "ok", data: {...} }
// api.js 集中解包，调用方直接拿到 data:
const res = await apiRequest('/api/tasks', { method: 'POST', body: ... })
const taskData = await res.json()  // 已经是 data 的内容，不用 .data
```

这与 DOVideo-AI 的 api.js 完全相同，非 JSON 响应（SSE text/event-stream）原样透传。

### 15.5 已创建文件

```
paperpilot-frontend/
├── package.json          # Vue 3 + Vite + marked
├── vite.config.js        # 开发代理 /api → localhost:8080
├── index.html            # SPA 入口
├── .gitignore
└── src/
    ├── main.js           # Vue 入口
    ├── App.vue           # 主组件（~300 行）
    ├── api.js            # API 信封解包
    ├── taskEvents.js     # SSE 连接池 + 指数退避
    ├── taskEventsPolicy.js  # 终端状态判定
    ├── markdown.js       # Markdown 渲染
    └── style.css         # 暗色主题样式（~450 行）
```

构建验证通过：`npm install && npx vite build` → 15 modules, 120KB JS + 13KB CSS (gzip: 43KB + 3KB)。

---

## 十六、Demo 演示路径

面试演示时的推荐路径（~5 分钟）：

```
1. 打开 Web UI，输入 PatchTST 论文 PDF + GitHub 链接，点击"开始分析"
   （展示：简洁的输入界面）

2. 进度条实时更新：
   ✓ PDF解析        (2s)
   ✓ 论文总结       (15s)
   → 代码分析中...  (20s)
   等待中: 概念映射
   等待中: 环境生成
   （展示：SSE 实时推送进度）

3. 结果展示页：
   Tab 1: 论文总结（结构化摘要）
   Tab 2: 代码结构（关键文件/类列表）
   Tab 3: 概念映射（Patch Embedding → class PatchEmbedding）
   Tab 4: 运行步骤 + Dockerfile
   （展示：四个 Tab 的信息密度）

4. （如果阶段 3+4 完成）点击"运行代码"
   → Docker 沙箱执行 → CUDA OOM 报错
   → Debug Agent 自动诊断 → 建议 batch_size=16
   → 展示修复建议 + 代码 diff
   （展示：Debug Agent 的智能化）
```

---

## 十七、参考资料

### 论文与工具

| 名称 | 链接/说明 |
|---|---|
| DOVideo-AI | 同目录参考项目 — 76 文件纯 Java Agent 系统，零 Python |
| LangChain4j | https://github.com/langchain4j/langchain4j — Java LLM 集成框架 |
| GROBID | https://github.com/kermitt2/grobid — PDF 结构化解析 |
| tree-sitter-analyzer | https://github.com/aimasteracc/tree-sitter-analyzer — 代码结构分析 |
| ast-grep | https://ast-grep.github.io — 结构化代码搜索 |
| python-sandbox | https://github.com/onyx-dot-app/python-sandbox — Docker 代码沙箱 |
| ChromaDB | https://github.com/chroma-core/chroma — 轻量向量数据库 |
| Aider repo-map | https://github.com/Aider-AI/aider — PageRank 代码地图参考 |
| RocketMQ Spring Boot | https://github.com/apache/rocketmq-spring — RocketMQ 集成 |

### 关键概念

- **repo-map 模式**：Aider 的 PageRank 代码文件排序 + token 预算拟合，70.3% SWE-bench Lite 正确文件识别率
- **GROBID + LLM 混合**：2026 年趋势 —— GROBID 处理结构化 PDF，困难文档升级到 LLM
- **Docker 沙箱最佳实践**：no-new-privileges + 只读文件系统 + 网络隔离 + 非 root 用户
- **RocketMQ 异步模式**：事务消息保证最终一致性 + 死信队列处理失败重试 + 幂等消费
