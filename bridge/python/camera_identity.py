"""Bind a camera by the operating system's device identity, not by OpenCV's index.

OpenCV only has a number, and that number moves when a phone or another camera
appears. The identity we store is the one the OS already uses:

- USB serial number, when that serial is unique among the cameras plugged in
- otherwise vendor, product and USB port location (the physical port)
- built-in cameras: the OS device id
- iPhone / Continuity cameras are labeled and refused

macOS reads AVFoundation in the same order OpenCV uses (``devicesWithMediaType``).
Windows reads Media Foundation in the same order OpenCV's MSMF backend uses.
The number passed to ``VideoCapture`` is looked up from the stored id at open time.
"""

from __future__ import annotations

import ctypes
import ctypes
import re
import subprocess
import sys
import threading
from dataclasses import dataclass

_LOCK = threading.Lock()


class CameraIdentityError(RuntimeError):
    pass


@dataclass(frozen=True)
class CameraDevice:
    """One video device as the OS sees it right now."""

    preferred_id: str
    match_ids: tuple[str, ...]
    index: int
    label: str
    kind: str
    serial: str
    location: str
    platform_id: str

    @property
    def stable_id(self) -> str:
        return self.preferred_id


def list_devices(*, include_phone: bool = False) -> list[CameraDevice]:
    with _LOCK:
        if sys.platform == "darwin":
            raw = _list_macos()
        elif sys.platform == "win32":
            raw = _list_windows()
        elif sys.platform.startswith("linux"):
            raw = _list_linux()
        else:
            raise CameraIdentityError(f"这个系统还不能读取摄像头硬件编号: {sys.platform}")
        devices = _assign_ids(raw)
    if include_phone:
        return devices
    return [d for d in devices if d.kind != "phone"]


def resolve_index(stable_id: str) -> int:
    """Current OpenCV index for a previously stored hardware id."""
    wanted = (stable_id or "").strip()
    if not wanted:
        raise CameraIdentityError("没有摄像头硬件编号。请到「硬件准备」重新选择主摄像头和腕部。")
    devices = list_devices(include_phone=True)
    serial_form = _is_plain_serial_id(wanted)
    if serial_form:
        owners = [d for d in devices if serial_form in d.match_ids and d.kind == "usb"]
        if len(owners) > 1:
            raise CameraIdentityError(
                "插上了两台序列号相同的相机，无法确定哪一台是原来绑定的。"
                "请只留一台，或到「硬件准备」按 USB 口重新选择。"
            )
    hits = [d for d in devices if wanted in d.match_ids]
    phones = [d for d in hits if d.kind == "phone"]
    hits = [d for d in hits if d.kind != "phone"]
    if phones and not hits:
        raise CameraIdentityError("这个硬件编号是 iPhone / Continuity 相机，不能当作主摄像头或腕部。")
    if len(hits) == 1:
        return hits[0].index
    if not hits:
        raise CameraIdentityError(
            f"摄像头硬件编号 {wanted} 现在不在系统里。"
            "请确认相机还插着；两台序列号相同的相机要插回绑定时的 USB 口。"
        )
    raise CameraIdentityError(f"硬件编号 {wanted} 同时对上了多台相机，已停止打开。")


def _is_plain_serial_id(stable_id: str) -> str:
    """Return ``vid:pid:serial`` when the id is the unique-serial form."""
    if stable_id.startswith("usb:") and "@" not in stable_id:
        body = stable_id[4:]
        parts = body.split(":")
        if len(parts) == 3 and parts[2] and parts[2] != "noserial":
            return body
    return ""


def _assign_ids(raw: list[dict]) -> list[CameraDevice]:
    counts: dict[tuple, int] = {}
    for item in raw:
        if item["kind"] == "usb" and item.get("serial"):
            key = (item["vid"], item["pid"], item["serial"])
            counts[key] = counts.get(key, 0) + 1
    devices: list[CameraDevice] = []
    for item in raw:
        kind = item["kind"]
        platform_id = item["platform_id"]
        serial = item.get("serial") or ""
        location = item.get("location") or ""
        if platform_id.startswith("/dev/"):
            preferred = "v4l:" + platform_id
            keys = {preferred, platform_id}
        elif kind == "builtin":
            preferred = "builtin:" + platform_id
            keys = {preferred, platform_id}
        elif kind == "phone":
            preferred = "phone:" + platform_id
            keys = {preferred, platform_id}
        else:
            vid = f"{int(item['vid']):04x}"
            pid = f"{int(item['pid']):04x}"
            # Location is stable. Putting the serial in the primary id makes the
            # string change whenever ioreg misses the serial, and the UI then
            # treats a camera the user just picked as unbound.
            loc = location or platform_id
            port_id = f"usb:{vid}:{pid}@{loc}"
            serial_id = f"usb:{vid}:{pid}:{serial}" if serial else ""
            legacy_port = f"usb:{vid}:{pid}:{serial or 'noserial'}@{location or platform_id}"
            preferred = port_id if loc else (serial_id or "os:" + platform_id)
            keys = {preferred, port_id, legacy_port, platform_id}
            if serial_id and counts.get((item["vid"], item["pid"], serial), 0) == 1:
                keys.add(serial_id)
        devices.append(
            CameraDevice(
                preferred_id=preferred,
                match_ids=tuple(sorted(keys)),
                index=int(item["index"]),
                label=item.get("label") or f"摄像头 {item['index']}",
                kind=kind,
                serial=serial,
                location=location,
                platform_id=platform_id,
            )
        )
    return devices


def _list_macos() -> list[dict]:
    rows = _avfoundation_devices()
    serials = _macos_usb_serials()
    out = []
    for row in rows:
        kind = _macos_kind(row["device_type"], row["label"])
        vid = pid = None
        location = ""
        serial = ""
        model = row.get("model") or ""
        parsed = re.search(r"VendorID_(\d+)\s+ProductID_(\d+)", model)
        if kind == "usb" and parsed:
            vid = int(parsed.group(1))
            pid = int(parsed.group(2))
            location = _location_from_unique_id(row["platform_id"], vid, pid)
            serial = serials.get(location, "")
        out.append(
            {
                "index": row["index"],
                "platform_id": row["platform_id"],
                "label": row["label"],
                "kind": kind,
                "vid": vid if vid is not None else 0,
                "pid": pid if pid is not None else 0,
                "serial": serial,
                "location": location,
            }
        )
    return out


def _macos_kind(device_type: str, label: str) -> str:
    blob = f"{device_type} {label}".lower()
    if "continuity" in blob or "deskview" in blob or "desk view" in blob or "iphone" in blob or "手机" in blob:
        return "phone"
    if "builtin" in blob or "built-in" in blob:
        return "builtin"
    return "usb"


def _location_from_unique_id(unique_id: str, vid: int, pid: int) -> str:
    text = unique_id.lower().removeprefix("0x")
    suffix = f"{vid:04x}{pid:04x}"
    if text.endswith(suffix) and len(text) > len(suffix):
        return text[: -len(suffix)].lstrip("0") or "0"
    return text


def _avfoundation_devices() -> list[dict]:
    import ctypes
    from ctypes import CDLL, c_char_p, c_uint64, c_void_p

    objc = CDLL("/usr/lib/libobjc.A.dylib")
    objc.objc_getClass.restype = c_void_p
    objc.objc_getClass.argtypes = [c_char_p]
    objc.sel_registerName.restype = c_void_p
    objc.sel_registerName.argtypes = [c_char_p]
    CDLL("/System/Library/Frameworks/CoreMedia.framework/CoreMedia")
    av = CDLL("/System/Library/Frameworks/AVFoundation.framework/AVFoundation")
    CDLL("/System/Library/Frameworks/Foundation.framework/Foundation")

    def sel(name: str):
        return objc.sel_registerName(name.encode())

    def msg(ret, args, recv, selector, *extra):
        objc.objc_msgSend.restype = ret
        objc.objc_msgSend.argtypes = [c_void_p, c_void_p, *args]
        return objc.objc_msgSend(recv, sel(selector), *extra)

    def ns(ptr) -> str:
        if not ptr:
            return ""
        raw = msg(c_char_p, [], ptr, "UTF8String")
        return raw.decode() if raw else ""

    pool = msg(c_void_p, [], objc.objc_getClass(b"NSAutoreleasePool"), "alloc")
    pool = msg(c_void_p, [], pool, "init")
    try:
        media = c_void_p.in_dll(av, "AVMediaTypeVideo")
        devices = msg(
            c_void_p,
            [c_void_p],
            objc.objc_getClass(b"AVCaptureDevice"),
            "devicesWithMediaType:",
            media,
        )
        count = int(msg(c_uint64, [], devices, "count"))
        rows = []
        for i in range(count):
            dev = msg(c_void_p, [c_uint64], devices, "objectAtIndex:", i)
            rows.append(
                {
                    "index": i,
                    "platform_id": ns(msg(c_void_p, [], dev, "uniqueID")),
                    "label": ns(msg(c_void_p, [], dev, "localizedName")),
                    "model": ns(msg(c_void_p, [], dev, "modelID")),
                    "device_type": ns(msg(c_void_p, [], dev, "deviceType")),
                }
            )
        return rows
    finally:
        msg(c_void_p, [], pool, "drain")


def _macos_usb_serials() -> dict[str, str]:
    """Map USB location (hex without leading zeros) to the device serial string."""
    try:
        proc = subprocess.run(
            ["ioreg", "-p", "IOUSB", "-l", "-w", "0"],
            capture_output=True,
            text=True,
            timeout=6,
            check=False,
        )
    except Exception:
        return {}
    text = proc.stdout or ""
    found: dict[str, str] = {}
    for part in re.split(r"\n\s*\+\-o ", text):
        if "IOUSBHostDevice" not in part[:400]:
            continue
        loc_m = re.search(r"@([0-9A-Fa-f]+)", part[:240])
        ser_m = re.search(r'"USB Serial Number" = "([^"]*)"', part)
        if not loc_m or not ser_m or not ser_m.group(1):
            continue
        loc = loc_m.group(1).lower().lstrip("0") or "0"
        found[loc] = ser_m.group(1)
    return found


_USB_LINK = re.compile(
    r"vid_([0-9a-f]{4})&pid_([0-9a-f]{4})(?:&mi_[0-9a-f]+)?#([^#]+)",
    re.IGNORECASE,
)


def parse_windows_symbolic_link(link: str, label: str = "") -> dict:
    """Turn a Media Foundation symbolic link into the same fields macOS produces."""
    match = _USB_LINK.search(link.replace("\\", "#"))
    blob = f"{link} {label}".lower()
    phone = any(s in blob for s in ("iphone", "continuity", "手机"))
    if not match:
        return {
            "platform_id": link,
            "label": label or link,
            "kind": "phone" if phone else "builtin",
            "vid": 0,
            "pid": 0,
            "serial": "",
            "location": link,
        }
    instance = match.group(3)
    generated = "&" in instance
    return {
        "platform_id": link,
        "label": label or "USB camera",
        "kind": "phone" if phone else "usb",
        "vid": int(match.group(1), 16),
        "pid": int(match.group(2), 16),
        "serial": "" if generated else instance,
        "location": instance if generated else "",
    }


def _list_windows() -> list[dict]:
    """Media Foundation enumeration order is OpenCV's MSMF index order."""
    import ctypes
    from ctypes import HRESULT, POINTER, WINFUNCTYPE, c_uint32, c_void_p, wintypes

    ole32 = ctypes.WinDLL("ole32")
    mf = ctypes.WinDLL("mf")
    mfplat = ctypes.WinDLL("mfplat")
    COINIT_APARTMENTTHREADED = 0x2
    ole32.CoInitializeEx(None, COINIT_APARTMENTTHREADED)
    mfplat.MFStartup.argtypes = [wintypes.ULONG, wintypes.DWORD]
    mfplat.MFStartup.restype = HRESULT
    hr = mfplat.MFStartup(0x0002 << 16 | 0x0070, 0)
    if hr < 0:
        raise CameraIdentityError(f"MFStartup 失败: {hr}")

    MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE = _guid(0xC60AC5FE, 0x252A, 0x478F, 0xA0, 0xEF, 0xBC, 0x8F, 0xA5, 0xF7, 0xCA, 0xD3)
    MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID = _guid(
        0x8AC3587A, 0x4AE7, 0x42D8, 0x99, 0xE0, 0x0A, 0x60, 0x13, 0xEE, 0xF9, 0x0F
    )
    MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK = _guid(
        0x58F0AAD8, 0x22BF, 0x4F8A, 0xBB, 0x3D, 0xD2, 0xC4, 0x97, 0x8C, 0x6E, 0x2F
    )
    MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME = _guid(0x60D0E559, 0x52F8, 0x4FA2, 0xBB, 0xCE, 0xAC, 0xDB, 0x34, 0xA8, 0xEC, 0x01)

    attrs = c_void_p()
    mfplat.MFCreateAttributes.argtypes = [POINTER(c_void_p), wintypes.UINT]
    mfplat.MFCreateAttributes.restype = HRESULT
    hr = mfplat.MFCreateAttributes(ctypes.byref(attrs), 1)
    if hr < 0 or not attrs:
        raise CameraIdentityError(f"MFCreateAttributes 失败: {hr}")
    _set_guid(attrs, 24, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID)

    arr = POINTER(c_void_p)()
    count = c_uint32()
    mf.MFEnumDeviceSources.argtypes = [c_void_p, POINTER(POINTER(c_void_p)), POINTER(c_uint32)]
    mf.MFEnumDeviceSources.restype = HRESULT
    hr = mf.MFEnumDeviceSources(attrs, ctypes.byref(arr), ctypes.byref(count))
    if hr < 0:
        raise CameraIdentityError(f"MFEnumDeviceSources 失败: {hr}")
    rows = []
    for i in range(int(count.value)):
        activate = arr[i]
        link = _get_allocated_string(activate, 13, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK)
        name = _get_allocated_string(activate, 13, MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME)
        parsed = parse_windows_symbolic_link(link, name)
        parsed["index"] = i
        rows.append(parsed)
        _release(activate)
    return rows


class _GUID(ctypes.Structure):
    _fields_ = [
        ("Data1", ctypes.c_uint32),
        ("Data2", ctypes.c_uint16),
        ("Data3", ctypes.c_uint16),
        ("Data4", ctypes.c_ubyte * 8),
    ]


def _guid(d1, d2, d3, *data4) -> _GUID:
    return _GUID(d1, d2, d3, (ctypes.c_ubyte * 8)(*data4))


def _set_guid(obj, vtable_index: int, key: _GUID, value: _GUID) -> None:
    import ctypes
    from ctypes import HRESULT, c_void_p

    vtbl = ctypes.cast(obj, ctypes.POINTER(ctypes.POINTER(c_void_p)))[0]
    proto = ctypes.WINFUNCTYPE(HRESULT, c_void_p, ctypes.POINTER(_GUID), _GUID)
    fn = proto(vtbl[vtable_index])
    hr = fn(obj, ctypes.byref(key), value)
    if hr < 0:
        raise CameraIdentityError(f"设置摄像头属性失败: {hr}")


def _get_allocated_string(obj, vtable_index: int, key: _GUID) -> str:
    import ctypes
    from ctypes import HRESULT, POINTER, c_uint32, c_void_p, c_wchar_p

    vtbl = ctypes.cast(obj, ctypes.POINTER(ctypes.POINTER(c_void_p)))[0]
    proto = ctypes.WINFUNCTYPE(HRESULT, c_void_p, ctypes.POINTER(_GUID), POINTER(c_wchar_p), POINTER(c_uint32))
    fn = proto(vtbl[vtable_index])
    text = c_wchar_p()
    length = c_uint32()
    hr = fn(obj, ctypes.byref(key), ctypes.byref(text), ctypes.byref(length))
    if hr < 0 or not text.value:
        return ""
    value = text.value
    ctypes.windll.ole32.CoTaskMemFree(text)
    return value


def _release(obj) -> None:
    import ctypes
    from ctypes import HRESULT, c_void_p

    vtbl = ctypes.cast(obj, ctypes.POINTER(ctypes.POINTER(c_void_p)))[0]
    proto = ctypes.WINFUNCTYPE(HRESULT, c_void_p)
    proto(vtbl[2])(obj)


def _list_linux() -> list[dict]:
    """``/dev/v4l/by-id`` is the stable name. Its real path is ``/dev/videoN``, OpenCV's index."""
    from pathlib import Path

    by_id = Path("/dev/v4l/by-id")
    if not by_id.is_dir():
        raise CameraIdentityError("没有 /dev/v4l/by-id，读不到摄像头硬件编号。")
    rows = []
    for path in sorted(by_id.iterdir()):
        if "index0" not in path.name:
            continue
        target = path.resolve()
        if not target.name.startswith("video"):
            continue
        serial = ""
        match = re.search(r"_([^_]+)-video-index0$", path.name)
        if match:
            serial = match.group(1)
        rows.append(
            {
                "index": int(target.name.removeprefix("video")),
                "platform_id": str(path),
                "label": path.name,
                "kind": "usb",
                "vid": 0,
                "pid": 0,
                "serial": serial,
                "location": str(path),
            }
        )
    return rows
