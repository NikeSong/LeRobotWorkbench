package com.lerobot.workbench.api;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lerobot.workbench.model.ApiResponse;
import com.lerobot.workbench.service.CalibrationService;
import com.lerobot.workbench.service.CameraService;
import com.lerobot.workbench.service.HardwareService;
import com.lerobot.workbench.service.JointPreviewService;
import com.lerobot.workbench.service.PortDiscoveryService;

@RestController
@RequestMapping("/api/hardware")
public class HardwareController {

    private final HardwareService hardwareService;
    private final PortDiscoveryService portDiscoveryService;
    private final CalibrationService calibrationService;
    private final CameraService cameraService;
    private final JointPreviewService jointPreviewService;

    public HardwareController(
            HardwareService hardwareService,
            PortDiscoveryService portDiscoveryService,
            CalibrationService calibrationService,
            CameraService cameraService,
            JointPreviewService jointPreviewService) {
        this.hardwareService = hardwareService;
        this.portDiscoveryService = portDiscoveryService;
        this.calibrationService = calibrationService;
        this.cameraService = cameraService;
        this.jointPreviewService = jointPreviewService;
    }

    @GetMapping("/ports")
    public ApiResponse ports() {
        return ApiResponse.success(Map.of("ports", portDiscoveryService.listPorts()));
    }

    @PostMapping("/ports/identify/begin")
    public ApiResponse identifyBegin() {
        return ApiResponse.success(portDiscoveryService.beginIdentify());
    }

    @PostMapping("/ports/identify/finish")
    public ApiResponse identifyFinish() {
        return ApiResponse.success(portDiscoveryService.finishIdentify());
    }

    @PostMapping("/ports/identify/reconnect")
    public ApiResponse identifyReconnect(@RequestBody(required = false) Map<String, Object> body) {
        String expected = body == null ? null : String.valueOf(body.getOrDefault("path", ""));
        if (expected != null && expected.isBlank()) {
            expected = null;
        }
        return ApiResponse.success(portDiscoveryService.confirmReconnect(expected));
    }

    @PostMapping("/find-ports")
    public ApiResponse findPorts() {
        return ApiResponse.success(Map.of("ports", portDiscoveryService.listPorts()));
    }

    @PostMapping("/calibrate")
    public ApiResponse calibrate(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(hardwareService.calibrate(body));
    }

    @GetMapping("/calibration/status")
    public ApiResponse calibrationStatus(
            @RequestParam(required = false) String followerType,
            @RequestParam(required = false) String followerId,
            @RequestParam(required = false) String leaderType,
            @RequestParam(required = false) String leaderId) {
        Map<String, Object> follower =
                followerType == null ? null : Map.of("type", followerType, "id", followerId == null ? "" : followerId);
        Map<String, Object> leader =
                leaderType == null ? null : Map.of("type", leaderType, "id", leaderId == null ? "" : leaderId);
        return ApiResponse.success(calibrationService.statusForArms(follower, leader));
    }

    @GetMapping("/calibration/poll")
    public ApiResponse calibrationPoll(
            @RequestParam String type,
            @RequestParam String id,
            @RequestParam(defaultValue = "-1") long beforeMtime) {
        return ApiResponse.success(calibrationService.poll(type, id, beforeMtime));
    }

    @PostMapping("/calibration/continue")
    public ApiResponse calibrationContinue(@RequestBody Map<String, Object> body) {
        String type = String.valueOf(body.getOrDefault("type", ""));
        String id = String.valueOf(body.getOrDefault("id", ""));
        String send = body.get("send") == null ? "\n" : String.valueOf(body.get("send"));
        return ApiResponse.success(calibrationService.continueCalibrate(type, id, send));
    }

    @PostMapping("/calibration/stop")
    public ApiResponse calibrationStop(@RequestBody Map<String, Object> body) {
        String type = String.valueOf(body.getOrDefault("type", ""));
        String id = String.valueOf(body.getOrDefault("id", ""));
        return ApiResponse.success(calibrationService.stopCalibrate(type, id));
    }

    @PostMapping("/joints/start")
    public ApiResponse jointsStart(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(jointPreviewService.start(body));
    }

    @PostMapping("/joints/stop")
    public ApiResponse jointsStop() {
        return ApiResponse.success(jointPreviewService.stop());
    }

    @GetMapping("/joints")
    public ApiResponse joints() {
        return ApiResponse.success(jointPreviewService.snapshot());
    }

    @PostMapping("/teleop/start")
    public ApiResponse teleopStart(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.success(hardwareService.teleop(true, body));
    }

    @PostMapping("/teleop/stop")
    public ApiResponse teleopStop(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.success(hardwareService.teleop(false, body));
    }

    @GetMapping("/teleop/status")
    public ApiResponse teleopStatus() {
        return ApiResponse.success(hardwareService.teleopStatus());
    }

    @GetMapping("/cameras")
    public ApiResponse cameras() {
        return ApiResponse.success(cameraService.listCameras());
    }

    @GetMapping("/cameras/selection")
    public ApiResponse cameraSelection() {
        return ApiResponse.success(cameraService.selection());
    }

    @PostMapping("/cameras/select")
    public ApiResponse selectCameras(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = cameraService.select(body);
        if (Boolean.FALSE.equals(result.get("ok"))) {
            return ApiResponse.fail(String.valueOf(result.get("message")));
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/cameras/{index}/frame.jpg")
    public ResponseEntity<byte[]> cameraFrame(@PathVariable int index) {
        try {
            byte[] jpeg = cameraService.frame(index);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .header("Cache-Control", "no-store")
                    .body(jpeg);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
