package com.lerobot.workbench.ros;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.lerobot.workbench.config.WorkbenchProperties;
import com.lerobot.workbench.service.RosEnvironmentService;

@Component
public class RosBootstrap {

    private static final Logger log = LoggerFactory.getLogger(RosBootstrap.class);

    private final ActiveRosMessageBus bus;
    private final WorkbenchProperties props;
    private final SimpMessagingTemplate messaging;
    private final RosEnvironmentService environmentService;

    public RosBootstrap(
            ActiveRosMessageBus bus,
            WorkbenchProperties props,
            SimpMessagingTemplate messaging,
            RosEnvironmentService environmentService) {
        this.bus = bus;
        this.props = props;
        this.messaging = messaging;
        this.environmentService = environmentService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!props.getRos().isEnabled()) {
            log.warn("ROS layer disabled by configuration");
            return;
        }

        bus.subscribe(RosTopics.JOB_PROGRESS, msg -> messaging.convertAndSend("/topic/jobs", msg));
        bus.subscribe(RosTopics.LOG, msg -> messaging.convertAndSend("/topic/logs", msg));
        bus.subscribe(RosTopics.STATUS, msg -> messaging.convertAndSend("/topic/status", msg));
        bus.subscribe(RosTopics.RECORD_EVENT, msg -> messaging.convertAndSend("/topic/record", msg));

        Map<String, Object> detect = environmentService.detect();
        boolean bridgeReady = Boolean.TRUE.equals(detect.get("readyForRealRos"));
        boolean forceMock = props.getRos().isMock();

        if (!forceMock && bridgeReady) {
            Map<String, Object> result = bus.useRosbridge();
            log.info("Auto-connect rosbridge: {}", result);
        } else {
            bus.useMock(forceMock
                    ? "config lerobot.ros.mock=true"
                    : "rosbridge not reachable — open UI to one-click deploy");
            log.warn(
                    "Running with MOCK ROS. Deploy via UI or: cd workbench/ros && docker compose up -d --build");
        }
    }
}
