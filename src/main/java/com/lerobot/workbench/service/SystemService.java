package com.lerobot.workbench.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.lerobot.workbench.config.WorkbenchProperties;
import com.lerobot.workbench.model.PipelineModule;
import com.lerobot.workbench.ros.RosMessageBus;

@Service
public class SystemService {

    private final WorkbenchProperties props;
    private final RosMessageBus bus;

    public SystemService(WorkbenchProperties props, RosMessageBus bus) {
        this.props = props;
        this.bus = bus;
    }

    public Map<String, Object> overview() {
        Path repo = Path.of(props.getRepoRoot()).toAbsolutePath().normalize();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app", "LeRobot Workbench");
        m.put("version", "0.1.0");
        m.put("repoRoot", repo.toString());
        m.put("repoExists", Files.isDirectory(repo.resolve("src/lerobot")));
        m.put("dataHome", props.getDataHome());
        m.put("ros", Map.of(
                "connected", bus.isConnected(),
                "transport", bus.transportName(),
                "mock", props.getRos().isMock(),
                "rosbridgeUrl", props.getRos().getRosbridgeUrl(),
                "namespace", props.getRos().getNamespace()));
        m.put("modules", modules());
        return m;
    }

    public List<PipelineModule> modules() {
        return List.of(
                new PipelineModule("hardware", "硬件准备", "找端口、电机设置、标定、遥操作试跑", "ready", "#/hardware"),
                new PipelineModule("record", "数据采集", "可视化录制回合，写入 LeRobotDataset", "ready", "#/record"),
                new PipelineModule("datasets", "数据集", "浏览、预览、推送 Hub", "ready", "#/datasets"),
                new PipelineModule("train", "训练", "配置策略并启动训练任务", "ready", "#/train"),
                new PipelineModule("eval", "评估 / 部署", "仿真评估与真机 rollout", "ready", "#/eval"),
                new PipelineModule("align", "数据对齐", "多模态时间对齐与整理（规划中）", "planned", "#/align"),
                new PipelineModule("annotate", "智能标注", "自动识别与标注（规划中）", "planned", "#/annotate"),
                new PipelineModule("pipeline", "流水线", "可视化编排完整工具链", "ready", "#/pipeline"));
    }
}
