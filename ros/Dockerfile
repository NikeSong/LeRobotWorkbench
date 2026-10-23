FROM ros:humble-ros-core

RUN apt-get update -qq \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
        ros-humble-rosbridge-suite \
        ros-humble-rosbridge-server \
    && rm -rf /var/lib/apt/lists/*

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 9090
ENTRYPOINT ["/entrypoint.sh"]
