#!/usr/bin/env python
"""Guided SO-100/101 calibration with live joint Min/Current/Max events.

Mirrors RynnRCP configure_so101_web calibration runner:
- emits structured JSON lines for the Workbench UI
- waits for blank Enter (or 'c') on stdin between phases
- streams Present_Position while the user moves the arm
"""

from __future__ import annotations

import argparse
import json
import os
import queue
import shutil
import sys
import threading
import time
from typing import Any

EVENT_PREFIX = "WB_CALIB_JSON "
POLL_INTERVAL_S = 0.12

_LINES: queue.Queue[str] = queue.Queue()
_STDIN_STARTED = False
_MIN_SPAN = 200


def emit(event: str, **payload: Any) -> None:
    print(EVENT_PREFIX + json.dumps({"event": event, **payload}, ensure_ascii=False), flush=True)


def emit_ranges(
    phase: str,
    positions: dict[str, Any],
    mins: dict[str, Any],
    maxes: dict[str, Any],
) -> None:
    ranges = {
        motor: {
            "min": int(mins[motor]),
            "position": int(positions[motor]),
            "max": int(maxes[motor]),
        }
        for motor in positions
    }
    emit("ranges", phase=phase, ranges=ranges)


def _stdin_loop() -> None:
    for line in sys.stdin:
        _LINES.put(line)


def _ensure_stdin() -> None:
    global _STDIN_STARTED
    if not _STDIN_STARTED:
        _STDIN_STARTED = True
        threading.Thread(target=_stdin_loop, daemon=True).start()


def _drain_pending() -> None:
    while True:
        try:
            _LINES.get_nowait()
        except queue.Empty:
            return


def _poll_line() -> str | None:
    try:
        return _LINES.get_nowait()
    except queue.Empty:
        return None


def wait_enter(accept_c: bool = False) -> str:
    """Block until a line arrives on stdin. Returns stripped line."""
    del accept_c
    _ensure_stdin()
    while True:
        line = _poll_line()
        if line is not None:
            return line.strip()
        time.sleep(0.05)


def wait_enter_with_positions(bus: Any, phase: str) -> str:
    """Poll Present_Position and emit ranges until Enter (or 'c')."""
    _ensure_stdin()
    _drain_pending()

    while True:
        positions = bus.sync_read("Present_Position", normalize=False)
        emit_ranges(phase, positions, positions, positions)
        line = _poll_line()
        if line is not None:
            return line.strip()
        time.sleep(POLL_INTERVAL_S)


def record_ranges_with_progress(
    bus: Any,
    motors: list[str],
    *,
    full_turn_motor: str | None = "wrist_roll",
) -> tuple[dict[str, int], dict[str, int]]:
    """Record min/max for ``motors``. Always emit all bus joints (6 for SO-101).

    ``wrist_roll`` is a continuous-rotation joint: LeRobot fixes its range to 0..4095,
    so it is shown live but not used for min/max discovery.
    """
    start = bus.sync_read("Present_Position", motors, normalize=False)
    mins = {m: int(start[m]) for m in motors}
    maxes = {m: int(start[m]) for m in motors}

    _ensure_stdin()
    _drain_pending()

    all_motors = list(bus.motors)

    while True:
        positions = bus.sync_read("Present_Position", normalize=False)
        for motor in motors:
            p = int(positions[motor])
            mins[motor] = min(p, mins[motor])
            maxes[motor] = max(p, maxes[motor])
        # Include full-turn joint in the live table with fixed encoder span.
        emit_mins = dict(mins)
        emit_maxes = dict(maxes)
        if full_turn_motor and full_turn_motor in positions:
            emit_mins[full_turn_motor] = 0
            emit_maxes[full_turn_motor] = 4095
        emit_pos = {m: int(positions[m]) for m in all_motors if m in positions}
        emit_ranges("recording_range", emit_pos, emit_mins, emit_maxes)
        if _poll_line() is not None:
            break
        time.sleep(POLL_INTERVAL_S)

    short = [m for m in motors if maxes[m] - mins[m] < _MIN_SPAN]
    if short:
        raise ValueError(f"这些关节行程小于 {_MIN_SPAN} 个编码器刻度，不保存: {short}")
    return mins, maxes


def create_device(arm_type: str, port: str, device_id: str) -> Any:
    t = arm_type.strip().lower()
    if t in {"so101_follower", "so100_follower"}:
        from lerobot.robots.so_follower import SO100Follower, SO101Follower
        from lerobot.robots.so_follower.config_so_follower import SO100FollowerConfig, SO101FollowerConfig

        if "so100" in t:
            return SO100Follower(SO100FollowerConfig(port=port, id=device_id, use_degrees=False))
        return SO101Follower(SO101FollowerConfig(port=port, id=device_id, use_degrees=False))

    if t in {"so101_leader", "so100_leader"}:
        from lerobot.teleoperators.so_leader import SO100Leader, SO101Leader
        from lerobot.teleoperators.so_leader.config_so_leader import SO100LeaderConfig, SO101LeaderConfig

        if "so100" in t:
            return SO100Leader(SO100LeaderConfig(port=port, id=device_id, use_degrees=False))
        return SO101Leader(SO101LeaderConfig(port=port, id=device_id, use_degrees=False))

    raise ValueError(f"不支持的型号: {arm_type}")


def operating_mode_position() -> int:
    from lerobot.motors.feetech import OperatingMode

    return OperatingMode.POSITION.value


def build_calibration(device: Any, homing_offsets: dict, range_mins: dict, range_maxes: dict) -> dict:
    from lerobot.motors import MotorCalibration

    calibration = {}
    for motor, motor_config in device.bus.motors.items():
        calibration[motor] = MotorCalibration(
            id=motor_config.id,
            drive_mode=0,
            homing_offset=int(homing_offsets[motor]),
            range_min=int(range_mins[motor]),
            range_max=int(range_maxes[motor]),
        )
    return calibration


def _atomic_save_calibration(device: Any) -> None:
    import draccus

    fpath = device.calibration_fpath
    tmp = fpath.with_name(fpath.name + ".tmp")
    with open(tmp, "w") as handle, draccus.config_type("json"):
        draccus.dump(device.calibration, handle, indent=4)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(tmp, fpath)


def _restore_interrupted(device: Any) -> None:
    fpath = device.calibration_fpath
    bak = fpath.with_name(fpath.name + ".bak")
    if not bak.is_file():
        return
    shutil.copy2(bak, fpath)
    device._load_calibration()
    if device.calibration:
        device.bus.write_calibration(device.calibration)
    bak.unlink()
    emit("log", level="warning", message="上次标定中断，已把电机恢复到上一份标定文件")


def run(arm_type: str, port: str, device_id: str) -> int:
    device = None
    try:
        emit("phase", phase="connecting", message=f"正在连接 {arm_type} @ {port}")
        device = create_device(arm_type, port, device_id)
        device.connect(calibrate=False)
        _restore_interrupted(device)
        emit("log", level="info", message=f"已连接 {arm_type}")

        # Existing calibration file?
        if getattr(device, "calibration", None):
            emit(
                "phase",
                phase="existing",
                message=f"发现已有标定文件（id={device_id}）。点「使用已有」或「重新标定」。",
            )
            # Keep streaming positions so UI is not blank while deciding
            choice = wait_enter_with_positions(device.bus, "existing")
            if choice.lower() != "c":
                emit("log", level="info", message="写入已有标定到电机")
                device.bus.write_calibration(device.calibration)
                emit("phase", phase="saved", message=f"已沿用标定: {device.calibration_fpath}")
                return 0

        bus = device.bus
        bus.disable_torque()
        for motor in bus.motors:
            bus.write("Operating_Mode", motor, operating_mode_position())

        emit(
            "phase",
            phase="middle_position",
            message="请把所有关节摆到行程中间，观察下方数值变化，然后点「记录中间位置」。",
        )
        wait_enter_with_positions(bus, "middle_position")

        fpath = device.calibration_fpath
        bak = fpath.with_name(fpath.name + ".bak")
        if fpath.is_file():
            shutil.copy2(fpath, bak)
        homing_offsets = bus.set_half_turn_homings()
        emit("log", level="info", message=f"归零偏移: {homing_offsets}")

        emit(
            "phase",
            phase="range_prompt",
            message="点「开始记录范围」，然后缓慢推动每个关节走完整行程（除 wrist_roll）。",
        )
        wait_enter_with_positions(bus, "range_prompt")

        full_turn_motor = "wrist_roll"
        unknown = [m for m in bus.motors if m != full_turn_motor]
        emit(
            "phase",
            phase="recording_range",
            message=(
                "共 6 个关节。请推动除 wrist_roll（腕部旋转）外的 5 个关节走完整行程；"
                "wrist_roll 为连续旋转，量程自动记为 0–4095，界面仍会显示它的当前值。"
            ),
        )
        range_mins, range_maxes = record_ranges_with_progress(
            bus, unknown, full_turn_motor=full_turn_motor if full_turn_motor in bus.motors else None
        )
        if full_turn_motor in bus.motors:
            range_mins[full_turn_motor] = 0
            range_maxes[full_turn_motor] = 4095

        device.calibration = build_calibration(device, homing_offsets, range_mins, range_maxes)
        bus.write_calibration(device.calibration)
        _atomic_save_calibration(device)
        if bak.is_file():
            bak.unlink()
        emit("phase", phase="saved", message=f"标定已保存: {device.calibration_fpath}")
        emit("log", level="info", message=f"Calibration saved to {device.calibration_fpath}")
        return 0
    except Exception as exc:
        emit("log", level="error", message=f"{type(exc).__name__}: {exc}")
        emit("phase", phase="error", message=str(exc))
        return 1
    finally:
        if device is not None:
            try:
                device.disconnect()
                emit("log", level="info", message="已断开连接")
            except Exception as exc:
                emit("log", level="warning", message=f"断开忽略: {exc}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Guided LeRobot SO calibration with live joint events")
    parser.add_argument("--type", required=True, help="so101_follower / so101_leader / so100_*")
    parser.add_argument("--port", required=True)
    parser.add_argument("--id", required=True)
    args = parser.parse_args(argv)
    return run(args.type, args.port, args.id)


if __name__ == "__main__":
    raise SystemExit(main())
