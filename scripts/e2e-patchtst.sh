#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
CONDA_ENV=${PAPERPILOT_CONDA_ENV:-paperpilot}
API_URL=${PAPERPILOT_API_URL:-http://127.0.0.1:8080}
AGENT_URL=${PAPERPILOT_AGENT_URL:-http://127.0.0.1:8001}
RUN_ID=$(date -u +%Y%m%dT%H%M%SZ)
ARTIFACT_DIR="$ROOT_DIR/.e2e-artifacts/$RUN_ID"
STORAGE_DIR="$ARTIFACT_DIR/storage"
# 跨运行保留已验证的官方仓库缓存；每个 task 的工作副本仍按 task/stage 隔离。
WORKSPACE_DIR=${PAPERPILOT_E2E_WORKSPACE_DIR:-$ROOT_DIR/.e2e-artifacts/workspaces}
STATE_FILE="$ARTIFACT_DIR/state.json"
API_PID=""
AGENT_PID=""
mkdir -p "$STORAGE_DIR" "$WORKSPACE_DIR"

stop_group() {
  local pid=${1:-}
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
}

diagnostics() {
  local status=$?
  if [[ $status -ne 0 ]]; then
    echo "E2E failed; artifacts: $ARTIFACT_DIR" >&2
    docker compose -f "$ROOT_DIR/docker-compose.yml" ps >"$ARTIFACT_DIR/docker-compose-ps.txt" 2>&1 || true
    tail -n 200 "$ARTIFACT_DIR/api.log" >"$ARTIFACT_DIR/api-tail.log" 2>/dev/null || true
    tail -n 200 "$ARTIFACT_DIR/agent.log" >"$ARTIFACT_DIR/agent-tail.log" 2>/dev/null || true
  fi
  stop_group "$API_PID"
  stop_group "$AGENT_PID"
  exit "$status"
}
trap diagnostics EXIT INT TERM

wait_health() {
  local url=$1
  local deadline=$((SECONDS + 120))
  until curl --fail --silent --show-error "$url" >/dev/null; do
    if (( SECONDS >= deadline )); then
      echo "health timeout: $url" >&2
      return 1
    fi
    sleep 2
  done
}

start_agent() {
  (
    cd "$ROOT_DIR/paperpilot-agent"
    exec setsid env PAPERPILOT_STORAGE_ROOT="$STORAGE_DIR" \
      PAPERPILOT_WORKSPACE_ROOT="$WORKSPACE_DIR" \
      PAPERPILOT_CLONE_TIMEOUT_SECONDS=300 \
      conda run --no-capture-output -n "$CONDA_ENV" \
      uvicorn app.main:app --host 127.0.0.1 --port 8001
  ) >"$ARTIFACT_DIR/agent.log" 2>&1 &
  AGENT_PID=$!
}

start_api() {
  (
    cd "$ROOT_DIR/paperpilot-api"
    exec setsid env PAPERPILOT_STORAGE_DIR="$STORAGE_DIR" AGENT_WORKER_URL="$AGENT_URL" \
      mvn -q spring-boot:run
  ) >"$ARTIFACT_DIR/api.log" 2>&1 &
  API_PID=$!
}

docker compose -f "$ROOT_DIR/docker-compose.yml" up -d mysql redis rocketmq-namesrv rocketmq-broker grobid
start_agent
start_api
wait_health "$AGENT_URL/internal/health"
wait_health "$API_URL/actuator/health"

cd "$ROOT_DIR"
conda run --no-capture-output -n "$CONDA_ENV" python scripts/e2e_patchtst.py run \
  --api "$API_URL" --state "$STATE_FILE"

stop_group "$API_PID"
API_PID=""
start_api
wait_health "$API_URL/actuator/health"
conda run --no-capture-output -n "$CONDA_ENV" python scripts/e2e_patchtst.py verify \
  --api "$API_URL" --state "$STATE_FILE"

echo "T5-02 PatchTST E2E passed; artifacts: $ARTIFACT_DIR"
