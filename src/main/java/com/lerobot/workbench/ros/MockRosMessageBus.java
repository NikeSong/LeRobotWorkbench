package com.lerobot.workbench.ros;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-process fallback bus used only when real ROS is unavailable.
 */
@Component
public class MockRosMessageBus implements RosMessageBus {

    private static final Logger log = LoggerFactory.getLogger(MockRosMessageBus.class);

    private final Map<String, List<Consumer<Map<String, Object>>>> subscribers = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mock-ros");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean connected;

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String transportName() {
        return "mock";
    }

    @Override
    public void connect() {
        connected = true;
        log.info("Mock ROS bus connected (fallback only)");
        publish(RosTopics.STATUS, Map.of(
                "state", "ready",
                "transport", "mock",
                "ts", Instant.now().toString()));
    }

    @Override
    public void disconnect() {
        connected = false;
        subscribers.clear();
    }

    @Override
    public void publish(String topic, Map<String, Object> message) {
        List<Consumer<Map<String, Object>>> handlers = subscribers.getOrDefault(topic, List.of());
        for (Consumer<Map<String, Object>> h : handlers) {
            executor.execute(() -> h.accept(message));
        }
    }

    @Override
    public void subscribe(String topic, Consumer<Map<String, Object>> handler) {
        subscribers.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    public Map<String, Object> callService(String service, Map<String, Object> request) {
        log.info("Mock ROS service {} ← {}", service, request);
        return switch (service) {
            case RosTopics.HW_FIND_PORT -> Map.of(
                    "ok", true,
                    "ports", List.of());
            case RosTopics.HW_CALIBRATE -> Map.of(
                    "ok", true,
                    "jobId", UUID.randomUUID().toString(),
                    "message", "Calibration started (mock — 请先部署真实 ROS)");
            case RosTopics.HW_TELEOP -> Map.of(
                    "ok", true,
                    "running", Boolean.TRUE.equals(request.get("start")),
                    "message", "Teleop toggled (mock)");
            case RosTopics.RECORD_START -> startMockJob("record", request);
            case RosTopics.RECORD_STOP -> Map.of("ok", true, "message", "Recording stopped (mock)");
            case RosTopics.TRAIN_START -> startMockJob("train", request);
            case RosTopics.TRAIN_STOP -> Map.of("ok", true, "message", "Training stopped (mock)");
            case RosTopics.EVAL_START -> startMockJob("eval", request);
            case RosTopics.ROLLOUT_START -> startMockJob("rollout", request);
            case RosTopics.DATASET_LIST -> Map.of("ok", true, "datasets", List.of());
            case RosTopics.DATASET_INFO -> Map.of(
                    "ok", true,
                    "repoId", request.getOrDefault("repoId", "local/demo"),
                    "fps", 30,
                    "features", List.of("observation.state", "action"),
                    "codebaseVersion", "v3.0");
            case RosTopics.ALIGN_RUN -> startMockJob("align", request);
            case RosTopics.ANNOTATE_RUN -> startMockJob("annotate", request);
            default -> Map.of("ok", false, "error", "Unknown service: " + service);
        };
    }

    private Map<String, Object> startMockJob(String kind, Map<String, Object> request) {
        String jobId = UUID.randomUUID().toString();
        executor.execute(() -> simulateProgress(kind, jobId));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("jobId", jobId);
        resp.put("kind", kind);
        resp.put("message", kind + " job started (mock)");
        resp.put("echo", request == null ? Map.of() : request);
        return resp;
    }

    private void simulateProgress(String kind, String jobId) {
        for (int i = 0; i <= 100; i += 20) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("jobId", jobId);
            msg.put("kind", kind);
            msg.put("progress", i);
            msg.put("status", i >= 100 ? "done" : "running");
            msg.put("message", kind + " " + i + "%");
            msg.put("ts", Instant.now().toString());
            publish(RosTopics.JOB_PROGRESS, msg);
        }
    }
}
