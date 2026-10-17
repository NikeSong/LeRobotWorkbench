package com.lerobot.workbench.ros;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.lerobot.workbench.config.WorkbenchProperties;

@Component
public class RosBootstrap {

    private static final Logger log = LoggerFactory.getLogger(RosBootstrap.class);

    private final RosMessageBus bus;
    private final WorkbenchProperties props;
    private final SimpMessagingTemplate messaging;

    public RosBootstrap(RosMessageBus bus, WorkbenchProperties props, SimpMessagingTemplate messaging) {
        this.bus = bus;
        this.props = props;
        this.messaging = messaging;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!props.getRos().isEnabled()) {
            log.warn("ROS layer disabled by configuration");
            return;
        }
        bus.connect();
        bus.subscribe(RosTopics.JOB_PROGRESS, msg -> messaging.convertAndSend("/topic/jobs", msg));
        bus.subscribe(RosTopics.LOG, msg -> messaging.convertAndSend("/topic/logs", msg));
        bus.subscribe(RosTopics.STATUS, msg -> messaging.convertAndSend("/topic/status", msg));
        bus.subscribe(RosTopics.RECORD_EVENT, msg -> messaging.convertAndSend("/topic/record", msg));
        log.info("ROS bus ready via {}", bus.transportName());
    }
}
