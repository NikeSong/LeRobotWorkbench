package com.lerobot.workbench.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs {@code lerobot-teleoperate} locally so the hardware page does not wait
 * on a rosbridge service that nobody advertises.
 */
@Service
public class TeleopService {

    private static final Logger log = LoggerFactory.getLogger(TeleopService.class);

    private final ProcessCommandResolver commands;
    private final CalibrationService calibrationService;
    private final JointPreviewService jointPreviewService;
    private final ActivityGate gate;
    private final AtomicReference<Session> session = new AtomicReference<>();

    public TeleopService(
            ProcessCommandResolver commands,
            CalibrationService calibrationService,
            JointPreviewService jointPreviewService,
            ActivityGate gate) {
        this.commands = commands;
        this.calibrationService = calibrationService;
        this.jointPreviewService = jointPreviewService;
        this.gate = gate;
    }

    public Map<String, Object> start(Map<String, Object> body) {
        Map<String, Object> req = body == null ? Map.of() : body;
        String robotType = str(req.get("robotType"), "so101_follower");
        String teleopType = str(req.get("teleopType"), "so101_leader");
        String robotPort = str(req.get("robotPort"), "");
        String teleopPort = str(req.get("teleopPort"), "");
        String robotId = str(req.get("robotId"), "");
        String teleopId = str(req.get("teleopId"), "");
        if (robotPort.isBlank() || teleopPort.isBlank()) {
            return Map.of("ok", false, "message", "缺少从臂或主臂串口。请先完成配对。");
        }
        robotId = calibrationService.deviceIdFor(robotType, robotPort, robotId);
        teleopId = calibrationService.deviceIdFor(teleopType, teleopPort, teleopId);
        if (!calibrationService.status(robotType, robotId).get("calibrated").equals(Boolean.TRUE)
                || !calibrationService.status(teleopType, teleopId).get("calibrated").equals(Boolean.TRUE)) {
            return Map.of(
                    "ok", false,
                    "message",
                    "还没找到这两条臂的标定文件（从臂 " + robotId + " / 主臂 " + teleopId + "）。请先完成标定。",
                    "robotId", robotId,
                    "teleopId", teleopId);
        }

        String busy = gate.conflict(ActivityGate.Kind.TELEOP);
        if (busy != null) {
            Session existing = session.get();
            if (existing != null && existing.process.isAlive()) {
                Map<String, Object> out = status();
                out.put("ok", true);
                out.put("message", "遥操作已在运行。请直接推动主臂。");
                return out;
            }
            return Map.of("ok", false, "message", busy);
        }
        try {
            jointPreviewService.stop();
        } catch (Exception ignored) {
            // release the serial ports first
        }

        Map<String, Object> deps = commands.ensureWorkbenchPythonDeps();
        if (!Boolean.TRUE.equals(deps.get("ok"))) {
            return Map.of("ok", false, "message", String.valueOf(deps.get("message")));
        }

        java.nio.file.Path script = commands.repoRoot().resolve("workbench/bridge/python/teleoperate_local.py");
        List<String> cmd = commands.pythonScript(script);
        cmd.add("--robot-type");
        cmd.add(robotType);
        cmd.add("--robot-port");
        cmd.add(robotPort);
        cmd.add("--robot-id");
        cmd.add(robotId);
        cmd.add("--teleop-type");
        cmd.add(teleopType);
        cmd.add("--teleop-port");
        cmd.add(teleopPort);
        cmd.add("--teleop-id");
        cmd.add(teleopId);
        cmd.add("--fps");
        cmd.add("30");

        int ticket = -1;
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(commands.repoRoot().toFile());
            Map<String, String> env = commands.processEnvironment();
            env.put("PYTHONUNBUFFERED", "1");
            pb.environment().putAll(env);
            pb.redirectErrorStream(true);
            ticket = gate.acquire(ActivityGate.Kind.TELEOP);
            p = pb.start();
            Session s = new Session(p, String.join(" ", cmd));
            session.set(s);
            int held = ticket;
            Thread t = new Thread(() -> drain(s, held), "teleop-log");
            t.setDaemon(true);
            t.start();
            Map<String, Object> out = status();
            out.put("ok", true);
            out.put("message", "遥操作已启动。推动主臂，从臂应跟随。下方会持续输出日志。");
            out.put("cmd", cmd);
            return out;
        } catch (Exception e) {
            if (p == null && ticket >= 0) {
                gate.release(ActivityGate.Kind.TELEOP, ticket);
            }
            log.error("teleop start failed", e);
            return Map.of("ok", false, "message", "无法启动遥操作: " + e.getMessage());
        }
    }

    public Map<String, Object> stop() {
        Session s = session.get();
        if (s == null || !s.process.isAlive()) {
            Map<String, Object> out = status();
            out.put("ok", true);
            out.put("message", "遥操作没有在运行。");
            return out;
        }
        s.process.destroy();
        try {
            if (!s.process.waitFor(2, TimeUnit.SECONDS)) {
                s.process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            s.process.destroyForcibly();
        }
        s.append("\n[界面] 已停止遥操作\n");
        Map<String, Object> out = status();
        out.put("ok", true);
        out.put("message", "遥操作已停止。");
        return out;
    }

    public Map<String, Object> status() {
        Session s = session.get();
        Map<String, Object> out = new LinkedHashMap<>();
        boolean running = s != null && s.process.isAlive();
        out.put("running", running);
        out.put("logTail", s == null ? "" : s.log.toString());
        if (s != null && !running) {
            try {
                out.put("exitCode", s.process.exitValue());
            } catch (Exception ignored) {
                // still starting
            }
        }
        return out;
    }

    private void drain(Session s, int ticket) {
        try (var in = s.process.getInputStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                s.append(chunk);
                for (String line : chunk.split("\n")) {
                    if (!line.isBlank()) {
                        log.info("[teleop] {}", line.strip());
                    }
                }
            }
        } catch (Exception e) {
            s.append("\n[error] " + e.getMessage() + "\n");
        } finally {
            gate.release(ActivityGate.Kind.TELEOP, ticket);
        }
    }

    private static String str(Object o, String fallback) {
        if (o == null) {
            return fallback;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equals(s) ? fallback : s;
    }

    private static final class Session {
        final Process process;
        final StringBuilder log = new StringBuilder();

        Session(Process process, String cmd) {
            this.process = process;
            log.append("启动: ").append(cmd).append('\n');
        }

        synchronized void append(String text) {
            log.append(text);
            if (log.length() > 20000) {
                log.delete(0, log.length() - 14000);
            }
        }
    }
}
