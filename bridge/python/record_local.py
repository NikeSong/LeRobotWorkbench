#!/usr/bin/env python
"""Local SO-100/101 recording without lerobot-record's draccus CLI (Python 3.14 unsafe).

Workbench-specific hardening:
- Preflight motor scan with clear Chinese errors (power / cabling)
- Connect follower first, then leader (same as teleoperate_local)
- Soft disconnect so Torque_Enable failures never mask the real error
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
import time

# macOS 上 torch 在导入时会打一条无意义的 WARNING，录制并不依赖它。
logging.getLogger("torch.distributed").setLevel(logging.ERROR)


def _probe_capture(index: int) -> tuple[int, int]:
    """Actual frame size from OpenCV. macOS often reports 480p while delivering 640x360."""
    import cv2

    backend = getattr(cv2, "CAP_AVFOUNDATION", cv2.CAP_ANY)
    cap = cv2.VideoCapture(int(index), backend)
    if not cap.isOpened():
        cap.release()
        cap = cv2.VideoCapture(int(index))
    frame = None
    try:
        for _ in range(8):
            ok, frame = cap.read()
            if ok and frame is not None and getattr(frame, "size", 0):
                break
            time.sleep(0.05)
    finally:
        cap.release()
    if frame is None or getattr(frame, "size", 0) == 0:
        raise RuntimeError(f"摄像头序号 {index} 读不到画面，已停止。不会用猜测的 640x480 去录。")
    height, width = frame.shape[:2]
    return int(width), int(height)


def _cameras(raw: str | None, fps: int) -> dict:
    from lerobot.cameras.opencv import OpenCVCameraConfig

    sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parent))
    from camera_identity import CameraIdentityError, resolve_index

    if not raw:
        return {}
    data = json.loads(raw)
    out = {}
    seen_indexes: dict[int, str] = {}
    for name, cfg in data.items():
        stable_id = str(cfg.get("stableId") or cfg.get("stable_id") or "").strip()
        try:
            index = resolve_index(stable_id)
        except CameraIdentityError as exc:
            raise RuntimeError(str(exc)) from exc
        if index in seen_indexes:
            raise RuntimeError(
                f"摄像头 {name} 和 {seen_indexes[index]} 解析到同一个设备（序号 {index}）。不会把同一路画面录成两路。"
            )
        seen_indexes[index] = name
        width, height = _probe_capture(index)
        print(
            f"摄像头 {name} 硬件编号 {stable_id} 当前序号 {index} 实际分辨率 {width}x{height}",
            flush=True,
        )
        out[name] = OpenCVCameraConfig(
            index_or_path=index,
            width=width,
            height=height,
            fps=int(fps),
            # Leave fourcc unset. Forcing MJPG fails on these Mac cameras and
            # the fallback frames no longer match a hardcoded 640x480.
            fourcc=None,
            warmup_s=0,
        )
    return out


def _harden_bus(bus) -> None:
    original = bus.write

    def write(*args, **kwargs):
        kwargs["num_retry"] = max(int(kwargs.get("num_retry", 0) or 0), 8)
        return original(*args, **kwargs)

    bus.write = write
    if hasattr(bus, "set_timeout"):
        try:
            bus.set_timeout(50)
        except Exception:
            pass


def _soft_disconnect_bus(bus) -> None:
    try:
        if getattr(bus, "is_connected", False):
            bus.disconnect(disable_torque=False)
            return
    except Exception:
        pass
    try:
        bus.port_handler.closePort()
    except Exception:
        pass


def _soft_disconnect_device(device, label: str) -> None:
    try:
        bus = getattr(device, "bus", None)
        if bus is not None:
            _soft_disconnect_bus(bus)
        for cam in getattr(device, "cameras", {}).values():
            try:
                if getattr(cam, "is_connected", False):
                    cam.disconnect()
            except Exception:
                pass
    except Exception as exc:
        print(f"{label} 断开时忽略: {exc}", flush=True)


def _assert_arm_alive(port: str, label: str) -> None:
    """Fail fast with a readable message before dataset/camera setup.

    Uses scservo_sdk directly so we do not wait on a full LeRobot import.
    """
    try:
        from scservo_sdk import PacketHandler, PortHandler
    except ImportError as exc:
        raise RuntimeError(
            "缺少 feetech-servo-sdk。请执行: uv sync --extra workbench"
        ) from exc

    ph = PortHandler(port)
    try:
        if not ph.openPort():
            raise RuntimeError(f"openPort failed on {port}")
        if not ph.setBaudRate(1_000_000):
            raise RuntimeError(f"setBaudRate failed on {port}")
    except Exception as exc:
        try:
            ph.closePort()
        except Exception:
            pass
        raise RuntimeError(
            f"{label}（{port}）无法打开串口: {exc}\n"
            "请检查 USB 是否插上、是否被其它程序占用（先停遥操作/标定）。"
        ) from exc

    pkt = PacketHandler(0)
    found: dict[int, int] = {}
    try:
        for _ in range(3):
            found.clear()
            for mid in range(1, 7):
                model, result, _error = pkt.ping(ph, mid)
                if result == 0:
                    found[mid] = model
            if len(found) >= 6:
                break
            time.sleep(0.25)
    finally:
        try:
            ph.closePort()
        except Exception:
            pass

    if not found:
        raise RuntimeError(
            f"{label}（{port}）串口能打开，但扫不到任何舵机。\n"
            "请检查：① 机械臂电源适配器是否通电并亮灯 ② USB/总线线是否插紧 "
            "③ 主臂与从臂是否接反。\n"
            "供电恢复后，建议先在「遥操作」页点开始，确认两边都显示已连接，再录制。"
        )
    missing = sorted(set(range(1, 7)) - set(found))
    if missing:
        raise RuntimeError(
            f"{label}（{port}）只找到舵机 ID {sorted(found.keys())}，缺少 {missing}。请检查舵机线。"
        )
    print(f"{label} 预检通过，舵机 ID: {sorted(found.keys())}", flush=True)


def _connect(device, label: str, attempts: int = 3) -> None:
    _harden_bus(device.bus)
    last: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            device.connect(calibrate=False)
            if not device.is_calibrated:
                raise RuntimeError(
                    f"{label} 电机里的标定和文件不一致，已停止，避免把错误关节写进数据。"
                    "请重新标定这一条臂。"
                )
            print(f"{label} 已连接", flush=True)
            return
        except Exception as exc:
            last = exc
            print(f"{label} 第 {attempt}/{attempts} 次连接失败: {exc}", flush=True)
            _soft_disconnect_device(device, label)
            time.sleep(0.5)
    raise RuntimeError(f"{label} 连接失败。请检查供电和舵机线是否插紧。") from last


def _friendly_error(exc: BaseException) -> str:
    text = str(exc)
    if (
        "扫不到任何舵机" in text
        or "只找到舵机" in text
        or "无法打开串口" in text
        or "硬件编号" in text
        or "重新选择" in text
    ):
        return text
    if "Missing motor IDs" in text or "motor check failed" in text:
        return (
            "录制失败：舵机握手失败（串口上找不到电机）。\n"
            "请确认两条臂都已接通电源适配器，USB 插紧，然后先试「遥操作」。\n"
            f"原始错误: {text.splitlines()[0]}"
        )
    if "Torque_Enable" in text and "no status packet" in text:
        return (
            "录制失败：写 Torque_Enable 无应答（常见于舵机未供电或已断开）。\n"
            "请给机械臂接上电源后再试。"
        )
    if "multiple access on port" in text or "returned no data" in text:
        return (
            "录制中断：串口被占用或设备断开（multiple access / no data）。\n"
            "请先点「停止」关掉「实时关节显示」和「遥操作」，等 1 秒再点「开始录制」。\n"
            "不要同时开关节预览与录制。"
        )
    if "has not captured any frames yet" in text or "没有画面" in text:
        return (
            "录制失败：摄像头已打开但还没有画面。\n"
            "请关掉占用相机的程序（含 iPhone Continuity）后再试。\n"
            f"原始错误: {text.splitlines()[0]}"
        )
    return f"录制失败: {exc}"


def _watch_control(path: str, events: dict, progress: dict) -> None:
    """UI appends one command per line. Reading never truncates the file, so stop cannot erase next."""
    control = __import__("pathlib").Path(path)
    offset = 0
    while not progress["done"]:
        try:
            if control.is_file():
                data = control.read_bytes()
                if len(data) < offset:
                    offset = 0
                chunk = data[offset:].decode("utf-8", "replace")
                offset = len(data)
                for line in chunk.splitlines():
                    cmd = line.strip().lower()
                    if cmd == "stop":
                        events["exit_early"] = True
                        events["stop_recording"] = True
                        print("收到界面指令：停止并保存当前回合", flush=True)
                    elif cmd == "next":
                        events["exit_early"] = True
                        print("收到界面指令：结束本回合并保存", flush=True)
        except Exception:
            pass
        elapsed = time.perf_counter() - progress["ep_started"]
        print(
            "WB_PROGRESS "
            f"saved={progress['saved']} total={progress['total']} "
            f"episode_elapsed={elapsed:.2f} episode_limit={progress['limit']:.2f}",
            flush=True,
        )
        time.sleep(0.4)


def _wait_cameras_ready(robot, timeout_s: float = 8.0) -> None:
    """Block until each camera's read thread has stored a frame.

    ``warmup_s=0`` returns as soon as the device opens. The first 1080p frame
    from a second camera often arrives a moment later, and ``read_latest``
    then raises "has not captured any frames yet".
    """
    cameras = getattr(robot, "cameras", None) or {}
    if not cameras:
        return
    pending = set(cameras)
    deadline = time.perf_counter() + timeout_s
    while pending and time.perf_counter() < deadline:
        for name in list(pending):
            try:
                cameras[name].read_latest(max_age_ms=10_000)
            except Exception:
                continue
            pending.discard(name)
            print(f"摄像头 {name} 已出图", flush=True)
        if pending:
            time.sleep(0.05)
    if pending:
        names = "、".join(sorted(pending))
        raise RuntimeError(
            f"摄像头已打开，但 {timeout_s:.0f} 秒内没有画面：{names}。\n"
            "请关掉正在占用相机的程序（含 iPhone Continuity），再点开始录制。"
        )


def _start_record_preview(robot, preview_dir: str | None):
    """Write the exact frames the recorder is about to store, for the UI."""
    if not preview_dir:
        return None
    import threading
    from pathlib import Path

    import cv2

    out = Path(preview_dir)
    out.mkdir(parents=True, exist_ok=True)
    stop = threading.Event()

    def loop() -> None:
        while not stop.is_set():
            cameras = getattr(robot, "cameras", None) or {}
            for name, cam in cameras.items():
                try:
                    frame = cam.async_read(timeout_ms=80)
                except Exception:
                    continue
                if frame is None or getattr(frame, "ndim", 0) != 3 or frame.shape[2] != 3:
                    continue
                bgr = frame[:, :, ::-1]
                ok, buf = cv2.imencode(".jpg", bgr, [int(cv2.IMWRITE_JPEG_QUALITY), 80])
                if not ok:
                    continue
                tmp = out / f"{name}.jpg.tmp"
                tmp.write_bytes(buf.tobytes())
                tmp.replace(out / f"{name}.jpg")
            stop.wait(0.03)

    threading.Thread(target=loop, name="record-preview", daemon=True).start()
    return stop


def _run_record(cfg, control_path: str | None = None, preview_dir: str | None = None) -> None:
    """Same flow as lerobot_record.record, with Workbench connect/disconnect hardening."""
    from lerobot.datasets import (
        LeRobotDataset,
        VideoEncodingManager,
        aggregate_pipeline_dataset_features,
        create_initial_features,
    )
    from lerobot.processor import make_default_processors
    from lerobot.robots import make_robot_from_config
    from lerobot.scripts.lerobot_record import record_loop
    from lerobot.teleoperators import make_teleoperator_from_config
    from lerobot.utils.cycle_timer import CycleTimer
    from lerobot.utils.feature_utils import combine_feature_dicts
    from lerobot.utils.utils import init_logging, log_say

    # Prefer quieter third-party noise in the Workbench log panel.
    logging.getLogger("torchcodec").setLevel(logging.ERROR)
    init_logging()

    robot = make_robot_from_config(cfg.robot)
    teleop = make_teleoperator_from_config(cfg.teleop) if cfg.teleop is not None else None
    teleop_action_processor, robot_action_processor, robot_observation_processor = make_default_processors()

    dataset_features = combine_feature_dicts(
        aggregate_pipeline_dataset_features(
            pipeline=teleop_action_processor,
            initial_features=create_initial_features(action=robot.action_features),
            use_videos=cfg.dataset.video,
        ),
        aggregate_pipeline_dataset_features(
            pipeline=robot_observation_processor,
            initial_features=create_initial_features(observation=robot.observation_features),
            use_videos=cfg.dataset.video,
        ),
    )

    dataset = None
    listener = None
    timer = CycleTimer(cfg.dataset.fps)
    progress = {
        "saved": 0,
        "total": cfg.dataset.num_episodes,
        "ep_started": time.perf_counter(),
        "limit": float(cfg.dataset.episode_time_s),
        "done": False,
    }

    try:
        from pathlib import Path

        from lerobot.utils.constants import HF_LEROBOT_HOME

        cfg.dataset.no_stamp = True
        root = (
            Path(cfg.dataset.root)
            if cfg.dataset.root
            else HF_LEROBOT_HOME / cfg.dataset.repo_id
        )
        print(f"WB_REPO {cfg.dataset.repo_id}", flush=True)
        num_cameras = len(robot.cameras)  # type: ignore[attr-defined]
        writer_kwargs = dict(
            image_writer_processes=cfg.dataset.num_image_writer_processes,
            image_writer_threads=cfg.dataset.num_image_writer_threads_per_camera * num_cameras,
            batch_encoding_size=cfg.dataset.video_encoding_batch_size,
            rgb_encoder=cfg.dataset.rgb_encoder,
            depth_encoder=cfg.dataset.depth_encoder,
            encoder_threads=cfg.dataset.encoder_threads,
            streaming_encoding=cfg.dataset.streaming_encoding,
            encoder_queue_maxsize=cfg.dataset.encoder_queue_maxsize,
        )
        if (root / "meta" / "info.json").is_file():
            print(f"继续写入已有任务文件夹 {root}", flush=True)
            dataset = LeRobotDataset.resume(
                cfg.dataset.repo_id,
                root=root,
                video_backend="pyav",
                **writer_kwargs,
            )
            if dataset.meta.robot_type != robot.robot_type or int(dataset.fps) != int(cfg.dataset.fps):
                raise RuntimeError(
                    "已有数据集和这次录制不一致："
                    f"文件夹是 {dataset.meta.robot_type} @ {dataset.fps} fps，"
                    f"这次是 {robot.robot_type} @ {cfg.dataset.fps} fps。"
                    "请换一个任务描述，不要把两套数据写进同一个文件夹。"
                )
            existing = set(dataset.features)
            incoming = set(dataset_features)
            if existing != incoming and not incoming.issubset(existing):
                raise RuntimeError(
                    "已有数据集的特征和这次相机/关节不一致。请换一个任务描述，避免把画面或动作拼错。"
                )
        else:
            print(f"新建任务文件夹 {root}", flush=True)
            dataset = LeRobotDataset.create(
                cfg.dataset.repo_id,
                cfg.dataset.fps,
                root=root,
                robot_type=robot.name,
                features=dataset_features,
                use_videos=cfg.dataset.video,
                video_backend="pyav",
                **writer_kwargs,
            )

        # Follower first (cameras + torque), then leader — matches teleoperate_local.
        print("连接从臂…", flush=True)
        _connect(robot, "从臂")
        if teleop is not None:
            print("连接主臂…", flush=True)
            _connect(teleop, "主臂")

        print("等待摄像头出图…", flush=True)
        _wait_cameras_ready(robot)
        preview_stop = _start_record_preview(robot, preview_dir)
        listener = None
        events = {"exit_early": False, "rerecord_episode": False, "stop_recording": False}
        watcher = None
        if control_path:
            import threading

            watcher = threading.Thread(
                target=_watch_control, args=(control_path, events, progress), daemon=True
            )
            watcher.start()
        print(
            "录制已就绪。推动主臂；点「保存本回合」写入这一轮，点「停止并保存」结束。"
            "每回合默认最长 "
            f"{cfg.dataset.episode_time_s:.0f} 秒，到时也会自动保存。",
            flush=True,
        )

        with VideoEncodingManager(dataset):
            recorded_episodes = 0
            while recorded_episodes < cfg.dataset.num_episodes and not events["stop_recording"]:
                episode_index = dataset.num_episodes
                log_say(f"Recording episode {episode_index}", cfg.play_sounds)
                progress["ep_started"] = time.perf_counter()
                progress["limit"] = float(cfg.dataset.episode_time_s)
                print(f"开始回合 {episode_index + 1}/{cfg.dataset.num_episodes}", flush=True)
                record_loop(
                    robot=robot,
                    events=events,
                    fps=cfg.dataset.fps,
                    teleop_action_processor=teleop_action_processor,
                    robot_action_processor=robot_action_processor,
                    robot_observation_processor=robot_observation_processor,
                    teleop=teleop,
                    dataset=dataset,
                    control_time_s=cfg.dataset.episode_time_s,
                    single_task=cfg.dataset.single_task,
                    display_data=False,
                    timer=timer,
                )

                if not events["stop_recording"] and (
                    (recorded_episodes < cfg.dataset.num_episodes - 1) or events["rerecord_episode"]
                ):
                    log_say("Reset the environment", cfg.play_sounds)
                    print("复位环境中…", flush=True)
                    record_loop(
                        robot=robot,
                        events=events,
                        fps=cfg.dataset.fps,
                        teleop_action_processor=teleop_action_processor,
                        robot_action_processor=robot_action_processor,
                        robot_observation_processor=robot_observation_processor,
                        teleop=teleop,
                        control_time_s=cfg.dataset.reset_time_s,
                        single_task=cfg.dataset.single_task,
                        display_data=False,
                    )

                if events["rerecord_episode"]:
                    events["rerecord_episode"] = False
                    events["exit_early"] = False
                    dataset.clear_episode_buffer()
                    timer.log_episode_summary("discarded episode")
                    timer.restart()
                    continue

                if dataset.has_pending_frames():
                    dataset.save_episode()
                else:
                    print("本回合没有帧，不写入数据集", flush=True)
                    dataset.clear_episode_buffer()
                    events["exit_early"] = False
                    if events["stop_recording"]:
                        break
                    continue
                recorded_episodes += 1
                progress["saved"] = recorded_episodes
                timer.log_episode_summary(f"episode {episode_index}")
                timer.restart()
                print(f"已保存回合 {recorded_episodes}", flush=True)
                print(
                    f"WB_SAVED episodes={recorded_episodes} repo={cfg.dataset.repo_id} episode={episode_index}",
                    flush=True,
                )
    finally:
        if "preview_stop" in locals() and preview_stop is not None:
            preview_stop.set()
        progress["done"] = True
        timer.log_run_summary()
        log_say("Stop recording", cfg.play_sounds, blocking=False)
        if dataset is not None:
            try:
                dataset.finalize()
            except Exception as exc:
                print(f"数据集收尾忽略: {exc}", flush=True)
        if teleop is not None:
            _soft_disconnect_device(teleop, "主臂")
        _soft_disconnect_device(robot, "从臂")
        if listener is not None:
            try:
                listener.stop()
            except Exception:
                pass


def main(argv: list[str] | None = None) -> int:
    print("record_local: 解析参数…", flush=True)
    parser = argparse.ArgumentParser(description="Local LeRobot dataset recording")
    parser.add_argument("--robot-type", default="so101_follower")
    parser.add_argument("--robot-port", required=True)
    parser.add_argument("--robot-id", required=True)
    parser.add_argument("--teleop-type", default="so101_leader")
    parser.add_argument("--teleop-port", required=True)
    parser.add_argument("--teleop-id", required=True)
    parser.add_argument("--repo-id", required=True)
    parser.add_argument("--root", default="")
    parser.add_argument("--single-task", required=True)
    parser.add_argument("--num-episodes", type=int, default=10)
    parser.add_argument("--fps", type=int, default=30)
    parser.add_argument("--episode-time-s", type=float, default=60)
    parser.add_argument("--reset-time-s", type=float, default=30)
    parser.add_argument("--cameras", default="")
    parser.add_argument("--control-file", default="")
    parser.add_argument("--preview-dir", default="")
    parser.add_argument("--push-to-hub", action="store_true")
    args = parser.parse_args(argv)

    try:
        print("record_local: 预检舵机…", flush=True)
        _assert_arm_alive(args.robot_port, "从臂")
        _assert_arm_alive(args.teleop_port, "主臂")
    except Exception as exc:
        print(_friendly_error(exc), flush=True)
        return 1

    try:
        cams = _cameras(args.cameras or None, args.fps)
    except Exception as exc:
        print(_friendly_error(exc), flush=True)
        return 1

    print("record_local: 加载 LeRobot…", flush=True)
    from lerobot.configs.dataset import DatasetRecordConfig
    from lerobot.robots.so_follower.config_so_follower import SO100FollowerConfig, SO101FollowerConfig
    from lerobot.scripts.lerobot_record import RecordConfig
    from lerobot.teleoperators.so_leader.config_so_leader import SO100LeaderConfig, SO101LeaderConfig
    from lerobot.utils.import_utils import register_third_party_plugins
    if "so100" in args.robot_type:
        robot_cfg = SO100FollowerConfig(port=args.robot_port, id=args.robot_id, cameras=cams)
    else:
        robot_cfg = SO101FollowerConfig(port=args.robot_port, id=args.robot_id, cameras=cams)
    if "so100" in args.teleop_type:
        teleop_cfg = SO100LeaderConfig(port=args.teleop_port, id=args.teleop_id)
    else:
        teleop_cfg = SO101LeaderConfig(port=args.teleop_port, id=args.teleop_id)

    dataset_cfg = DatasetRecordConfig(
        repo_id=args.repo_id,
        single_task=args.single_task,
        num_episodes=args.num_episodes,
        fps=args.fps,
        episode_time_s=args.episode_time_s,
        reset_time_s=args.reset_time_s,
        push_to_hub=bool(args.push_to_hub),
        video=True,
        streaming_encoding=True,
        encoder_threads=2,
        root=args.root or None,
    )
    cfg = RecordConfig(
        robot=robot_cfg,
        teleop=teleop_cfg,
        dataset=dataset_cfg,
        display_data=False,
        play_sounds=False,
    )

    print(
        f"开始录制 repo={args.repo_id} episodes={args.num_episodes} "
        f"robot={args.robot_port} teleop={args.teleop_port} cams={list(cams)}",
        flush=True,
    )
    register_third_party_plugins()
    try:
        _run_record(cfg, control_path=args.control_file or None, preview_dir=args.preview_dir or None)
    except Exception as exc:
        print(_friendly_error(exc), flush=True)
        return 1
    print("录制结束", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
