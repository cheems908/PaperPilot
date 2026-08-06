# PatchTST Gold 标注规范

## 标注单位

每条记录表示一个论文概念。论文证据由章节、PDF 页码和短摘录定位；源码证据使用固定
commit 下的 `filePath + qualifiedName + startLine + endLine`。同一概念允许对应多个源码符号。

## 确定性等级

- `CONFIRMED`：论文描述与源码行为存在直接、可复核的对应。
- `AUXILIARY`：辅助实现，支持核心概念但不是概念本身。
- `LOW_CONFIDENCE`：实现存在，但论文正文支持较弱或属于实现扩展。
- `NO_EXPLICIT_IMPLEMENTATION`：论文有明确概念，但固定 commit 中没有稳定的命名符号。

无明确实现的概念必须保留空 `mappings`，用于评估系统是否能够克制地放弃映射；不得为了提高
命中率强行指定相似文件。低置信度和辅助项应单独报告，不与确认项混为同一分母。

## 审核规则

1. 不使用 PaperPilot 的输出生成 gold；标注以论文和固定源码人工对照为准。
2. `qualifiedName` 与 Python AST 定义一致，方法使用 `Class.method`。
3. 行号覆盖完整 AST 定义，而不是只指向一行关键词。
4. 一对多映射中每个符号均标记 `PRIMARY` 或 `SUPPORTING`。
5. 修改 gold 后必须运行 fixture 测试；源码升级必须建立新 benchmark 版本，不能原地改 commit。

## 指标口径

排序键为 `commitSha + filePath + qualifiedName`。Precision@K、Recall@K 和 MRR 只以
`CONFIRMED` 概念为主指标；辅助及低置信度分层报告；无明确实现项报告 abstention accuracy。
证据完整率要求论文定位、固定 commit、文件、符号和行号全部存在。
