package com.lerobot.workbench.service;

import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class HardwareService {

    private final CalibrationService calibrationService;
    private final TeleopService teleopService;

    public HardwareService(CalibrationService calibrationService, TeleopService teleopService) {
        this.calibrationService = calibrationService;
        this.teleopService = teleopService;
    }

    public Map<String, Object> findPorts() {
        // Prefer local discovery; ROS service is optional.
        return Map.of("ok", true, "note", "use /api/hardware/ports");
    }

    public Map<String, Object> calibrate(Map<String, Object> body) {
        // Do NOT route through ROS services — run LeRobot calibrate and prove via JSON file.
        return calibrationService.startCalibrate(body);
    }

    public Map<String, Object> teleop(boolean start, Map<String, Object> body) {
        return start ? teleopService.start(body) : teleopService.stop();
    }

    public Map<String, Object> teleopStatus() {
        return teleopService.status();
    }
}
