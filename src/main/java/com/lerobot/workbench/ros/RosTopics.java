package com.lerobot.workbench.ros;

/**
 * Canonical ROS topic / service names used by Workbench ↔ Python bridge.
 * All LeRobot operations are expressed as ROS messages under this namespace.
 */
public final class RosTopics {

    private RosTopics() {}

    public static final String NS = "/lerobot";

    // Status / telemetry
    public static final String STATUS = NS + "/status";
    public static final String LOG = NS + "/log";
    public static final String JOB_PROGRESS = NS + "/job/progress";

    // Hardware
    public static final String HW_FIND_PORT = NS + "/hardware/find_port";
    public static final String HW_CALIBRATE = NS + "/hardware/calibrate";
    public static final String HW_TELEOP = NS + "/hardware/teleop";
    public static final String HW_PORTS = NS + "/hardware/ports";

    // Record
    public static final String RECORD_START = NS + "/record/start";
    public static final String RECORD_STOP = NS + "/record/stop";
    public static final String RECORD_EVENT = NS + "/record/event";

    // Train
    public static final String TRAIN_START = NS + "/train/start";
    public static final String TRAIN_STOP = NS + "/train/stop";

    // Eval / rollout
    public static final String EVAL_START = NS + "/eval/start";
    public static final String ROLLOUT_START = NS + "/rollout/start";

    // Dataset
    public static final String DATASET_LIST = NS + "/dataset/list";
    public static final String DATASET_INFO = NS + "/dataset/info";

    // Future: alignment & annotation
    public static final String ALIGN_RUN = NS + "/align/run";
    public static final String ANNOTATE_RUN = NS + "/annotate/run";
}
