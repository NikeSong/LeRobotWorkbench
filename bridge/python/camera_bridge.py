#!/usr/bin/env python3
"""Local camera list + JPEG frame server for LeRobot Workbench hardware page.

Devices come from camera_identity (OS hardware id). iPhone / Continuity cameras
are not opened. The OpenCV index in each row is only the current handle.
"""

from __future__ import annotations

import json
import logging
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlparse

logging.basicConfig(level=logging.INFO, format="[camera-bridge] %(message)s")
log = logging.getLogger("camera_bridge")

_lock = threading.Lock()
_caps: dict[int, Any] = {}


def _import_cv2():
    try:
        import cv2  # type: ignore

        return cv2
    except ImportError as e:
        raise RuntimeError("需要 OpenCV：请在本机安装 opencv-python 或 uv sync --extra opencv") from e


def _describe(device, idx: int, name: str, width: int, height: int, fps: float) -> dict[str, Any]:
    label = name or f"摄像头 {idx}"
    detail = f"{width}×{height}" if width and height else "已识别，尚未读到画面"
    if fps > 1:
        detail += f" · {fps:.0f}fps"
    return {
        "id": idx,
        "index": idx,
        "type": "OpenCV",
        "label": label,
        "detail": detail,
        "width": width,
        "height": height,
        "fps": fps,
        "stableId": device.stable_id,
        "matchIds": list(device.match_ids),
        "kind": device.kind,
        "serial": device.serial,
        "location": device.location,
        "stableName": label,
    }


def list_cameras(max_index: int = 6, only: list[int] | None = None) -> list[dict[str, Any]]:
    cv2 = _import_cv2()
    from camera_identity import list_devices

    devices = list_devices(include_phone=False)
    if only is not None:
        devices = [d for d in devices if d.index in only]
    found: list[dict[str, Any]] = []
    for device in devices:
        idx = device.index
        name = device.label
        cap = cv2.VideoCapture(idx)
        try:
            # Short open — don't hang on Continuity Camera auth dialogs.
            if hasattr(cv2, "CAP_PROP_OPEN_TIMEOUT_MSEC"):
                cap.set(cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, 800)
            if hasattr(cv2, "CAP_PROP_READ_TIMEOUT_MSEC"):
                cap.set(cv2.CAP_PROP_READ_TIMEOUT_MSEC, 800)
            if not cap.isOpened():
                found.append(_describe(device, idx, name, 0, 0, 0))
                continue
            ok, frame = cap.read()
            if not ok or frame is None:
                found.append(_describe(device, idx, name, 0, 0, 0))
                continue
            # Use the pixels OpenCV actually delivers. CAP_PROP_* often reports
            # 640x480 on macOS while the frame buffer is 640x360.
            h, w = frame.shape[:2]
            fps = float(cap.get(cv2.CAP_PROP_FPS) or 0)
            found.append(_describe(device, idx, name, w, h, fps))
        finally:
            cap.release()
            time.sleep(0.05)
    return found


def _get_cap(idx: int):
    cv2 = _import_cv2()
    with _lock:
        cap = _caps.get(idx)
        if cap is not None and cap.isOpened():
            return cap
        cap = cv2.VideoCapture(idx)
        if not cap.isOpened():
            raise RuntimeError(f"无法打开摄像头 {idx}")
        _caps[idx] = cap
        return cap


def release_all() -> None:
    with _lock:
        for cap in _caps.values():
            try:
                cap.release()
            except Exception:
                pass
        _caps.clear()


def grab_jpeg(idx: int, quality: int = 70) -> bytes:
    cv2 = _import_cv2()
    cap = _get_cap(idx)
    ok, frame = cap.read()
    if not ok or frame is None:
        with _lock:
            old = _caps.pop(idx, None)
            if old is not None:
                old.release()
        cap = _get_cap(idx)
        ok, frame = cap.read()
    if not ok or frame is None:
        raise RuntimeError(f"摄像头 {idx} 读帧失败")
    ok, buf = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), quality])
    if not ok:
        raise RuntimeError("JPEG 编码失败")
    return buf.tobytes()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args: Any) -> None:
        log.info("%s - %s", self.address_string(), fmt % args)

    def _send(self, code: int, body: bytes, content_type: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def _send_json(self, code: int, obj: Any) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self._send(code, body, "application/json; charset=utf-8")

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)
        if path == "/health":
            self._send_json(200, {"ok": True})
            return
        if path == "/list":
            only = None
            if "only" in qs:
                try:
                    only = [int(x) for x in qs["only"][0].split(",") if x.strip() != ""]
                except Exception:
                    only = None
            try:
                cams = list_cameras(only=only)
                self._send_json(200, {"ok": True, "cameras": cams})
            except Exception as e:
                self._send_json(500, {"ok": False, "message": str(e), "cameras": []})
            return
        if path == "/release":
            release_all()
            self._send_json(200, {"ok": True})
            return
        if path.startswith("/frame/"):
            try:
                idx = int(path.rsplit("/", 1)[-1])
                q = int(qs.get("q", ["65"])[0])
                jpeg = grab_jpeg(idx, quality=q)
                self._send(200, jpeg, "image/jpeg")
            except Exception as e:
                self._send_json(500, {"ok": False, "message": str(e)})
            return
        self._send_json(404, {"ok": False, "message": "not found"})


def main() -> int:
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8092
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    log.info("listening on http://127.0.0.1:%s", port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
