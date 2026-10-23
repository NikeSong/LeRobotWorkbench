#!/usr/bin/env bash
set -euo pipefail
source /opt/ros/humble/setup.bash
exec ros2 launch rosbridge_server rosbridge_websocket_launch.xml address:=0.0.0.0 port:=9090
