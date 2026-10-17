package com.lerobot.workbench.service;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.lerobot.workbench.ros.RosMessageBus;
import com.lerobot.workbench.ros.RosTopics;

@Service
public class HardwareService {

    private final RosMessageBus bus;

    public HardwareService(RosMessageBus bus) {
        this.bus = bus;
    }

    public Map<String, Object> findPorts() {
        return bus.callService(RosTopics.HW_FIND_PORT, Map.of());
    }

    public Map<String, Object> calibrate(Map<String, Object> body) {
        return bus.callService(RosTopics.HW_CALIBRATE, body);
    }

    public Map<String, Object> teleop(boolean start, Map<String, Object> body) {
        Map<String, Object> req = new java.util.LinkedHashMap<>(body == null ? Map.of() : body);
        req.put("start", start);
        return bus.callService(RosTopics.HW_TELEOP, req);
    }
}
