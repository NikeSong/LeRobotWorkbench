package com.lerobot.workbench.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lerobot")
public class WorkbenchProperties {

    private String repoRoot = "..";
    private String dataHome = System.getProperty("user.home") + "/.cache/huggingface/lerobot";
    private String pythonBin = "uv";
    private final Ros ros = new Ros();

    public String getRepoRoot() {
        return repoRoot;
    }

    public void setRepoRoot(String repoRoot) {
        this.repoRoot = repoRoot;
    }

    public String getDataHome() {
        return dataHome;
    }

    public void setDataHome(String dataHome) {
        this.dataHome = dataHome;
    }

    public String getPythonBin() {
        return pythonBin;
    }

    public void setPythonBin(String pythonBin) {
        this.pythonBin = pythonBin;
    }

    public Ros getRos() {
        return ros;
    }

    public static class Ros {
        private boolean enabled = true;
        /** Force mock even when native/docker ROS is available. */
        private boolean mock = false;
        private String rosbridgeUrl = "ws://127.0.0.1:9090";
        private long reconnectMs = 3000;
        private String namespace = "/lerobot";
        /**
         * Pixi + RoboStack project that already has ROS 2 (e.g. ~/work/dev/ros-learn).
         * Docker is only a fallback when this is absent.
         */
        private String nativePixiProject = System.getProperty("user.home") + "/work/dev/ros-learn";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isMock() {
            return mock;
        }

        public void setMock(boolean mock) {
            this.mock = mock;
        }

        public String getRosbridgeUrl() {
            return rosbridgeUrl;
        }

        public void setRosbridgeUrl(String rosbridgeUrl) {
            this.rosbridgeUrl = rosbridgeUrl;
        }

        public long getReconnectMs() {
            return reconnectMs;
        }

        public void setReconnectMs(long reconnectMs) {
            this.reconnectMs = reconnectMs;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getNativePixiProject() {
            return nativePixiProject;
        }

        public void setNativePixiProject(String nativePixiProject) {
            this.nativePixiProject = nativePixiProject;
        }
    }
}
