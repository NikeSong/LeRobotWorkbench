#!/usr/bin/env bash
# Install Python deps required by the Java Workbench (calibrate / teleop / record).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if ! command -v uv >/dev/null 2>&1; then
  echo "未找到 uv。请先安装: curl -LsSf https://astral.sh/uv/install.sh | sh"
  exit 1
fi
echo "安装 Workbench Python 依赖（含 dataset / hardware / feetech）…"
uv sync --extra workbench
echo "校验 datasets…"
uv run python -c "import datasets; print('datasets', datasets.__version__, 'OK')"
echo "完成。可启动: cd workbench && mvn -q spring-boot:run"
