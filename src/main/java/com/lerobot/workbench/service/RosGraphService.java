package com.lerobot.workbench.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import com.lerobot.workbench.ros.ActiveRosMessageBus;

/**
 * Lists live ROS graph nodes via {@code ros2 node list} in the native Pixi env.
 */
@Service
public class RosGraphService {

    private static final Logger log = LoggerFactory.getLogger(RosGraphService.class);

    private final WorkbenchProperties props;
    private final ActiveRosMessageBus bus;
    private final RosEnvironmentService environmentService;

    public RosGraphService(
            WorkbenchProperties props, ActiveRosMessageBus bus, RosEnvironmentService environmentService) {
        this.props = props;
        this.bus = bus;
        this.environmentService = environmentService;
    }

    public Map<String, Object> listNodes() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transport", bus.transportName());
        out.put("connected", bus.isConnected());
        out.put("rosbridgeUrl", props.getRos().getRosbridgeUrl());
        out.put("rosbridgeReachable", environmentService.isRosbridgeReachable(props.getRos().getRosbridgeUrl()));

        Path project = resolvePixiProject();
        out.put("pixiProject", project.toString());

        List<Map<String, Object>> nodes = new ArrayList<>();
        try {
            List<String> names = ros2Lines(project, "node", "list");
            for (String name : names) {
                String clean = stripAnsi(name).trim();
                if (!clean.startsWith("/")) {
                    continue; // drop pixi banners / noise
                }
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("name", clean);
                n.put("kind", classify(clean));
                n.put("status", "online");
                n.put("detail", shortInfo(project, clean));
                nodes.add(n);
            }
            out.put("ok", true);
            out.put("source", "ros2 node list");
        } catch (Exception e) {
            log.warn("ros2 node list failed: {}", e.getMessage());
            out.put("ok", false);
            out.put("message", "无法读取 ROS 节点图: " + e.getMessage()
                    + "。请先在「ROS 环境」一键部署 / 连接真实 ROS。");
            // Still show rosbridge as a logical node if port is up
            if (Boolean.TRUE.equals(out.get("rosbridgeReachable"))) {
                nodes.add(Map.of(
                        "name", "/rosbridge_websocket",
                        "kind", "bridge",
                        "status", "online",
                        "detail", "端口可达（node list 暂不可用）"));
            }
        }
        out.put("nodes", nodes);
        out.put("count", nodes.size());
        return out;
    }

    private String shortInfo(Path project, String nodeName) {
        try {
            List<String> lines = ros2Lines(project, "node", "info", nodeName);
            List<String> keep = new ArrayList<>();
            for (String line : lines) {
                String clean = stripAnsi(line).trim();
                if (clean.isEmpty() || clean.startsWith("Pixi task") || clean.contains("✨")) {
                    continue;
                }
                keep.add(clean);
                if (keep.size() >= 4) {
                    break;
                }
            }
            return String.join(" · ", keep);
        } catch (Exception e) {
            return "";
        }
    }

    private static String stripAnsi(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\u001B\\[[;\\d]*m", "").replace("✨", "").trim();
    }

    private List<String> ros2Lines(Path project, String... ros2Args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("pixi");
        cmd.add("run");
        cmd.add("ros2");
        cmd.addAll(List.of(ros2Args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(project.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean finished = p.waitFor(15, TimeUnit.SECONDS);
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("ros2 timed out");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException(out.isBlank() ? "exit " + p.exitValue() : out.trim());
        }
        List<String> lines = new ArrayList<>();
        for (String line : out.split("\n")) {
            String s = line.trim();
            if (!s.isEmpty()) {
                lines.add(s);
            }
        }
        return lines;
    }

    private Path resolvePixiProject() {
        String configured = props.getRos().getNativePixiProject();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isRegularFile(p.resolve("pixi.toml"))) {
                return p;
            }
        }
        return Path.of(System.getProperty("user.home"), "work/dev/ros-learn").toAbsolutePath().normalize();
    }

    private static String classify(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("rosbridge")) {
            return "bridge";
        }
        if (n.contains("robot_state") || n.contains("controller") || n.contains("joint")) {
            return "robot";
        }
        if (n.contains("rviz") || n.contains("gazebo") || n.contains("gz")) {
            return "sim";
        }
        return "node";
    }
}
