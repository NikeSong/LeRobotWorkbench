package com.lerobot.workbench.service;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.lerobot.workbench.ros.RosMessageBus;
import com.lerobot.workbench.ros.RosTopics;

@Service
public class JobService {

    private final RosMessageBus bus;

    public JobService(RosMessageBus bus) {
        this.bus = bus;
    }

    public Map<String, Object> startRecord(Map<String, Object> config) {
        return bus.callService(RosTopics.RECORD_START, config);
    }

    public Map<String, Object> stopRecord() {
        return bus.callService(RosTopics.RECORD_STOP, Map.of());
    }

    public Map<String, Object> startTrain(Map<String, Object> config) {
        return bus.callService(RosTopics.TRAIN_START, config);
    }

    public Map<String, Object> stopTrain() {
        return bus.callService(RosTopics.TRAIN_STOP, Map.of());
    }

    public Map<String, Object> startEval(Map<String, Object> config) {
        return bus.callService(RosTopics.EVAL_START, config);
    }

    public Map<String, Object> startRollout(Map<String, Object> config) {
        return bus.callService(RosTopics.ROLLOUT_START, config);
    }

    public Map<String, Object> listDatasets() {
        return bus.callService(RosTopics.DATASET_LIST, Map.of());
    }

    public Map<String, Object> datasetInfo(String repoId) {
        return bus.callService(RosTopics.DATASET_INFO, Map.of("repoId", repoId));
    }

    public Map<String, Object> runAlign(Map<String, Object> config) {
        return bus.callService(RosTopics.ALIGN_RUN, config);
    }

    public Map<String, Object> runAnnotate(Map<String, Object> config) {
        return bus.callService(RosTopics.ANNOTATE_RUN, config);
    }
}
