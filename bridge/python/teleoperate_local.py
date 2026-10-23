#!/usr/bin/env python
"""Start SO-100/101 teleop without lerobot-teleoperate's draccus CLI.

Python 3.14 + draccus fails while registering `str | None` fields
(`TypeError: str | None is not callable`). Building configs in code avoids that.
"""

from __future__ import annotations

import argparse
import time

from lerobot.processor import make_default_processors
from lerobot.robots.so_follower import SO100Follower, SO101Follower
from lerobot.robots.so_follower.config_so_follower import SO100FollowerConfig, SO101FollowerConfig
from lerobot.scripts.lerobot_teleoperate import teleop_loop
from lerobot.teleoperators.so_leader import SO100Leader, SO101Leader
from lerobot.teleoperators.so_leader.config_so_leader import SO100LeaderConfig, SO101LeaderConfig
from lerobot.utils.utils import init_logging


def _harden_bus(bus) -> None:
    """Feetech 总线偶尔丢状态包，配置阶段默认不重试，这里至少再试几次。"""
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


def _connect(device, label: str, attempts: int = 3):
    _harden_bus(device.bus)
    last: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            device.connect(calibrate=False)
            print(f"{label} 已连接", flush=True)
            return
        except Exception as exc:
            last = exc
            print(f"{label} 第 {attempt}/{attempts} 次连接失败: {exc}", flush=True)
            try:
                if device.is_connected:
                    device.disconnect()
            except Exception:
                try:
                    device.bus.disconnect(False)
                except Exception:
                    pass
            time.sleep(0.5)
    raise RuntimeError(f"{label} 连接失败。请检查这条臂的供电和舵机线是否插紧。") from last


def main() -> int:
    parser = argparse.ArgumentParser(description="Local SO teleoperation")
    parser.add_argument("--robot-type", default="so101_follower")
    parser.add_argument("--robot-port", required=True)
    parser.add_argument("--robot-id", required=True)
    parser.add_argument("--teleop-type", default="so101_leader")
    parser.add_argument("--teleop-port", required=True)
    parser.add_argument("--teleop-id", required=True)
    parser.add_argument("--fps", type=int, default=30)
    args = parser.parse_args()

    init_logging()
    print(
        f"连接从臂 {args.robot_type} {args.robot_port} id={args.robot_id}",
        flush=True,
    )
    print(
        f"连接主臂 {args.teleop_type} {args.teleop_port} id={args.teleop_id}",
        flush=True,
    )

    if "so100" in args.robot_type:
        robot = SO100Follower(SO100FollowerConfig(port=args.robot_port, id=args.robot_id))
    else:
        robot = SO101Follower(SO101FollowerConfig(port=args.robot_port, id=args.robot_id))
    if "so100" in args.teleop_type:
        teleop = SO100Leader(SO100LeaderConfig(port=args.teleop_port, id=args.teleop_id))
    else:
        teleop = SO101Leader(SO101LeaderConfig(port=args.teleop_port, id=args.teleop_id))
    teleop_action_processor, robot_action_processor, robot_observation_processor = make_default_processors()

    # 先连从臂：它要写力矩和 PID，比主臂更容易因丢包失败。
    _connect(robot, "从臂")
    _connect(teleop, "主臂")
    print("遥操作已连接。推动主臂，从臂会跟随。在界面点停止即可结束。", flush=True)
    try:
        teleop_loop(
            teleop=teleop,
            robot=robot,
            fps=args.fps,
            display_data=False,
            teleop_action_processor=teleop_action_processor,
            robot_action_processor=robot_action_processor,
            robot_observation_processor=robot_observation_processor,
        )
    except KeyboardInterrupt:
        print("已中断", flush=True)
    finally:
        teleop.disconnect()
        robot.disconnect()
        print("已断开", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
