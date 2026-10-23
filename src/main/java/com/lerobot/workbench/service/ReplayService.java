package com.lerobot.workbench.service;

import java.nio.charset.StandardCharsets;
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

@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final ProcessCommandResolver commands;
    private final DatasetLibraryService datasets;
    private final JointPreviewService jointPreviewService;
    private final TeleopService teleopService;
    private final ObjectMapper mapper;
    private final ActivityGate gate;
    private final AtomicReference<Session> session = new AtomicReference<>();

    public ReplayService(
            ProcessCommandResolver commands,
            DatasetLibraryService datasets,
            JointPreviewService jointPreviewService,
            TeleopService teleopService,
            ActivityGate gate,
            ObjectMapper mapper) {
        this.commands = commands;
        this.datasets = datasets;
        this.jointPreviewService = jointPreviewService;
        this.teleopService = teleopService;
        this.gate = gate;
        this.mapper = mapper;
    }

    public Map<String, Object> start(Map<String, Object> body) {
        String repoId = str(body.get("repoId"));
        String port = str(body.get("robotPort"));
        String robotId = str(body.get("robotId"));
        String robotType = str(body.getOrDefault("robotType", "so101_follower"));
        int episode = body.get("episode") instanceof Number n ? n.intValue() : 0;
        if (port.isBlank() || robotId.isBlank()) {
            return Map.of("ok", false, "message", "请先在硬件准备里配对从臂，重放需要它按原来的动作再走一遍。");
        }
        Path root;
        try {
            root = datasets.require(repoId);
        } catch (RuntimeException e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
        Map<String, Object> info = datasets.list().get("datasets") instanceof List<?> rows
                ? rows.stream()
                        .filter(row -> row instanceof Map<?, ?> map && repoId.equals(String.valueOf(map.get("repoId"))))
                        .findFirst()
                        .map(row -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> map = (Map<String, Object>) row;
                            return map;
                        })
                        .orElse(Map.of())
                : Map.of();
        if (Boolean.TRUE.equals(info.get("empty"))) {
            return Map.of("ok", false, "message", "这条采集没有写完回合，不能重放。请删掉后重新录。");
        }
        String busy = gate.conflict(ActivityGate.Kind.REPLAY);
        if (busy != null) {
            return Map.of("ok", false, "message", busy);
        }
        stop();
        try {
            jointPreviewService.stop();
            teleopService.stop();
        } catch (Exception ignored) {
            // ports must be free
        }
        List<String> cmd = commands.pythonScript(commands.repoRoot().resolve("workbench/bridge/python/replay_local.py"));
        cmd.add("--root");
        cmd.add(root.toString());
        cmd.add("--episode");
        cmd.add(String.valueOf(episode));
        cmd.add("--robot-port");
        cmd.add(port);
        cmd.add("--robot-id");
        cmd.add(robotId);
        cmd.add("--robot-type");
        cmd.add(robotType);
        int ticket = -1;
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(commands.repoRoot().toFile());
            pb.environment().putAll(commands.processEnvironment());
            pb.redirectErrorStream(true);
            ticket = gate.acquire(ActivityGate.Kind.REPLAY);
            process = pb.start();
            Session s = new Session(process, repoId, info);
            session.set(s);
            int held = ticket;
            Thread t = new Thread(() -> drain(s, held), "replay-log");
            t.setDaemon(true);
            t.start();
            Map<String, Object> out = status();
            out.put("ok", true);
            out.put("message", "开始重放。从臂会按采集时的动作再走一遍，画面显示在关节面板。");
            return out;
        } catch (Exception e) {
            if (process == null && ticket >= 0) {
                gate.release(ActivityGate.Kind.REPLAY, ticket);
            }
            return Map.of("ok", false, "message", "无法启动重放: " + e.getMessage());
        }
    }

    public Map<String, Object> stop() {
        Session s = session.get();
        if (s != null && s.process.isAlive()) {
            s.process.destroy();
            try {
                if (!s.process.waitFor(3, TimeUnit.SECONDS)) {
                    s.process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                s.process.destroyForcibly();
            }
        }
        Map<String, Object> out = status();
        out.put("ok", true);
        return out;
    }

    public Map<String, Object> status() {
        Session s = session.get();
        Map<String, Object> out = new LinkedHashMap<>();
        boolean running = s != null && s.process.isAlive();
        out.put("running", running);
        if (s != null) {
            out.put("repoId", s.repoId);
            out.put("displayName", s.displayName);
            out.put("videos", s.videos);
            out.put("frame", s.frame);
            out.put("logTail", s.logText());
        }
        return out;
    }

    private void drain(Session s, int ticket) {
        try (var in = s.process.getInputStream()) {
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) != -1) {
                String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                for (String line : chunk.split("\n")) {
                    String stripped = line.strip();
                    if (stripped.startsWith("WB_FRAME ")) {
                        try {
                            s.frame = mapper.readValue(stripped.substring("WB_FRAME ".length()), new TypeReference<>() {});
                        } catch (Exception ignored) {
                            // keep last frame
                        }
                    } else if (!stripped.isBlank()) {
                        s.append(stripped + "\n");
                        log.info("[replay] {}", stripped);
                    }
                }
            }
        } catch (Exception e) {
            s.append("重放读取中断: " + e.getMessage() + "\n");
        } finally {
            gate.release(ActivityGate.Kind.REPLAY, ticket);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static final class Session {
        final Process process;
        final String repoId;
        final String displayName;
        final List<String> videos;
        volatile Map<String, Object> frame = Map.of();
        final StringBuilder log = new StringBuilder();

        @SuppressWarnings("unchecked")
        Session(Process process, String repoId, Map<String, Object> info) {
            this.process = process;
            this.repoId = repoId;
            this.displayName = String.valueOf(info.getOrDefault("displayName", repoId));
            Object videos = info.get("videos");
            this.videos = videos instanceof List<?> list ? new ArrayList<>((List<String>) list) : List.of();
        }

        synchronized void append(String text) {
            log.append(text);
            if (log.length() > 8000) {
                log.delete(0, log.length() - 4000);
            }
        }

        synchronized String logText() {
            return log.toString();
        }
    }
}
