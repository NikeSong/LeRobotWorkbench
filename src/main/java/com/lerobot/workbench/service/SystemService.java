package com.lerobot.workbench.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.lerobot.workbench.config.WorkbenchProperties;
import com.lerobot.workbench.model.PipelineModule;
import com.lerobot.workbench.ros.ActiveRosMessageBus;

@Service
public class SystemService {

    private final WorkbenchProperties props;
    private final ActiveRosMessageBus bus;
    private final RosEnvironmentService rosEnvironmentService;

    public SystemService(
            WorkbenchProperties props, ActiveRosMessageBus bus, RosEnvironmentService rosEnvironmentService) {
        this.props = props;
        this.bus = bus;
        this.rosEnvironmentService = rosEnvironmentService;
    }

    public Map<String, Object> overview() {
        Path repo = Path.of(props.getRepoRoot()).toAbsolutePath().normalize();
        Map<String, Object> detect = rosEnvironmentService.detect();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app", "LeRobot Workbench");
        m.put("version", "0.1.0");
        m.put("repoRoot", repo.toString());
        m.put("repoExists", Files.isDirectory(repo.resolve("src/lerobot")));
        m.put("dataHome", props.getDataHome());
        m.put("ros", Map.of(
                "connected", bus.isConnected(),
                "transport", bus.transportName(),
                "mode", bus.mode(),
                "usingMock", "mock".equals(bus.mode()),
                "forceMockConfig", props.getRos().isMock(),
                "rosbridgeUrl", props.getRos().getRosbridgeUrl(),
                "rosbridgeReachable", detect.get("readyForRealRos"),
                "namespace", props.getRos().getNamespace(),
                "detect", detect));
        m.put("modules", modules());
        return m;
    }

    public List<PipelineModule> modules() {
        return List.of(
                new PipelineModule("hardware", "硬件与设置", "配对、标定、摄像头和保存位置。定好后从主流程收起", "ready", "#/settings"),
                new PipelineModule("record", "数据采集", "填写任务后录制，写入 LeRobotDataset", "ready", "#/record"),
                new PipelineModule("datasets", "数据集", "浏览本机采集和质量评测", "ready", "#/datasets"),
                new PipelineModule("train", "训练", "尚未接入 lerobot-train，提交会被拒绝", "planned", "#/settings"),
                new PipelineModule("eval", "评估 / 部署", "尚未接入，提交会被拒绝", "planned", "#/settings"),
                new PipelineModule("align", "数据对齐", "尚未实现，不会改数据集", "planned", "#/settings"),
                new PipelineModule("annotate", "智能标注", "尚未实现，不会改数据集", "planned", "#/settings"));
    }
}
