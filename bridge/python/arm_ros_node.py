#!/usr/bin/env python3
"""Host-side arm node: serial ports ↔ rosbridge.

Runs on the Mac/Linux host (not in Docker) so /dev/cu.* / ttyACM* are reachable.
Publishes joint state and accepts joint commands over rosbridge JSON protocol.
"""

from __future__ import annotations

import argparse
import json
import logging
import time

logging.basicConfig(level=logging.INFO, format="[arm-node] %(message)s")
log = logging.getLogger("arm")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rosbridge", default="ws://127.0.0.1:9090")
    parser.add_argument("--follower-port", default="")
    parser.add_argument("--leader-port", default="")
    args = parser.parse_args()

    log.info("Arm ROS node starting")
    log.info("rosbridge=%s follower=%s leader=%s", args.rosbridge, args.follower_port, args.leader_port)
    log.info("Wire Feetech/Dynamixel buses here; publishing heartbeat for now.")

    try:
        import websocket  # type: ignore
    except ImportError:
        log.warning("websocket-client not installed; heartbeat-only mode")
        while True:
            time.sleep(5)
            log.info("alive (no websocket)")
        return

    def on_open(ws):  # noqa: ANN001
        advertise = {
            "op": "advertise",
            "topic": "/lerobot/arm/status",
            "type": "std_msgs/String",
        }
        ws.send(json.dumps(advertise))
        log.info("advertised /lerobot/arm/status")

    def tick(ws):  # noqa: ANN001
        payload = {
            "op": "publish",
            "topic": "/lerobot/arm/status",
            "msg": {
                "data": json.dumps(
                    {
                        "followerPort": args.follower_port,
                        "leaderPort": args.leader_port,
                        "ts": time.time(),
                    }
                )
            },
        }
        ws.send(json.dumps(payload))

    ws = websocket.WebSocketApp(args.rosbridge, on_open=on_open)
    # simple loop
    import threading

    def runner():
        ws.run_forever()

    threading.Thread(target=runner, daemon=True).start()
    time.sleep(1)
    while True:
        try:
            if ws.sock and ws.sock.connected:
                tick(ws)
        except Exception as exc:  # noqa: BLE001
            log.warning("publish failed: %s", exc)
        time.sleep(2)


if __name__ == "__main__":
    main()
