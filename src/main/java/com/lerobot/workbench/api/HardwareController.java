package com.lerobot.workbench.api;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lerobot.workbench.model.ApiResponse;
import com.lerobot.workbench.service.HardwareService;

@RestController
@RequestMapping("/api/hardware")
public class HardwareController {

    private final HardwareService hardwareService;

    public HardwareController(HardwareService hardwareService) {
        this.hardwareService = hardwareService;
    }

    @PostMapping("/find-ports")
    public ApiResponse findPorts() {
        return ApiResponse.success(hardwareService.findPorts());
    }

    @PostMapping("/calibrate")
    public ApiResponse calibrate(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(hardwareService.calibrate(body));
    }

    @PostMapping("/teleop/start")
    public ApiResponse teleopStart(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.success(hardwareService.teleop(true, body));
    }

    @PostMapping("/teleop/stop")
    public ApiResponse teleopStop(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.success(hardwareService.teleop(false, body));
    }
}
