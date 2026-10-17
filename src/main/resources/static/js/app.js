const state = {
  overview: null,
  jobProgress: 0,
  currentJob: null,
};

function $(sel) {
  return document.querySelector(sel);
}

function $all(sel) {
  return [...document.querySelectorAll(sel)];
}

function toast(msg) {
  const el = $("#toast");
  el.textContent = msg;
  el.classList.add("show");
  setTimeout(() => el.classList.remove("show"), 2600);
}

function appendLog(line) {
  const box = $("#live-log");
  if (!box) return;
  const ts = new Date().toLocaleTimeString();
  box.textContent += `[${ts}] ${line}\n`;
  box.scrollTop = box.scrollHeight;
}

function setProgress(pct) {
  state.jobProgress = pct;
  const bar = $("#job-progress");
  if (bar) bar.style.width = `${pct}%`;
  const label = $("#job-progress-label");
  if (label) label.textContent = `${pct}%`;
}

function route() {
  const hash = location.hash.replace("#/", "") || "home";
  $all(".view").forEach((v) => v.classList.toggle("active", v.dataset.view === hash));
  $all(".nav button").forEach((b) => b.classList.toggle("active", b.dataset.route === hash));
  const titles = {
    home: ["工作台总览", "用界面完成 LeRobot 全流程，底层统一走 ROS 通讯。"],
    hardware: ["硬件准备", "找端口、标定、遥操作——全部一键触发。"],
    record: ["数据采集", "配置任务与回合后开始录制，进度实时回传。"],
    datasets: ["数据集", "浏览本地 / Hub 数据集元信息。"],
    train: ["策略训练", "选择策略与数据集，启动训练任务。"],
    eval: ["评估与部署", "仿真评估或真机 rollout。"],
    align: ["数据对齐", "多模态时间对齐与整理（后续深化）。"],
    annotate: ["智能标注", "自动识别与标注流水线（后续深化）。"],
    pipeline: ["流水线编排", "一眼看清从硬件到部署的完整链路。"],
  };
  const t = titles[hash] || titles.home;
  $("#page-title").textContent = t[0];
  $("#page-desc").textContent = t[1];
}

async function loadOverview() {
  const res = await Api.get("/api/system/overview");
  state.overview = res.data;
  const ros = res.data.ros || {};
  const dot = $("#ros-dot");
  const label = $("#ros-label");
  if (ros.connected) {
    dot.className = "dot on";
    label.textContent = `ROS · ${ros.transport}${ros.mock ? " (mock)" : ""}`;
  } else {
    dot.className = "dot warn";
    label.textContent = "ROS 未连接";
  }
  $("#stat-repo").textContent = res.data.repoExists ? "已链接" : "未找到";
  $("#stat-transport").textContent = ros.transport || "-";
  $("#stat-modules").textContent = (res.data.modules || []).length;

  const grid = $("#module-grid");
  grid.innerHTML = "";
  (res.data.modules || []).forEach((m) => {
    const el = document.createElement("article");
    el.className = "panel module-tile";
    el.innerHTML = `
      <div class="badge ${m.status}">${m.status === "ready" ? "可用" : "规划中"}</div>
      <h3 style="margin-top:12px">${m.title}</h3>
      <p class="desc">${m.description}</p>`;
    el.onclick = () => {
      location.hash = m.route;
    };
    grid.appendChild(el);
  });
}

async function loadDatasets() {
  const res = await Api.get("/api/datasets");
  const rows = (res.data.datasets || []).map(
    (d) => `<tr>
      <td>${d.repoId}</td>
      <td>${d.episodes}</td>
      <td>${d.fps}</td>
      <td>${d.frames}</td>
      <td><button class="btn ghost" data-ds="${d.repoId}">详情</button></td>
    </tr>`
  );
  $("#dataset-tbody").innerHTML = rows.join("") || `<tr><td colspan="5">暂无数据集</td></tr>`;
  $all("#dataset-tbody [data-ds]").forEach((btn) => {
    btn.onclick = async () => {
      const id = btn.dataset.ds.replace("/", "--");
      const info = await Api.get(`/api/datasets/${encodeURIComponent(id)}`);
      toast(`features: ${(info.data.features || []).join(", ")}`);
      appendLog(`dataset info ${JSON.stringify(info.data)}`);
    };
  });
}

function formToObject(form) {
  const data = {};
  new FormData(form).forEach((v, k) => {
    if (v !== "" && !Number.isNaN(Number(v)) && String(Number(v)) === String(v)) {
      data[k] = Number(v);
    } else if (v === "true" || v === "false") {
      data[k] = v === "true";
    } else {
      data[k] = v;
    }
  });
  return data;
}

function bindActions() {
  $("#btn-find-ports").onclick = async () => {
    const res = await Api.post("/api/hardware/find-ports");
    const ports = res.data.ports || [];
    $("#ports-out").textContent = ports.map((p) => `${p.path}  (${p.hint || "-"})`).join("\n") || "无端口";
    toast("已扫描串口");
    appendLog("find-ports → " + JSON.stringify(ports));
  };

  $("#form-calibrate").onsubmit = async (e) => {
    e.preventDefault();
    const body = formToObject(e.target);
    const res = await Api.post("/api/hardware/calibrate", body);
    toast(res.data.message || "标定已启动");
    appendLog("calibrate → " + JSON.stringify(res.data));
  };

  $("#btn-teleop-start").onclick = async () => {
    const res = await Api.post("/api/hardware/teleop/start", formToObject($("#form-teleop")));
    toast(res.data.message || "遥操作已启动");
  };
  $("#btn-teleop-stop").onclick = async () => {
    const res = await Api.post("/api/hardware/teleop/stop", {});
    toast(res.data.message || "遥操作已停止");
  };

  $("#form-record").onsubmit = async (e) => {
    e.preventDefault();
    setProgress(0);
    const res = await Api.post("/api/record/start", formToObject(e.target));
    state.currentJob = res.data.jobId;
    toast("录制任务已启动");
    appendLog("record start → " + JSON.stringify(res.data));
  };
  $("#btn-record-stop").onclick = async () => {
    const res = await Api.post("/api/record/stop");
    toast(res.data.message || "已停止录制");
  };

  $("#form-train").onsubmit = async (e) => {
    e.preventDefault();
    setProgress(0);
    const res = await Api.post("/api/train/start", formToObject(e.target));
    state.currentJob = res.data.jobId;
    toast("训练任务已启动");
    appendLog("train start → " + JSON.stringify(res.data));
  };
  $("#btn-train-stop").onclick = async () => {
    await Api.post("/api/train/stop");
    toast("训练已请求停止");
  };

  $("#form-eval").onsubmit = async (e) => {
    e.preventDefault();
    setProgress(0);
    const body = formToObject(e.target);
    const path = body.mode === "rollout" ? "/api/rollout/start" : "/api/eval/start";
    const res = await Api.post(path, body);
    state.currentJob = res.data.jobId;
    toast((body.mode === "rollout" ? "Rollout" : "评估") + "已启动");
    appendLog(`${body.mode} → ` + JSON.stringify(res.data));
  };

  $("#form-align").onsubmit = async (e) => {
    e.preventDefault();
    setProgress(0);
    const res = await Api.post("/api/align/run", formToObject(e.target));
    state.currentJob = res.data.jobId;
    toast("对齐任务已启动（规划能力）");
  };

  $("#form-annotate").onsubmit = async (e) => {
    e.preventDefault();
    setProgress(0);
    const res = await Api.post("/api/annotate/run", formToObject(e.target));
    state.currentJob = res.data.jobId;
    toast("标注任务已启动（规划能力）");
  };

  $all("[data-goto]").forEach((btn) => {
    btn.onclick = () => {
      location.hash = btn.dataset.goto;
    };
  });
}

function onLive(channel, msg) {
  if (channel === "jobs") {
    if (typeof msg.progress === "number") setProgress(msg.progress);
    if (msg.message) appendLog(`[job] ${msg.message}`);
  } else if (channel === "logs") {
    appendLog(`[${msg.level || "INFO"}] ${msg.message || JSON.stringify(msg)}`);
  } else if (channel === "status") {
    appendLog(`[status] ${JSON.stringify(msg)}`);
  }
}

async function boot() {
  route();
  window.addEventListener("hashchange", route);
  $all(".nav button").forEach((b) => {
    b.onclick = () => {
      location.hash = `#/${b.dataset.route}`;
    };
  });
  bindActions();
  Live.connect(onLive);
  try {
    await loadOverview();
    await loadDatasets();
  } catch (e) {
    toast("初始化失败: " + e.message);
    appendLog(String(e));
  }
}

document.addEventListener("DOMContentLoaded", boot);
