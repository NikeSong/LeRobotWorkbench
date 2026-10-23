#!/usr/bin/env python3
"""List episodes and trim the head or tail of one episode in place."""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

JOINTS = ["肩平移", "肩抬升", "肘", "腕屈伸", "腕旋转", "夹爪"]


def _episodes(root: Path) -> list[tuple[Path, dict]]:
    files = sorted((root / "meta" / "episodes").rglob("*.parquet"))
    rows: list[tuple[Path, dict]] = []
    for path in files:
        table = pq.read_table(path)
        for row in table.to_pylist():
            rows.append((path, row))
    rows.sort(key=lambda item: int(item[1]["episode_index"]))
    return rows


def _data_path(root: Path, info: dict, chunk: int, file_index: int) -> Path:
    return root / info["data_path"].format(chunk_index=chunk, file_index=file_index)


def _video_path(root: Path, info: dict, key: str, chunk: int, file_index: int) -> Path:
    return root / info["video_path"].format(video_key=key, chunk_index=chunk, file_index=file_index)


def _vec_stats(values: np.ndarray) -> dict:
    array = np.asarray(values, dtype=np.float64)
    if array.ndim == 1:
        array = array[:, None]
    flat = {
        "min": np.min(array, axis=0).tolist(),
        "max": np.max(array, axis=0).tolist(),
        "mean": np.mean(array, axis=0).tolist(),
        "std": np.std(array, axis=0).tolist(),
        "count": [int(array.shape[0])],
    }
    for name, q in (("q01", 0.01), ("q10", 0.1), ("q50", 0.5), ("q90", 0.9), ("q99", 0.99)):
        flat[name] = np.quantile(array, q, axis=0).tolist()
    return flat


def list_episodes(root: Path) -> dict:
    info = json.loads((root / "meta" / "info.json").read_text())
    fps = float(info["fps"])
    episodes = []
    for _path, row in _episodes(root):
        length = int(row["length"])
        episodes.append(
            {
                "index": int(row["episode_index"]),
                "frames": length,
                "seconds": round(length / fps, 2),
            }
        )
    return {"ok": True, "episodes": episodes, "fps": fps}


def episode_detail(root: Path, episode: int) -> dict:
    info = json.loads((root / "meta" / "info.json").read_text())
    fps = float(info["fps"])
    rows = _episodes(root)
    match = next((row for _path, row in rows if int(row["episode_index"]) == episode), None)
    if match is None:
        raise FileNotFoundError(f"没有第 {episode} 条")
    data_file = _data_path(root, info, int(match["data/chunk_index"]), int(match["data/file_index"]))
    table = pq.read_table(data_file)
    mask = np.asarray(table.column("episode_index").to_pylist()) == episode
    table = table.filter(pa.array(mask))
    action = np.asarray(table.column("action").to_pylist(), dtype=float)
    state = np.asarray(table.column("observation.state").to_pylist(), dtype=float)
    names = info.get("features", {}).get("action", {}).get("names") or JOINTS
    labels = [JOINTS[i] if i < len(JOINTS) else str(names[i]) for i in range(action.shape[1])]
    videos = []
    for key, role in (
        ("observation.images.front", "主摄像头"),
        ("observation.images.wrist", "腕部"),
    ):
        rel = info["video_path"].format(
            video_key=key,
            chunk_index=int(match[f"videos/{key}/chunk_index"]),
            file_index=int(match[f"videos/{key}/file_index"]),
        )
        videos.append(
            {
                "role": role,
                "file": rel,
                "from": float(match[f"videos/{key}/from_timestamp"]),
                "to": float(match[f"videos/{key}/to_timestamp"]),
            }
        )
    return {
        "ok": True,
        "index": episode,
        "fps": fps,
        "frames": int(table.num_rows),
        "labels": labels,
        "action": np.round(action, 2).tolist(),
        "state": np.round(state, 2).tolist(),
        "videos": videos,
    }


def _cut_video(src: Path, dst: Path, start: int, end: int, fps: float) -> int:
    from fractions import Fraction

    import av

    container = av.open(str(src))
    stream_in = container.streams.video[0]
    output = av.open(str(dst), mode="w")
    rate = int(round(fps))
    stream_out = output.add_stream("libx264", rate=rate, options={"preset": "veryfast", "crf": "23", "g": "2"})
    stream_out.width = stream_in.codec_context.width
    stream_out.height = stream_in.codec_context.height
    stream_out.pix_fmt = "yuv420p"
    stream_out.time_base = Fraction(1, rate)
    kept = 0
    index = 0
    for frame in container.decode(stream_in):
        if index >= end:
            break
        if index >= start:
            reformatted = frame.reformat(format="yuv420p")
            reformatted.pts = kept
            reformatted.time_base = Fraction(1, rate)
            for packet in stream_out.encode(reformatted):
                output.mux(packet)
            kept += 1
        index += 1
    for packet in stream_out.encode(None):
        output.mux(packet)
    output.close()
    container.close()
    return kept


def _write_episode_row(path: Path, row: dict) -> None:
    table = pq.read_table(path)
    updated = pa.Table.from_pylist([row], schema=table.schema)
    pq.write_table(updated, path)


def trim_episode(root: Path, episode: int, start: int, end: int) -> dict:
    info_path = root / "meta" / "info.json"
    info = json.loads(info_path.read_text())
    fps = float(info["fps"])
    episode_rows = _episodes(root)
    if episode < 0 or episode >= len(episode_rows):
        raise IndexError(f"没有第 {episode} 条")
    _path, row = episode_rows[episode]
    length = int(row["length"])
    if not (0 <= start < end <= length):
        raise ValueError(f"剪切范围无效：保留 {start} 到 {end}，本条共 {length} 帧")
    if start == 0 and end == length:
        return {"ok": True, "message": "没有需要剪掉的帧", "frames": length}

    data_files: dict[tuple[int, int], pa.Table] = {}
    for _ep_path, ep_row in episode_rows:
        key = (int(ep_row["data/chunk_index"]), int(ep_row["data/file_index"]))
        if key not in data_files:
            data_files[key] = pq.read_table(_data_path(root, info, key[0], key[1]))

    target_key = (int(row["data/chunk_index"]), int(row["data/file_index"]))
    target = data_files[target_key]
    mask = np.asarray(target.column("episode_index").to_pylist()) == episode
    indexes = np.flatnonzero(mask)
    if len(indexes) != length:
        raise RuntimeError(f"数据帧数 {len(indexes)} 和元数据 {length} 不一致")
    keep = indexes[start:end]
    drop = np.ones(target.num_rows, dtype=bool)
    drop[indexes] = False
    drop[keep] = True
    data_files[target_key] = target.filter(pa.array(drop))

    video_keys = [
        key
        for key, feature in info["features"].items()
        if isinstance(feature, dict) and feature.get("dtype") == "video"
    ]
    kept_counts = []
    replacements: list[tuple[Path, Path]] = []
    for key in video_keys:
        src = _video_path(
            root,
            info,
            key,
            int(row[f"videos/{key}/chunk_index"]),
            int(row[f"videos/{key}/file_index"]),
        )
        tmp = src.with_suffix(".trim.mp4")
        kept_counts.append(_cut_video(src, tmp, start, end, fps))
        replacements.append((tmp, src))
    new_length = min([end - start, *kept_counts]) if kept_counts else end - start
    if new_length < end - start:
        extra = (end - start) - new_length
        filtered = data_files[target_key]
        ep_mask = np.asarray(filtered.column("episode_index").to_pylist()) == episode
        ep_indexes = np.flatnonzero(ep_mask)
        keep_mask = np.ones(filtered.num_rows, dtype=bool)
        keep_mask[ep_indexes[-extra:]] = False
        data_files[target_key] = filtered.filter(pa.array(keep_mask))

    cursor = 0
    numeric_parts: dict[str, list[np.ndarray]] = {
        "action": [],
        "observation.state": [],
        "timestamp": [],
        "frame_index": [],
        "index": [],
        "episode_index": [],
        "task_index": [],
    }
    for ep_path, ep_row in episode_rows:
        key = (int(ep_row["data/chunk_index"]), int(ep_row["data/file_index"]))
        table = data_files[key]
        ep_index = int(ep_row["episode_index"])
        mask = np.asarray(table.column("episode_index").to_pylist()) == ep_index
        count = int(mask.sum())
        positions = np.flatnonzero(mask)
        frame_index = pa.array(np.arange(count), type=pa.int64())
        timestamp = pa.array((np.arange(count) / fps).astype(np.float32), type=pa.float32())
        global_index = pa.array(cursor + np.arange(count), type=pa.int64())
        table = table.set_column(table.schema.get_field_index("frame_index"), "frame_index", _scatter(table, "frame_index", positions, frame_index))
        table = table.set_column(table.schema.get_field_index("timestamp"), "timestamp", _scatter(table, "timestamp", positions, timestamp))
        table = table.set_column(table.schema.get_field_index("index"), "index", _scatter(table, "index", positions, global_index))
        data_files[key] = table
        ep_table = table.filter(pa.array(mask))
        ep_row["length"] = count
        ep_row["dataset_from_index"] = cursor
        ep_row["dataset_to_index"] = cursor + count
        for video_key in video_keys:
            if ep_index == episode:
                ep_row[f"videos/{video_key}/from_timestamp"] = 0.0
                ep_row[f"videos/{video_key}/to_timestamp"] = count / fps
        _fill_episode_stats(ep_row, ep_table)
        _write_episode_row(ep_path, ep_row)
        for name in numeric_parts:
            numeric_parts[name].append(np.asarray(ep_table.column(name).to_pylist(), dtype=float))
        cursor += count

    for key, table in data_files.items():
        dest = _data_path(root, info, key[0], key[1])
        pq.write_table(table, dest)

    for tmp, src in replacements:
        shutil.move(tmp, src)

    info["total_frames"] = cursor
    for key in video_keys:
        feature = info["features"].get(key)
        if isinstance(feature, dict) and isinstance(feature.get("info"), dict):
            feature["info"]["video.codec"] = "h264"
    info_path.write_text(json.dumps(info, indent=4))
    _write_global_stats(root, numeric_parts)
    return {
        "ok": True,
        "episode": episode,
        "frames": new_length,
        "totalFrames": cursor,
        "message": f"第 {episode + 1} 条已保留 {new_length} 帧，并写回原文件",
    }


def _scatter(table: pa.Table, name: str, positions: np.ndarray, values: pa.Array) -> pa.Array:
    current = table.column(name)
    python = current.to_pylist()
    incoming = values.to_pylist()
    for offset, position in enumerate(positions):
        python[int(position)] = incoming[offset]
    return pa.array(python, type=current.type)


def _fill_episode_stats(row: dict, table: pa.Table) -> None:
    mapping = {
        "action": "stats/action",
        "observation.state": "stats/observation.state",
        "timestamp": "stats/timestamp",
        "frame_index": "stats/frame_index",
        "index": "stats/index",
        "episode_index": "stats/episode_index",
        "task_index": "stats/task_index",
    }
    for column, prefix in mapping.items():
        stats = _vec_stats(np.asarray(table.column(column).to_pylist(), dtype=float))
        for stat_name, value in stats.items():
            key = f"{prefix}/{stat_name}"
            if key in row:
                row[key] = [float(item) for item in value]


def _write_global_stats(root: Path, parts: dict[str, list[np.ndarray]]) -> None:
    path = root / "meta" / "stats.json"
    stats = json.loads(path.read_text()) if path.is_file() else {}
    for name, chunks in parts.items():
        values = np.concatenate(chunks, axis=0)
        stats[name] = _vec_stats(values)
    path.write_text(json.dumps(stats))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    parser.add_argument("--command", choices=["list", "episode", "trim"], required=True)
    parser.add_argument("--episode", type=int, default=0)
    parser.add_argument("--start", type=int, default=0)
    parser.add_argument("--end", type=int, default=0)
    args = parser.parse_args()
    root = Path(args.root)
    if args.command == "list":
        payload = list_episodes(root)
    elif args.command == "episode":
        payload = episode_detail(root, args.episode)
    else:
        payload = trim_episode(root, args.episode, args.start, args.end)
    print(json.dumps(payload, ensure_ascii=False))


if __name__ == "__main__":
    main()
