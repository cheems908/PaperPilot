#!/usr/bin/env python3
"""T5-02 PatchTST HTTP 端到端执行与重启后校验。"""

from __future__ import annotations

import argparse
import hashlib
import json
import time
import uuid
from pathlib import Path

import httpx


TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED"}
STAGES = {"PARSE_PAPER", "CLONE_REPOSITORY", "INDEX_CODE", "MAP_CONCEPTS"}


def api(client: httpx.Client, method: str, path: str, **kwargs):
    response = client.request(method, path, **kwargs)
    response.raise_for_status()
    body = response.json()
    if body.get("code") != 0:
        raise AssertionError(f"business error {path}: {body}")
    return body.get("data"), response


def wait_task(client: httpx.Client, task_id: int, timeout: float) -> dict:
    deadline = time.monotonic() + timeout
    latest = None
    while time.monotonic() < deadline:
        latest, _ = api(client, "GET", f"/api/v1/tasks/{task_id}")
        if latest["status"] in TERMINAL:
            return latest
        time.sleep(2)
    stages, _ = api(client, "GET", f"/api/v1/tasks/{task_id}/stages")
    raise TimeoutError(f"task {task_id} timeout; task={latest}; stages={stages}")


def observe_sse(client: httpx.Client, task_id: int) -> None:
    with client.stream("GET", f"/api/v1/tasks/{task_id}/events", timeout=10) as response:
        response.raise_for_status()
        for line in response.iter_lines():
            if line.startswith("event:") or line.startswith("data:"):
                return
    raise AssertionError(f"task {task_id} SSE did not emit snapshot/event")


def decode_snapshot(value):
    return json.loads(value) if isinstance(value, str) else value


def validate_result(result: dict, expected_commit: str) -> list[tuple]:
    assert result["status"] == "SUCCEEDED", result
    stages = result["stages"]
    assert {stage["stage"] for stage in stages} == STAGES, stages
    latest = {}
    for stage in stages:
        if stage["stage"] not in latest or stage["attempt"] > latest[stage["stage"]]["attempt"]:
            latest[stage["stage"]] = stage
    latest_statuses = {name: stage["status"] for name, stage in latest.items()}
    assert all(status == "SUCCEEDED" for status in latest_statuses.values()), latest_statuses
    assert all(stage.get("snapshot") for stage in latest.values()), latest_statuses

    snapshots = {name: decode_snapshot(value) for name, value in result["result"].items()}
    index = snapshots["INDEX_CODE"]
    mapping = snapshots["MAP_CONCEPTS"]
    assert index["commitSha"] == expected_commit, index
    assert mapping["commitSha"] == expected_commit, mapping
    assert index["symbolCount"] > 0, index

    signatures = []
    assert result["mappings"], result
    for concept in result["mappings"]:
        assert concept.get("source") and concept.get("evidenceText"), concept
        assert concept.get("candidates"), concept
        for candidate in concept["candidates"]:
            assert candidate.get("qualifiedName") and candidate.get("filePath"), candidate
            assert isinstance(candidate.get("startLine"), int) and candidate["startLine"] > 0, candidate
            assert candidate.get("totalScore") is not None and candidate.get("status"), candidate
            assert candidate.get("evidence") is not None, candidate
            signatures.append((concept["term"], candidate["qualifiedName"], candidate["filePath"],
                               candidate["startLine"], candidate["status"]))
    assert len(signatures) == len(set(signatures)), "duplicate business mappings"
    return sorted(signatures)


def run(args) -> None:
    paper = args.paper.resolve()
    assert paper.is_file(), f"PDF not found: {paper}"
    assert hashlib.sha256(paper.read_bytes()).hexdigest() == args.pdf_sha
    state = {"expectedCommit": args.commit, "tasks": []}
    with httpx.Client(base_url=args.api, timeout=60) as client:
        project, _ = api(client, "POST", "/api/v1/projects", json={
            "name": f"PatchTST E2E {uuid.uuid4().hex[:8]}", "description": "T5-02 automated acceptance"
        })
        for run_number in (1, 2):
            with paper.open("rb") as stream:
                uploaded, _ = api(client, "POST", "/api/v1/files/papers",
                                  files={"file": (paper.name, stream, "application/pdf")})
            assert uploaded["sha256"] == args.pdf_sha
            request_key = f"patchtst-e2e-{uuid.uuid4()}"
            created, response = api(client, "POST",
                                    f"/api/v1/projects/{project['id']}/analysis-tasks",
                                    json={"fileId": uploaded["fileId"],
                                          "githubUrl": args.repo, "requestKey": request_key})
            assert response.status_code == 202 and created["status"] == "QUEUED"
            duplicate, duplicate_response = api(client, "POST",
                                                  f"/api/v1/projects/{project['id']}/analysis-tasks",
                                                  json={"fileId": uploaded["fileId"],
                                                        "githubUrl": args.repo,
                                                        "requestKey": request_key})
            assert duplicate_response.status_code == 202 and duplicate["taskId"] == created["taskId"]
            observe_sse(client, created["taskId"])
            terminal = wait_task(client, created["taskId"], args.timeout)
            assert terminal["status"] == "SUCCEEDED", terminal
            result, _ = api(client, "GET", f"/api/v1/tasks/{created['taskId']}/result")
            signatures = validate_result(result, args.commit)
            state["tasks"].append({"taskId": created["taskId"], "signatures": signatures})
        assert state["tasks"][0]["signatures"] == state["tasks"][1]["signatures"], \
            "two benchmark runs produced different structures"
    args.state.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"phase": "run", "valid": True,
                      "taskIds": [task["taskId"] for task in state["tasks"]]}, ensure_ascii=False))


def verify(args) -> None:
    state = json.loads(args.state.read_text(encoding="utf-8"))
    with httpx.Client(base_url=args.api, timeout=60) as client:
        for saved in state["tasks"]:
            result, _ = api(client, "GET", f"/api/v1/tasks/{saved['taskId']}/result")
            signatures = validate_result(result, state["expectedCommit"])
            assert signatures == [tuple(item) for item in saved["signatures"]]
            observe_sse(client, saved["taskId"])
    print(json.dumps({"phase": "restart-verify", "valid": True,
                      "taskIds": [task["taskId"] for task in state["tasks"]]}, ensure_ascii=False))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=("run", "verify"))
    parser.add_argument("--api", default="http://127.0.0.1:8080")
    parser.add_argument("--paper", type=Path,
                        default=Path("paperpilot-agent/data/papers/PatchTST.pdf"))
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--repo", default="https://github.com/yuqinie98/PatchTST")
    parser.add_argument("--commit", default="204c21efe0b39603ad6e2ca640ef5896646ab1a9")
    parser.add_argument("--pdf-sha", default="ffd4021d25b4959883242f256b0fe4ec42f477f66db78c61bfacf7baa7848b0e")
    parser.add_argument("--timeout", type=float, default=600)
    args = parser.parse_args()
    run(args) if args.phase == "run" else verify(args)


if __name__ == "__main__":
    main()
