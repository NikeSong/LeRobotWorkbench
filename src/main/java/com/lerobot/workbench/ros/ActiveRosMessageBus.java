package com.lerobot.workbench.ros;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Runtime-switchable ROS bus. Prefer real rosbridge when available; mock is fallback only.
 */
@Component
@Primary
public class ActiveRosMessageBus implements RosMessageBus {

    private static final Logger log = LoggerFactory.getLogger(ActiveRosMessageBus.class);

    private final MockRosMessageBus mock;
    private final RosbridgeMessageBus rosbridge;
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    private volatile RosMessageBus active;
    private volatile String mode = "mock";

    public ActiveRosMessageBus(MockRosMessageBus mock, RosbridgeMessageBus rosbridge) {
        this.mock = mock;
        this.rosbridge = rosbridge;
        this.active = mock;
    }

    public synchronized Map<String, Object> useMock(String reason) {
        switchTo(mock, "mock");
        return Map.of("ok", true, "mode", "mock", "reason", reason == null ? "manual" : reason);
    }

    public synchronized Map<String, Object> useRosbridge() {
        try {
            switchTo(rosbridge, "rosbridge");
            if (!rosbridge.isConnected()) {
                // roll back
                switchTo(mock, "mock");
                return Map.of(
                        "ok", false,
                        "mode", "mock",
                        "message", "无法连接 rosbridge，已保持 mock。请先「一键部署 ROS」或检查 " + "ws://127.0.0.1:9090");
            }
            return Map.of("ok", true, "mode", "rosbridge", "message", "已连接到真实 ROS（rosbridge）");
        } catch (Exception e) {
            switchTo(mock, "mock");
            return Map.of("ok", false, "mode", "mock", "message", "连接失败: " + e.getMessage());
        }
    }

    public String mode() {
        return mode;
    }

    private void switchTo(RosMessageBus next, String nextMode) {
        if (active != null && active != next) {
            try {
                active.disconnect();
            } catch (Exception e) {
                log.warn("disconnect previous bus failed", e);
            }
        }
        active = next;
        mode = nextMode;
        next.connect();
        // re-bind subscriptions
        for (Subscription s : subscriptions) {
            next.subscribe(s.topic(), s.handler());
        }
        log.info("Active ROS bus → {}", nextMode);
    }

    @Override
    public boolean isConnected() {
        return active != null && active.isConnected();
    }

    @Override
    public String transportName() {
        return mode;
    }

    @Override
    public void publish(String topic, Map<String, Object> message) {
        active.publish(topic, message);
    }

    @Override
    public Map<String, Object> callService(String service, Map<String, Object> request) {
        return active.callService(service, request);
    }

    @Override
    public void subscribe(String topic, Consumer<Map<String, Object>> handler) {
        subscriptions.add(new Subscription(topic, handler));
        active.subscribe(topic, handler);
    }

    @Override
    public void connect() {
        active.connect();
    }

    @Override
    public void disconnect() {
        active.disconnect();
    }

    private record Subscription(String topic, Consumer<Map<String, Object>> handler) {}
}
