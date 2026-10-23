package com.lerobot.workbench.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lerobot.workbench.config.WorkbenchProperties;
import com.lerobot.workbench.ros.ActiveRosMessageBus;
import com.lerobot.workbench.ros.RosTopics;

/**
 * Starts host-side ROS companion nodes: arm (serial) and policy (local/remote model).
 */
@Service
public class RosNodeService {

    private static final Logger log = LoggerFactory.getLogger(RosNodeService.class);

    private final WorkbenchProperties props;
    private final ActiveRosMessageBus bus;
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Map<String, Object> armConfig = new ConcurrentHashMap<>();
    private final Map<String, Object> policyConfig = new ConcurrentHashMap<>();

    public RosNodeService(WorkbenchProperties props, ActiveRosMessageBus bus) {
        this.props = props;
        this.bus = bus;
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transport", bus.transportName());
        out.put("connected", bus.isConnected());
        out.put("arm", Map.of(
                "running", processes.containsKey("arm") && processes.get("arm").isAlive(),
                "config", armConfig));
        out.put("policy", Map.of(
                "running", processes.containsKey("policy") && processes.get("policy").isAlive(),
                "config", policyConfig));
        return out;
    }

    public Map<String, Object> startArmNode(Map<String, Object> body) {
        armConfig.clear();
        armConfig.putAll(body);
        // Publish intended config on ROS so bridges / other tools can react.
        bus.publish(RosTopics.STATUS, Map.of(
                "node", "arm",
                "event", "configure",
                "config", body));

        Path bridge = Path.of(props.getRepoRoot()).toAbsolutePath().normalize()
                .resolve("workbench/bridge/python/arm_ros_node.py");
        if (!bridge.toFile().isFile()) {
            return Map.of(
                    "ok", true,
                    "message", "臂节点配置已写入 ROS。请确保主机桥接脚本可用: " + bridge,
                    "config", body,
                    "startedProcess", false);
        }
        try {
            stop("arm");
            List<String> cmd = new ArrayList<>();
            cmd.add(props.getPythonBin().equals("uv") ? "uv" : props.getPythonBin());
            if (props.getPythonBin().equals("uv")) {
                cmd.add("run");
                cmd.add("python");
            }
            cmd.add(bridge.toString());
            cmd.add("--rosbridge");
            cmd.add(props.getRos().getRosbridgeUrl());
            if (body.get("followerPort") != null) {
                cmd.add("--follower-port");
                cmd.add(String.valueOf(body.get("followerPort")));
            }
            if (body.get("leaderPort") != null) {
                cmd.add("--leader-port");
                cmd.add(String.valueOf(body.get("leaderPort")));
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(Path.of(props.getRepoRoot()).toAbsolutePath().normalize().toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            processes.put("arm", p);
            log.info("Started arm ROS node: {}", cmd);
            return Map.of("ok", true, "message", "机械臂 ROS 节点已启动", "startedProcess", true, "config", body);
        } catch (IOException e) {
            return Map.of("ok", false, "message", "启动臂节点失败: " + e.getMessage(), "config", body);
        }
    }

    public Map<String, Object> startPolicyNode(Map<String, Object> body) {
        policyConfig.clear();
        policyConfig.putAll(body);
        String source = String.valueOf(body.getOrDefault("source", "local"));
        bus.publish(RosTopics.STATUS, Map.of(
                "node", "policy",
                "event", "configure",
                "source", source,
                "config", body));

        Path bridge = Path.of(props.getRepoRoot()).toAbsolutePath().normalize()
                .resolve("workbench/bridge/python/policy_ros_node.py");
        if (!bridge.toFile().isFile()) {
            return Map.of(
                    "ok", true,
                    "message", "策略节点配置已写入 ROS（" + source + "）。脚本: " + bridge,
                    "config", body,
                    "startedProcess", false);
        }
        try {
            stop("policy");
            List<String> cmd = new ArrayList<>();
            if (props.getPythonBin().equals("uv")) {
                cmd.add("uv");
                cmd.add("run");
                cmd.add("python");
            } else {
                cmd.add(props.getPythonBin());
            }
            cmd.add(bridge.toString());
            cmd.add("--rosbridge");
            cmd.add(props.getRos().getRosbridgeUrl());
            cmd.add("--source");
            cmd.add(source);
            if (body.get("path") != null) {
                cmd.add("--path");
                cmd.add(String.valueOf(body.get("path")));
            }
            if (body.get("url") != null) {
                cmd.add("--url");
                cmd.add(String.valueOf(body.get("url")));
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(Path.of(props.getRepoRoot()).toAbsolutePath().normalize().toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            processes.put("policy", p);
            return Map.of("ok", true, "message", "策略 ROS 节点已启动（" + source + "）", "startedProcess", true, "config", body);
        } catch (IOException e) {
            return Map.of("ok", false, "message", "启动策略节点失败: " + e.getMessage());
        }
    }

    public Map<String, Object> stopNode(String name) {
        boolean stopped = stop(name);
        return Map.of("ok", true, "stopped", stopped, "name", name);
    }

    private boolean stop(String name) {
        Process p = processes.remove(name);
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (p.isAlive()) {
                p.destroyForcibly();
            }
            return true;
        }
        return false;
    }
}
