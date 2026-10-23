package com.lerobot.workbench.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lerobot.workbench.config.WorkbenchProperties;
import com.lerobot.workbench.model.ApiResponse;
import com.lerobot.workbench.ros.ActiveRosMessageBus;
import com.lerobot.workbench.service.RosEnvironmentService;
import com.lerobot.workbench.service.RosGraphService;
import com.lerobot.workbench.service.RosNodeService;

@RestController
@RequestMapping("/api/ros")
public class RosController {

    private final RosEnvironmentService environmentService;
    private final ActiveRosMessageBus bus;
    private final RosNodeService nodeService;
    private final RosGraphService graphService;
    private final WorkbenchProperties props;

    public RosController(
            RosEnvironmentService environmentService,
            ActiveRosMessageBus bus,
            RosNodeService nodeService,
            RosGraphService graphService,
            WorkbenchProperties props) {
        this.environmentService = environmentService;
        this.bus = bus;
        this.nodeService = nodeService;
        this.graphService = graphService;
        this.props = props;
    }

    @GetMapping("/status")
    public ApiResponse status() {
        Map<String, Object> detect = environmentService.detect();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", bus.mode());
        data.put("connected", bus.isConnected());
        data.put("rosbridgeUrl", props.getRos().getRosbridgeUrl());
        data.put("detect", detect);
        data.put("graph", graphService.listNodes());
        data.put("usingMock", "mock".equals(bus.mode()));
        return ApiResponse.success(data);
    }

    @GetMapping("/nodes")
    public ApiResponse graphNodes() {
        // ROS 页只展示通讯层真实节点，不掺业务策略/模型
        Map<String, Object> graph = graphService.listNodes();
        return ApiResponse.success(graph);
    }

    @PostMapping("/detect")
    public ApiResponse detect() {
        return ApiResponse.success(environmentService.detect());
    }

    @PostMapping("/deploy")
    public ApiResponse deploy() {
        return ApiResponse.success(environmentService.deploy());
    }

    @PostMapping("/deploy/stop")
    public ApiResponse stopDeploy() {
        return ApiResponse.success(environmentService.stopDeploy());
    }

    @PostMapping("/connect")
    public ApiResponse connect() {
        Map<String, Object> detect = environmentService.detect();
        if (!Boolean.TRUE.equals(detect.get("readyForRealRos"))) {
            return ApiResponse.fail("尚未检测到 rosbridge。请先「一键部署 ROS」，或确认 ws://127.0.0.1:9090 可访问。");
        }
        return ApiResponse.success(bus.useRosbridge());
    }

    @PostMapping("/disconnect")
    public ApiResponse disconnect() {
        return ApiResponse.success(bus.useMock("user requested fallback"));
    }

    @PostMapping("/nodes/arm")
    public ApiResponse arm(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(nodeService.startArmNode(body));
    }

    @PostMapping("/nodes/policy")
    public ApiResponse policy(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(nodeService.startPolicyNode(body));
    }

    @PostMapping("/nodes/stop")
    public ApiResponse stopNode(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(nodeService.stopNode(String.valueOf(body.getOrDefault("name", "arm"))));
    }
}
