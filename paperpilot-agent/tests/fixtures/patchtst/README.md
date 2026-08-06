# PatchTST Benchmark Fixture

本目录冻结 PaperPilot T5 的单论文基准。PDF 因版权和仓库体积不提交；本地文件应放在
`paperpilot-agent/data/papers/PatchTST.pdf`，并与 `benchmark.json` 中 SHA-256 一致。

验证 fixture：

```bash
conda run -n paperpilot pytest -q tests/test_patchtst_benchmark_fixture.py
```

严格核对官方仓库：

```bash
git clone https://github.com/yuqinie98/PatchTST.git /tmp/patchtst
git -C /tmp/patchtst checkout 204c21efe0b39603ad6e2ca640ef5896646ab1a9
conda run -n paperpilot python -m tools.validate_patchtst_fixture --repo /tmp/patchtst
```

如果本地存在 PDF，测试还会校验其 checksum、页数和 ICLR 2023 标题页；PDF 缺失时不会影响
仓库内 fixture 的离线完整性校验。

映射质量评估（仓库必须处于 benchmark 固定 commit）：

```bash
conda run -n paperpilot python -m tools.build_patchtst_results \
  --paper data/papers/PatchTST.pdf --repo /path/to/PatchTST \
  --commit 204c21efe0b39603ad6e2ca640ef5896646ab1a9 --out-dir build/benchmark
conda run -n paperpilot python -m tools.evaluate_patchtst \
  --gold tests/fixtures/patchtst/gold.json \
  --result '规则版=build/benchmark/rule-result.json' \
  --result '增强版（确定性验证器）=build/benchmark/enhanced-result.json'
```

当前固化指标见 `docs/reports/T5-03-patchtst-mapping-quality.md`。增强版明确使用确定性验证器，
不是外部真实 LLM；因此 token 为 0，不把 fake 验证结果宣传为 LLM 效果。
