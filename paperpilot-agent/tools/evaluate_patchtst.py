"""CLI for deterministic PatchTST mapping evaluation."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from tools.benchmark_evaluator import evaluate, load_json, render_markdown


def _result_spec(value: str) -> tuple[str, Path]:
    if "=" in value:
        label, path = value.split("=", 1)
        if label and path:
            return label, Path(path)
    path = Path(value)
    return path.stem, path


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate PatchTST paper-to-code mappings")
    parser.add_argument("--gold", type=Path, required=True)
    parser.add_argument("--alignment", type=Path,
                        help="benchmark-only concept alignment; never passed to production mapping")
    parser.add_argument("--result", action="append", required=True,
                        help="result JSON path or LABEL=path; repeat for rule/enhanced")
    parser.add_argument("--json-out", type=Path)
    parser.add_argument("--markdown-out", type=Path)
    args = parser.parse_args()

    gold = load_json(args.gold)
    alignment = load_json(args.alignment) if args.alignment else None
    evaluations = [evaluate(gold, load_json(path), label, alignment)
                   for label, path in map(_result_spec, args.result)]
    output = {"schemaVersion": 1, "benchmarkId": gold["benchmarkId"], "evaluations": evaluations}
    rendered_json = json.dumps(output, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    rendered_markdown = render_markdown(evaluations)
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(rendered_json, encoding="utf-8")
    if args.markdown_out:
        args.markdown_out.parent.mkdir(parents=True, exist_ok=True)
        args.markdown_out.write_text(rendered_markdown, encoding="utf-8")
    if not args.json_out and not args.markdown_out:
        print(rendered_json, end="")


if __name__ == "__main__":
    main()
