package com.lerobot.workbench.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class JointPreviewService {

    private static final Logger log = LoggerFactory.getLogger(JointPreviewService.class);

    private final ProcessCommandResolver commands;
    private final ObjectMapper mapper;
    private final ActivityGate gate;
    private final AtomicReference<Map<String, Object>> latest = new AtomicReference<>(Map.of("ok", false));
    private volatile Process process;
    private volatile int heldTicket = -1;

    public JointPreviewService(ProcessCommandResolver commands, ObjectMapper mapper, ActivityGate gate) {
        this.commands = commands;
        this.mapper = mapper;
        this.gate = gate;
    }

    public synchronized Map<String, Object> start(Map<String, Object> body) {
        String leaderPort = str(body.get("leaderPort"));
        String followerPort = str(body.get("followerPort"));
        if (leaderPort.isBlank() || followerPort.isBlank()) {
            return Map.of("ok", false, "message", "请先在设置里配对主臂和从臂。");
        }
        String busy = gate.conflict(ActivityGate.Kind.JOINTS);
        if (busy != null) {
            return Map.of("ok", false, "message", busy);
        }
        stop();
        Path script = commands.repoRoot().resolve("workbench/bridge/python/joint_preview.py");
        List<String> cmd = new java.util.ArrayList<>(commands.pythonScript(script));
        cmd.add("--leader-port");
        cmd.add(leaderPort);
        cmd.add("--follower-port");
        cmd.add(followerPort);
        cmd.add("--leader-id");
        cmd.add(str(body.getOrDefault("leaderId", "leader")));
        cmd.add("--follower-id");
        cmd.add(str(body.getOrDefault("followerId", "follower")));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(commands.repoRoot().toFile());
            Map<String, String> env = commands.processEnvironment();
            env.put("PYTHONUNBUFFERED", "1");
            pb.environment().putAll(env);
            pb.redirectErrorStream(true);
            latest.set(Map.of("ok", false, "message", "正在启动关节预览…"));
            heldTicket = gate.acquire(ActivityGate.Kind.JOINTS);
            process = pb.start();
            Thread t = new Thread(this::drain, "joint-preview");
            t.setDaemon(true);
            t.start();
            return Map.of("ok", true, "message", "已开始读取主臂 / 从臂关节。录制开始前会自动断开，避免占用串口。");
        } catch (Exception e) {
            releaseTicket();
            return Map.of("ok", false, "message", "启动关节预览失败: " + e.getMessage());
        }
    }

    public synchronized Map<String, Object> stop() {
        Process p = process;
        process = null;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        releaseTicket();
        latest.set(Map.of("ok", false, "running", false, "message", "已停止"));
        // Give macOS serial stack a beat to release the USB devices.
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Map.of("ok", true, "running", false);
    }

    private void releaseTicket() {
        int ticket = heldTicket;
        heldTicket = -1;
        if (ticket >= 0) {
            gate.release(ActivityGate.Kind.JOINTS, ticket);
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snap = new LinkedHashMap<>(latest.get());
        snap.put("running", process != null && process.isAlive());
        return snap;
    }

    private void drain() {
        Process p = process;
        if (p == null) {
            return;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("{")) {
                    try {
                        latest.set(mapper.readValue(trimmed, new TypeReference<>() {}));
                    } catch (Exception ignored) {
                        // keep last good frame
                    }
                } else if (!trimmed.isBlank()) {
                    // Never wipe a good joint frame with a log line (import noise, etc.).
                    log.info("[joints] {}", trimmed);
                    Map<String, Object> prev = new LinkedHashMap<>(latest.get());
                    if (!Boolean.TRUE.equals(prev.get("ok"))) {
                        prev.put("ok", false);
                        prev.put("message", trimmed);
                        latest.set(prev);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("joint preview ended: {}", e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
