# LeRobot Workbench

本地可视化流水线工作台：用 **Java（Spring Boot）+ HTML/CSS/JS** 提供点击式界面，**所有业务通讯统一走 ROS**（开发默认 mock 总线，生产可接 `rosbridge`）。

```
浏览器 UI  ──HTTP/STOMP──►  Java Workbench
                              │
                              ▼
                         ROS Message Bus
                     (mock 或 rosbridge ws://)
                              │
                              ▼
                    Python Bridge / LeRobot CLI
```

## 功能模块

| 界面 | 对应能力 | 状态 |
|------|----------|------|
| 硬件准备 | find-port / calibrate / teleoperate | UI + ROS 服务就绪（mock 可点通） |
| 数据采集 | lerobot-record | UI + ROS 服务就绪 |
| 数据集 | 列表 / 详情 | UI + ROS 服务就绪 |
| 训练 | lerobot-train | UI + ROS 服务就绪 |
| 评估 / 部署 | eval / rollout | UI + ROS 服务就绪 |
| 数据对齐 | 多模态对齐整理 | 界面与服务占位，待深化 |
| 智能标注 | 自动识别标注 | 界面与服务占位，待深化 |
| 流水线 | 端到端编排视图 | 可用 |

## 快速启动

要求：JDK 17+、Maven、[uv](https://docs.astral.sh/uv/)。

**先装 Python 依赖（含录制所需的 `datasets` 等），再启 Java：**

```bash
# 在仓库根目录（不是 workbench/）
uv sync --extra workbench
# 或
bash workbench/setup_python.sh
```

只跑 `uv sync`（不带 extra）**不会**安装 `datasets`，录制会立刻报错。Workbench 启动录制时若检测到缺失，会尝试自动执行 `uv sync --extra workbench`。

**Docker 不是必须。** 本机已有 ROS（例如 Pixi + RoboStack 的 `~/work/dev/ros-learn`）时，一键部署会直接用本机 `pixi run ros2 launch rosbridge…`。

```bash
cd workbench
mvn -q spring-boot:run
```

浏览器打开：<http://localhost:8080> → 左侧 **ROS 环境**。

启动逻辑：

1. 探测本机 Pixi ROS 工程（`lerobot.ros.native-pixi-project`，默认 `~/work/dev/ros-learn`）
2. 若工程可用 → 「一键部署」安装/启动本机 rosbridge（可自动 `pixi add ros-humble-rosbridge-suite`）
3. 若没有本机 ROS，但有 Docker → 才走 Docker 备选
4. 都没有 → Mock，并提示配置路径

自定义本机 ROS 工程：

```bash
LEROBOT_ROS_PIXI=/path/to/your/ros-pixi-project mvn spring-boot:run
```

强制 Mock：`LEROBOT_ROS_MOCK=true mvn spring-boot:run`

## 配置

见 `src/main/resources/application.yml`：

- `lerobot.repo-root` — LeRobot 仓库根目录
- `lerobot.ros.mock` — `true` 用内存总线；`false` 连 rosbridge
- `lerobot.ros.rosbridge-url` — 默认 `ws://127.0.0.1:9090`
- `lerobot.ros.namespace` — 默认 `/lerobot`

## ROS 话题 / 服务约定

命名空间：`/lerobot`

| 名称 | 用途 |
|------|------|
| `/lerobot/status` | 总线状态 |
| `/lerobot/log` | 日志流 |
| `/lerobot/job/progress` | 任务进度 |
| `/lerobot/hardware/*` | 找端口 / 标定 / 遥操作 |
| `/lerobot/record/*` | 录制启停 |
| `/lerobot/train/*` | 训练启停 |
| `/lerobot/eval/start` `/rollout/start` | 评估与部署 |
| `/lerobot/dataset/*` | 数据集查询 |
| `/lerobot/align/run` `/annotate/run` | 对齐与标注（规划） |

## 架构说明

- **前端**：静态 SPA（hash 路由），SockJS + STOMP 收实时日志/进度
- **后端**：Spring Boot 编排层，不直接调 LeRobot Python API
- **ROS 层**：`RosMessageBus` 接口；`MockRosMessageBus` / `RosbridgeMessageBus` 可切换
- **桥接**：`bridge/python/lerobot_ros_bridge.py` 将服务调用映射为 `uv run lerobot-*`

## 后续路线

1. 用 `rclpy` 把桥接升级为正式 ROS2 节点（service + publisher）
2. 录制页嵌入相机预览（Rerun / Foxglove / WebRTC）
3. 实现数据对齐（时间戳校验、丢帧修补、特征一致性）
4. 接入 VLM / 关键帧模型做自动标注
5. 流水线页支持拖拽编排与一键复跑
