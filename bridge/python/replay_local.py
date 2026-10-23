#!/usr/bin/env python
"""Replay one recorded episode on the follower and stream joint frames for the UI."""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path


JOINTS = [
    "shoulder_pan",
    "shoulder_lift",
    "elbow_flex",
    "wrist_flex",
    "wrist_roll",
    "gripper",
]


def _load_frames(root: Path, episode: int) -> tuple[list[dict], float]:
    import pyarrow.parquet as pq

    info = json.loads((root / "meta" / "info.json").read_text(encoding="utf-8"))
    fps = float(info.get("fps") or 30)
    action_names = info["features"]["action"]["names"]
    state_names = info["features"]["observation.state"]["names"]
    files = sorted((root / "data").rglob("*.parquet"))
    if not files:
        raise RuntimeError("这个采集没有数据文件（当时停止过快，回合没写完）。")
    frames: list[dict] = []
    for path in files:
        table = pq.read_table(
            path, columns=["action", "observation.state", "episode_index", "timestamp"]
        )
        data = table.to_pydict()
        for action, state, ep, ts in zip(
            data["action"], data["observation.state"], data["episode_index"], data["timestamp"], strict=False
        ):
            ep_i = int(ep[0] if isinstance(ep, list) else ep)
            if ep_i != episode:
                continue
            frames.append(
                {
                    "t": float(ts[0] if isinstance(ts, list) else ts),
                    "leader": _vec(action, action_names),
                    "follower": _vec(state, state_names),
                }
            )
    frames.sort(key=lambda f: f["t"])
    if not frames:
        raise RuntimeError(f"没有找到第 {episode + 1} 回合的数据。")
    return frames, fps


def _vec(values, names: list[str]) -> dict:
    out = {}
    seq = list(values)
    for name, value in zip(names, seq, strict=False):
        key = str(name).removesuffix(".pos")
        if key in JOINTS:
            out[key] = round(float(value), 2)
    return out


def _action_dict(joints: dict) -> dict:
    return {f"{name}.pos": joints[name] for name in JOINTS if name in joints}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    parser.add_argument("--episode", type=int, default=0)
    parser.add_argument("--robot-port", required=True)
    parser.add_argument("--robot-id", required=True)
    parser.add_argument("--robot-type", default="so101_follower")
    args = parser.parse_args()

    root = Path(args.root)
    frames, fps = _load_frames(root, args.episode)
    print(f"重放 {len(frames)} 帧，fps={fps:.0f}", flush=True)

    from lerobot.robots.so_follower.config_so_follower import SO100FollowerConfig, SO101FollowerConfig
    from lerobot.robots.so_follower.so_follower import SO100Follower, SO101Follower

    if "so100" in args.robot_type:
        robot = SO100Follower(SO100FollowerConfig(port=args.robot_port, id=args.robot_id, cameras={}))
    else:
        robot = SO101Follower(SO101FollowerConfig(port=args.robot_port, id=args.robot_id, cameras={}))
    try:
        robot.connect(calibrate=False)
        print("从臂已连接，开始重放", flush=True)
        t0 = frames[0]["t"]
        start = time.perf_counter()
        for frame in frames:
            target = start + (frame["t"] - t0)
            delay = target - time.perf_counter()
            if delay > 0:
                time.sleep(delay)
            robot.send_action(_action_dict(frame["leader"]))
            payload = {
                "ok": True,
                "t": round(frame["t"] - t0, 3),
                "leader": frame["leader"],
                "follower": frame["follower"],
            }
            print("WB_FRAME " + json.dumps(payload, ensure_ascii=False), flush=True)
        print("重放结束", flush=True)
    finally:
        try:
            robot.disconnect()
        except Exception:
            pass
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"重放失败: {exc}", flush=True)
        raise SystemExit(1)
