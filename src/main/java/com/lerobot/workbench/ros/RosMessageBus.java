package com.lerobot.workbench.ros;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Abstraction over ROS transport. Implementations:
 * <ul>
 *   <li>{@link MockRosMessageBus} — in-process, no ROS required (default for local UI)</li>
 *   <li>{@link RosbridgeMessageBus} — WebSocket client to rosbridge_suite</li>
 * </ul>
 */
public interface RosMessageBus {

    boolean isConnected();

    String transportName();

    void publish(String topic, Map<String, Object> message);

    /** Call a ROS service; returns JSON-like map response. */
    Map<String, Object> callService(String service, Map<String, Object> request);

    void subscribe(String topic, Consumer<Map<String, Object>> handler);

    void connect();

    void disconnect();
}
