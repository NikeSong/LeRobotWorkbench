#!/usr/bin/env python3
"""Stream leader/follower joint positions as JSON lines for the workbench UI."""

from __future__ import annotations

import argparse
import json
import sys
import time


JOINTS = [
    "shoulder_pan",
    "shoulder_lift",
    "elbow_flex",
    "wrist_flex",
    "wrist_roll",
    "gripper",
]


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


def _soft_disconnect(dev) -> None:
    if dev is None:
        return
    try:
        bus = getattr(dev, "bus", None)
        if bus is not None and getattr(bus, "is_connected", False):
            bus.disconnect(disable_torque=False)
            return
    except Exception:
        pass
    try:
        if getattr(dev, "is_connected", False):
            dev.disconnect()
    except Exception:
        pass


def _open(kind: str, port: str, device_id: str):
    if kind == "leader":
        from lerobot.teleoperators.so_leader.config_so_leader import SO101LeaderConfig
        from lerobot.teleoperators.so_leader.so_leader import SO101Leader

        cfg = SO101LeaderConfig(port=port, id=device_id or "leader")
        dev = SO101Leader(cfg)
    else:
        from lerobot.robots.so_follower.config_so_follower import SO101FollowerConfig
        from lerobot.robots.so_follower.so_follower import SO101Follower

        # No cameras — joint preview must not touch OpenCV / Continuity Camera.
        cfg = SO101FollowerConfig(port=port, id=device_id or "follower", cameras={})
        dev = SO101Follower(cfg)
    _harden_bus(dev.bus)
    last: Exception | None = None
    for attempt in range(1, 4):
        try:
            dev.connect(calibrate=False)
            print(f"{kind} connected on {port}", flush=True)
            return dev
        except Exception as exc:
            last = exc
            print(f"{kind} connect {attempt}/3 failed: {exc}", flush=True)
            _soft_disconnect(dev)
            time.sleep(0.4)
    raise RuntimeError(f"{kind} 无法连接 {port}: {last}") from last


def _joints(raw: dict) -> dict:
    out = {}
    for name in JOINTS:
        key = f"{name}.pos"
        if key in raw:
            try:
                out[name] = round(float(raw[key]), 2)
            except (TypeError, ValueError):
                out[name] = None
        elif name in raw:
            try:
                out[name] = round(float(raw[name]), 2)
            except (TypeError, ValueError):
                out[name] = None
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--leader-port", required=True)
    parser.add_argument("--follower-port", required=True)
    parser.add_argument("--leader-id", default="leader")
    parser.add_argument("--follower-id", default="follower")
    parser.add_argument("--hz", type=float, default=10)
    args = parser.parse_args()

    print(
        json.dumps(
            {"ok": False, "phase": "starting", "message": "正在连接主臂 / 从臂…"},
            ensure_ascii=False,
        ),
        flush=True,
    )

    leader = follower = None
    try:
        # Follower first — same as teleop / record.
        follower = _open("follower", args.follower_port, args.follower_id)
        leader = _open("leader", args.leader_port, args.leader_id)
        period = 1.0 / max(args.hz, 1)
        print(
            json.dumps(
                {"ok": True, "phase": "ready", "message": "关节预览已连接", "leader": {}, "follower": {}},
                ensure_ascii=False,
            ),
            flush=True,
        )
        while True:
            t0 = time.perf_counter()
            payload = {
                "ok": True,
                "ts": time.time(),
                "leader": _joints(leader.get_action()),
                "follower": _joints(follower.get_observation()),
            }
            sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
            sys.stdout.flush()
            dt = time.perf_counter() - t0
            time.sleep(max(0.0, period - dt))
    except KeyboardInterrupt:
        return 0
    except Exception as exc:
        print(json.dumps({"ok": False, "message": str(exc)}, ensure_ascii=False), flush=True)
        return 1
    finally:
        _soft_disconnect(leader)
        _soft_disconnect(follower)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
