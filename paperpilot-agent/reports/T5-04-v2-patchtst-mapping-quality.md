# PatchTST 映射质量报告

## 指标

| 版本 | 模式 | 概念覆盖率 | P@1 | P@3 | P@5 | R@1 | R@3 | R@5 | MRR | 证据完整率 | NEEDS_REVIEW | 弃答准确率 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 规则版算法 | END_TO_END | 0.8889 | 0.0000 | 0.0741 | 0.0444 | 0.0000 | 0.1667 | 0.1667 | 0.0926 | 1.0000 | 0.1643 | 1.0000 |
| 增强版最终API | END_TO_END | 0.8889 | 0.0000 | 0.0741 | 0.0444 | 0.0000 | 0.1667 | 0.1667 | 0.0926 | 1.0000 | 0.7357 | 1.0000 |
| Oracle检索 | ORACLE_RETRIEVAL | 1.0000 | 0.0000 | 0.0741 | 0.0444 | 0.0000 | 0.1667 | 0.1667 | 0.0926 | 1.0000 | 0.9636 | 1.0000 |

## 口径

主排名指标仅使用 `CONFIRMED` 概念；P@K、R@K 为逐概念宏平均，MRR 取首个正确符号。
`AUXILIARY` 与 `LOW_CONFIDENCE` 不进入主指标；`NO_EXPLICIT_IMPLEMENTATION` 仅进入弃答准确率。
证据完整要求论文定位与文本，以及固定 commit、文件、符号和起始行全部存在。

## 执行元数据

- **规则版算法**：mode=rules+hash-embedding，model=none，promptVersion=N/A，平均阶段耗时=1952.192 ms，LLM tokens=0
- **增强版最终API**：mode=N/A，model=N/A，promptVersion=N/A，平均阶段耗时=N/A ms，LLM tokens=N/A
- **Oracle检索**：mode=oracle-concepts+retrieval，model=deterministic-sha256-v1，promptVersion=1，平均阶段耗时=274.264 ms，LLM tokens=0

## 错误分析

### 规则版算法

- `MULTI_FILE_OR_MULTI_SYMBOL_PARTIAL`：1
- `RIGHT_FILE_WRONG_SYMBOL`：2
- `SEMANTIC_OR_RULE_RETRIEVAL`：4
- `TERM_OR_CONCEPT_EXTRACTION`：1
- `UNRELATED_TOP_K_SYMBOL`：38

失败样例（按 conceptId 稳定排序，完整列表见 JSON）：

- `PT-01` Time-series patching：FALSE_NEGATIVE / SEMANTIC_OR_RULE_RETRIEVAL；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/layers/PatchTST_backbone.py|PatchTST_backbone.forward
- `PT-01` Time-series patching：FALSE_POSITIVE / UNRELATED_TOP_K_SYMBOL；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/Formers/FEDformer/layers/utils.py|LpLoss
- `PT-01` Time-series patching：FALSE_POSITIVE / UNRELATED_TOP_K_SYMBOL；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/Formers/FEDformer/utils/timefeatures.py|TimeFeature
- `PT-01` Time-series patching：FALSE_POSITIVE / UNRELATED_TOP_K_SYMBOL；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/Formers/Pyraformer/utils/timefeatures.py|TimeFeature
- `PT-01` Time-series patching：FALSE_POSITIVE / UNRELATED_TOP_K_SYMBOL；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/utils/timefeatures.py|TimeFeature

### 增强版最终API

- `MULTI_FILE_OR_MULTI_SYMBOL_PARTIAL`：1
- `RIGHT_FILE_WRONG_SYMBOL`：2
- `SEMANTIC_OR_RULE_RETRIEVAL`：4
- `TERM_OR_CONCEPT_EXTRACTION`：1
- `UNRELATED_TOP_K_SYMBOL`：38

失败样例（按 conceptId 稳定排序，完整列表见 JSON）：

- `PT-01` Time-series patching：FALSE_NEGATIVE / SEMANTIC_OR_RULE_RETRIEVAL；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/layers/PatchTST_backbone.py|PatchTST_backbone.forward
- `PT-01` Time-series patching：FALSE_POSITIVE / UNRELATED_TOP_K_SYMBOL；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/Formers/FEDformer/layers/utils.py|LpLoss
- `PT-01` Time-series patching：FALSE_POSITIVE / UNRELATED_TOP_K_SYMBOL；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/Formers/Pyraformer/utils/timefeatures.py|TimeFeature
- `PT-01` Time-series patching：FALSE_POSITIVE / UNRELATED_TOP_K_SYMBOL；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/utils/timefeatures.py|TimeFeature
- `PT-01` Time-series patching：FALSE_POSITIVE / UNRELATED_TOP_K_SYMBOL；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/Formers/Pyraformer/preprocess_flow.py|dezip

### Oracle检索

- `MULTI_FILE_OR_MULTI_SYMBOL_PARTIAL`：1
- `RIGHT_FILE_WRONG_SYMBOL`：3
- `SEMANTIC_OR_RULE_RETRIEVAL`：4
- `UNRELATED_TOP_K_SYMBOL`：43

失败样例（按 conceptId 稳定排序，完整列表见 JSON）：

- `PT-01` Time-series patching：FALSE_NEGATIVE / SEMANTIC_OR_RULE_RETRIEVAL；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_supervised/layers/PatchTST_backbone.py|PatchTST_backbone.forward
- `PT-01` Time-series patching：FALSE_POSITIVE / UNRELATED_TOP_K_SYMBOL；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_self_supervised/src/callback/tracking.py|TrackTimerCB.format_time
- `PT-01` Time-series patching：FALSE_POSITIVE / UNRELATED_TOP_K_SYMBOL；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_self_supervised/src/callback/patch_mask.py|Patch
- `PT-01` Time-series patching：FALSE_POSITIVE / UNRELATED_TOP_K_SYMBOL；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_self_supervised/src/callback/patch_mask.py|PatchCB.set_patch
- `PT-01` Time-series patching：FALSE_POSITIVE / UNRELATED_TOP_K_SYMBOL；204c21efe0b39603ad6e2ca640ef5896646ab1a9|PatchTST_self_supervised/src/callback/patch_mask.py|create_patch

## 下一步优化

- 复合概念覆盖率已达到 8/9，下一步优先优化检索与重排，而非继续扩大抽取规则。
- 对一对多和跨文件实现增加多样性召回，避免 Top-K 被同模块近义符号占满。
- 将索引中的 docstring、父符号与调用关系用于重排，同时保持路径和行号只能来自 AST。
- LLM 评估必须记录模型、promptVersion、参数和 token；缺失时报告 N/A，不作推测。
