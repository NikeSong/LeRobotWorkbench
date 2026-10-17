#!/usr/bin/env python3
"""LeRobot ↔ ROS bridge (rosbridge-friendly).

This node is the long-term integration point: Java Workbench talks ROS only;
this process translates ROS service calls into LeRobot CLI / Python APIs.

Modes:
  1) Standalone HTTP shim (default, no ROS install required for bring-up tests)
  2) ROS2 + rosbridge when available (set LEROBOT_BRIDGE_MODE=ros2)

Run (HTTP shim for local pairing with mock Java bus is optional):
  python workbench/bridge/python/lerobot_ros_bridge.py

With real ROS2 (example):
  # terminal A: ros2 launch rosbridge_server rosbridge_websocket_launch.xml
  # terminal B: LEROBOT_BRIDGE_MODE=ros2 python .../lerobot_ros_bridge.py
"""

from __future__ import annotations

import json
import logging
import os
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

logging.basicConfig(level=logging.INFO, format="[bridge] %(message)s")
log = logging.getLogger("lerobot_bridge")

REPO_ROOT = Path(__file__).resolve().parents[3]
NS = "/lerobot"


def _run_lerobot(args: list[str]) -> dict[str, Any]:
    """Invoke a lerobot CLI via uv run when possible."""
    cmd = ["uv", "run", *args] if (REPO_ROOT / "uv.lock").exists() else args
    log.info("exec: %s", " ".join(cmd))
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
            timeout=3600,
            check=False,
        )
        return {
            "ok": proc.returncode == 0,
            "returncode": proc.returncode,
            "stdout": proc.stdout[-4000:],
            "stderr": proc.stderr[-4000:],
        }
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": str(exc)}


def handle_service(service: str, payload: dict[str, Any]) -> dict[str, Any]:
    """Map ROS service names to LeRobot operations."""
    if service.endswith("/hardware/find_port") or service == f"{NS}/hardware/find_port":
        # Real implementation would call lerobot-find-port interactively;
        # here we list common tty devices as a starting point.
        import glob

        ports = [{"path": p, "hint": "serial"} for p in sorted(glob.glob("/dev/tty.usbmodem*"))]
        ports += [{"path": p, "hint": "acm"} for p in sorted(glob.glob("/dev/ttyACM*"))]
        return {"ok": True, "ports": ports or [{"path": "/dev/ttyUSB0", "hint": "fallback"}]}

    if service.endswith("/record/start"):
        robot = payload.get("robotType", "so101_follower")
        teleop = payload.get("teleopType", "so101_leader")
        args = [
            "lerobot-record",
            f"--robot.type={robot}",
            f"--teleop.type={teleop}",
            f"--dataset.repo_id={payload.get('repoId', 'local/demo')}",
            f"--dataset.num_episodes={payload.get('numEpisodes', 1)}",
            f"--dataset.single_task={payload.get('singleTask', 'task')}",
            f"--dataset.fps={payload.get('fps', 30)}",
        ]
        if payload.get("robotPort"):
            args.append(f"--robot.port={payload['robotPort']}")
        if payload.get("teleopPort"):
            args.append(f"--teleop.port={payload['teleopPort']}")
        # Fire-and-forget in a thread for long jobs
        threading.Thread(target=_run_lerobot, args=(args,), daemon=True).start()
        return {"ok": True, "message": "record started", "args": args}

    if service.endswith("/train/start"):
        args = [
            "lerobot-train",
            f"--policy.type={payload.get('policyType', 'act')}",
            f"--dataset.repo_id={payload.get('datasetRepoId', 'local/demo')}",
            f"--steps={payload.get('steps', 1000)}",
            f"--batch_size={payload.get('batchSize', 8)}",
            f"--output_dir={payload.get('outputDir', 'outputs/train/workbench')}",
        ]
        threading.Thread(target=_run_lerobot, args=(args,), daemon=True).start()
        return {"ok": True, "message": "train started", "args": args}

    if service.endswith("/eval/start"):
        args = [
            "lerobot-eval",
            f"--policy.path={payload.get('policyPath', '')}",
            f"--env.type={payload.get('envType', 'pusht')}",
            f"--eval.n_episodes={payload.get('nEpisodes', 10)}",
        ]
        threading.Thread(target=_run_lerobot, args=(args,), daemon=True).start()
        return {"ok": True, "message": "eval started", "args": args}

    if service.endswith("/align/run"):
        return {"ok": True, "message": "align stub — implement dataset timestamp repair here", "echo": payload}

    if service.endswith("/annotate/run"):
        return {"ok": True, "message": "annotate stub — hook VLM / keyframe models here", "echo": payload}

    return {"ok": False, "error": f"unhandled service {service}", "payload": payload}


class Handler(BaseHTTPRequestHandler):
    def _json(self, code: int, body: dict[str, Any]) -> None:
        raw = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self) -> None:  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        data = json.loads(self.rfile.read(length) or b"{}")
        service = data.get("service", self.path)
        payload = data.get("args") or data.get("payload") or {}
        self._json(200, handle_service(service, payload))

    def log_message(self, fmt: str, *args: Any) -> None:
        log.info("%s - %s", self.address_string(), fmt % args)


def main() -> None:
    mode = os.environ.get("LEROBOT_BRIDGE_MODE", "http")
    if mode == "ros2":
        log.error("ROS2 mode scaffold: wire rclpy services to handle_service(); falling back to HTTP.")
    port = int(os.environ.get("LEROBOT_BRIDGE_PORT", "8091"))
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    log.info("HTTP bridge on http://127.0.0.1:%s  (repo=%s)", port, REPO_ROOT)
    log.info("POST JSON: {\"service\":\"/lerobot/train/start\", \"args\":{...}}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log.info("bye")
        sys.exit(0)


if __name__ == "__main__":
    main()
