const Live = {
  client: null,
  connected: false,

  connect(onEvent) {
    if (typeof SockJS === "undefined" || typeof Stomp === "undefined") {
      console.warn("SockJS/Stomp not loaded; live updates disabled");
      return;
    }
    const socket = new SockJS("/ws");
    const client = Stomp.over(socket);
    client.debug = null;
    client.connect({}, () => {
      this.connected = true;
      this.client = client;
      ["jobs", "logs", "status", "record"].forEach((ch) => {
        client.subscribe(`/topic/${ch}`, (frame) => {
          try {
            onEvent(ch, JSON.parse(frame.body));
          } catch (e) {
            onEvent(ch, { raw: frame.body });
          }
        });
      });
    });
  },
};
