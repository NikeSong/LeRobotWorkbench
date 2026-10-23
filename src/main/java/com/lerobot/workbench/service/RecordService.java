package com.lerobot.workbench.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs local dataset recording via {@code record_local.py} — no ROS service required.
 */
@Service
public class RecordService {

    private static final Logger log = LoggerFactory.getLogger(RecordService.class);

    private static final Pattern PROGRESS =
            Pattern.compile("WB_PROGRESS saved=(\\d+) total=(\\d+) episode_elapsed=([\\d.]+) episode_limit=([\\d.]+)");
    private static final Pattern SAVED =
            Pattern.compile("WB_SAVED episodes=(\\d+) repo=(\\S+) episode=(\\d+)");
    private static final Pattern REPO = Pattern.compile("WB_REPO (\\S+)");

    private final ProcessCommandResolver commands;
    private final CalibrationService calibrationService;
    private final TeleopService teleopService;
    private final JointPreviewService jointPreviewService;
    private final DatasetLibraryService datasets;
    private final CameraService cameraService;
    private final ReplayService replayService;
    private final ActivityGate gate;
    private final ObjectMapper mapper;
    private final AtomicReference<Session> session = new AtomicReference<>();

    public RecordService(
            ProcessCommandResolver commands,
            CalibrationService calibrationService,
            TeleopService teleopService,
            JointPreviewService jointPreviewService,
            DatasetLibraryService datasets,
            CameraService cameraService,
            ReplayService replayService,
            ActivityGate gate,
            ObjectMapper mapper) {
        this.commands = commands;
        this.calibrationService = calibrationService;
        this.teleopService = teleopService;
        this.jointPreviewService = jointPreviewService;
        this.datasets = datasets;
        this.cameraService = cameraService;
        this.replayService = replayService;
        this.gate = gate;
        this.mapper = mapper;
    }

    public Map<String, Object> start(Map<String, Object> body) {
        Map<String, Object> req = body == null ? Map.of() : body;
        String robotType = str(req.get("robotType"), "so101_follower");
        String teleopType = str(req.get("teleopType"), "so101_leader");
        String robotPort = str(req.get("robotPort"), "");
        String teleopPort = str(req.get("teleopPort"), "");
        String robotId = calibrationService.deviceIdFor(robotType, robotPort, str(req.get("robotId"), ""));
        String teleopId = calibrationService.deviceIdFor(teleopType, teleopPort, str(req.get("teleopId"), ""));
        String singleTask = str(req.get("singleTask"), "").trim();
        String folder;
        try {
            folder = TaskRepoIds.pascal(singleTask);
            if (folder.isBlank()) {
                throw new IllegalArgumentException("请先填写任务描述，用来生成数据文件夹。");
            }
        } catch (IllegalArgumentException e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
        Path root;
        try {
            root = datasets.datasetRoot(str(req.get("datasetRoot"), ""), folder);
        } catch (IllegalArgumentException e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
        String repoId = datasets.repoIdFor(root);
        int numEpisodes = intVal(req.get("numEpisodes"), 10);
        int fps = intVal(req.get("fps"), 30);
        if (numEpisodes < 1 || numEpisodes > 500) {
            return Map.of("ok", false, "message", "回合数需要在 1 到 500 之间。");
        }
        if (fps < 1 || fps > 60) {
            return Map.of("ok", false, "message", "FPS 需要在 1 到 60 之间。");
        }

        if (robotPort.isBlank() || teleopPort.isBlank()) {
            return Map.of("ok", false, "message", "缺少从臂或主臂串口。请先完成硬件配对。");
        }
        if (!Boolean.TRUE.equals(calibrationService.status(robotType, robotId).get("calibrated"))
                || !Boolean.TRUE.equals(calibrationService.status(teleopType, teleopId).get("calibrated"))) {
            return Map.of(
                    "ok",
                    false,
                    "message",
                    "还没找到标定文件（从臂 " + robotId + " / 主臂 " + teleopId + "）。请先完成标定。");
        }

        String busy = gate.conflict(ActivityGate.Kind.RECORD);
        if (busy != null) {
            Session existing = session.get();
            if (existing != null && existing.process.isAlive()) {
                Map<String, Object> out = status();
                out.put("ok", true);
                out.put("message", "录制已在运行。");
                out.put("jobId", existing.jobId);
                return out;
            }
            return Map.of("ok", false, "message", busy);
        }
        try {
            replayService.stop();
        } catch (Exception ignored) {
            // continue
        }
        try {
            jointPreviewService.stop();
        } catch (Exception ignored) {
            // continue
        }
        try {
            teleopService.stop();
        } catch (Exception ignored) {
            // continue
        }
        datasets.remember(root);

        Map<String, Object> deps = commands.ensureWorkbenchPythonDeps();
        if (!Boolean.TRUE.equals(deps.get("ok"))) {
            return Map.of("ok", false, "message", String.valueOf(deps.get("message")));
        }

        cameraService.holdForRecording();
        Path previewDir;
        try {
            previewDir = Files.createTempDirectory("lerobot-cam-preview");
        } catch (Exception e) {
            cameraService.releaseHold();
            return Map.of("ok", false, "message", "无法创建预览目录: " + e.getMessage());
        }
        Path script = commands.repoRoot().resolve("workbench/bridge/python/record_local.py");
        String camerasJson = "{}";
        Object cams = req.get("cameras");
        if (cams != null) {
            try {
                camerasJson = mapper.writeValueAsString(cams);
            } catch (Exception e) {
                cameraService.releaseHold();
                deletePreview(previewDir);
                return Map.of("ok", false, "message", "摄像头配置无效: " + e.getMessage());
            }
        }

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
        cmd.add("--repo-id");
        cmd.add(folder);
        cmd.add("--root");
        cmd.add(root.toString());
        cmd.add("--single-task");
        cmd.add(singleTask);
        cmd.add("--num-episodes");
        cmd.add(String.valueOf(numEpisodes));
        cmd.add("--fps");
        cmd.add(String.valueOf(fps));
        cmd.add("--cameras");
        cmd.add(camerasJson);
        cmd.add("--preview-dir");
        cmd.add(previewDir.toString());

        String jobId = "record-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            Path control = Files.createTempFile("lerobot-record-", ".ctl");
            cmd.add("--control-file");
            cmd.add(control.toString());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(commands.repoRoot().toFile());
            Map<String, String> env = commands.processEnvironment();
            env.put("PYTHONUNBUFFERED", "1");
            pb.environment().putAll(env);
            pb.redirectErrorStream(true);
            int ticket = gate.acquire(ActivityGate.Kind.RECORD);
            Process p = pb.start();
            Session s = new Session(p, String.join(" ", cmd), jobId, repoId, singleTask, control, numEpisodes, previewDir);
            session.set(s);
            Thread t = new Thread(() -> drain(s, ticket), "record-log");
            t.setDaemon(true);
            t.start();
            // Return immediately — early failures show up via /api/record/status poll.
            Map<String, Object> out = status();
            out.put("ok", true);
            out.put("jobId", jobId);
            out.put("cmd", cmd);
            out.put("repoId", repoId);
            out.put(
                    "message",
                    "录制已开始。推动主臂，点「保存本回合」写入这一轮；点「停止并保存」会把当前回合落盘后结束。");
            return out;
        } catch (Exception e) {
            cameraService.releaseHold();
            deletePreview(previewDir);
            log.error("record start failed", e);
            return Map.of("ok", false, "message", "无法启动录制: " + e.getMessage());
        }
    }

    public Map<String, Object> nextEpisode() {
        return signal("next", "已请求保存本回合。");
    }

    public Map<String, Object> stop() {
        Map<String, Object> out = signal("stop", "正在保存当前回合并结束…");
        Session s = session.get();
        if (s == null) {
            return out;
        }
        try {
            if (!s.process.waitFor(120, TimeUnit.SECONDS)) {
                s.process.destroyForcibly();
                s.append("\n[界面] 保存超过 120 秒，进程已强制结束。这一回合的视频可能不完整。\n");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            s.process.destroyForcibly();
        }
        finishDraft(s);
        out = status();
        out.put("ok", true);
        out.put("message", s.savedEpisodes > 0 ? "录制已保存，可以重命名或删除。" : "录制已结束，但没有写完任何回合。");
        return out;
    }

    private Map<String, Object> signal(String command, String message) {
        Session s = session.get();
        if (s == null || !s.process.isAlive()) {
            Map<String, Object> out = status();
            out.put("ok", true);
            out.put("message", "录制没有在运行。");
            return out;
        }
        try {
            Files.writeString(
                    s.controlFile,
                    command + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception e) {
            return Map.of("ok", false, "message", "无法通知录制进程: " + e.getMessage());
        }
        Map<String, Object> out = status();
        out.put("ok", true);
        out.put("message", message);
        return out;
    }

    public Map<String, Object> status() {
        Session s = session.get();
        Map<String, Object> out = new LinkedHashMap<>();
        boolean running = s != null && s.process.isAlive();
        out.put("running", running);
        out.put("logTail", s == null ? "" : s.log.toString());
        if (s != null) {
            out.put("jobId", s.jobId);
            out.put("repoId", s.stampedRepo != null ? s.stampedRepo : s.repoId);
            out.put("progress", s.progress);
            out.put("savedEpisodes", s.savedEpisodes);
            out.put("totalEpisodes", s.totalEpisodes);
            out.put("displayName", s.displayName);
            if (!running) {
                try {
                    out.put("exitCode", s.process.exitValue());
                } catch (Exception ignored) {
                    // ignore
                }
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
                for (String line : chunk.split("\n", -1)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String stripped = line.strip();
                    if (isRecordNoise(stripped)) {
                        continue;
                    }
                    if (!stripped.startsWith("WB_PROGRESS")) {
                        s.append(stripped + "\n");
                        log.info("[record] {}", stripped);
                    }
                    note(s, stripped);
                }
            }
        } catch (Exception e) {
            s.append("\n[error] " + e.getMessage() + "\n");
        }
        finishDraft(s);
        cameraService.releaseHold();
        gate.release(ActivityGate.Kind.RECORD, ticket);
    }

    private static void deletePreview(Path previewDir) {
        if (previewDir == null) {
            return;
        }
        try (var walk = Files.walk(previewDir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best effort
                }
            });
        } catch (Exception ignored) {
            // best effort
        }
    }

    public byte[] previewJpeg(String camera) {
        if (!"front".equals(camera) && !"wrist".equals(camera)) {
            return null;
        }
        Session s = session.get();
        if (s == null || s.previewDir == null) {
            return null;
        }
        Path file = s.previewDir.resolve(camera + ".jpg");
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return Files.readAllBytes(file);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isRecordNoise(String line) {
        if (line.startsWith("objc[")) {
            return true;
        }
        if (line.startsWith("Svt[")) {
            return true;
        }
        if (line.contains("Redirects are currently not supported")) {
            return true;
        }
        if (line.contains("libtorchcodec") || line.contains("Could not load this library")) {
            return true;
        }
        if (line.contains("AVFFrameReceiver") || line.contains("AVFAudioReceiver")) {
            return true;
        }
        return line.startsWith("FFmpeg version ") && line.endsWith(":");
    }

    private void note(Session s, String line) {
        Matcher progress = PROGRESS.matcher(line);
        if (progress.find()) {
            int saved = Integer.parseInt(progress.group(1));
            int total = Math.max(1, Integer.parseInt(progress.group(2)));
            double elapsed = Double.parseDouble(progress.group(3));
            double limit = Math.max(0.1, Double.parseDouble(progress.group(4)));
            double frac = saved + Math.min(1.0, elapsed / limit);
            int pct = (int) Math.round(100.0 * frac / total);
            s.savedEpisodes = saved;
            s.totalEpisodes = total;
            s.progress = Math.max(0, Math.min(s.process.isAlive() ? 99 : 100, pct));
        }
        Matcher saved = SAVED.matcher(line);
        if (saved.find()) {
            s.savedEpisodes = Integer.parseInt(saved.group(1));
            s.stampedRepo = s.repoId;
            datasets.recordEpisode(s.repoId, s.task, Integer.parseInt(saved.group(3)));
        }
        Matcher repo = REPO.matcher(line);
        if (repo.find()) {
            // Python prints the folder token. The library key is the repo id from this request.
            s.stampedRepo = s.repoId;
            datasets.markDraft(s.repoId, s.task);
        }
    }

    private void finishDraft(Session s) {
        synchronized (s) {
            if (s.draftWritten) {
                return;
            }
            s.draftWritten = true;
        }
        if (s.stampedRepo != null) {
            datasets.markDraft(s.stampedRepo, s.task);
            try {
                var listed = datasets.list();
                Object rows = listed.get("datasets");
                if (rows instanceof List<?> list) {
                    for (Object row : list) {
                        if (row instanceof Map<?, ?> map && s.stampedRepo.equals(String.valueOf(map.get("repoId")))) {
                            s.displayName = String.valueOf(map.get("displayName"));
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
                // list is optional
            }
            if (s.savedEpisodes > 0) {
                s.progress = 100;
            }
        }
    }

    private static String str(Object o, String fallback) {
        if (o == null) {
            return fallback;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equals(s) ? fallback : s;
    }

    private static int intVal(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static final class Session {
        final Process process;
        final String jobId;
        final String repoId;
        final String task;
        final Path controlFile;
        final StringBuilder log = new StringBuilder();
        volatile int progress;
        volatile int savedEpisodes;
        volatile int totalEpisodes;
        volatile String stampedRepo;
        volatile String displayName;
        volatile boolean draftWritten;
        final Path previewDir;

        Session(
                Process process,
                String cmd,
                String jobId,
                String repoId,
                String task,
                Path controlFile,
                int totalEpisodes,
                Path previewDir) {
            this.process = process;
            this.jobId = jobId;
            this.repoId = repoId;
            this.task = task;
            this.controlFile = controlFile;
            this.previewDir = previewDir;
            this.totalEpisodes = totalEpisodes;
            log.append("启动: ").append(cmd).append('\n');
        }

        synchronized void append(String text) {
            // Strip C0 controls (keep \n\t) so JSON responses stay valid.
            String clean = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
            log.append(clean);
            if (log.length() > 24000) {
                log.delete(0, log.length() - 16000);
            }
        }
    }
}
