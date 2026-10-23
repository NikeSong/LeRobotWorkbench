package com.lerobot.workbench.service;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lerobot.workbench.config.WorkbenchProperties;

/**
 * Runs guided calibration ({@code calibrate_guided.py}) with live joint Min/Cur/Max,
 * driven from the web UI via stdin — same pattern as RynnRCP configure_so101_web.
 */
@Service
public class CalibrationService {

    private static final Logger log = LoggerFactory.getLogger(CalibrationService.class);
    private static final String EVENT_PREFIX = "WB_CALIB_JSON ";

    private final WorkbenchProperties props;
    private final ProcessCommandResolver commands;
    private final ObjectMapper mapper;
    private final ActivityGate gate;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public CalibrationService(
            WorkbenchProperties props, ProcessCommandResolver commands, ObjectMapper mapper, ActivityGate gate) {
        this.props = props;
        this.commands = commands;
        this.mapper = mapper;
        this.gate = gate;
    }

    public Path calibrationRoot() {
        String env = System.getenv("HF_LEROBOT_CALIBRATION");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        String home = System.getenv("HF_LEROBOT_HOME");
        if (home != null && !home.isBlank()) {
            return Path.of(home).resolve("calibration").toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".cache/huggingface/lerobot/calibration")
                .toAbsolutePath()
                .normalize();
    }

    public Map<String, Object> status(String type, String id) {
        Path file = resolveFile(type, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("id", id);
        out.put("path", file.toString());
        out.put("calibrated", calibrationFileUsable(file));
        if (Files.isRegularFile(file)) {
            try {
                out.put("size", Files.size(file));
                out.put("modified", Files.getLastModifiedTime(file).toInstant().toString());
            } catch (Exception e) {
                out.put("error", e.getMessage());
            }
        }
        Session s = sessions.get(key(type, id));
        if (s != null) {
            out.put("running", s.process.isAlive());
            out.put("logTail", s.log.toString());
            out.putAll(guide(s));
        } else {
            out.put("running", false);
            out.put("logTail", "");
        }
        return out;
    }

    public Map<String, Object> statusForArms(Map<String, Object> follower, Map<String, Object> leader) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("calibrationRoot", calibrationRoot().toString());
        out.put("files", listAll());
        if (follower != null) {
            out.put("follower", status(str(follower.get("type")), str(follower.get("id"))));
        }
        if (leader != null) {
            out.put("leader", status(str(leader.get("type")), str(leader.get("id"))));
        }
        return out;
    }

    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Path root = calibrationRoot();
        if (!Files.isDirectory(root)) {
            return rows;
        }
        for (String kind : List.of("robots", "teleoperators")) {
            Path base = root.resolve(kind);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> types = Files.list(base)) {
                types.filter(Files::isDirectory).forEach(typeDir -> {
                    try (Stream<Path> files = Files.list(typeDir)) {
                        files.filter(f -> f.getFileName().toString().endsWith(".json")).forEach(f -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("kind", kind);
                            row.put("type", typeDir.getFileName().toString());
                            String name = f.getFileName().toString();
                            row.put("id", name.substring(0, name.length() - 5));
                            row.put("path", f.toString());
                            try {
                                row.put("modified", Files.getLastModifiedTime(f).toInstant().toString());
                                row.put("size", Files.size(f));
                            } catch (Exception ignored) {
                                // skip
                            }
                            rows.add(row);
                        });
                    } catch (Exception ignored) {
                        // skip
                    }
                });
            } catch (Exception ignored) {
                // skip
            }
        }
        return rows;
    }

    public Map<String, Object> startCalibrate(Map<String, Object> body) {
        String role = str(body.get("role"));
        String type = str(body.get("type"));
        String id = str(body.getOrDefault("id", "my_arm"));
        String port = str(body.get("port"));
        if (type.isBlank() || port.isBlank()) {
            return Map.of("ok", false, "message", "缺少型号或端口。请先完成主从臂配对。");
        }

        boolean leader = "leader".equalsIgnoreCase(role) || type.contains("leader");
        Path expected = resolveFile(type, id);
        long beforeMtime = Files.isRegularFile(expected) ? expected.toFile().lastModified() : -1L;
        String k = key(type, id);

        String busy = gate.conflict(ActivityGate.Kind.CALIBRATE);
        if (busy != null) {
            Session already = sessions.get(k);
            if (already != null && already.process.isAlive()) {
                Map<String, Object> out = new LinkedHashMap<>(guide(already));
                out.put("ok", true);
                out.put("running", true);
                out.put("message", "标定已在进行中，请按下方步骤操作，并观察关节数值。");
                out.put("expectedFile", expected.toString());
                out.put("beforeMtime", beforeMtime);
                out.put("status", status(type, id));
                return out;
            }
            return Map.of("ok", false, "message", busy);
        }

        Session existing = sessions.get(k);
        if (existing != null && existing.process.isAlive()) {
            Map<String, Object> out = new LinkedHashMap<>(guide(existing));
            out.put("ok", true);
            out.put("running", true);
            out.put("message", "标定已在进行中，请按下方步骤操作，并观察关节数值。");
            out.put("expectedFile", expected.toString());
            out.put("beforeMtime", beforeMtime);
            out.put("status", status(type, id));
            return out;
        }

        Map<String, Object> deps = commands.ensureWorkbenchPythonDeps();
        if (!Boolean.TRUE.equals(deps.get("ok"))) {
            return Map.of("ok", false, "message", String.valueOf(deps.get("message")));
        }

        Path script = commands.repoRoot().resolve("workbench/bridge/python/calibrate_guided.py");
        if (!Files.isRegularFile(script)) {
            return Map.of("ok", false, "message", "找不到标定脚本: " + script);
        }

        List<String> cmd = commands.pythonScript(script);
        cmd.add("--type");
        cmd.add(type);
        cmd.add("--port");
        cmd.add(port);
        cmd.add("--id");
        cmd.add(id);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(commands.repoRoot().toFile());
            Map<String, String> env = commands.processEnvironment();
            env.put("PYTHONUNBUFFERED", "1");
            pb.environment().putAll(env);
            pb.redirectErrorStream(true);
            int ticket = gate.acquire(ActivityGate.Kind.CALIBRATE);
            Process p;
            try {
                p = pb.start();
            } catch (Exception startError) {
                gate.release(ActivityGate.Kind.CALIBRATE, ticket);
                throw startError;
            }
            Session session = new Session(type, id, role, leader, expected, beforeMtime, p, p.getOutputStream());
            sessions.put(k, session);
            append(session, "启动: " + String.join(" ", cmd) + "\n");
            Thread t = new Thread(() -> drain(session, ticket), "calibrate-" + k);
            t.setDaemon(true);
            t.start();

            Map<String, Object> out = new LinkedHashMap<>(guide(session));
            out.put("ok", true);
            out.put("running", true);
            out.put("calibrated", false);
            out.put("expectedFile", expected.toString());
            out.put("beforeMtime", beforeMtime);
            out.put("cmd", cmd);
            out.put("message", "标定已开始。请看关节实时数值，按提示摆臂后点按钮继续。");
            return out;
        } catch (Exception e) {
            log.error("calibrate failed to start", e);
            return Map.of("ok", false, "message", "无法启动标定: " + e.getMessage());
        }
    }

    /** Send Enter (or custom line) to the waiting calibrate process. */
    public Map<String, Object> continueCalibrate(String type, String id, String line) {
        Session s = sessions.get(key(type, id));
        if (s == null || !s.process.isAlive()) {
            return Map.of("ok", false, "message", "没有正在等待的标定进程。");
        }
        String payload = (line == null || line.isBlank()) ? "\n" : line.endsWith("\n") ? line : line + "\n";
        try {
            s.stdin.write(payload.getBytes(StandardCharsets.UTF_8));
            s.stdin.flush();
            append(s, "\n[界面] 已发送继续指令\n");
            Thread.sleep(200);
            Map<String, Object> out = new LinkedHashMap<>(guide(s));
            out.put("ok", true);
            out.put("message", "已继续。请按新的提示操作机械臂。");
            out.put("running", s.process.isAlive());
            out.put("status", status(type, id));
            return out;
        } catch (Exception e) {
            return Map.of("ok", false, "message", "无法向标定进程发送指令: " + e.getMessage());
        }
    }

    public Map<String, Object> stopCalibrate(String type, String id) {
        Session s = sessions.get(key(type, id));
        if (s == null) {
            return Map.of("ok", true, "message", "没有运行中的标定。");
        }
        try {
            s.process.destroyForcibly();
            append(s, "\n[界面] 已停止标定\n");
            return Map.of("ok", true, "message", "已停止标定进程。", "running", false);
        } catch (Exception e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    public Map<String, Object> poll(String type, String id, long beforeMtime) {
        Session s = sessions.get(key(type, id));
        Map<String, Object> st = status(type, id);
        long baseline = beforeMtime;
        if (s != null) {
            baseline = s.beforeMtime;
            st.putAll(guide(s));
        }
        boolean fileReady = Boolean.TRUE.equals(st.get("calibrated"));
        boolean updated = false;
        if (fileReady) {
            try {
                long m = Files.getLastModifiedTime(Path.of(String.valueOf(st.get("path")))).toMillis();
                updated = baseline < 0 || m > baseline;
            } catch (Exception ignored) {
                updated = fileReady;
            }
        }
        st.put("updatedThisRun", updated);
        st.put("ok", true);
        boolean alive = s != null && s.process.isAlive();
        st.put("running", alive);
        if (!alive && s != null) {
            try {
                st.put("exitCode", s.process.exitValue());
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (updated || "saved".equals(s != null ? s.eventPhase : null) || "done".equals(st.get("phase"))) {
            if (updated || "saved".equals(s != null ? s.eventPhase : null)) {
                st.put("phase", "done");
                st.put("title", "标定完成");
                st.put(
                        "instruction",
                        "标定文件已写入。这条臂现在算「已标定」。可以标定另一条臂，或去做遥操作。");
                st.put("primaryAction", null);
                st.put("secondaryAction", null);
            }
        }
        st.put("ts", Instant.now().toString());
        return st;
    }

    private Map<String, Object> guide(Session s) {
        String phase = s.eventPhase != null ? mapEventPhase(s.eventPhase) : detectPhase(s.log.toString());
        s.phase = phase;
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("phase", phase);
        g.put("eventPhase", s.eventPhase);
        g.put("roleLabel", s.leader ? "主臂（你手里操作的那条）" : "从臂（桌上被控制的那条）");
        g.put(
                "steps",
                List.of(
                        Map.of("id", "start", "label", "启动"),
                        Map.of("id", "home", "label", "中间位置"),
                        Map.of("id", "range", "label", "扫行程"),
                        Map.of("id", "done", "label", "完成")));
        g.put(
                "activeStep",
                switch (phase) {
                    case "existing" -> "start";
                    case "home" -> "home";
                    case "range_prompt", "range" -> "range";
                    case "done" -> "done";
                    default -> "start";
                });

        if (s.eventMessage != null && !s.eventMessage.isBlank()) {
            // keep structured instruction below; message available for toast
            g.put("eventMessage", s.eventMessage);
        }

        switch (phase) {
            case "existing" -> {
                g.put("title", "发现已有标定文件");
                g.put(
                        "instruction",
                        "这条臂以前标过。若要沿用旧标定，点「使用已有标定」。若要重新标定，点「重新标定」。"
                                + "\n下方关节数值会实时刷新，方便确认机械臂已连接。");
                g.put("primaryAction", Map.of("id", "reuse", "label", "使用已有标定", "send", "\n"));
                g.put("secondaryAction", Map.of("id", "redo", "label", "重新标定", "send", "c\n"));
            }
            case "home" -> {
                g.put("title", "第 1 步：记录中间位置");
                g.put(
                        "instruction",
                        "请用手转动 "
                                + (s.leader ? "主臂" : "从臂")
                                + " 的每个关节，摆到大概中间位置（不要顶死两端）。"
                                + "\n观察下方 Min / 当前 / Max —— 当前值应随你的动作变化。"
                                + "\n摆好后点「记录中间位置」。"
                                + "\n参考视频：https://huggingface.co/docs/lerobot/main/en/so101#calibration-video");
                g.put("primaryAction", Map.of("id", "middle", "label", "记录中间位置", "send", "\n"));
            }
            case "range_prompt" -> {
                g.put("title", "第 2 步：准备记录行程");
                g.put(
                        "instruction",
                        "中间位置已记下。点「开始记录范围」，然后缓慢推动每个关节从一端到另一端"
                                + "（腕部旋转 wrist_roll 除外，系统会自动填满量程）。");
                g.put("primaryAction", Map.of("id", "begin-range", "label", "开始记录范围", "send", "\n"));
            }
            case "range" -> {
                g.put("title", "第 3 步：扫关节行程（6 关节，推 5 个）");
                g.put(
                        "instruction",
                        "SO-101 共 6 个关节。请依次慢慢转动除 wrist_roll（腕部旋转）外的 5 个关节，从一端到另一端。"
                                + "\nwrist_roll 是连续旋转关节，量程自动记为 0–4095，表格里仍会显示它的当前值。"
                                + "\n观察 Min / Max 应逐渐拉开。全部扫完后点「结束并保存」。");
                g.put("primaryAction", Map.of("id", "finish-range", "label", "结束并保存", "send", "\n"));
            }
            case "done" -> {
                g.put("title", "标定完成");
                g.put("instruction", "标定文件已保存。可以标定另一条臂，或进行遥操作试跑。");
                g.put("primaryAction", null);
            }
            case "error" -> {
                g.put("title", "标定出错");
                g.put("instruction", s.eventMessage != null ? s.eventMessage : "请查看下方日志后重试。");
                g.put("primaryAction", null);
            }
            default -> {
                g.put("title", "正在连接机械臂…");
                g.put(
                        "instruction",
                        "请稍等。连接成功后会显示实时关节数值。"
                                + "\n若长时间停在这里，请检查 USB 是否插好、端口是否选对。");
                g.put("primaryAction", null);
            }
        }
        g.put("logTail", s.log.toString());
        g.put("joints", new LinkedHashMap<>(s.joints));
        g.put("showJoints", !s.joints.isEmpty() && !"done".equals(phase) && !"error".equals(phase));
        return g;
    }

    private static String mapEventPhase(String eventPhase) {
        return switch (eventPhase) {
            case "existing" -> "existing";
            case "middle_position" -> "home";
            case "range_prompt" -> "range_prompt";
            case "recording_range" -> "range";
            case "saved" -> "done";
            case "error" -> "error";
            default -> "starting";
        };
    }

    private static String detectPhase(String logText) {
        String lower = logText.toLowerCase(Locale.ROOT);
        if (lower.contains("calibration saved") || lower.contains("标定已保存")) {
            return "done";
        }
        if (lower.contains("recording_range") || lower.contains("recording positions")) {
            return "range";
        }
        if (lower.contains("range_prompt") || lower.contains("开始记录范围")) {
            return "range_prompt";
        }
        if (lower.contains("middle_position") || lower.contains("middle of its range")) {
            return "home";
        }
        if (lower.contains("existing") || lower.contains("type 'c'")) {
            return "existing";
        }
        return "starting";
    }

    private void drain(Session s, int ticket) {
        StringBuilder lineBuf = new StringBuilder();
        try (var in = s.process.getInputStream()) {
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) != -1) {
                String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                for (int i = 0; i < chunk.length(); i++) {
                    char c = chunk.charAt(i);
                    if (c == '\n') {
                        handleLine(s, lineBuf.toString());
                        lineBuf.setLength(0);
                    } else if (c != '\r') {
                        lineBuf.append(c);
                    }
                }
            }
            if (!lineBuf.isEmpty()) {
                handleLine(s, lineBuf.toString());
            }
            s.process.waitFor(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            append(s, "\n[error] " + e.getMessage() + "\n");
        } finally {
            gate.release(ActivityGate.Kind.CALIBRATE, ticket);
        }
    }

    private boolean calibrationFileUsable(Path file) {
        if (!Files.isRegularFile(file) || file.getFileName().toString().endsWith(".bak")) {
            return false;
        }
        try {
            JsonNode root = mapper.readTree(file.toFile());
            if (root == null || !root.isObject() || root.isEmpty()) {
                return false;
            }
            boolean any = false;
            var fields = root.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                any = true;
                JsonNode motor = entry.getValue();
                if (!motor.hasNonNull("range_min") || !motor.hasNonNull("range_max")) {
                    return false;
                }
                int span = motor.get("range_max").asInt() - motor.get("range_min").asInt();
                int minimum = "wrist_roll".equals(entry.getKey()) ? 1 : 200;
                if (span < minimum) {
                    return false;
                }
            }
            return any;
        } catch (Exception e) {
            return false;
        }
    }

    private void handleLine(Session s, String line) {
        if (line == null) {
            return;
        }
        String trimmed = line.strip();
        if (trimmed.startsWith(EVENT_PREFIX)) {
            parseEvent(s, trimmed.substring(EVENT_PREFIX.length()));
            return;
        }
        if (!trimmed.isBlank()) {
            append(s, trimmed + "\n");
            log.info("[calibrate] {}", trimmed);
        }
    }

    private void parseEvent(Session s, String json) {
        try {
            JsonNode node = mapper.readTree(json);
            String event = text(node, "event");
            if ("phase".equals(event)) {
                s.eventPhase = text(node, "phase");
                s.eventMessage = text(node, "message");
                append(s, "[阶段] " + s.eventPhase + " — " + s.eventMessage + "\n");
            } else if ("ranges".equals(event)) {
                String phase = text(node, "phase");
                if (phase != null && !phase.isBlank()) {
                    s.eventPhase = phase;
                }
                JsonNode ranges = node.get("ranges");
                if (ranges != null && ranges.isObject()) {
                    Map<String, Map<String, Integer>> joints = new LinkedHashMap<>();
                    ranges.fields().forEachRemaining(e -> {
                        JsonNode v = e.getValue();
                        Map<String, Integer> row = new LinkedHashMap<>();
                        row.put("min", v.path("min").asInt());
                        row.put("position", v.path("position").asInt());
                        row.put("max", v.path("max").asInt());
                        joints.put(e.getKey(), row);
                    });
                    s.joints = joints;
                }
            } else if ("log".equals(event)) {
                String msg = text(node, "message");
                String level = text(node, "level");
                append(s, "[" + (level == null ? "info" : level) + "] " + msg + "\n");
            }
        } catch (Exception e) {
            append(s, "[parse] " + e.getMessage() + " | " + json + "\n");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static void append(Session s, String text) {
        s.log.append(text);
        if (s.log.length() > 16000) {
            s.log.delete(0, s.log.length() - 12000);
        }
    }

    private Path resolveFile(String type, String id) {
        String t = type == null ? "" : type;
        String i = id == null || id.isBlank() ? "my_arm" : id;
        boolean teleop = t.toLowerCase(Locale.ROOT).contains("leader")
                || t.toLowerCase(Locale.ROOT).contains("teleop")
                || t.toLowerCase(Locale.ROOT).contains("homunculus");
        String kind = teleop ? "teleoperators" : "robots";
        // LeRobot SO101Follower/Leader 的 Robot.name 是 so_follower / so_leader，
        // 标定 JSON 写在该目录下，而不是 CLI 的 so101_follower 名称。
        String folder = calibrationFolderName(t);
        return calibrationRoot().resolve(kind).resolve(folder).resolve(i + ".json");
    }

    /**
     * Id written into the calibration JSON name. An explicit id wins when that file
     * exists. Otherwise the id is derived from the serial port. Never pick another
     * arm's newest file in the same folder.
     */
    public String deviceIdFor(String type, String port, String preferred) {
        if (preferred != null && !preferred.isBlank()
                && Files.isRegularFile(calibrationDir(type).resolve(preferred + ".json"))) {
            return preferred;
        }
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return generatedDeviceId(type, port);
    }

    public static String generatedDeviceId(String type, String port) {
        boolean leader = type != null && type.toLowerCase(Locale.ROOT).contains("leader");
        return (leader ? "leader_" : "follower_") + portTail(port);
    }

    static String portTail(String port) {
        String p = port == null ? "" : port.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9A-Fa-f]{4,})$").matcher(p);
        if (m.find()) {
            String hex = m.group(1).toUpperCase(Locale.ROOT);
            return "USB" + hex.substring(Math.max(0, hex.length() - 4));
        }
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        String base = slash >= 0 ? p.substring(slash + 1) : p;
        String cleaned = base.replaceAll("[^A-Za-z0-9]+", "_");
        if (cleaned.isBlank()) {
            return "port";
        }
        return cleaned.length() > 12 ? cleaned.substring(cleaned.length() - 12) : cleaned;
    }

    private Path calibrationDir(String type) {
        String t = type == null ? "" : type;
        boolean teleop = t.toLowerCase(Locale.ROOT).contains("leader")
                || t.toLowerCase(Locale.ROOT).contains("teleop")
                || t.toLowerCase(Locale.ROOT).contains("homunculus");
        String kind = teleop ? "teleoperators" : "robots";
        return calibrationRoot().resolve(kind).resolve(calibrationFolderName(t));
    }

    /** Map UI/CLI type → on-disk calibration folder (Robot.name / Teleop.name). */
    static String calibrationFolderName(String type) {
        if (type == null) {
            return "";
        }
        String t = type.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "so101_follower", "so100_follower", "so_follower" -> "so_follower";
            case "so101_leader", "so100_leader", "so_leader" -> "so_leader";
            default -> type;
        };
    }

    private static String key(String type, String id) {
        return type + "::" + id;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static final class Session {
        final String type;
        final String id;
        final String role;
        final boolean leader;
        final Path expected;
        final long beforeMtime;
        final Process process;
        final OutputStream stdin;
        final StringBuilder log = new StringBuilder();
        volatile String phase = "starting";
        volatile String eventPhase = "connecting";
        volatile String eventMessage = "";
        volatile Map<String, Map<String, Integer>> joints = new LinkedHashMap<>();

        Session(
                String type,
                String id,
                String role,
                boolean leader,
                Path expected,
                long beforeMtime,
                Process process,
                OutputStream stdin) {
            this.type = type;
            this.id = id;
            this.role = role;
            this.leader = leader;
            this.expected = expected;
            this.beforeMtime = beforeMtime;
            this.process = process;
            this.stdin = stdin;
        }
    }
}
