# PatchTST 映射质量报告

## 指标

| 版本 | P@1 | P@3 | P@5 | R@1 | R@3 | R@5 | MRR | 证据完整率 | NEEDS_REVIEW | 弃答准确率 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 规则版 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.9929 | 0.2993 | 1.0000 |
| 增强版（确定性验证器） | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.9929 | 0.7702 | 1.0000 |

## 口径

主排名指标仅使用 `CONFIRMED` 概念；P@K、R@K 为逐概念宏平均，MRR 取首个正确符号。
`AUXILIARY` 与 `LOW_CONFIDENCE` 不进入主指标；`NO_EXPLICIT_IMPLEMENTATION` 仅进入弃答准确率。
证据完整要求论文定位与文本，以及固定 commit、文件、符号和起始行全部存在。

## 执行元数据

- **规则版**：mode=rules+hash-embedding，model=none，promptVersion=N/A，平均阶段耗时=3046.546 ms，LLM tokens=0
- **增强版（确定性验证器）**：mode=rules+hash-embedding+verification，model=deterministic-sha256-v1，promptVersion=1，平均阶段耗时=3038.59 ms，LLM tokens=0

## 错误分析

### 规则版

- `TERM_OR_CONCEPT_EXTRACTION`：9

失败样例（按 conceptId 稳定排序，完整列表见 JSON）：

- `PT-01` Time-series patching：FALSE_NEGATIVE / TERM_OR_CONCEPT_EXTRACTION；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/layers/PatchTST_backbone.py|PatchTST_backbone.forward
- `PT-02` Channel-independent shared Transformer：FALSE_NEGATIVE / TERM_OR_CONCEPT_EXTRACTION；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/layers/PatchTST_backbone.py|TSTiEncoder.forward
- `PT-03` Patch projection and learnable positional encoding：FALSE_NEGATIVE / TERM_OR_CONCEPT_EXTRACTION；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/layers/PatchTST_backbone.py|TSTiEncoder.__init__, 204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/layers/PatchTST_layers.py|positional_encoding
- `PT-04` Vanilla Transformer encoder stack：FALSE_NEGATIVE / TERM_OR_CONCEPT_EXTRACTION；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/layers/PatchTST_backbone.py|TSTEncoder.forward
- `PT-05` Multi-head self-attention：FALSE_NEGATIVE / TERM_OR_CONCEPT_EXTRACTION；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/layers/PatchTST_backbone.py|_MultiheadAttention.forward

### 增强版（确定性验证器）

- `TERM_OR_CONCEPT_EXTRACTION`：9

失败样例（按 conceptId 稳定排序，完整列表见 JSON）：

- `PT-01` Time-series patching：FALSE_NEGATIVE / TERM_OR_CONCEPT_EXTRACTION；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/layers/PatchTST_backbone.py|PatchTST_backbone.forward
- `PT-02` Channel-independent shared Transformer：FALSE_NEGATIVE / TERM_OR_CONCEPT_EXTRACTION；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/layers/PatchTST_backbone.py|TSTiEncoder.forward
- `PT-03` Patch projection and learnable positional encoding：FALSE_NEGATIVE / TERM_OR_CONCEPT_EXTRACTION；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/layers/PatchTST_backbone.py|TSTiEncoder.__init__, 204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/layers/PatchTST_layers.py|positional_encoding
- `PT-04` Vanilla Transformer encoder stack：FALSE_NEGATIVE / TERM_OR_CONCEPT_EXTRACTION；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/layers/PatchTST_backbone.py|TSTEncoder.forward
- `PT-05` Multi-head self-attention：FALSE_NEGATIVE / TERM_OR_CONCEPT_EXTRACTION；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/layers/PatchTST_backbone.py|_MultiheadAttention.forward

## 下一步优化

- 让概念抽取输出稳定 conceptId 或基准别名，降低术语差异导致的概念对齐失败。
- 对一对多和跨文件实现增加多样性召回，避免 Top-K 被同模块近义符号占满。
- 将索引中的 docstring、父符号与调用关系用于重排，同时保持路径和行号只能来自 AST。
- LLM 评估必须记录模型、promptVersion、参数和 token；缺失时报告 N/A，不作推测。
