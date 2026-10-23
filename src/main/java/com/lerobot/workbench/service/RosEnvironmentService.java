package com.lerobot.workbench.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lerobot.workbench.config.WorkbenchProperties;

/**
 * Detects native ROS (Pixi/RoboStack/PATH) first; Docker is only an optional fallback.
 */
@Service
public class RosEnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(RosEnvironmentService.class);

    private final WorkbenchProperties props;
    private volatile Process nativeBridgeProcess;
    private volatile String lastDeployLog = "";

    public RosEnvironmentService(WorkbenchProperties props) {
        this.props = props;
    }

    public Map<String, Object> detect() {
        Map<String, Object> out = new LinkedHashMap<>();
        String os = System.getProperty("os.name", "unknown");
        out.put("os", os);

        Map<String, Object> nativeRos = detectNativeRos();
        out.put("native", nativeRos);

        Map<String, Object> docker = probeCommand("docker", "--version");
        out.put("docker", docker);
        Map<String, Object> compose = probeCompose();
        out.put("compose", compose);

        // PATH ros2 (often empty on mac until pixi shell)
        Map<String, Object> ros2Path = probeCommand("ros2", "--help");
        out.put("ros2OnPath", ros2Path);

        boolean bridgeUp = isRosbridgeReachable(props.getRos().getRosbridgeUrl());
        out.put("rosbridge", Map.of(
                "url", props.getRos().getRosbridgeUrl(),
                "reachable", bridgeUp,
                "nativeProcess", nativeBridgeProcess != null && nativeBridgeProcess.isAlive(),
                "container", probeContainer("lerobot-rosbridge")));

        boolean nativeReady = Boolean.TRUE.equals(nativeRos.get("available"));
        boolean dockerReady = Boolean.TRUE.equals(docker.get("available"))
                && Boolean.TRUE.equals(compose.get("available"));

        if (nativeReady) {
            out.put("recommended", "已检测到本机 ROS（Pixi/RoboStack）。Docker 不是必须的；一键部署会启动本机 rosbridge。");
            out.put("deployMode", "native");
        } else if (dockerReady) {
            out.put("recommended", "未找到本机 ROS，可用 Docker 部署 rosbridge（备选方案）。");
            out.put("deployMode", "docker");
        } else {
            out.put("recommended", "未找到本机 ROS，也未找到 Docker。请指向已有 Pixi ROS 工程，或安装 Docker 作为备选。");
            out.put("deployMode", "none");
        }

        List<String> next = new ArrayList<>();
        if (bridgeUp) {
            next.add("rosbridge 已在运行，点「连接真实 ROS」即可。");
        } else if (nativeReady) {
            next.add("点「一键部署 ROS」→ 用本机 Pixi 安装/启动 rosbridge（无需 Docker）。");
        } else if (dockerReady) {
            next.add("点「一键部署 ROS」→ 用 Docker 启动 rosbridge。");
        } else {
            next.add("配置 lerobot.ros.native-pixi-project 指向你的 ROS 工程（例如 ~/work/dev/ros-learn）。");
            next.add("或安装 Docker Desktop 作为备选：" + "https://www.docker.com/products/docker-desktop/");
        }
        out.put("nextSteps", next);
        out.put("readyForRealRos", bridgeUp);
        out.put("dockerRequired", false);
        return out;
    }

    public Map<String, Object> deploy() {
        Map<String, Object> env = detect();
        String mode = String.valueOf(env.getOrDefault("deployMode", "none"));
        if ("native".equals(mode)) {
            return deployNative();
        }
        if ("docker".equals(mode)) {
            return deployDocker();
        }
        Object nativeObj = env.get("native");
        String projectHint = "?";
        if (nativeObj instanceof Map<?, ?> nativeMap && nativeMap.get("project") != null) {
            projectHint = String.valueOf(nativeMap.get("project"));
        }
        return Map.of(
                "ok", false,
                "dockerRequired", false,
                "message", "没有可用的本机 ROS，也没有 Docker。"
                        + " 请把 native-pixi-project 设为你的 ROS 工程路径（当前: "
                        + projectHint
                        + "），或安装 Docker 作为备选。",
                "detect", env);
    }

    public Map<String, Object> stopDeploy() {
        StringBuilder logBuf = new StringBuilder();
        boolean stoppedNative = false;
        if (nativeBridgeProcess != null && nativeBridgeProcess.isAlive()) {
            nativeBridgeProcess.destroy();
            try {
                nativeBridgeProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (nativeBridgeProcess.isAlive()) {
                nativeBridgeProcess.destroyForcibly();
            }
            stoppedNative = true;
            logBuf.append("stopped native rosbridge process\n");
        }
        // Also try docker down if present
        try {
            Path composeDir = resolveComposeDir();
            if (Files.isRegularFile(composeDir.resolve("docker-compose.yml"))) {
                ProcessBuilder pb = new ProcessBuilder(composeCommand("down"));
                pb.directory(composeDir.toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                logBuf.append(readProcessOutput(p, Duration.ofMinutes(1)));
                p.waitFor(60, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            logBuf.append("docker stop skipped: ").append(e.getMessage()).append('\n');
        }
        lastDeployLog = logBuf.toString();
        return Map.of(
                "ok", true,
                "message", stoppedNative ? "已停止本机 rosbridge" : "已尝试停止 ROS 相关进程",
                "logTail", tail(lastDeployLog, 2000));
    }

    public String getLastDeployLog() {
        return lastDeployLog;
    }

    private Map<String, Object> deployNative() {
        Path project = resolveNativeProject();
        StringBuilder logBuf = new StringBuilder();
        try {
            // Ensure rosbridge package exists in the pixi env
            if (!nativeHasRosbridge(project)) {
                logBuf.append("安装 ros-humble-rosbridge-suite 到本机 Pixi 环境…\n");
                ProcessBuilder add = new ProcessBuilder("pixi", "add", "ros-humble-rosbridge-suite");
                add.directory(project.toFile());
                add.redirectErrorStream(true);
                Process addProc = add.start();
                String addOut = readProcessOutput(addProc, Duration.ofMinutes(20));
                logBuf.append(addOut);
                int addCode = addProc.waitFor();
                if (addCode != 0 && !nativeHasRosbridge(project)) {
                    lastDeployLog = logBuf.toString();
                    return Map.of(
                            "ok", false,
                            "mode", "native",
                            "message", "本机 Pixi 安装 rosbridge 失败，请查看日志。",
                            "logTail", tail(logBuf.toString(), 4000));
                }
            }

            if (nativeBridgeProcess != null && nativeBridgeProcess.isAlive()) {
                nativeBridgeProcess.destroy();
                nativeBridgeProcess.waitFor(3, TimeUnit.SECONDS);
            }

            List<String> launch = List.of(
                    "pixi", "run", "ros2", "launch", "rosbridge_server",
                    "rosbridge_websocket_launch.xml", "address:=0.0.0.0", "port:=9090");
            log.info("Starting native rosbridge: {} @ {}", launch, project);
            logBuf.append("启动: ").append(String.join(" ", launch)).append('\n');
            ProcessBuilder pb = new ProcessBuilder(launch);
            pb.directory(project.toFile());
            pb.redirectErrorStream(true);
            nativeBridgeProcess = pb.start();

            // Drain logs in background
            Process proc = nativeBridgeProcess;
            Thread t = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (this) {
                            lastDeployLog = (lastDeployLog + line + "\n");
                            if (lastDeployLog.length() > 20000) {
                                lastDeployLog = lastDeployLog.substring(lastDeployLog.length() - 16000);
                            }
                        }
                        log.info("[rosbridge] {}", line);
                    }
                } catch (Exception ignored) {
                    // process ended
                }
            }, "native-rosbridge-log");
            t.setDaemon(true);
            t.start();

            boolean up = waitForBridge(Duration.ofSeconds(60));
            lastDeployLog = logBuf + "\n" + lastDeployLog;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", up);
            out.put("mode", "native");
            out.put("project", project.toString());
            out.put("rosbridgeReachable", up);
            out.put("dockerRequired", false);
            out.put("message", up
                    ? "已用本机 ROS（Pixi）启动 rosbridge，无需 Docker。"
                    : "已拉起本机 rosbridge 进程，但 60s 内端口未就绪。请看日志或点「重新检测」。");
            out.put("logTail", tail(lastDeployLog, 4000));
            out.put("detect", detect());
            return out;
        } catch (Exception e) {
            log.error("native ROS deploy failed", e);
            lastDeployLog = logBuf + "\n" + e.getMessage();
            return Map.of(
                    "ok", false,
                    "mode", "native",
                    "dockerRequired", false,
                    "message", "本机 ROS 启动失败: " + e.getMessage(),
                    "logTail", lastDeployLog);
        }
    }

    private Map<String, Object> deployDocker() {
        Path composeDir = resolveComposeDir();
        if (!Files.isRegularFile(composeDir.resolve("docker-compose.yml"))) {
            return Map.of("ok", false, "message", "找不到部署文件: " + composeDir);
        }
        try {
            List<String> cmd = composeCommand("up", "-d", "--build");
            log.info("Deploying ROS via Docker: {} (cwd={})", cmd, composeDir);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(composeDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String logText = readProcessOutput(p, Duration.ofMinutes(15));
            lastDeployLog = logText;
            int code = p.waitFor();
            boolean up = isRosbridgeReachable(props.getRos().getRosbridgeUrl())
                    || waitForBridge(Duration.ofSeconds(45));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", code == 0 || up);
            out.put("mode", "docker");
            out.put("exitCode", code);
            out.put("rosbridgeReachable", up);
            out.put("dockerRequired", false);
            out.put("message", up
                    ? "已用 Docker 部署 rosbridge（备选路径）。"
                    : "Docker 部署命令已执行，但尚未检测到 rosbridge。");
            out.put("logTail", tail(logText, 4000));
            out.put("detect", detect());
            return out;
        } catch (Exception e) {
            return Map.of("ok", false, "mode", "docker", "message", "Docker 部署失败: " + e.getMessage());
        }
    }

    private Map<String, Object> detectNativeRos() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> pixi = probeCommand("pixi", "--version");
        m.put("pixi", pixi);

        Path project = resolveNativeProject();
        m.put("project", project.toString());
        boolean hasPixiToml = Files.isRegularFile(project.resolve("pixi.toml"));
        m.put("hasPixiToml", hasPixiToml);

        Path ros2Bin = project.resolve(".pixi/envs/default/bin/ros2");
        boolean hasRos2 = Files.isExecutable(ros2Bin);
        m.put("ros2Binary", ros2Bin.toString());
        m.put("hasRos2", hasRos2);

        boolean hasBridge = hasRos2 && nativeHasRosbridge(project);
        m.put("hasRosbridgePkg", hasBridge);

        boolean available = Boolean.TRUE.equals(pixi.get("available")) && hasPixiToml && hasRos2;
        m.put("available", available);
        m.put("detail", available
                ? ("本机 ROS 就绪: " + project.getFileName()
                        + (hasBridge ? "（已含 rosbridge）" : "（将自动安装 rosbridge）"))
                : ("未找到可用 Pixi ROS 工程: " + project));
        return m;
    }

    private Path resolveNativeProject() {
        String configured = props.getRos().getNativePixiProject();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isRegularFile(p.resolve("pixi.toml"))) {
                return p;
            }
        }
        // Sibling guess: ../ros-learn next to lerobot-main
        Path sibling = Path.of(props.getRepoRoot()).toAbsolutePath().normalize().getParent();
        if (sibling != null) {
            Path guess = sibling.resolve("ros-learn");
            if (Files.isRegularFile(guess.resolve("pixi.toml"))) {
                return guess;
            }
        }
        return Path.of(System.getProperty("user.home"), "work/dev/ros-learn").toAbsolutePath().normalize();
    }

    private boolean nativeHasRosbridge(Path project) {
        try {
            ProcessBuilder pb = new ProcessBuilder("pixi", "run", "ros2", "pkg", "prefix", "rosbridge_server");
            pb.directory(project.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(20, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.exitValue() == 0 && !out.toLowerCase(Locale.ROOT).contains("package not found");
        } catch (Exception e) {
            return false;
        }
    }

    private Path resolveComposeDir() {
        Path fromCwd = Path.of("ros").toAbsolutePath().normalize();
        if (Files.isRegularFile(fromCwd.resolve("docker-compose.yml"))) {
            return fromCwd;
        }
        return Path.of(props.getRepoRoot()).toAbsolutePath().normalize().resolve("workbench/ros");
    }

    private boolean waitForBridge(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (isRosbridgeReachable(props.getRos().getRosbridgeUrl())) {
                return true;
            }
            Thread.sleep(1500);
        }
        return false;
    }

    private Map<String, Object> probeCommand(String bin, String... args) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> cmd = new ArrayList<>();
        cmd.add(bin);
        cmd.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(4, TimeUnit.SECONDS);
            String out = finished ? new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim() : "";
            if (!finished) {
                p.destroyForcibly();
            }
            boolean ok = finished && p.exitValue() == 0;
            if ("ros2".equals(bin)) {
                ok = finished && (p.exitValue() == 0 || out.toLowerCase(Locale.ROOT).contains("usage"));
            }
            m.put("available", ok);
            m.put("detail", ok ? firstLine(out) : "未找到 " + bin);
            return m;
        } catch (Exception e) {
            m.put("available", false);
            m.put("detail", "未找到 " + bin);
            return m;
        }
    }

    private Map<String, Object> probeCompose() {
        Map<String, Object> modern = probeCommand("docker", "compose", "version");
        if (Boolean.TRUE.equals(modern.get("available"))) {
            modern.put("mode", "docker compose");
            return modern;
        }
        Map<String, Object> legacy = probeCommand("docker-compose", "version");
        legacy.put("mode", Boolean.TRUE.equals(legacy.get("available")) ? "docker-compose" : "none");
        return legacy;
    }

    private Map<String, Object> probeContainer(String name) {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "inspect", "-f", "{{.State.Running}}", name);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Map.of("running", false, "detail", "timeout");
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return Map.of("running", "true".equalsIgnoreCase(out), "detail", out);
        } catch (Exception e) {
            return Map.of("running", false, "detail", "n/a");
        }
    }

    private List<String> composeCommand(String... args) {
        List<String> cmd = new ArrayList<>();
        Map<String, Object> compose = probeCompose();
        if ("docker compose".equals(compose.get("mode"))) {
            cmd.add("docker");
            cmd.add("compose");
        } else {
            cmd.add("docker-compose");
        }
        cmd.addAll(List.of(args));
        return cmd;
    }

    public boolean isRosbridgeReachable(String wsUrl) {
        try {
            URI uri = URI.create(wsUrl);
            String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 9090;
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), 800);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static String readProcessOutput(Process p, Duration timeout) throws Exception {
        StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } catch (Exception ignored) {
                // closed
            }
        }, "ros-deploy-log");
        reader.setDaemon(true);
        reader.start();
        boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            sb.append("\n[timeout] exceeded ").append(timeout).append('\n');
        }
        reader.join(2000);
        return sb.toString();
    }

    private static String firstLine(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.lines().findFirst().orElse(s);
    }

    private static String tail(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(s.length() - max);
    }
}
