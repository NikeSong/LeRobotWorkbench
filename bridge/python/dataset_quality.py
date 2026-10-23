#!/usr/bin/env python3
"""Score a local LeRobot dataset for the Workbench detail report."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq

JOINTS = ["shoulder_pan", "shoulder_lift", "elbow_flex", "wrist_flex", "wrist_roll", "gripper"]
JOINT_LABELS = ["肩平移", "肩抬升", "肘", "腕屈伸", "腕旋转", "夹爪"]


def _vec(table, name: str) -> np.ndarray:
    return np.asarray(table.column(name).to_pylist(), dtype=float)


def _mean_stat(stats: dict, key: str) -> float | None:
    block = stats.get(key) or {}
    mean = block.get("mean")
    if mean is None:
        return None
    return float(np.mean(np.asarray(mean, dtype=float)))


def evaluate(root: Path) -> dict:
    info = json.loads((root / "meta" / "info.json").read_text())
    stats = {}
    stats_path = root / "meta" / "stats.json"
    if stats_path.is_file():
        stats = json.loads(stats_path.read_text())
    side = {}
    side_path = root / "meta" / "workbench.json"
    if side_path.is_file():
        side = json.loads(side_path.read_text())

    data_files = sorted((root / "data").rglob("*.parquet")) if (root / "data").is_dir() else []
    if not data_files:
        raise FileNotFoundError("没有 data parquet")
    fps = float(info.get("fps") or 30)
    expected = 1.0 / fps if fps else 1 / 30
    rows = 0
    off = 0
    missing_action = False
    chunks = []
    for path in data_files:
        names = set(pq.read_schema(path).names)
        if "action" not in names:
            missing_action = True
        cols = [c for c in ("timestamp", "action", "observation.state") if c in names]
        table = pq.read_table(path, columns=cols or None)
        rows += table.num_rows
        chunks.append(table)
        if "timestamp" in table.column_names and table.num_rows > 1:
            ts = np.asarray(table.column("timestamp").to_pylist(), dtype=float).reshape(-1)
            dt = np.diff(ts)
            off += int((np.abs(dt - expected) > 0.002).sum())
    table = chunks[0]
    n = rows

    action_parts = [_vec(part, "action") for part in chunks if "action" in part.column_names]
    state_parts = [_vec(part, "observation.state") for part in chunks if "observation.state" in part.column_names]
    action = np.concatenate(action_parts, axis=0) if action_parts else None
    state = np.concatenate(state_parts, axis=0) if state_parts else None
    if action is not None and action.ndim == 1:
        action = action.reshape(-1, 1)
    if state is not None and state.ndim == 1:
        state = state.reshape(-1, 1)
    joints = []
    speed_buckets: list[float] = []
    gripper: list[float] = []
    max_track = 0.0
    if action is not None and action.ndim == 2 and action.shape[1] >= 1:
        da = np.linalg.norm(np.diff(action, axis=0), axis=1) if n > 1 else np.array([0.0])
        step = max(1, int(round(fps)))
        buckets = max(1, int(np.ceil(len(da) / step))) if len(da) else 1
        for i in range(min(buckets, 60)):
            sl = da[i * step : (i + 1) * step]
            speed_buckets.append(round(float(sl.mean()) if len(sl) else 0.0, 2))
        gripper_idx = action.shape[1] - 1
        for i in range(0, n, step):
            gripper.append(round(float(action[i, gripper_idx]), 1))
        names = info.get("features", {}).get("action", {}).get("names") or JOINTS
        for i in range(action.shape[1]):
            label = JOINT_LABELS[i] if i < len(JOINT_LABELS) else str(names[i] if i < len(names) else i)
            a = action[:, i]
            row = {
                "joint": label,
                "actionStd": round(float(a.std()), 2),
                "stillFrac": round(float((np.abs(np.diff(a)) < 0.5).mean()), 3) if n > 1 else 1.0,
            }
            if state is not None and state.shape == action.shape:
                s = state[:, i]
                err = np.abs(a - s)
                row["stateRange"] = round(float(s.max() - s.min()), 2)
                row["trackMae"] = round(float(err.mean()), 2)
                row["trackMax"] = round(float(err.max()), 2)
                max_track = max(max_track, float(err.max()))
            joints.append(row)

    videos = []
    video_dir = root / "videos"
    if video_dir.is_dir():
        for p in sorted(video_dir.rglob("*.mp4")):
            videos.append(
                {
                    "path": str(p.relative_to(root)).replace("\\", "/"),
                    "bytes": p.stat().st_size,
                }
            )

    episodes = int(info.get("total_episodes") or 0)
    frames = int(info.get("total_frames") or n)
    duration = 0.0
    if "timestamp" in table.column_names and table.num_rows:
        first_ts = np.asarray(table.column("timestamp").to_pylist(), dtype=float).reshape(-1)
        last = chunks[-1]
        last_ts = np.asarray(last.column("timestamp").to_pylist(), dtype=float).reshape(-1) if "timestamp" in last.column_names else first_ts
        if len(first_ts) and len(last_ts):
            duration = float(last_ts.max() - first_ts.min())
    front_mean = _mean_stat(stats, "observation.images.front")
    wrist_mean = _mean_stat(stats, "observation.images.wrist")

    issues = []
    if episodes < 20:
        issues.append(f"只有 {episodes} 个回合，不够训练出能泛化的策略。同一任务建议再录 20–50 个成功回合。")
    if max_track > 30:
        issues.append(f"主臂指令和从臂实际位置最大差了 {max_track:.0f}°，快速动作时从臂跟得比较慢。")
    if gripper and max(gripper) - min(gripper) > 5 and gripper[-1] < 5 and gripper[0] > 15:
        issues.append("夹爪从张开收到闭合后没有再张开。若任务还要放下物体，这段演示不完整。")
    if off > 0:
        issues.append(f"有 {off} 个帧间隔偏离 1/fps 超过 2 ms。")
    if not videos:
        issues.append("没有视频文件，视觉策略无法使用图像。")
    if missing_action or action is None:
        issues.append("没有 action 列，不能当作机器人演示来训练。")
    if rows != frames:
        issues.append(f"info.json 写着 {frames} 帧，parquet 里实际有 {rows} 帧。")

    consistent = off == 0 and videos and action is not None and not missing_action and rows == frames and max_track <= 30
    if episodes >= 20 and consistent:
        verdict = "可以用来训练"
        tone = "ok"
    elif episodes >= 1 and off == 0 and videos:
        verdict = "格式合格，量不够训练"
        tone = "warn"
    else:
        verdict = "先不要拿去训练"
        tone = "bad"

    return {
        "ok": True,
        "displayName": side.get("displayName") or root.name,
        "task": side.get("task") or "",
        "repoId": "",
        "robotType": info.get("robot_type") or "",
        "codebaseVersion": info.get("codebase_version") or "",
        "episodes": episodes,
        "frames": frames,
        "fps": fps,
        "durationS": round(duration, 2),
        "timestampOff": off,
        "maxTrackDeg": round(max_track, 1),
        "frontBrightness": None if front_mean is None else round(front_mean, 2),
        "wristBrightness": None if wrist_mean is None else round(wrist_mean, 2),
        "videos": videos,
        "joints": joints,
        "speedBuckets": speed_buckets,
        "gripper": gripper,
        "verdict": verdict,
        "tone": tone,
        "issues": issues,
        "summary": (
            f"{frames} 帧、{duration:.1f} 秒、{fps:.0f} fps。"
            + ("时间戳稳定。" if off == 0 else f"时间戳有 {off} 处偏差。")
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    args = parser.parse_args()
    print(json.dumps(evaluate(Path(args.root)), ensure_ascii=False))


if __name__ == "__main__":
    main()
