package com.lerobot.workbench.ros;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lerobot.workbench.config.WorkbenchProperties;

/**
 * ROS transport via rosbridge_suite WebSocket protocol.
 * Start rosbridge on the robot/host machine, then set {@code lerobot.ros.mock=false}.
 */
@Component
@ConditionalOnProperty(prefix = "lerobot.ros", name = "mock", havingValue = "false")
public class RosbridgeMessageBus implements RosMessageBus {

    private static final Logger log = LoggerFactory.getLogger(RosbridgeMessageBus.class);

    private final WorkbenchProperties props;
    private final ObjectMapper mapper;
    private final Map<String, CopyOnWriteArrayList<Consumer<Map<String, Object>>>> subscribers =
            new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Map<String, Object>>> pendingServices =
            new ConcurrentHashMap<>();

    private volatile WebSocketClient client;
    private volatile boolean connected;

    public RosbridgeMessageBus(WorkbenchProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public boolean isConnected() {
        return connected && client != null && client.isOpen();
    }

    @Override
    public String transportName() {
        return "rosbridge";
    }

    @Override
    public synchronized void connect() {
        if (isConnected()) {
            return;
        }
        String url = props.getRos().getRosbridgeUrl();
        try {
            client = new WebSocketClient(URI.create(url)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    connected = true;
                    log.info("Connected to rosbridge at {}", url);
                    // re-advertise subscriptions
                    subscribers.keySet().forEach(RosbridgeMessageBus.this::sendSubscribe);
                }

                @Override
                public void onMessage(String message) {
                    handleIncoming(message);
                }

                @Override
                public void onMessage(ByteBuffer bytes) {
                    handleIncoming(new String(bytes.array()));
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    connected = false;
                    log.warn("rosbridge closed: {} {}", code, reason);
                }

                @Override
                public void onError(Exception ex) {
                    log.error("rosbridge error", ex);
                }
            };
            client.connectBlocking(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            connected = false;
            log.error("Failed to connect rosbridge at {}", url, e);
        }
    }

    @Override
    public synchronized void disconnect() {
        connected = false;
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @Override
    public void publish(String topic, Map<String, Object> message) {
        ensureConnected();
        Map<String, Object> frame = Map.of(
                "op", "publish",
                "topic", topic,
                "msg", message);
        sendJson(frame);
    }

    @Override
    public void subscribe(String topic, Consumer<Map<String, Object>> handler) {
        subscribers.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(handler);
        if (isConnected()) {
            sendSubscribe(topic);
        }
    }

    @Override
    public Map<String, Object> callService(String service, Map<String, Object> request) {
        ensureConnected();
        String id = "svc-" + UUID.randomUUID();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pendingServices.put(id, future);
        Map<String, Object> frame = Map.of(
                "op", "call_service",
                "id", id,
                "service", service,
                "args", request == null ? Map.of() : request);
        sendJson(frame);
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingServices.remove(id);
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    private void sendSubscribe(String topic) {
        sendJson(Map.of(
                "op", "subscribe",
                "topic", topic,
                "type", "std_msgs/String"));
    }

    private void ensureConnected() {
        if (!isConnected()) {
            connect();
        }
        if (!isConnected()) {
            throw new IllegalStateException("rosbridge is not connected: " + props.getRos().getRosbridgeUrl());
        }
    }

    private void sendJson(Object payload) {
        try {
            client.send(mapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to send rosbridge frame", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleIncoming(String raw) {
        try {
            Map<String, Object> frame = mapper.readValue(raw, new TypeReference<>() {});
            String op = String.valueOf(frame.get("op"));
            if ("publish".equals(op)) {
                String topic = String.valueOf(frame.get("topic"));
                Object msg = frame.get("msg");
                Map<String, Object> body;
                if (msg instanceof Map<?, ?> m) {
                    body = (Map<String, Object>) m;
                } else if (msg instanceof String s) {
                    // std_msgs/String often wraps JSON in .data
                    body = mapper.readValue(s, new TypeReference<>() {});
                } else {
                    body = Map.of("raw", msg);
                }
                // unwrap common {data: "...json..."} pattern
                if (body.containsKey("data") && body.get("data") instanceof String data) {
                    try {
                        body = mapper.readValue(data, new TypeReference<>() {});
                    } catch (Exception ignored) {
                        // keep as-is
                    }
                }
                for (Consumer<Map<String, Object>> h : subscribers.getOrDefault(topic, new CopyOnWriteArrayList<>())) {
                    h.accept(body);
                }
            } else if ("service_response".equals(op)) {
                String id = String.valueOf(frame.get("id"));
                CompletableFuture<Map<String, Object>> fut = pendingServices.remove(id);
                if (fut != null) {
                    Object values = frame.get("values");
                    if (values instanceof Map<?, ?> m) {
                        fut.complete((Map<String, Object>) m);
                    } else if (values instanceof String s) {
                        fut.complete(mapper.readValue(s, new TypeReference<>() {}));
                    } else {
                        fut.complete(Map.of("ok", frame.getOrDefault("result", true), "values", values));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Bad rosbridge frame: {}", raw, e);
        }
    }
}
