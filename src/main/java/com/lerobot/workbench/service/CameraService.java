package com.lerobot.workbench.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lerobot.workbench.config.WorkbenchProperties;

import jakarta.annotation.PreDestroy;

/**
 * Discovers OpenCV cameras and proxies JPEG frames from a small Python bridge.
 */
@Service
public class CameraService {

    private static final Logger log = LoggerFactory.getLogger(CameraService.class);
    private static final int BRIDGE_PORT = 8092;

    private final WorkbenchProperties props;
    private final ObjectMapper mapper;
    private final AtomicReference<Process> bridge = new AtomicReference<>();
    private volatile boolean heldForRecording;
    private final Map<String, Object> selection = new LinkedHashMap<>();

    public CameraService(WorkbenchProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        selection.put("main", null);
        selection.put("wrist", null);
    }

    public Map<String, Object> listCameras() {
        if (heldForRecording) {
            return Map.of(
                    "ok", false,
                    "cameras", List.of(),
                    "selection", currentSelection(),
                    "message", "录制中不能重新打开摄像头。");
        }
        ensureBridge();
        try {
            Map<String, Object> body = getJson("http://127.0.0.1:" + BRIDGE_PORT + "/list");
            List<Map<String, Object>> cameras = castList(body.get("cameras"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("cameras", cameras);
            out.put("selection", currentSelection());
            out.put("bridge", "http://127.0.0.1:" + BRIDGE_PORT);
            return out;
        } catch (Exception e) {
            log.warn("camera list failed: {}", e.getMessage());
            return Map.of(
                    "ok", false,
                    "cameras", List.of(),
                    "selection", currentSelection(),
                    "message", "摄像头检测失败：" + e.getMessage() + "。请确认已安装 opencv-python。");
        }
    }

    public Map<String, Object> select(Map<String, Object> body) {
        if (body != null && body.containsKey("main")) {
            selection.put("main", normalizeCam(body.get("main")));
        }
        if (body != null && body.containsKey("wrist")) {
            selection.put("wrist", normalizeCam(body.get("wrist")));
        }
        Object main = selection.get("main");
        Object wrist = selection.get("wrist");
        if (main instanceof Map<?, ?> m && wrist instanceof Map<?, ?> w) {
            Object mi = m.get("index");
            Object wi = w.get("index");
            if (mi != null && wi != null && String.valueOf(mi).equals(String.valueOf(wi))) {
                return Map.of("ok", false, "message", "主摄像头和腕部摄像头不能是同一台设备");
            }
            Object ms = m.get("stableId");
            Object ws = w.get("stableId");
            if (ms != null && ws != null && !String.valueOf(ms).isBlank()
                    && String.valueOf(ms).equals(String.valueOf(ws))) {
                return Map.of("ok", false, "message", "主摄像头和腕部摄像头不能是同一台设备");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("selection", currentSelection());
        out.put("message", "摄像头角色已保存");
        return out;
    }

    public Map<String, Object> selection() {
        return Map.of("ok", true, "selection", currentSelection());
    }

    /** Drop the preview process so recording can open the same cameras. */
    public void holdForRecording() {
        heldForRecording = true;
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    URI.create("http://127.0.0.1:" + BRIDGE_PORT + "/release").toURL().openConnection();
            conn.setConnectTimeout(400);
            conn.setReadTimeout(1500);
            conn.getResponseCode();
        } catch (Exception ignored) {
            // bridge may already be down
        }
        stopBridge();
    }

    public void releaseHold() {
        heldForRecording = false;
    }

    public byte[] frame(int index) throws IOException {
        if (heldForRecording) {
            throw new IOException("录制中，画面改由录制进程输出");
        }
        ensureBridge();
        HttpURLConnection conn = (HttpURLConnection)
                URI.create("http://127.0.0.1:" + BRIDGE_PORT + "/frame/" + index + "?q=65").toURL().openConnection();
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(4000);
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        byte[] bytes = in == null ? new byte[0] : in.readAllBytes();
        if (code >= 400) {
            throw new IOException(new String(bytes, StandardCharsets.UTF_8));
        }
        return bytes;
    }

    private Map<String, Object> currentSelection() {
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("main", selection.get("main"));
        copy.put("wrist", selection.get("wrist"));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Object normalizeCam(Object raw) {
        if (raw == null || raw instanceof String s && (s.isBlank() || "null".equals(s))) {
            return null;
        }
        if (raw instanceof Map<?, ?> m) {
            Object index = m.get("index") != null ? m.get("index") : m.get("id");
            Object label = m.get("label");
            Object detail = m.get("detail");
            Object type = m.get("type");
            Map<String, Object> cam = new LinkedHashMap<>();
            cam.put("index", index);
            cam.put("label", label != null ? String.valueOf(label) : "摄像头 " + index);
            cam.put("detail", detail != null ? String.valueOf(detail) : "");
            cam.put("type", type != null ? String.valueOf(type) : "OpenCV");
            Object stableId = m.get("stableId");
            if (stableId != null && !String.valueOf(stableId).isBlank()) {
                cam.put("stableId", String.valueOf(stableId));
            }
            return cam;
        }
        if (raw instanceof Number n) {
            return Map.of("index", n.intValue(), "label", "摄像头 " + n.intValue(), "type", "OpenCV");
        }
        try {
            int idx = Integer.parseInt(String.valueOf(raw));
            return Map.of("index", idx, "label", "摄像头 " + idx, "type", "OpenCV");
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private synchronized void ensureBridge() {
        if (heldForRecording) {
            throw new IllegalStateException("录制中不能重新打开摄像头。");
        }
        Process p = bridge.get();
        if (p != null && p.isAlive() && ping()) {
            return;
        }
        stopBridge();
        Path script = Path.of(props.getRepoRoot())
                .toAbsolutePath()
                .normalize()
                .resolve("workbench/bridge/python/camera_bridge.py");
        if (!Files.isRegularFile(script)) {
            throw new IllegalStateException("找不到 camera_bridge.py: " + script);
        }
        Path repo = Path.of(props.getRepoRoot()).toAbsolutePath().normalize();
        // Prefer project venv python; ProcessCommandResolver is not injected here — resolve locally.
        Path venvPy = repo.resolve(".venv/bin/python");
        String python = Files.isRegularFile(venvPy) || Files.isSymbolicLink(venvPy)
                ? venvPy.toString()
                : (props.getPythonBin() == null || props.getPythonBin().isBlank() || "uv".equals(props.getPythonBin())
                        ? "python3"
                        : props.getPythonBin());
        List<String> cmd = new ArrayList<>();
        cmd.add(python);
        cmd.add(script.toString());
        cmd.add(String.valueOf(BRIDGE_PORT));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(repo.toFile());
            pb.redirectErrorStream(true);
            // macOS: avoid AVFoundation auth from a non-GUI thread spawned by Java
            pb.environment().put("OPENCV_AVFOUNDATION_SKIP_AUTH", "1");
            pb.environment().put("PYTHONUNBUFFERED", "1");
            Process started = pb.start();
            bridge.set(started);
            // drain logs
            Thread t = new Thread(() -> {
                try (BufferedReader r =
                        new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        log.info("[camera-bridge] {}", line);
                    }
                } catch (IOException ignored) {
                    // process ended
                }
            }, "camera-bridge-log");
            t.setDaemon(true);
            t.start();
            for (int i = 0; i < 40; i++) {
                if (ping()) {
                    return;
                }
                if (!started.isAlive()) {
                    throw new IllegalStateException("camera bridge 启动失败，请检查是否安装了 opencv-python");
                }
                Thread.sleep(150);
            }
            throw new IllegalStateException("camera bridge 启动超时");
        } catch (Exception e) {
            stopBridge();
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private boolean ping() {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    URI.create("http://127.0.0.1:" + BRIDGE_PORT + "/health").toURL().openConnection();
            conn.setConnectTimeout(400);
            conn.setReadTimeout(400);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @PreDestroy
    public void stopBridge() {
        Process p = bridge.getAndSet(null);
        if (p != null) {
            p.destroy();
            try {
                p.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    private Map<String, Object> getJson(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(15000);
        try (InputStream in = conn.getInputStream()) {
            return mapper.readValue(in, new TypeReference<>() {});
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object raw) {
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return List.of();
    }
}
