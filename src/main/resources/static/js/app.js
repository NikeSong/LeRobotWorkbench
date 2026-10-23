const STORE_KEY = "lerobot.workbench.progress";
const PREFS_KEY = "lerobot.workbench.prefs";

function loadPrefs() {
  try {
    return JSON.parse(localStorage.getItem(PREFS_KEY) || "{}");
  } catch {
    return {};
  }
}

function savePrefs(patch) {
  const next = { ...loadPrefs(), ...patch };
  localStorage.setItem(PREFS_KEY, JSON.stringify(next));
  renderSettingsSummary();
  return next;
}

function renderSettingsSummary() {
  const el = $("#settings-summary");
  if (!el) return;
  const prefs = loadPrefs();
  const parent = state.datasetParent || prefs.datasetParent || "默认数据目录";
  const robot = prefs.robotType || "so101_follower";
  const teleop = prefs.teleopType || "so101_leader";
  const fps = prefs.fps || 30;
  const arms = state.arms.follower && state.arms.leader ? "主从臂已配对" : "主从臂还没配对";
  const cams = state.cameraRoles.main && state.cameraRoles.wrist ? "两路摄像头已选定" : "摄像头还没选定";
  const calib = state.calibrated.follower && state.calibrated.leader ? "两条臂都已标定" : "标定还没完成";
  el.className = "role-chip";
  el.textContent = `${arms} · ${calib} · ${cams} · ${robot} / ${teleop} · ${fps} fps · 保存到 ${parent}`;
}

const PAIR_STEPS = [
  { id: "follower-unplug", title: "① 拔从臂" },
  { id: "follower-plug", title: "② 插回从臂" },
  { id: "leader-unplug", title: "③ 拔主臂" },
  { id: "leader-plug", title: "④ 插回主臂" },
  { id: "done", title: "⑤ 完成" },
];

const state = {
  overview: null,
  jobProgress: 0,
  currentJob: null,
  currentKind: null,
  ports: [],
  arms: {
    follower: null,
    leader: null,
  },
  calibrated: {
    follower: false,
    leader: false,
  },
  datasetId: null,
  modelReady: false,
  aligned: false,
  pendingRole: null,
  pair: {
    step: null,
    waitingPath: null,
    detected: null,
  },
  cameras: [],
  cameraRoles: {
    main: null,
    wrist: null,
  },
  /** deviceId -> MediaStream for live picker tiles */
  cameraStreams: {},
  /** when true and both roles set, grid only shows selected cams */
  cameraCollapsed: false,
};

const JOB_PAGES = new Set(["record", "train", "eval", "align", "annotate"]);

function loadProgress() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORE_KEY) || "{}");
    if (saved.arms) state.arms = { follower: saved.arms.follower || null, leader: saved.arms.leader || null };
    if (saved.cameraRoles) {
      state.cameraRoles = {
        main: saved.cameraRoles.main || null,
        wrist: saved.cameraRoles.wrist || null,
      };
      state.cameraCollapsed = !!(state.cameraRoles.main && state.cameraRoles.wrist);
    }
    // calibrated 不以 localStorage 为准，启动后由磁盘标定文件 API 刷新
    state.datasetId = saved.datasetId || null;
    state.modelReady = false;
    state.aligned = false;
  } catch {
    /* ignore broken storage */
  }
}

function saveProgress() {
  localStorage.setItem(
    STORE_KEY,
    JSON.stringify({
      arms: state.arms,
      cameraRoles: state.cameraRoles,
      calibrated: state.calibrated,
      datasetId: state.datasetId,
      modelReady: state.modelReady,
      aligned: state.aligned,
    })
  );
  applyLocks();
  syncActionButtons();
}

function hardwareGaps() {
  const gaps = [];
  if (!state.arms.follower) gaps.push("还没有选择从臂。请先到「设置」把一条设备设为从臂。");
  if (!state.arms.leader) gaps.push("还没有选择主臂。请先到「设置」把一条设备设为主臂。");
  if (state.arms.follower && !state.calibrated.follower) {
    gaps.push("从臂还没标定。请先在「设置」里完成从臂标定。");
  }
  if (state.arms.leader && !state.calibrated.leader) {
    gaps.push("主臂还没标定。请先在「设置」里完成主臂标定。");
  }
  return gaps;
}

/** 返回不可用原因；可用时返回 null。 */
function gateReason(id) {
  const hw = hardwareGaps();
  if (id === "record") return hw.length ? hw.join(" ") : null;
  if (id === "align" || id === "annotate" || id === "train" || id === "eval") {
    return "这一步还没有接到真实程序，不能从这里启动。";
  }
  return null;
}

function rolloutReason() {
  if (!state.modelReady) return "还没有可用的策略。请先完成训练。";
  if (!state.arms.follower) return "真机部署需要先选择从臂。";
  if (!state.calibrated.follower) return "从臂还没标定，不能上真机。请先完成从臂标定。";
  return null;
}

function bindReasonTip(el, reason) {
  if (!reason) {
    el.classList.remove("is-locked");
    el.removeAttribute("data-reason");
    return;
  }
  el.classList.add("is-locked");
  el.dataset.reason = reason;
}

function showEl(id, visible) {
  const el = $(id);
  if (el) el.hidden = !visible;
}

/** Hide actions that are already done, or that only make sense while a job is running. */
function syncActionButtons() {
  const armsReady = !!(state.arms.follower && state.arms.leader);
  const camsReady = !!(state.cameraRoles.main && state.cameraRoles.wrist);
  const calibReady = !!(state.calibrated.follower && state.calibrated.leader);
  const calibrating = !!state._calibrateTimer;
  const calibOpen = !calibReady || !!state.calibrateExpanded || calibrating;

  showEl("#btn-pair-start", !armsReady);
  showEl("#btn-pair-again", armsReady);
  showEl("#btn-cameras-refresh", !camsReady);
  showEl("#btn-cameras-reselect", camsReady);
  showEl("#btn-calibrate", calibOpen && !calibrating);
  showEl("#btn-calibrate-refresh", calibOpen && !calibrating);
  showEl("#btn-calibrate-stop", calibrating);

  showEl("#btn-teleop-start", !state.teleopRunning);
  showEl("#btn-teleop-stop", !!state.teleopRunning);
  showEl("#btn-replay-stop", !!state.replayRunning);
  showEl("#btn-record-start", !state.recordRunning);
  showEl("#btn-record-next", !!state.recordRunning);
  showEl("#btn-record-stop", !!state.recordRunning);

  showEl("#btn-ros-deploy", !state.rosLive);
  showEl("#btn-ros-connect", !state.rosLive);
  showEl("#btn-ros-mock", !state.rosLive);
  showEl("#btn-ros-stop", !!state.rosLive);
}

function applyLocks() {
  const tip = $("#reason-tip");
  $all("[data-route]").forEach((el) => {
    const reason = gateReason(el.dataset.route);
    bindReasonTip(el, reason);
  });
  ["record", "train", "eval", "align", "annotate"].forEach((id) => {
    const banner = $(`#banner-${id}`);
    const reason = id === "eval" ? gateReason("eval") : gateReason(id);
    if (!banner) return;
    if (reason) {
      banner.hidden = false;
      banner.textContent = reason;
    } else if (id === "eval" && rolloutReason() && gateReason("eval") == null) {
      banner.hidden = false;
      banner.textContent = "仿真评估已可用。真机 Rollout：" + rolloutReason();
    } else {
      banner.hidden = true;
      banner.textContent = "";
    }
  });
  const lockSubmit = (formId, reason) => {
    const form = $(formId);
    if (!form) return;
    const submit = form.querySelector("button[type=submit]");
    if (submit) bindReasonTip(submit, reason);
  };
  lockSubmit("#form-record", gateReason("record"));
  lockSubmit("#form-train", gateReason("train"));
  lockSubmit("#form-align", gateReason("align"));
  lockSubmit("#form-annotate", gateReason("annotate"));
  const evalForm = $("#form-eval");
  if (evalForm) {
    const mode = evalForm.querySelector("[name=mode]")?.value;
    const reason = mode === "rollout" ? rolloutReason() : gateReason("eval");
    const submit = evalForm.querySelector("button[type=submit]");
    if (submit) bindReasonTip(submit, reason);
  }
  const teleopReason = gateReason("record");
  const teleopBtn = $("#btn-teleop-start");
  if (teleopBtn) bindReasonTip(teleopBtn, teleopReason);
  if (tip) {
    $all(".is-locked").forEach((el) => {
      if (el.dataset.tipBound) return;
      el.dataset.tipBound = "1";
      el.addEventListener("mouseenter", (ev) => showTip(ev.currentTarget));
      el.addEventListener("mousemove", moveTip);
      el.addEventListener("mouseleave", hideTip);
    });
  }
  syncActionButtons();
}

function showTip(el) {
  const tip = $("#reason-tip");
  const text = el.dataset.reason;
  if (!tip || !text) return;
  tip.textContent = text;
  tip.style.display = "block";
}

function moveTip(ev) {
  const tip = $("#reason-tip");
  if (!tip || tip.style.display === "none") return;
  const pad = 14;
  let x = ev.clientX + pad;
  let y = ev.clientY + pad;
  const rect = tip.getBoundingClientRect();
  if (x + rect.width > window.innerWidth - 8) x = ev.clientX - rect.width - pad;
  if (y + rect.height > window.innerHeight - 8) y = ev.clientY - rect.height - pad;
  tip.style.left = `${x}px`;
  tip.style.top = `${y}px`;
}

function hideTip() {
  const tip = $("#reason-tip");
  if (tip) tip.style.display = "none";
}

function $(sel) {
  return document.querySelector(sel);
}

function $all(sel) {
  return [...document.querySelectorAll(sel)];
}

const JOINT_LABELS = [
  ["shoulder_pan", "肩部旋转"],
  ["shoulder_lift", "大臂"],
  ["elbow_flex", "小臂"],
  ["wrist_flex", "腕部俯仰"],
  ["wrist_roll", "腕部旋转"],
  ["gripper", "夹爪"],
];

let jointTimer = null;

function startJointPoll() {
  stopJointPoll();
  state.jointsRunning = true;
  syncActionButtons();
  jointTimer = setInterval(refreshJoints, 200);
  refreshJoints();
}

function stopJointPoll() {
  if (jointTimer) clearInterval(jointTimer);
  jointTimer = null;
  state.jointsRunning = false;
  syncActionButtons();
}

async function refreshJoints() {
  const res = await Api.get("/api/hardware/joints");
  const data = res.data || {};
  const tip = $("#joints-status");
  if (tip) {
    if (data.message && !data.ok) tip.textContent = data.message;
    else if (data.running) tip.textContent = "正在读取关节…";
    else tip.textContent = "";
  }
  if (typeof data.running === "boolean") {
    state.jointsRunning = data.running;
    syncActionButtons();
  }
  if (data.ok === false && data.message && !data.leader && !data.follower) {
    return;
  }
  renderJoints("leader", data.leader);
  renderJoints("follower", data.follower);
}

function renderJoints(role, joints) {
  const box = $("#joints-" + role);
  if (!box) return;
  const values = joints || {};
  box.innerHTML = JOINT_LABELS.map(([key, label]) => {
    const v = values[key];
    const num = typeof v === "number" ? v : null;
    const pct = num == null ? 0 : Math.max(0, Math.min(100, ((num + 180) / 360) * 100));
    return `<div class="joint-row"><span>${label}</span><div class="joint-bar"><i style="width:${pct}%"></i></div><b>${
      num == null ? "—" : num.toFixed(1)
    }</b></div>`;
  }).join("");
}

const frameStops = [];

function stopFrameLoops() {
  frameStops.splice(0).forEach((stop) => stop());
}

function watchFrame(img, urlFn) {
  if (!img) return;
  let alive = true;
  const tick = () => {
    if (!alive || !img.isConnected) {
      alive = false;
      return;
    }
    const probe = new Image();
    const url = urlFn();
    probe.onload = () => {
      if (!alive) return;
      img.src = probe.src;
      const frame = img.closest(".camera-card-frame, .live-cam-frame");
      frame?.classList.add("live");
      const tip = frame?.querySelector(".cam-loading, [id$='-tip']");
      if (tip && img.id.startsWith("live-cam")) {
        const cam = img.id.includes("wrist") ? state.cameraRoles.wrist : state.cameraRoles.main;
        tip.textContent = cam?.width ? cam.width + "×" + cam.height + " · 录制同源" : "";
      } else if (tip) {
        tip.textContent = "";
      }
      setTimeout(tick, 40);
    };
    probe.onerror = () => {
      if (alive) setTimeout(tick, 250);
    };
    probe.src = url + (url.includes("?") ? "&" : "?") + "t=" + Date.now();
  };
  tick();
  const stop = () => {
    alive = false;
  };
  frameStops.push(stop);
}

function opencvPreviewUrl(cam, recordName) {
  if (state.recordPreview && recordName) return `/api/record/preview/${recordName}`;
  return `/api/hardware/cameras/${cam.index}/frame.jpg`;
}

async function attachRecordCameras() {
  stopFrameLoops();
  const roles = [
    ["main", "front", "live-cam-main", "live-cam-main-frame", "live-cam-main-tip", "replay-cam-main"],
    ["wrist", "wrist", "live-cam-wrist", "live-cam-wrist-frame", "live-cam-wrist-tip", "replay-cam-wrist"],
  ];
  for (const [role, recordName, imgId, frameId, tipId, replayId] of roles) {
    const img = $("#" + imgId);
    const frame = $("#" + frameId);
    const tip = $("#" + tipId);
    const replay = $("#" + replayId);
    const cam = state.cameraRoles[role];
    if (!img || !frame) continue;
    if (replay) {
      replay.hidden = true;
      replay.removeAttribute("src");
    }
    img.hidden = false;
    if (cam?.index == null || cam.index === "") {
      frame.classList.remove("live");
      if (tip) tip.textContent = "请先到「设置」选定" + (role === "main" ? "主摄像头" : "腕部摄像头");
      continue;
    }
    if (tip) tip.textContent = "正在读取录制同源画面…";
    watchFrame(img, () => opencvPreviewUrl(cam, recordName));
  }
}

function clearRecordCameras() {
  stopFrameLoops();
  ["live-cam-main", "live-cam-wrist"].forEach((id) => {
    const img = $("#" + id);
    if (!img) return;
    img.removeAttribute("src");
  });
  ["replay-cam-main", "replay-cam-wrist"].forEach((id) => {
    const video = $("#" + id);
    if (!video) return;
    video.pause?.();
    video.removeAttribute("src");
    video.hidden = true;
  });
  ["live-cam-main-frame", "live-cam-wrist-frame"].forEach((id) => {
    const frame = $("#" + id);
    if (frame) frame.classList.remove("live");
  });
  const mainTip = $("#live-cam-main-tip");
  const wristTip = $("#live-cam-wrist-tip");
  if (mainTip) mainTip.textContent = "未开始";
  if (wristTip) wristTip.textContent = "未开始";
}

function videosForEpisode(videos, episode) {
  const list = Array.isArray(videos) ? videos : [];
  const file = `file-${String(episode).padStart(3, "0")}.mp4`;
  const pick = (pattern) =>
    list.find((v) => pattern.test(v) && v.endsWith(file)) || list.find((v) => pattern.test(v)) || "";
  return [pick(/front|main/i), pick(/wrist/i)];
}

function showReplayVideos(repoId, videos, episode) {
  stopFrameLoops();
  const chosen = videosForEpisode(videos, episode);
  const slots = [
    ["live-cam-main", "replay-cam-main", "live-cam-main-frame", "live-cam-main-tip"],
    ["live-cam-wrist", "replay-cam-wrist", "live-cam-wrist-frame", "live-cam-wrist-tip"],
  ];
  slots.forEach(([imgId, videoId, frameId, tipId], i) => {
    const img = $("#" + imgId);
    const video = $("#" + videoId);
    const frame = $("#" + frameId);
    const tip = $("#" + tipId);
    if (!video || !frame) return;
    if (img) img.hidden = true;
    video.hidden = false;
    if (chosen[i]) {
      video.src = `/api/datasets/media?repoId=${encodeURIComponent(repoId)}&file=${encodeURIComponent(chosen[i])}`;
      video.currentTime = 0;
      video.play().catch(() => {});
      frame.classList.add("live");
      if (tip) tip.textContent = "正在重放录下的画面";
    } else {
      video.removeAttribute("src");
      frame.classList.remove("live");
      if (tip) tip.textContent = i === 0 ? "本条采集没有主摄视频" : "本条采集没有腕部视频";
    }
  });
}

function syncReplayClock(t) {
  if (typeof t !== "number" || Number.isNaN(t)) return;
  ["replay-cam-main", "replay-cam-wrist"].forEach((id) => {
    const video = $("#" + id);
    if (!video || video.hidden || !video.getAttribute("src")) return;
    if (video.readyState < 1) return;
    const gap = Math.abs((video.currentTime || 0) - t);
    if (gap > (video.paused ? 0.08 : 0.35)) video.currentTime = t;
  });
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
  let hash = location.hash.replace("#/", "") || "home";
  if (hash === "ros" || hash === "hardware") hash = "settings";
  const shown = new Set([hash]);
  if (hash === "settings") {
    shown.add("ros");
    shown.add("hardware");
  }
  $all(".view").forEach((v) => v.classList.toggle("active", shown.has(v.dataset.view)));
  $all(".nav button").forEach((b) => b.classList.toggle("active", b.dataset.route === hash));
  const jobPanel = $("#job-panel");
  if (jobPanel) jobPanel.hidden = !JOB_PAGES.has(hash);
  if (hash !== "hardware" && hash !== "record" && hash !== "settings") stopCameraPreviews();
  if (hash === "ros" || hash === "settings") {
    loadRosPanel().catch((e) => console.warn(e));
    renderSettingsSummary();
  }
  if (hash === "hardware" || hash === "settings") {
    refreshCalibrationStatus().catch((e) => console.warn(e));
    refreshCameras({ keepSelection: true }).catch((e) => console.warn(e));
  }
  if (hash === "record") {
    loadTakes().catch((e) => console.warn(e));
    ensureLiveView().catch((e) => console.warn(e));
  }
  if (hash === "datasets") {
    loadDatasets().catch((e) => console.warn(e));
  }
  if (hash === "report") {
    loadQualityReport().catch((e) => console.warn(e));
  }
  const titles = {
    home: ["工作台总览", "平时只采集。设备和保存位置在设置里。"],
    settings: ["设置", "配对、标定、摄像头、帧率、保存位置。定好之后采集页不再显示这些框。"],
    ros: ["设置", "配对、标定、摄像头、帧率、保存位置。定好之后采集页不再显示这些框。"],
    hardware: ["设置", "配对、标定、摄像头、帧率、保存位置。定好之后采集页不再显示这些框。"],
    record: ["数据采集", "配置任务与回合后开始录制，进度实时回传。"],
    datasets: ["数据集", "浏览本地采集，点详情查看质量报表。"],
    report: ["采集质量报表", "从同步、画面、动作和能否训练几个角度评测这条数据。"],
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
  const usingMock = !!ros.usingMock || ros.mode === "mock" || ros.transport === "mock";
  if (ros.connected && !usingMock) {
    dot.className = "dot on";
    label.textContent = "ROS · rosbridge";
  } else if (ros.connected && usingMock) {
    dot.className = "dot warn";
    label.textContent = "ROS · Mock（需部署）";
  } else {
    dot.className = "dot warn";
    label.textContent = "ROS 未连接";
  }
  const banner = $("#ros-home-banner");
  if (banner) {
    if (usingMock || !ros.rosbridgeReachable) {
      banner.hidden = false;
      banner.className = "ros-banner";
      $("#ros-banner-title").textContent = "当前还在用 Mock，不是真实 ROS";
      $("#ros-banner-desc").textContent = "采集本身不依赖 ROS。若要连接 rosbridge，到「设置」里检测或部署。";
    } else {
      banner.hidden = false;
      banner.className = "ros-banner ok";
      $("#ros-banner-title").textContent = "真实 ROS 已连接";
      $("#ros-banner-desc").textContent = ros.rosbridgeUrl || "ws://127.0.0.1:9090";
    }
  }
  $("#stat-repo").textContent = res.data.repoExists ? "已链接" : "未找到";
  $("#stat-transport").textContent = usingMock ? "Mock" : ros.transport || "-";
  $("#stat-modules").textContent = (res.data.modules || []).length;

  const grid = $("#module-grid");
  grid.innerHTML = "";
  (res.data.modules || []).forEach((m) => {
    const route = (m.route || "").replace("#/", "");
    const reason = gateReason(route);
    const el = document.createElement("article");
    el.className = "panel module-tile";
    el.dataset.route = route;
    const badge = reason ? "未解锁" : m.status === "ready" ? "可用" : "规划中";
    const badgeClass = reason ? "planned" : m.status === "ready" ? "ready" : "planned";
    el.innerHTML = `
      <div class="badge ${badgeClass}">${badge}</div>
      <h3 style="margin-top:12px">${m.title}</h3>
      <p class="desc">${m.description}</p>`;
    el.onclick = () => openRoute(route);
    grid.appendChild(el);
  });
  applyLocks();
}

function openRoute(route) {
  const reason = gateReason(route);
  if (reason) {
    toast(reason);
    return;
  }
  location.hash = `#/${route}`;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

async function saveAllTakes() {
  const btn = $("#btn-save-all-takes");
  if (btn) btn.disabled = true;
  try {
    const res = await Api.post("/api/datasets/save-all", {});
    toast(res.data.message || "已全部保存");
    await loadTakes();
  } catch (err) {
    toast("全部保存失败: " + (err.message || err));
  } finally {
    if (btn) btn.disabled = false;
  }
}

async function loadTakes() {
  const box = $("#take-list");
  if (!box) return;
  const res = await Api.get("/api/datasets");
  const rows = res.data.datasets || [];
  if (!rows.length) {
    box.innerHTML = `<div class="role-chip empty">还没有采集记录。</div>`;
    return;
  }
  const cards = [];
  rows.forEach((d) => {
    const takes = d.takes && d.takes.length ? d.takes : [{ index: 0, name: d.displayName || d.repoId }];
    if (d.empty) {
      cards.push({ repoId: d.repoId, name: d.displayName || d.repoId, episode: null, empty: true, saved: d.saved, frames: d.frames });
      return;
    }
    takes.forEach((t) => {
      cards.push({
        repoId: d.repoId,
        name: t.name,
        episode: t.index,
        empty: false,
        saved: d.saved,
        frames: d.frames,
      });
    });
  });
  box.innerHTML = cards
    .map(
      (d) => `<div class="take-card">
      <div>
        <strong>${escapeHtml(d.name)}</strong>
        <em>${d.empty ? "没有写完回合，不能重放" : `第 ${Number(d.episode) + 1} 回合`} · ${d.saved ? "已保存" : "未保存"}</em>
      </div>
      <div class="take-actions">
        ${d.empty ? "" : `<button class="btn" type="button" data-replay="${escapeHtml(d.repoId)}" data-episode="${d.episode}">重放</button>`}
        <button class="btn ghost" type="button" data-save="${escapeHtml(d.repoId)}">保存</button>
        <button class="btn ghost" type="button" data-rename="${escapeHtml(d.repoId)}" data-name="${escapeHtml(d.name || "")}">重命名</button>
        <button class="btn danger" type="button" data-del="${escapeHtml(d.repoId)}">删除</button>
      </div>
    </div>`
    )
    .join("");
  box.querySelectorAll("[data-replay]").forEach((btn) => {
    btn.onclick = () => startReplay(btn.dataset.replay, Number(btn.dataset.episode || 0));
  });
  box.querySelectorAll("[data-save]").forEach((btn) => {
    btn.onclick = async () => {
      const res = await Api.post("/api/datasets/save", { repoId: btn.dataset.save });
      toast(res.data.message || "已保存");
      await loadTakes();
    };
  });
  box.querySelectorAll("[data-rename]").forEach((btn) => {
    btn.onclick = async () => {
      const name = window.prompt("采集名称", btn.dataset.name || "");
      if (!name) return;
      const res = await Api.post("/api/datasets/rename", { repoId: btn.dataset.rename, displayName: name });
      toast(res.data.message || "已改名");
      await loadTakes();
    };
  });
  box.querySelectorAll("[data-del]").forEach((btn) => {
    btn.onclick = async () => {
      if (!window.confirm("删除这条采集？磁盘上的记录会一起去掉。")) return;
      const res = await Api.post("/api/datasets/delete", { repoId: btn.dataset.del });
      toast(res.data.message || "已删除");
      await loadTakes();
    };
  });
}

let replayTimer = null;

function stopReplayPoll() {
  if (replayTimer) clearInterval(replayTimer);
  replayTimer = null;
  state.replayRunning = false;
  syncActionButtons();
}

function startReplayPoll() {
  stopReplayPoll();
  state.replayRunning = true;
  syncActionButtons();
  replayTimer = setInterval(async () => {
    try {
      const res = await Api.get("/api/replay/status");
      const frame = res.data.frame || {};
      if (frame.leader) renderJoints("leader", frame.leader);
      if (frame.follower) renderJoints("follower", frame.follower);
      if (typeof frame.t === "number") {
        syncReplayClock(frame.t);
        const tip = $("#joints-status");
        if (tip) tip.textContent = `正在重放 ${frame.t.toFixed(1)} 秒`;
      }
      if (!res.data.running) {
        stopReplayPoll();
        ensureLiveView().catch(() => {});
      }
    } catch {
      stopReplayPoll();
      syncActionButtons();
    }
  }, 200);
}

async function startReplay(repoId, episode = 0) {
  const follower = requireArm("follower");
  if (!follower) return;
  cancelLiveView();
  stopJointPoll();
  try {
    await Api.post("/api/hardware/joints/stop");
  } catch {
    /* optional */
  }
  const prefs = loadPrefs();
  const res = await Api.post("/api/replay/start", {
    repoId,
    episode: episode == null || Number.isNaN(episode) ? 0 : episode,
    robotPort: follower.path,
    robotId: state.calibIds?.follower || autoCalibrateId("follower"),
    robotType: prefs.robotType || "so101_follower",
  });
  toast(res.data.message || "重放");
  appendLog("replay → " + (res.data.message || ""));
  if (!res.data.ok) return;
  showReplayVideos(repoId, res.data.videos || [], episode);
  startReplayPoll();
}

let liveViewToken = 0;

function cancelLiveView() {
  liveViewToken += 1;
}

async function ensureLiveView() {
  const token = ++liveViewToken;
  const page = location.hash.replace("#/", "") || "home";
  if (page !== "record") return;
  if (state.replayRunning) return;
  try {
    const status = await Api.get("/api/record/status");
    if (token !== liveViewToken || state.replayRunning) return;
    if (status.data?.running) {
      state.recordRunning = true;
      state.recordPreview = true;
      syncActionButtons();
      attachRecordCameras();
      return;
    }
  } catch {
    /* still try to show the live cameras */
  }
  if (token !== liveViewToken || state.replayRunning || state.recordPreview) return;
  state.recordPreview = false;
  try {
    await refreshCameras({ keepSelection: true });
  } catch {
    /* preview still uses the last resolved index */
  }
  if (token !== liveViewToken || state.replayRunning || state.recordPreview) return;
  const follower = state.arms.follower;
  const leader = state.arms.leader;
  attachRecordCameras();
  if (!follower || !leader) {
    const tip = $("#joints-status");
    if (tip && !state.jointsRunning) tip.textContent = "配对主臂和从臂后，这里会显示关节。";
    return;
  }
  if (state.jointsRunning) return;
  const res = await Api.post("/api/hardware/joints/start", {
    leaderPort: leader.path,
    followerPort: follower.path,
    leaderId: state.calibIds?.leader || autoCalibrateId("leader"),
    followerId: state.calibIds?.follower || autoCalibrateId("follower"),
  });
  if (token !== liveViewToken) {
    try {
      await Api.post("/api/hardware/joints/stop");
    } catch {
      /* superseded */
    }
    return;
  }
  if (res.data.ok) startJointPoll();
  else {
    const tip = $("#joints-status");
    if (tip) tip.textContent = res.data.message || "关节还没接上";
  }
}

function openQualityReport(repoId) {
  state.reportRepoId = repoId;
  location.hash = "#/report";
}

function barRow(values, suffix) {
  const max = Math.max(...values, 0.01);
  return `<div class="report-bars">${values
    .map((v, i) => {
      const h = Math.max(2, Math.round((v / max) * 110));
      return `<i><em>${v}${suffix || ""}</em><b style="height:${h}px"></b><small>${i}</small></i>`;
    })
    .join("")}</div>`;
}

async function loadQualityReport() {
  const root = $("#report-root");
  if (!root) return;
  const repoId = state.reportRepoId;
  if (!repoId) {
    root.innerHTML = `<p class="desc">请从数据集列表点「详情」。</p>`;
    return;
  }
  root.innerHTML = `<p class="desc">正在评测 ${escapeHtml(repoId)} …</p>`;
  const res = await Api.get(`/api/datasets/quality?repoId=${encodeURIComponent(repoId)}`);
  const d = res.data || {};
  if (!res.ok || d.ok === false) {
    root.innerHTML = `<p class="desc">${escapeHtml(d.message || res.message || "评测失败")}</p>`;
    loadEpisodeBrowser(repoId).catch((e) => console.warn(e));
    return;
  }
  const joints = d.joints || [];
  const issues = (d.issues || []).map((x) => `<li>${escapeHtml(x)}</li>`).join("");
  const speed = d.speedBuckets || [];
  const grip = d.gripper || [];
  root.innerHTML = `
    <h3>${escapeHtml(d.displayName || repoId)}</h3>
    <p class="desc">${escapeHtml(d.repoId || repoId)} · ${escapeHtml(d.task || "无任务描述")} · ${escapeHtml(d.robotType || "")} · ${escapeHtml(d.codebaseVersion || "")}</p>
    <div class="report-callout ${d.tone || "warn"}">
      <strong>${escapeHtml(d.verdict || "")}</strong>
      <div>${escapeHtml(d.summary || "")}</div>
      ${issues ? `<ul>${issues}</ul>` : ""}
    </div>
    <div class="stat-row">
      <div class="stat"><b>${d.episodes ?? "—"}</b><span>回合数</span></div>
      <div class="stat"><b>${d.frames ?? "—"}</b><span>帧 · ${d.fps || "?"} fps</span></div>
      <div class="stat"><b>${d.durationS ?? "—"} s</b><span>时长</span></div>
      <div class="stat"><b>${d.timestampOff ?? "—"}</b><span>时间戳偏差帧</span></div>
      <div class="stat"><b>${d.maxTrackDeg ?? "—"}°</b><span>最大跟踪误差</span></div>
    </div>
    <h3 style="margin-top:22px">动作随时间</h3>
    <p class="report-note">每秒平均关节速度（度/帧）。横轴是秒。</p>
    ${speed.length ? barRow(speed, "") : "<p class=\"desc\">没有动作序列。</p>"}
    <h3 style="margin-top:22px">夹爪开合</h3>
    <p class="report-note">每秒采样的夹爪目标（度）。数值大表示张开。</p>
    ${grip.length ? barRow(grip, "") : ""}
    <h3 style="margin-top:22px">各关节</h3>
    <table class="table">
      <thead><tr><th>关节</th><th>状态范围 (°)</th><th>平均跟踪误差 (°)</th><th>最大跟踪误差 (°)</th><th>几乎不动的帧</th></tr></thead>
      <tbody>
        ${joints
          .map(
            (j) => `<tr>
            <td>${escapeHtml(j.joint)}</td>
            <td>${j.stateRange ?? "—"}</td>
            <td>${j.trackMae ?? "—"}</td>
            <td>${j.trackMax ?? "—"}</td>
            <td>${j.stillFrac != null ? Math.round(j.stillFrac * 100) + "%" : "—"}</td>
          </tr>`
          )
          .join("")}
      </tbody>
    </table>
    <div class="grid cols-2" style="margin-top:16px">
      <article class="panel">
        <h3>画面</h3>
        <p class="desc">主摄平均亮度 ${d.frontBrightness ?? "—"}，腕部 ${d.wristBrightness ?? "—"}（0–1）。视频 ${(d.videos || []).length} 路。</p>
      </article>
      <article class="panel">
        <h3>能否开训</h3>
        <p class="desc">${d.episodes >= 20 ? "回合数够做一次正式训练。" : "可以确认训练脚本能读这条数据。一条短轨迹学不出能泛化的策略。"}</p>
      </article>
    </div>
    <div class="actions"><button class="btn secondary" type="button" id="btn-report-back">返回列表</button></div>
  `;
  $("#btn-report-back").onclick = () => {
    location.hash = "#/datasets";
  };
  loadEpisodeBrowser(repoId).catch((e) => console.warn(e));
}

let episodeView = null;

async function loadEpisodeBrowser(repoId) {
  const list = $("#episode-list");
  const editor = $("#episode-editor");
  if (!list) return;
  if (editor) editor.hidden = true;
  episodeView = null;
  list.innerHTML = `<div class="role-chip empty">正在读取每一条…</div>`;
  const res = await Api.get(`/api/datasets/episodes?repoId=${encodeURIComponent(repoId)}`);
  const episodes = res.data.episodes || [];
  if (!res.data.ok && res.data.ok !== undefined && !episodes.length) {
    list.innerHTML = `<div class="role-chip empty">${escapeHtml(res.data.message || "读不到回合")}</div>`;
    return;
  }
  if (!episodes.length) {
    list.innerHTML = `<div class="role-chip empty">这个数据集里还没有回合。</div>`;
    return;
  }
  list.innerHTML = episodes
    .map(
      (ep) => `<button class="btn ghost" type="button" data-ep="${ep.index}">第 ${ep.index + 1} 条 · ${ep.frames} 帧 · ${ep.seconds} 秒</button>`
    )
    .join(" ");
  list.querySelectorAll("[data-ep]").forEach((btn) => {
    btn.onclick = () => openEpisode(repoId, Number(btn.dataset.ep));
  });
}

async function openEpisode(repoId, index) {
  const editor = $("#episode-editor");
  const list = $("#episode-list");
  if (editor) editor.hidden = false;
  if (list) list.querySelectorAll("[data-ep]").forEach((btn) => btn.classList.toggle("active", Number(btn.dataset.ep) === index));
  const res = await Api.get(`/api/datasets/episode?repoId=${encodeURIComponent(repoId)}&episode=${index}`);
  const data = res.data || {};
  if (data.ok === false) {
    toast(data.message || "打不开这一条");
    return;
  }
  episodeView = { repoId, ...data, head: 0, tail: data.frames - 1 };
  const front = data.videos?.[0];
  const wrist = data.videos?.[1];
  const frontVideo = $("#ep-video-front");
  const wristVideo = $("#ep-video-wrist");
  if (frontVideo && front) {
    frontVideo.src = `/api/datasets/media?repoId=${encodeURIComponent(repoId)}&file=${encodeURIComponent(front.file)}`;
  }
  if (wristVideo && wrist) {
    wristVideo.src = `/api/datasets/media?repoId=${encodeURIComponent(repoId)}&file=${encodeURIComponent(wrist.file)}`;
  }
  for (const id of ["ep-scrub", "ep-head", "ep-tail"]) {
    const input = $("#" + id);
    if (!input) continue;
    input.min = "0";
    input.max = String(Math.max(0, data.frames - 1));
  }
  $("#ep-scrub").value = "0";
  $("#ep-head").value = "0";
  $("#ep-tail").value = String(data.frames - 1);
  const paint = () => paintEpisodeFrame();
  $("#ep-scrub").oninput = paint;
  $("#ep-head").oninput = () => {
    episodeView.head = Number($("#ep-head").value);
    if (episodeView.head >= episodeView.tail) {
      episodeView.tail = Math.min(episodeView.frames - 1, episodeView.head + 1);
      $("#ep-tail").value = String(episodeView.tail);
    }
    paintEpisodeTrim();
  };
  $("#ep-tail").oninput = () => {
    episodeView.tail = Number($("#ep-tail").value);
    if (episodeView.tail <= episodeView.head) {
      episodeView.head = Math.max(0, episodeView.tail - 1);
      $("#ep-head").value = String(episodeView.head);
    }
    paintEpisodeTrim();
  };
  $("#ep-save").onclick = saveEpisodeTrim;
  paint();
  paintEpisodeTrim();
}

function paintEpisodeFrame() {
  if (!episodeView) return;
  const frame = Number($("#ep-scrub").value);
  const t = frame / episodeView.fps;
  for (const id of ["ep-video-front", "ep-video-wrist"]) {
    const video = $("#" + id);
    if (video && video.readyState >= 1 && Math.abs(video.currentTime - t) > 0.04) video.currentTime = t;
  }
  const labels = episodeView.labels || [];
  const action = episodeView.action[frame] || [];
  const state = episodeView.state[frame] || [];
  $("#ep-frame-label").textContent = `第 ${frame + 1} / ${episodeView.frames} 帧 · ${t.toFixed(2)} 秒`;
  $("#ep-joints").innerHTML = labels
    .map((label, i) => {
      return `<div class="joint-row"><span>${escapeHtml(label)}</span><b>动作 ${action[i] ?? "—"}°</b><b>实际 ${state[i] ?? "—"}°</b></div>`;
    })
    .join("");
}

function paintEpisodeTrim() {
  if (!episodeView) return;
  const head = episodeView.head;
  const tail = episodeView.tail;
  const keep = tail - head + 1;
  $("#ep-trim-label").textContent = `去掉前 ${head} 帧，去掉后 ${episodeView.frames - 1 - tail} 帧，保留 ${keep} 帧。`;
}

async function saveEpisodeTrim() {
  if (!episodeView) return;
  const head = episodeView.head;
  const tail = episodeView.tail;
  const keep = tail - head + 1;
  if (keep < 2) {
    toast("至少保留 2 帧");
    return;
  }
  if (head === 0 && tail === episodeView.frames - 1) {
    toast("没有剪掉任何帧");
    return;
  }
  const ok = window.confirm(
    `第 ${episodeView.index + 1} 条将只保留第 ${head + 1} 到第 ${tail + 1} 帧，并替换原来的文件。这个操作不能撤销。`
  );
  if (!ok) return;
  const btn = $("#ep-save");
  if (btn) btn.disabled = true;
  toast("正在剪切并写回，视频重新编码需要一点时间…");
  try {
    const res = await Api.post("/api/datasets/trim", {
      repoId: episodeView.repoId,
      episode: episodeView.index,
      start: head,
      end: tail + 1,
    });
    if (!res.data.ok) {
      toast(res.data.message || "保存失败");
      return;
    }
    toast(res.data.message || "已写回");
    await loadEpisodeBrowser(episodeView.repoId);
    await openEpisode(episodeView.repoId, res.data.episode ?? episodeView.index);
  } catch (err) {
    toast("保存失败: " + err.message);
  } finally {
    if (btn) btn.disabled = false;
  }
}

async function loadDatasets() {
  const res = await Api.get("/api/datasets");
  const rows = (res.data.datasets || []).map(
    (d) => `<tr>
      <td>${escapeHtml(d.displayName || d.repoId)}${d.empty ? "（空）" : ""}</td>
      <td>${d.episodes}</td>
      <td>${d.fps}</td>
      <td>${d.frames}</td>
      <td>
        <button class="btn ghost" data-ds="${d.repoId}">详情</button>
        <button class="btn" data-use="${d.repoId}">用于训练</button>
      </td>
    </tr>`
  );
  $("#dataset-tbody").innerHTML = rows.join("") || `<tr><td colspan="5">暂无数据集</td></tr>`;
  $all("#dataset-tbody [data-ds]").forEach((btn) => {
    btn.onclick = () => openQualityReport(btn.dataset.ds);
  });
  $all("#dataset-tbody [data-use]").forEach((btn) => {
    btn.onclick = () => {
      state.datasetId = btn.dataset.use;
      state.aligned = false;
      state.modelReady = false;
      saveProgress();
      const trainRepo = document.querySelector("#form-train [name=datasetRepoId]");
      const alignRepo = document.querySelector("#form-align [name=repoId]");
      const annRepo = document.querySelector("#form-annotate [name=repoId]");
      if (trainRepo) trainRepo.value = state.datasetId;
      if (alignRepo) alignRepo.value = state.datasetId;
      if (annRepo) annRepo.value = state.datasetId;
      toast("已选用数据集，可以去做对齐或训练");
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

function renderArmChips() {
  for (const role of ["follower", "leader"]) {
    const chip = $(`#chip-${role}`);
    const arm = state.arms[role];
    const title = role === "follower" ? "从臂（被控制的臂）" : "主臂（你手里操作的臂）";
    if (!arm) {
      chip.className = "role-chip empty";
      chip.innerHTML = `<span>${title}</span><strong>未选择</strong>`;
    } else {
      const cal = state.calibrated[role] ? "已标定" : "未标定";
      const sid = arm.serialId ? ` · ID ${arm.serialId}` : "";
      chip.className = "role-chip";
      chip.innerHTML = `<span>${title}</span><strong>${arm.label}${sid} · ${cal}</strong>`;
    }
  }
  const rec = $("#record-arms");
  if (rec) {
    const f = state.arms.follower;
    const l = state.arms.leader;
    if (f && l) {
      rec.className = "role-chip";
      rec.textContent = `从臂：${f.serialId || f.label}　·　主臂：${l.serialId || l.label}`;
    } else {
      rec.className = "role-chip empty";
      rec.textContent = "还没选齐设备。请到「设置」点「开始配对向导」，按提示拔插 USB。";
    }
  }
  renderCameraChips();
  syncActionButtons();
}

function renderCameraChips() {
  for (const role of ["main", "wrist"]) {
    const chip = $(`#chip-cam-${role}`);
    if (!chip) continue;
    const cam = state.cameraRoles[role];
    const title = role === "main" ? "主摄像头" : "腕部摄像头";
    if (!cam) {
      chip.className = "role-chip empty";
      chip.innerHTML = `<span>${title}</span><strong>未选择</strong>`;
    } else {
      chip.className = "role-chip";
      const idText = cam.missing ? `已绑定 ${cam.stableId}，现在没连上` : cam.stableId || cam.label;
      chip.innerHTML = `<span>${title}</span><strong>${cam.label || "摄像头"} · ${idText}</strong>`;
    }
  }
  const rec = $("#record-cams");
  if (rec) {
    const m = state.cameraRoles.main;
    const w = state.cameraRoles.wrist;
    if (m && w) {
      rec.className = "role-chip";
      rec.textContent = `主摄像头：${m.label}（${m.stableId || "未绑定"}）　·　腕部：${w.label}（${w.stableId || "未绑定"}）`;
    } else {
      rec.className = "role-chip empty";
      rec.textContent = "还没选齐摄像头。请到「设置」检测并选定主摄像头 / 腕部摄像头。";
    }
  }
}

function camerasForGrid() {
  const both = state.cameraRoles.main && state.cameraRoles.wrist;
  if (state.cameraCollapsed && both) {
    // keep stable order: main then wrist
    const list = [];
    if (state.cameraRoles.main) list.push(state.cameraRoles.main);
    if (state.cameraRoles.wrist) list.push(state.cameraRoles.wrist);
    return list;
  }
  return state.cameras;
}

function roleOfCamera(cam) {
  if (sameCamera(state.cameraRoles.main, cam)) return "main";
  if (sameCamera(state.cameraRoles.wrist, cam)) return "wrist";
  return null;
}

function renderCameras() {
  const box = $("#camera-grid");
  if (!box) return;
  const list = camerasForGrid();
  if (!state.cameras.length) {
    box.innerHTML = `<div class="role-chip empty">未检测到摄像头。请插入摄像头后点「检测摄像头」，并允许浏览器使用相机。</div>`;
    return;
  }
  if (!list.length) {
    box.innerHTML = `<div class="role-chip empty">没有可显示的摄像头。</div>`;
    return;
  }
  box.innerHTML = list
    .map((c, i) => {
      const role = roleOfCamera(c);
      const badge =
        role === "main" ? "主摄像头" : role === "wrist" ? "腕部摄像头" : "待选择";
      const cardCls =
        role === "main" ? "selected-main" : role === "wrist" ? "selected-wrist" : "";
      return `<div class="camera-card ${cardCls}" data-cam-i="${i}">
        <div class="camera-card-head">
          <div>
            <strong>${c.label || "摄像头"}</strong>
            <small>${c.detail || ""} · ${c.stableId || "无硬件编号"}</small>
          </div>
          <span class="camera-card-badge">${badge}</span>
        </div>
        <div class="camera-card-frame">
          <img alt="" data-cam-index="${c.index}" />
          <div class="cam-loading">正在打开画面…</div>
        </div>
        <div class="camera-card-actions">
          <button class="btn" type="button" data-cam-role="main" data-cam-i="${i}">设为主摄像头</button>
          <button class="btn secondary" type="button" data-cam-role="wrist" data-cam-i="${i}">设为腕部</button>
        </div>
      </div>`;
    })
    .join("");
  box.querySelectorAll("[data-cam-role]").forEach((btn) => {
    btn.onclick = () => {
      const cam = list[Number(btn.dataset.camI)];
      assignCamera(btn.dataset.camRole, cam);
    };
  });
  stopFrameLoops();
  box.querySelectorAll("img[data-cam-index]").forEach((img) => {
    const cam = list.find((c) => Number(c.index) === Number(img.dataset.camIndex));
    if (!cam) return;
    const recordName = roleOfCamera(cam) === "wrist" ? "wrist" : roleOfCamera(cam) === "main" ? "front" : null;
    watchFrame(img, () => opencvPreviewUrl(cam, recordName));
  });
}

function cameraSettings(stream) {
  const track = stream.getVideoTracks()[0];
  return track?.getSettings?.() || {};
}

async function openFastCamera(deviceId) {
  const attempts = [
    { width: { ideal: 640 }, height: { ideal: 360 }, frameRate: { ideal: 30, min: 24 } },
    { width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 30, min: 24 } },
    { frameRate: { ideal: 30 } },
  ];
  let best = null;
  let bestRate = 0;
  for (const extra of attempts) {
    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        video: { deviceId: { exact: deviceId }, ...extra },
        audio: false,
      });
    } catch {
      continue;
    }
    const rate = Number(cameraSettings(stream).frameRate || 0);
    if (rate > bestRate) {
      if (best) best.getTracks().forEach((t) => t.stop());
      best = stream;
      bestRate = rate;
    } else {
      stream.getTracks().forEach((t) => t.stop());
    }
    if (rate >= 24) break;
  }
  if (!best) throw new Error("无法打开摄像头");
  return best;
}

async function ensureCameraStream(deviceId) {
  if (!deviceId) throw new Error("无 deviceId");
  const existing = state.cameraStreams[deviceId];
  if (existing) {
    const live = existing.getTracks().some((t) => t.readyState === "live");
    const rate = Number(cameraSettings(existing).frameRate || 0);
    if (live && rate >= 24) return existing;
    existing.getTracks().forEach((t) => t.stop());
    delete state.cameraStreams[deviceId];
  }
  const stream = await openFastCamera(deviceId);
  state.cameraStreams[deviceId] = stream;
  return stream;
}

async function attachCameraStreams(list) {
  const keep = new Set((list || []).map((c) => c.deviceId).filter(Boolean));
  for (const [id, stream] of Object.entries(state.cameraStreams)) {
    if (!keep.has(id)) {
      stream.getTracks().forEach((t) => t.stop());
      delete state.cameraStreams[id];
    }
  }
  const box = $("#camera-grid");
  if (!box) return;
  const cards = [...box.querySelectorAll(".camera-card")];
  await Promise.all(
    cards.map(async (card, i) => {
      const cam = list[i];
      const frame = card.querySelector(".camera-card-frame");
      const video = frame?.querySelector("video");
      const loading = frame?.querySelector(".cam-loading");
      if (!cam?.deviceId || !video || !frame) return;
      try {
        const stream = await ensureCameraStream(cam.deviceId);
        if (video.srcObject !== stream) {
          video.srcObject = stream;
        }
        await video.play().catch(() => {});
        frame.classList.add("live");
        if (loading) loading.textContent = "";
      } catch (e) {
        frame.classList.remove("live");
        if (loading) loading.textContent = "无法打开画面：" + (e.message || e);
      }
    })
  );
}

function cameraIds(cam) {
  if (!cam) return [];
  return [cam.stableId, ...(cam.matchIds || [])].filter(Boolean);
}

function sameCamera(a, b) {
  const other = new Set(cameraIds(b));
  return cameraIds(a).some((id) => other.has(id));
}

function matchSavedCamera(prev, list) {
  if (!cameraIds(prev).length) return null;
  return list.find((c) => sameCamera(prev, c)) || null;
}

async function refreshCameras(opts = {}) {
  const { keepSelection = true, expand = false } = opts;
  let opencv = [];
  try {
    const res = await Api.get("/api/hardware/cameras");
    opencv = res.data.cameras || [];
    if (res.data.ok === false && res.data.message) toast(res.data.message);
  } catch (e) {
    toast("摄像头检测失败: " + (e.message || e));
    return [];
  }
  state.cameras = opencv
    .map((c) => ({
      index: c.index,
      label: c.label || "摄像头 " + c.index,
      detail: c.detail || "",
      width: c.width,
      height: c.height,
      fps: c.fps,
      type: "OpenCV",
      stableId: c.stableId || "",
      matchIds: Array.isArray(c.matchIds) ? c.matchIds : [],
      kind: c.kind || "",
      serial: c.serial || "",
      location: c.location || "",
      stableName: c.stableName || c.label,
    }))
    .filter((c) => c.stableId && !isPhoneOrContinuityCam(c));

  if (!keepSelection) {
    state.cameraRoles = { main: null, wrist: null };
    state.cameraCollapsed = false;
  } else {
    let needsReselect = false;
    const listed = state.cameras.length > 0;
    for (const role of ["main", "wrist"]) {
      const prev = state.cameraRoles[role];
      if (!prev) continue;
      if (!cameraIds(prev).length) {
        state.cameraRoles[role] = null;
        needsReselect = true;
        continue;
      }
      const match = matchSavedCamera(prev, state.cameras);
      if (match) {
        state.cameraRoles[role] = {
          ...match,
          alias: role === "main" ? "主摄像头" : "腕部",
          missing: false,
        };
      } else if (listed) {
        state.cameraRoles[role] = {
          ...prev,
          alias: role === "main" ? "主摄像头" : "腕部",
          missing: true,
        };
      }
    }
    if (needsReselect) {
      toast("摄像头要按硬件编号重新选一次。序号会变，不能再用来认相机。");
    }
    if (expand) state.cameraCollapsed = false;
    else if (state.cameraRoles.main && state.cameraRoles.wrist) state.cameraCollapsed = true;
  }

  renderCameraChips();
  renderCameras();
  saveProgress();
  return state.cameras;
}

async function assignCamera(role, cam) {
  if (!cam) return;
  if (!cam.stableId) {
    toast("这台摄像头没有硬件编号，不能绑定");
    return;
  }
  if (isPhoneOrContinuityCam(cam)) {
    toast("请不要选 iPhone / Continuity 摄像头，选 USB 或内置相机");
    return;
  }
  const other = role === "main" ? "wrist" : "main";
  if (sameCamera(state.cameraRoles[other], cam)) {
    toast("主摄像头和腕部不能是同一台");
    return;
  }
  state.cameraRoles[role] = {
    ...cam,
    alias: role === "main" ? "主摄像头" : "腕部",
    role,
  };
  try {
    await Api.post("/api/hardware/cameras/select", { [role]: state.cameraRoles[role] });
  } catch {
    /* local selection still valid for UI */
  }
  if (state.cameraRoles.main && state.cameraRoles.wrist) {
    state.cameraCollapsed = true;
    toast("已按硬件编号绑定。之后序号变化也会认回这台相机。");
  } else {
    state.cameraCollapsed = false;
    toast(role === "main" ? "已设为主摄像头，请再选腕部" : "已设为腕部，请再选主摄像头");
  }
  saveProgress();
  renderCameraChips();
  renderCameras();
}

async function reselectCameras() {
  state.cameraRoles = { main: null, wrist: null };
  state.cameraCollapsed = false;
  try {
    await Api.post("/api/hardware/cameras/select", { main: null, wrist: null });
  } catch {
    /* ignore */
  }
  saveProgress();
  renderCameraChips();
  if (!state.cameras.length) {
    await refreshCameras({ keepSelection: false });
  } else {
    renderCameras();
  }
  toast("已清空选择，请重新点选主摄像头 / 腕部");
}

function stopCameraPreviews() {
  stopFrameLoops();
  state.cameraStreams = {};
  document.querySelectorAll("#camera-grid img, #live-cam-main, #live-cam-wrist").forEach((img) => {
    img.removeAttribute("src");
  });
}

function buildCamerasConfig() {
  const m = state.cameraRoles.main;
  const w = state.cameraRoles.wrist;
  const cfg = {};
  if (m?.stableId) {
    cfg.front = {
      type: "opencv",
      stableId: m.stableId,
      index_or_path: Number(m.index),
      width: Number(m.width) || 640,
      height: Number(m.height) || 360,
      fps: Math.round(Number(m.fps) || 30) || 30,
    };
  }
  if (w?.stableId) {
    cfg.wrist = {
      type: "opencv",
      stableId: w.stableId,
      index_or_path: Number(w.index),
      width: Number(w.width) || 640,
      height: Number(w.height) || 360,
      fps: Math.round(Number(w.fps) || 30) || 30,
    };
  }
  return cfg;
}

function assignPort(role, port) {
  const prev = state.arms[role];
  const serialId = port.serialId || extractArmSerial(port.path || port.name || "");
  state.arms[role] = {
    ...port,
    serialId,
    stableId: port.stableId || (serialId ? "arm_" + serialId : null),
    role,
    alias: role === "follower" ? "从臂" : "主臂",
  };
  if (!prev || (prev.serialId && serialId && prev.serialId !== serialId) || prev.path !== port.path) {
    state.calibrated[role] = false;
  }
  renderArmChips();
  syncCalibrateIdField();
  saveProgress();
  toast(
    (role === "follower" ? "已设为从臂" : "已设为主臂") +
      (serialId ? "（记住编号 " + serialId + "，换口也会自动认回）" : "")
  );
  refreshCalibrationStatus().catch(() => {});
}

/** USB serial from path, e.g. /dev/cu.usbmodem5B421351081 → 5B421351081 */
function extractArmSerial(pathOrName) {
  const s = String(pathOrName || "");
  const m = s.match(/(?:usbmodem|usbserial|wchusbserial)([A-Za-z0-9]+)/i);
  if (m) return m[1];
  const tail = s.replace(/^.*\//, "").replace(/^cu\.|^tty\./, "");
  return tail.replace(/[^A-Za-z0-9]/g, "") || "";
}

/** Re-bind saved leader/follower to current /dev paths by serialId. */
function reconcileArms(ports) {
  const list = ports || state.ports || [];
  let changed = false;
  for (const role of ["follower", "leader"]) {
    const arm = state.arms[role];
    if (!arm) continue;
    const serialId = arm.serialId || extractArmSerial(arm.path || arm.name || "");
    if (!serialId) continue;
    const match =
      list.find((p) => p.serialId === serialId) ||
      list.find((p) => String(p.path || "").includes(serialId) || String(p.name || "").includes(serialId));
    if (match && match.path !== arm.path) {
      state.arms[role] = {
        ...arm,
        ...match,
        serialId: match.serialId || serialId,
        role,
        alias: arm.alias || (role === "follower" ? "从臂" : "主臂"),
      };
      changed = true;
    } else if (match && !arm.serialId) {
      state.arms[role] = { ...arm, serialId, stableId: "arm_" + serialId };
      changed = true;
    }
  }
  if (changed) {
    saveProgress();
    renderArmChips();
    syncCalibrateIdField();
  }
  return changed;
}

function isPhoneOrContinuityCam(cam) {
  const label = String(cam?.label || cam?.stableName || "").toLowerCase();
  return /iphone|continuity|desk view|手机|continuity camera/.test(label);
}

function renderPorts() {
  const box = $("#port-list");
  if (!box) return;
  if (!state.ports.length) {
    box.innerHTML = `<div class="role-chip empty">没有发现串口。请先把两条臂的 USB 都插上，再点「开始配对向导」。</div>`;
    return;
  }
  const fPath = state.arms.follower?.path;
  const lPath = state.arms.leader?.path;
  box.innerHTML = state.ports
    .map((p) => {
      let role = "尚未配对";
      if (p.path === fPath) role = "已配对：从臂";
      if (p.path === lPath) role = "已配对：主臂";
      return `<div class="port-card">
        <div>
          <strong>${p.label}</strong>
          <small>${role}</small>
        </div>
      </div>`;
    })
    .join("");
}

async function refreshPorts() {
  const res = await Api.get("/api/hardware/ports");
  state.ports = res.data.ports || [];
  reconcileArms(state.ports);
  renderPorts();
  return state.ports;
}

function setWizardSteps(activeId) {
  const box = $("#wizard-steps");
  const order = PAIR_STEPS.map((s) => s.id);
  const activeIdx = order.indexOf(activeId);
  box.innerHTML = PAIR_STEPS.map((s, i) => {
    let cls = "";
    if (s.id === activeId) cls = "on";
    else if (i < activeIdx) cls = "done";
    return `<span class="${cls}">${s.title}</span>`;
  }).join("");
}

function showWizardButtons(buttons) {
  const actions = $("#wizard-actions");
  actions.innerHTML = buttons
    .map(
      (b) =>
        `<button class="btn ${b.cls || ""}" type="button" id="${b.id}">${b.label}</button>`
    )
    .join("");
  buttons.forEach((b) => {
    $(`#${b.id}`).onclick = b.onClick;
  });
}

function closePairWizard() {
  state.pair = { step: null, waitingPath: null, detected: null };
  $("#identify-wizard").hidden = true;
}

async function startPairWizard() {
  await refreshPorts();
  if (state.ports.length < 2) {
    toast("请先插上两条臂的 USB（至少 2 个设备）");
    return;
  }
  const begin = await Api.post("/api/hardware/ports/identify/begin");
  if (!begin.data.ok && begin.data.count === 0) {
    toast(begin.data.hint);
    return;
  }
  state.pair.step = "follower-unplug";
  $("#identify-wizard").hidden = false;
  setWizardSteps("follower-unplug");
  $("#identify-hint").innerHTML =
    "<strong>第 1 步：拔下从臂</strong><br>从臂是台子上「被控制」的那条机械臂。<br>请只拔这一根 USB，另一根别动。拔好后点下面的按钮。";
  showWizardButtons([
    { id: "wiz-unplugged", label: "我已拔出从臂", onClick: onFollowerUnplugged },
    { id: "wiz-cancel", label: "取消", cls: "ghost", onClick: closePairWizard },
  ]);
}

async function onFollowerUnplugged() {
  const res = await Api.post("/api/hardware/ports/identify/finish");
  if (!res.data.ok) {
    toast(res.data.message);
    return;
  }
  state.pair.detected = res.data.port;
  state.pair.waitingPath = res.data.waitingPath || res.data.port.path;
  assignPort("follower", res.data.port);
  state.pair.step = "follower-plug";
  setWizardSteps("follower-plug");
  $("#identify-hint").innerHTML =
    `<strong>认出来了：从臂</strong><br>请把刚才拔下的那根 USB <strong>插回去</strong>。插紧后点「已插回从臂」。`;
  showWizardButtons([
    { id: "wiz-replugged", label: "已插回从臂", onClick: onFollowerReplugged },
    { id: "wiz-cancel", label: "取消", cls: "ghost", onClick: closePairWizard },
  ]);
}

async function onFollowerReplugged() {
  const res = await Api.post("/api/hardware/ports/identify/reconnect", {
    path: state.pair.waitingPath,
  });
  if (!res.data.ok) {
    toast(res.data.message);
    return;
  }
  if (res.data.port) {
    assignPort("follower", res.data.port);
  }
  await Api.post("/api/hardware/ports/identify/begin");
  state.pair.step = "leader-unplug";
  setWizardSteps("leader-unplug");
  $("#identify-hint").innerHTML =
    "<strong>第 2 步：拔下主臂</strong><br>主臂是你手里「操作」的那条机械臂。<br>请只拔这一根 USB。拔好后点下面的按钮。";
  showWizardButtons([
    { id: "wiz-unplugged", label: "我已拔出主臂", onClick: onLeaderUnplugged },
    { id: "wiz-cancel", label: "取消", cls: "ghost", onClick: closePairWizard },
  ]);
  await refreshPorts();
}

async function onLeaderUnplugged() {
  const res = await Api.post("/api/hardware/ports/identify/finish");
  if (!res.data.ok) {
    toast(res.data.message);
    return;
  }
  state.pair.detected = res.data.port;
  state.pair.waitingPath = res.data.waitingPath || res.data.port.path;
  assignPort("leader", res.data.port);
  state.pair.step = "leader-plug";
  setWizardSteps("leader-plug");
  $("#identify-hint").innerHTML =
    `<strong>认出来了：主臂</strong><br>请把主臂 USB <strong>插回去</strong>。插紧后点「已插回主臂」。`;
  showWizardButtons([
    { id: "wiz-replugged", label: "已插回主臂", onClick: onLeaderReplugged },
    { id: "wiz-cancel", label: "取消", cls: "ghost", onClick: closePairWizard },
  ]);
}

async function onLeaderReplugged() {
  const res = await Api.post("/api/hardware/ports/identify/reconnect", {
    path: state.pair.waitingPath,
  });
  if (!res.data.ok) {
    toast(res.data.message);
    return;
  }
  if (res.data.port) {
    assignPort("leader", res.data.port);
  }
  state.pair.step = "done";
  setWizardSteps("done");
  $("#identify-hint").innerHTML =
    "<strong>配对完成</strong><br>从臂和主臂都已记住。接下来可以直接标定或遥操作，不用再管端口号。";
  showWizardButtons([
    {
      id: "wiz-finish",
      label: "完成",
      onClick: async () => {
        closePairWizard();
        await refreshPorts();
        toast("主臂 / 从臂已配对");
      },
    },
  ]);
  await refreshPorts();
}

function requireArm(role) {
  const arm = state.arms[role];
  if (!arm) {
    toast(role === "follower" ? "请先选择从臂" : "请先选择主臂");
    location.hash = "#/hardware";
    return null;
  }
  return arm;
}

function mark(ok) {
  return ok ? '<span class="yes">已就绪</span>' : '<span class="no">未就绪</span>';
}

async function loadRosPanel() {
  const res = await Api.get("/api/ros/status");
  const data = res.data || {};
  const detect = data.detect || {};
  const usingMock = !!data.usingMock;
  const banner = $("#ros-status-banner");
  if (banner) {
    banner.className = usingMock ? "ros-banner" : "ros-banner ok";
  }
  $("#ros-mode-title").textContent = usingMock ? "当前：Mock（演示模式）" : "当前：真实 ROS（rosbridge）";
  $("#ros-mode-desc").textContent = usingMock
    ? "还没有连上真实 ROS。请检测环境，没有的话点「一键部署 ROS」。"
    : `已连接 ${data.rosbridgeUrl || ""}`;

  const native = detect.native || {};
  const docker = detect.docker || {};
  const compose = detect.compose || {};
  const bridge = detect.rosbridge || {};
  const mode = detect.deployMode || "none";
  $("#ros-checklist").innerHTML = `
    <li><span>本机 ROS（Pixi）${native.project ? `<small style="color:var(--muted)"> ${native.project}</small>` : ""}</span>${mark(!!native.available)}</li>
    <li><span>本机已含 rosbridge 包</span>${mark(!!native.hasRosbridgePkg)}</li>
    <li><span>rosbridge 端口 ${bridge.url || ""}</span>${mark(!!bridge.reachable)}</li>
    <li><span>Docker（可选备选）</span>${mark(!!docker.available)}</li>
    <li><span>Docker Compose（可选）</span>${mark(!!compose.available)}</li>
    <li><span>部署方式</span><strong>${mode === "native" ? "本机 ROS" : mode === "docker" ? "Docker 备选" : "未就绪"}</strong></li>`;
  if (detect.recommended) {
    $("#ros-mode-desc").textContent = detect.recommended;
  }

  const reachable = !!(detect.rosbridge && detect.rosbridge.reachable);
  state.rosLive = !usingMock && reachable;
  syncActionButtons();
  await refreshRosNodes();
  return data;
}

async function refreshRosNodes() {
  const tbody = $("#ros-node-tbody");
  if (!tbody) return;
  tbody.innerHTML = `<tr><td colspan="4">加载中…</td></tr>`;
  try {
    const res = await Api.get("/api/ros/nodes");
    const nodes = res.data.nodes || [];
    if (!nodes.length) {
      tbody.innerHTML = `<tr><td colspan="4">${res.data.message || "图中暂无节点。请先连接真实 ROS。"}</td></tr>`;
      return;
    }
    const filtered = nodes.filter((n) => {
      const kind = String(n.kind || "").toLowerCase();
      const name = String(n.name || "").toLowerCase();
      if (kind === "policy" || name.includes("policy") || name.includes("model")) return false;
      return true;
    });
    if (!filtered.length) {
      tbody.innerHTML = `<tr><td colspan="4">图中暂无通讯节点（例如 rosbridge）。业务节点请到对应功能页处理。</td></tr>`;
      return;
    }
    tbody.innerHTML = filtered
      .map(
        (n) => `<tr>
        <td><code>${n.name}</code></td>
        <td>${n.kind || "node"}</td>
        <td><span class="badge ${n.status === "online" ? "ready" : "planned"}">${n.status || "-"}</span></td>
        <td style="color:var(--muted);font-size:0.85rem">${n.detail || ""}</td>
      </tr>`
      )
      .join("");
  } catch (e) {
    tbody.innerHTML = `<tr><td colspan="4">读取失败: ${e.message}</td></tr>`;
  }
}

/** USB serial tail from port path, e.g. /dev/tty.usbmodem5A4601081 → USB1081 */
function portSerialTail(path) {
  const p = String(path || "");
  const m = p.match(/([0-9A-Fa-f]{4,})$/);
  if (m) return "USB" + m[1].slice(-4).toUpperCase();
  const base = p.split("/").pop() || "port";
  return base.replace(/[^A-Za-z0-9]+/g, "_").slice(-12) || "port";
}

/** Auto device id: follower_USB1081 / leader_USB0631 — readable + filesystem-safe */
function autoCalibrateId(role) {
  const arm = state.arms[role];
  const tail = portSerialTail(arm?.path);
  return role === "leader" ? `leader_${tail}` : `follower_${tail}`;
}

function syncCalibrateIdField() {
  const roleEl = $("#calibrate-role");
  const idEl = $("#calibrate-id");
  const hint = $("#calibrate-id-hint");
  if (!roleEl || !idEl) return;
  const role = roleEl.value === "leader" ? "leader" : "follower";
  const id = autoCalibrateId(role);
  idEl.value = id;
  if (hint) {
    const arm = state.arms[role];
    const label = role === "leader" ? "主臂" : "从臂";
    hint.textContent = arm?.path
      ? `${label} · 串口 ${arm.path} → 标定文件名 ${id}.json`
      : `请先选定${label}串口，再开始标定`;
  }
}

function currentCalibrateIds() {
  syncCalibrateIdField();
  const typeSel = $("#calibrate-type");
  const robotType = document.querySelector('#form-teleop [name="robotType"]');
  const teleopType = document.querySelector('#form-teleop [name="teleopType"]');
  return {
    followerType: robotType ? robotType.value : "so101_follower",
    followerId: autoCalibrateId("follower"),
    leaderType: teleopType ? teleopType.value : "so101_leader",
    leaderId: autoCalibrateId("leader"),
    selectedType: typeSel ? typeSel.value : "so101_follower",
    selectedId: autoCalibrateId(
      $("#calibrate-role")?.value === "leader" ? "leader" : "follower"
    ),
  };
}

function idFromCalibPath(path) {
  if (!path) return "";
  const name = String(path).split("/").pop() || "";
  return name.endsWith(".json") ? name.slice(0, -5) : name;
}

function updateCalibrationPanel(followerStatus, leaderStatus) {
  const both = state.calibrated.follower && state.calibrated.leader;
  const body = $("#calibrate-body");
  const summary = $("#calibrate-summary");
  const running = !!state._calibrateTimer;
  const collapsed = both && !state.calibrateExpanded && !running;
  if (body) body.hidden = collapsed;
  if (summary) summary.hidden = !collapsed;
  const fEl = $("#calib-sum-follower");
  const lEl = $("#calib-sum-leader");
  if (fEl) {
    const id = state.calibIds?.follower || "";
    fEl.innerHTML = `<span>从臂</span><strong>已标定${id ? " · " + id : ""}</strong>`;
  }
  if (lEl) {
    const id = state.calibIds?.leader || "";
    lEl.innerHTML = `<span>主臂</span><strong>已标定${id ? " · " + id : ""}</strong>`;
  }
  if (followerStatus || leaderStatus) {
    /* statuses already applied */
  }
  syncActionButtons();
}

function switchCalibrateToLeader() {
  const roleEl = $("#calibrate-role");
  const typeEl = $("#calibrate-type");
  if (roleEl) roleEl.value = "leader";
  if (typeEl) {
    typeEl.value = typeEl.value.includes("so100") ? "so100_leader" : "so101_leader";
  }
  syncCalibrateIdField();
}

async function refreshCalibrationStatus() {
  const ids = currentCalibrateIds();
  const q = new URLSearchParams({
    followerType: ids.followerType,
    followerId: ids.followerId,
    leaderType: ids.leaderType,
    leaderId: ids.leaderId,
  });
  const res = await Api.get(`/api/hardware/calibration/status?${q}`);
  const f = res.data.follower || {};
  const l = res.data.leader || {};
  state.calibrated.follower = !!f.calibrated;
  state.calibrated.leader = !!l.calibrated;
  state.calibIds = {
    follower: f.calibrated ? idFromCalibPath(f.path) : autoCalibrateId("follower"),
    leader: l.calibrated ? idFromCalibPath(l.path) : autoCalibrateId("leader"),
  };
  renderArmChips();
  updateCalibrationPanel(f, l);
  saveProgress();
  const box = $("#calibrate-log");
  if (box && !state._calibrateTimer) {
    const files = (res.data.files || [])
      .map((x) => `${x.kind}/${x.type}/${x.id}.json`)
      .join("\n");
    box.textContent =
      `标定根目录: ${res.data.calibrationRoot}\n` +
      `从臂(${ids.followerType}/${ids.followerId}): ${f.calibrated ? "已标定 ✓  " + (f.path || "") : "未找到文件 ✗"}\n` +
      `主臂(${ids.leaderType}/${ids.leaderId}): ${l.calibrated ? "已标定 ✓  " + (l.path || "") : "未找到文件 ✗"}\n` +
      (files ? `\n已有标定文件:\n${files}` : "\n（目录下尚无标定文件）");
  }
  return res.data;
}

function bindActions() {
  $("#btn-find-ports").onclick = async () => {
    const ports = await refreshPorts();
    toast(ports.length ? `发现 ${ports.length} 个设备` : "还没有设备");
  };

  $("#btn-pair-start").onclick = () => startPairWizard();
  const btnPairAgain = $("#btn-pair-again");
  if (btnPairAgain) btnPairAgain.onclick = () => startPairWizard();

  const btnCam = $("#btn-cameras-refresh");
  if (btnCam) {
    btnCam.onclick = async () => {
      stopCameraPreviews();
      // Browser enumeration only by default — avoids opening Continuity / iPhone via OpenCV.
      const cams = await refreshCameras({
        expand: true,
        scanOpenCv: false,
        keepSelection: true,
      });
      toast(
        cams.length
          ? `发现 ${cams.length} 路可用摄像头（已过滤手机 Continuity）。选好主/腕部后会记住。`
          : "没有发现摄像头"
      );
    };
  }
  const btnCamReselect = $("#btn-cameras-reselect");
  if (btnCamReselect) {
    btnCamReselect.onclick = () => reselectCameras();
  }

  $("#btn-ros-detect").onclick = async () => {
    await Api.post("/api/ros/detect");
    await loadRosPanel();
    await loadOverview();
    toast("已重新检测");
  };

  $("#btn-ros-deploy").onclick = async () => {
    $("#ros-deploy-log").textContent = "正在构建并启动 ROS 容器，首次可能需要几分钟下载镜像…\n";
    toast("开始一键部署 ROS");
    const res = await Api.post("/api/ros/deploy");
    $("#ros-deploy-log").textContent = (res.data.logTail || res.data.message || JSON.stringify(res.data)).trim();
    if (res.data.ok && res.data.rosbridgeReachable) {
      const conn = await Api.post("/api/ros/connect");
      toast(conn.data.message || "部署完成并已连接");
    } else {
      toast(res.data.message || "部署未完成");
    }
    await loadRosPanel();
    await loadOverview();
  };

  $("#btn-ros-connect").onclick = async () => {
    const res = await Api.post("/api/ros/connect");
    toast(res.message || res.data?.message || (res.ok ? "已连接" : "连接失败"));
    await loadRosPanel();
    await loadOverview();
  };

  $("#btn-ros-mock").onclick = async () => {
    await Api.post("/api/ros/disconnect");
    toast("已切换为 Mock 演示");
    await loadRosPanel();
    await loadOverview();
  };

  $("#btn-ros-stop").onclick = async () => {
    const res = await Api.post("/api/ros/deploy/stop");
    toast(res.data.message || "已停止");
    $("#ros-deploy-log").textContent = res.data.logTail || "";
    await loadRosPanel();
  };

  const btnNodes = $("#btn-ros-nodes-refresh");
  if (btnNodes) {
    btnNodes.onclick = () => refreshRosNodes();
  }

  $("#calibrate-role").onchange = () => {
    const role = $("#calibrate-role").value;
    const type = $("#calibrate-type");
    if (role === "leader") {
      type.value = type.value.includes("so100") ? "so100_leader" : "so101_leader";
    } else {
      type.value = type.value.includes("so100") ? "so100_follower" : "so101_follower";
    }
    syncCalibrateIdField();
    refreshCalibrationStatus().catch(() => {});
  };
  syncCalibrateIdField();

  $("#btn-calibrate-refresh").onclick = async () => {
    await refreshCalibrationStatus();
    toast("已按标定文件刷新状态");
  };

  const btnAgain = $("#btn-calibrate-again");
  if (btnAgain) {
    btnAgain.onclick = () => {
      state.calibrateExpanded = true;
      updateCalibrationPanel();
    };
  }

  const btnCalibrateStop = $("#btn-calibrate-stop");
  if (btnCalibrateStop) {
    btnCalibrateStop.onclick = async () => {
      try {
        const type = $("#calibrate-type").value;
        const id = $("#calibrate-id").value;
        const res = await Api.post("/api/hardware/calibration/stop", { type, id });
        if (state._calibrateTimer) {
          clearInterval(state._calibrateTimer);
          state._calibrateTimer = null;
        }
        const btn = $("#btn-calibrate");
        if (btn) btn.disabled = false;
        toast(res.data.message || "已停止");
        await refreshCalibrationStatus();
      } catch (err) {
        toast("停止失败: " + err.message);
      }
    };
  }

  function renderJointTable(joints) {
    const panel = $("#calibrate-joints");
    const table = $("#calibrate-joints-table");
    const hint = $("#calibrate-joints-hint");
    if (!panel || !table) return;
    const names = Object.keys(joints || {});
    if (!names.length) {
      panel.hidden = true;
      table.innerHTML = "";
      return;
    }
    panel.hidden = false;
    if (hint) {
      hint.textContent =
        names.length +
        " 个关节实时显示（SO-101 共 6 个；扫行程时 wrist_roll 量程自动 0–4095）";
    }
    // Encoder span roughly 0..4095 for Feetech; use observed extrema for bar scale.
    let scaleMin = Infinity;
    let scaleMax = -Infinity;
    names.forEach((n) => {
      const j = joints[n];
      scaleMin = Math.min(scaleMin, j.min, j.position);
      scaleMax = Math.max(scaleMax, j.max, j.position);
    });
    if (!Number.isFinite(scaleMin) || scaleMax <= scaleMin) {
      scaleMin = 0;
      scaleMax = 4095;
    }
    const span = scaleMax - scaleMin || 1;
    table.innerHTML = names
      .map((name) => {
        const j = joints[name];
        const left = ((j.min - scaleMin) / span) * 100;
        const width = Math.max(((j.max - j.min) / span) * 100, 1.5);
        const cursor = ((j.position - scaleMin) / span) * 100;
        return (
          `<div class="joint-row">` +
          `<div class="joint-name">${name}</div>` +
          `<div class="joint-track">` +
          `<div class="joint-span" style="left:${left}%;width:${width}%"></div>` +
          `<div class="joint-cursor" style="left:${cursor}%"></div>` +
          `</div>` +
          `<div class="joint-vals">${j.min} / <strong>${j.position}</strong> / ${j.max}</div>` +
          `</div>`
        );
      })
      .join("");
  }

  function renderCalibrateGuide(data) {
    const box = $("#calibrate-guide");
    if (!box) return;
    box.hidden = false;
    $("#calibrate-title").textContent = data.title || "标定进行中";
    const role = data.roleLabel ? `正在标定：${data.roleLabel}\n\n` : "";
    $("#calibrate-instruction").textContent = role + (data.instruction || "");
    const steps = data.steps || [];
    const active = data.activeStep || "";
    $("#calibrate-steps").innerHTML = steps
      .map((s) => {
        const cls = s.id === active ? "on" : s.id === "done" && active === "done" ? "done" : "";
        return `<span class="${cls}">${s.label}</span>`;
      })
      .join("");
    const actions = $("#calibrate-actions");
    actions.innerHTML = "";
    const addBtn = (action, cls) => {
      if (!action) return;
      const b = document.createElement("button");
      b.type = "button";
      b.className = "btn " + (cls || "");
      b.textContent = action.label;
      b.onclick = async () => {
        b.disabled = true;
        try {
          const type = $("#calibrate-type").value;
          const id = $("#calibrate-id").value;
          const res = await Api.post("/api/hardware/calibration/continue", {
            type,
            id,
            send: action.send || "\n",
          });
          renderCalibrateGuide(res.data);
          if ($("#calibrate-log")) {
            $("#calibrate-log").textContent = res.data.logTail || res.data.status?.logTail || "";
          }
          toast(res.data.message || "已继续");
        } catch (err) {
          toast("继续失败: " + err.message);
        } finally {
          b.disabled = false;
        }
      };
      actions.appendChild(b);
    };
    addBtn(data.primaryAction, "");
    addBtn(data.secondaryAction, "secondary");
    if (data.showJoints === false) {
      const panel = $("#calibrate-joints");
      if (panel) panel.hidden = true;
    } else {
      renderJointTable(data.joints || {});
    }
    if ($("#calibrate-log") && data.logTail) {
      $("#calibrate-log").textContent = data.logTail;
      $("#calibrate-log").scrollTop = $("#calibrate-log").scrollHeight;
    }
  }

  $("#form-calibrate").onsubmit = async (e) => {
    e.preventDefault();
    syncCalibrateIdField();
    const body = formToObject(e.target);
    const role = body.role === "leader" ? "leader" : "follower";
    const arm = requireArm(role);
    if (!arm) return;
    body.port = arm.path;
    body.id = autoCalibrateId(role);
    const idEl = $("#calibrate-id");
    if (idEl) idEl.value = body.id;
    const btn = $("#btn-calibrate");
    if (btn) btn.disabled = true;
    toast("正在启动标定…请看下方引导与关节数值");
    try {
      const res = await Api.post("/api/hardware/calibrate", body);
      appendLog("calibrate → " + JSON.stringify(res.data));
      if (!res.data.ok) {
        toast(res.data.message || "标定启动失败");
        if (btn) btn.disabled = false;
        return;
      }
      renderCalibrateGuide(res.data);
      toast(res.data.message || "请按引导操作机械臂");
      const before = res.data.beforeMtime ?? -1;
      const type = body.type;
      const id = body.id;
      if (state._calibrateTimer) clearInterval(state._calibrateTimer);
      const finishOk = async (calibrated) => {
        clearInterval(state._calibrateTimer);
        state._calibrateTimer = null;
        state.calibrated[role] = !!calibrated;
        renderArmChips();
        saveProgress();
        await refreshCalibrationStatus();
        if (btn) btn.disabled = false;
        if (calibrated && state.calibrated.follower && state.calibrated.leader) {
          state.calibrateExpanded = false;
          updateCalibrationPanel();
        }
        if (calibrated && role === "follower" && !state.calibrated.leader) {
          switchCalibrateToLeader();
          toast("从臂已标定。已自动切换到主臂，点「开始标定」继续。");
        } else if (calibrated) {
          toast(role === "leader" ? "主臂标定完成" : "标定完成：已写入标定文件");
        }
      };
      // ~4Hz so joint bars feel live (backend emits ~8Hz)
      state._calibrateTimer = setInterval(async () => {
        try {
          const poll = await Api.get(
            `/api/hardware/calibration/poll?type=${encodeURIComponent(type)}&id=${encodeURIComponent(id)}&beforeMtime=${before}`
          );
          renderCalibrateGuide(poll.data);
          if (poll.data.calibrated && poll.data.updatedThisRun) {
            await finishOk(true);
          } else if (!poll.data.running && (poll.data.phase === "done" || poll.data.calibrated)) {
            await finishOk(!!poll.data.calibrated);
          } else if (!poll.data.running) {
            await finishOk(!!poll.data.calibrated);
            if (!poll.data.calibrated) {
              toast("标定进程已结束。若未完成，请查看日志后重试。");
            }
          }
        } catch (err) {
          clearInterval(state._calibrateTimer);
          state._calibrateTimer = null;
          toast("状态刷新失败: " + err.message);
          if (btn) btn.disabled = false;
        }
      }, 250);
    } catch (err) {
      toast("标定请求失败: " + err.message);
      appendLog(String(err));
      if (btn) btn.disabled = false;
    }
  };

  function renderTeleop(data) {
    const status = $("#teleop-status");
    const logBox = $("#teleop-log");
    state.teleopRunning = !!data.running;
    syncActionButtons();
    if (status) {
      status.textContent = data.running
        ? "运行中：推动主臂，从臂应跟随。"
        : data.message || (data.exitCode != null ? `已结束（退出码 ${data.exitCode}）` : "未在运行");
    }
    if (logBox && data.logTail) {
      logBox.textContent = data.logTail;
      logBox.scrollTop = logBox.scrollHeight;
    }
  }

  function stopTeleopPoll() {
    if (state._teleopTimer) {
      clearInterval(state._teleopTimer);
      state._teleopTimer = null;
    }
  }

  function startTeleopPoll() {
    stopTeleopPoll();
    state._teleopTimer = setInterval(async () => {
      try {
        const res = await Api.get("/api/hardware/teleop/status");
        renderTeleop(res.data);
        if (!res.data.running) stopTeleopPoll();
      } catch (err) {
        stopTeleopPoll();
        toast("遥操作状态读取失败: " + err.message);
      }
    }, 800);
  }

  $("#btn-teleop-start").onclick = async () => {
    const btn = $("#btn-teleop-start");
    const blocked = gateReason("record");
    if (blocked) {
      toast(blocked);
      const status = $("#teleop-status");
      if (status) status.textContent = blocked;
      return;
    }
    const follower = requireArm("follower");
    const leader = requireArm("leader");
    if (!follower || !leader) return;
    const body = formToObject($("#form-teleop"));
    const prefs = loadPrefs();
    body.robotType = prefs.robotType || body.robotType || "so101_follower";
    body.teleopType = prefs.teleopType || body.teleopType || "so101_leader";
    body.robotPort = follower.path;
    body.teleopPort = leader.path;
    body.robotId = state.calibIds?.follower || autoCalibrateId("follower");
    body.teleopId = state.calibIds?.leader || autoCalibrateId("leader");
    if (btn) btn.disabled = true;
    toast("正在启动遥操作…");
    const status = $("#teleop-status");
    if (status) status.textContent = "正在连接从臂和主臂…";
    try {
      const res = await Api.post("/api/hardware/teleop/start", body);
      renderTeleop(res.data);
      toast(res.data.message || (res.data.ok ? "遥操作已启动" : "启动失败"));
      if (res.data.ok) startTeleopPoll();
    } catch (err) {
      toast("遥操作请求失败: " + err.message);
      if (status) status.textContent = "启动失败: " + err.message;
    } finally {
      if (btn) btn.disabled = false;
    }
  };
  $("#btn-teleop-stop").onclick = async () => {
    stopTeleopPoll();
    const res = await Api.post("/api/hardware/teleop/stop", {});
    renderTeleop(res.data);
    toast(res.data.message || "遥操作已停止");
  };

  function renderRecord(data) {
    const status = $("#record-status");
    const logBox = $("#record-log");
    state.recordRunning = !!data.running;
    syncActionButtons();
    if (status) {
      status.textContent = data.running
        ? `录制中${data.repoId ? " · " + data.repoId : ""} — 推动主臂；点「保存本回合」写入这一轮`
        : data.message || (data.exitCode != null ? `已结束（退出码 ${data.exitCode}）` : "未在录制");
    }
    if (logBox && data.logTail) {
      logBox.textContent = data.logTail;
      logBox.scrollTop = logBox.scrollHeight;
    }
  }

  function stopRecordPoll() {
    if (state._recordTimer) {
      clearInterval(state._recordTimer);
      state._recordTimer = null;
    }
  }

  function startRecordPoll() {
    stopRecordPoll();
    let ticks = 0;
    state._recordTimer = setInterval(async () => {
      try {
        const res = await Api.get("/api/record/status");
        renderRecord(res.data);
        if (typeof res.data.progress === "number") setProgress(res.data.progress);
        if (res.data.logTail && res.data.logTail !== state._recordLog) {
          const prev = state._recordLog || "";
          const next = res.data.logTail;
          const delta = next.startsWith(prev) ? next.slice(prev.length) : next.slice(-500);
          delta
            .split("\n")
            .map((line) => line.trim())
            .filter(Boolean)
            .forEach((line) => appendLog(line));
          state._recordLog = next;
        }
        ticks += 1;
        const savedNow = res.data.savedEpisodes || 0;
        if (savedNow !== state._savedEpisodesSeen) {
          state._savedEpisodesSeen = savedNow;
          if (savedNow > 0) {
            loadTakes().catch(() => {});
            loadDatasets().catch(() => {});
          }
        }
        if (!res.data.running) {
          stopRecordPoll();
          state.recordPreview = false;
          stopFrameLoops();
          loadTakes().catch(() => {});
          loadDatasets().catch(() => {});
          if ((res.data.savedEpisodes || 0) > 0) {
            state.datasetId = res.data.repoId || state.pendingDatasetId;
            saveProgress();
            setProgress(100);
            toast("已写入「" + (res.data.displayName || res.data.repoId || "采集") + "」，可保存、改名或重放");
          } else if (res.data.exitCode != null && res.data.exitCode !== 0) {
            toast("录制进程异常退出（码 " + res.data.exitCode + "），请看下方日志");
          } else if (ticks <= 3) {
            toast("录制进程已结束，请看下方日志");
          }
        }
      } catch (err) {
        stopRecordPoll();
      }
    }, 800);
  }

  function taskFolderName(task) {
    const parts = String(task || "").match(/[\p{L}\p{N}]+/gu) || [];
    return parts
      .map((word) => {
        const first = Array.from(word)[0] || "";
        if (/^[A-Za-z]$/.test(first)) {
          return first.toUpperCase() + Array.from(word).slice(1).join("").toLowerCase();
        }
        return word;
      })
      .join("")
      .slice(0, 64);
  }

  const taskInput = document.querySelector("#form-record [name=singleTask]");
  const savePath = $("#record-save-path");
  const folderHint = $("#record-folder-hint");
  const refreshSavePath = () => {
    const name = taskFolderName(taskInput ? taskInput.value : "");
    const parent = (state.datasetParent || "").replace(/\/$/, "");
    if (savePath) savePath.value = name && parent ? parent + "/" + name : parent;
    const summary = $("#record-setup-summary");
    if (summary) {
      const prefs = loadPrefs();
      summary.textContent = name
        ? `保存到 ${savePath ? savePath.value : parent} · ${prefs.robotType || "so101_follower"} · ${Number(prefs.fps) || 30} fps。要改型号或目录，打开设置。`
        : "填写任务后，文件夹名会出现在设置里的保存路径末尾。";
    }
    if (folderHint) {
      folderHint.textContent = name
        ? "最后一级「" + name + "」跟着任务描述变化。local/my_pick_task 只是旧占位，磁盘上没有这个文件夹。"
        : "先填写任务描述，文件夹名会自动出现在路径末尾。";
    }
  };
  const markTaskField = () => {
    if (!taskInput) return;
    taskInput.classList.toggle("task-empty", !taskInput.value.trim());
  };
  if (taskInput) {
    taskInput.addEventListener("input", () => {
      markTaskField();
      refreshSavePath();
    });
    markTaskField();
  }
  const prefs = loadPrefs();
  const robotPref = $("#pref-robot-type");
  const teleopPref = $("#pref-teleop-type");
  const fpsPref = $("#pref-fps");
  if (robotPref && prefs.robotType) robotPref.value = prefs.robotType;
  if (teleopPref && prefs.teleopType) teleopPref.value = prefs.teleopType;
  if (fpsPref && prefs.fps) fpsPref.value = prefs.fps;
  const persistDefaults = () => {
    savePrefs({
      robotType: robotPref ? robotPref.value : "so101_follower",
      teleopType: teleopPref ? teleopPref.value : "so101_leader",
      fps: Number(fpsPref && fpsPref.value) || 30,
      datasetParent: state.datasetParent || "",
    });
    refreshSavePath();
  };
  if (robotPref) robotPref.onchange = persistDefaults;
  if (teleopPref) teleopPref.onchange = persistDefaults;
  if (fpsPref) fpsPref.onchange = persistDefaults;
  Api.get("/api/system/dataset-home")
    .then((res) => {
      state.datasetParent = prefs.datasetParent || res.data.path || "";
      refreshSavePath();
      renderSettingsSummary();
    })
    .catch(() => refreshSavePath());
  const pickBtn = $("#btn-pick-folder");
  if (pickBtn) {
    pickBtn.onclick = async () => {
      pickBtn.disabled = true;
      toast("请在弹出的系统窗口里选择文件夹");
      try {
        const res = await Api.post("/api/system/choose-directory", {});
        if (!res.data.ok) {
          if (!res.data.cancelled) toast(res.data.message || "没有选中文件夹");
          return;
        }
        state.datasetParent = res.data.path;
        savePrefs({ datasetParent: res.data.path });
        refreshSavePath();
      } catch (err) {
        toast("无法打开文件夹选择框: " + err.message);
      } finally {
        pickBtn.disabled = false;
      }
    };
  }

  async function stopLiveHardware() {
    cancelLiveView();
    stopJointPoll();
    stopReplayPoll();
    stopCameraPreviews();
    clearRecordCameras();
    try {
      await Api.post("/api/hardware/joints/stop");
    } catch {
      /* optional */
    }
    try {
      await Api.post("/api/hardware/teleop/stop", {});
    } catch {
      /* optional */
    }
    try {
      await Api.post("/api/replay/stop");
    } catch {
      /* optional */
    }
  }

  $("#form-record").onsubmit = async (e) => {
    e.preventDefault();
    if (taskInput && !taskInput.value.trim()) {
      taskInput.classList.add("task-empty");
      taskInput.focus();
      toast("请先填写这次的任务描述");
      return;
    }
    const blocked = gateReason("record");
    if (blocked) {
      toast(blocked);
      return;
    }
    setProgress(0);
    state.currentKind = "record";
    await refreshPorts();
    const follower = requireArm("follower");
    const leader = requireArm("leader");
    if (!follower || !leader) return;
    toast("正在关闭实时画面…");
    await stopLiveHardware();
    stopTeleopPoll();
    // Let macOS release serial + camera devices before record opens them.
    await new Promise((r) => setTimeout(r, 600));
    const body = formToObject(e.target);
    const prefs = loadPrefs();
    body.robotType = prefs.robotType || "so101_follower";
    body.teleopType = prefs.teleopType || "so101_leader";
    body.fps = Number(prefs.fps) || 30;
    body.datasetRoot = state.datasetParent || prefs.datasetParent || "";
    body.robotPort = follower.path;
    body.teleopPort = leader.path;
    body.robotId = state.calibIds?.follower || autoCalibrateId("follower");
    body.teleopId = state.calibIds?.leader || autoCalibrateId("leader");
    await refreshCameras({ keepSelection: true });
    const cams = buildCamerasConfig();
    if (!cams.front || !cams.wrist) {
      const status = $("#record-status");
      if (status) status.textContent = "还没有按硬件编号绑定主摄像头和腕部。";
      toast("请到「硬件准备」重新点一次主摄像头和腕部。录制只认硬件编号，不认序号。");
      return;
    }
    if (Object.keys(cams).length) body.cameras = cams;
    const btn = $("#btn-record-start");
    if (btn) btn.disabled = true;
    toast("正在启动录制…");
    renderRecord({ running: true, message: "正在启动录制进程…", logTail: "请求中…" });
    try {
      const res = await Api.post("/api/record/start", body);
      appendLog("record start → " + JSON.stringify(res.data));
      renderRecord(res.data);
      const logBox = $("#record-log");
      if (logBox) {
        logBox.scrollIntoView({ behavior: "smooth", block: "nearest" });
      }
      if (!res.data.ok) {
        toast(res.data.message || "录制启动失败");
        return;
      }
      state.currentJob = res.data.jobId;
      state.pendingDatasetId = res.data.repoId || body.datasetRoot;
      state.recordPreview = true;
      attachRecordCameras();
      toast(res.data.message || "录制已启动");
      startRecordPoll();
    } catch (err) {
      toast("录制请求失败: " + err.message);
      appendLog(String(err));
      renderRecord({ running: false, message: "请求失败: " + err.message, logTail: String(err) });
    } finally {
      if (btn) btn.disabled = false;
    }
  };
  const saveAllBtn = $("#btn-save-all-takes");
  if (saveAllBtn) saveAllBtn.onclick = () => saveAllTakes();

  $("#btn-record-next").onclick = async () => {
    const res = await Api.post("/api/record/next");
    toast(res.data.message || "已请求保存本回合");
  };
  $("#btn-record-stop").onclick = async () => {
    toast("正在把当前回合一并保存…");
    state.recordPreview = false;
    stopFrameLoops();
    const res = await Api.post("/api/record/stop");
    renderRecord(res.data);
    if (typeof res.data.progress === "number") setProgress(res.data.savedEpisodes > 0 ? 100 : res.data.progress);
    await loadTakes();
    toast(res.data.message || "已停止录制");
    ensureLiveView().catch(() => {});
  };
  $("#btn-replay-stop").onclick = async () => {
    stopReplayPoll();
    await Api.post("/api/replay/stop");
    clearRecordCameras();
    toast("已停止重放");
    ensureLiveView().catch(() => {});
  };

  $("#form-train").onsubmit = async (e) => {
    e.preventDefault();
    const blocked = gateReason("train");
    if (blocked) {
      toast(blocked);
      return;
    }
    setProgress(0);
    state.currentKind = "train";
    const res = await Api.post("/api/train/start", formToObject(e.target));
    toast(res.data.message || "训练没有启动");
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
    const blocked = body.mode === "rollout" ? rolloutReason() : gateReason("eval");
    if (blocked) {
      toast(blocked);
      return;
    }
    const path = body.mode === "rollout" ? "/api/rollout/start" : "/api/eval/start";
    const res = await Api.post(path, body);
    toast(res.data.message || "没有启动");
    appendLog(`${body.mode} → ` + JSON.stringify(res.data));
  };

  $("#form-align").onsubmit = async (e) => {
    e.preventDefault();
    const blocked = gateReason("align");
    if (blocked) {
      toast(blocked);
      return;
    }
    setProgress(0);
    state.currentKind = "align";
    const res = await Api.post("/api/align/run", formToObject(e.target));
    toast(res.data.message || "对齐没有执行");
  };

  $("#form-annotate").onsubmit = async (e) => {
    e.preventDefault();
    const blocked = gateReason("annotate");
    if (blocked) {
      toast(blocked);
      return;
    }
    setProgress(0);
    state.currentKind = "annotate";
    const res = await Api.post("/api/annotate/run", formToObject(e.target));
    toast(res.data.message || "标注没有执行");
  };

  $all("[data-goto]").forEach((btn) => {
    btn.onclick = () => {
      const route = btn.dataset.goto.replace("#/", "");
      openRoute(route);
    };
  });
  const evalMode = document.querySelector("#form-eval [name=mode]");
  if (evalMode) evalMode.onchange = () => applyLocks();
}

function onJobFinished(kind) {
  if (kind === "record" && state.pendingDatasetId) {
    state.datasetId = state.pendingDatasetId;
    state.aligned = false;
    state.modelReady = false;
    saveProgress();
    toast("录制完成，数据集已可用于对齐和训练");
  }
}

function onLive(channel, msg) {
  if (channel === "jobs") {
    if (typeof msg.progress === "number") setProgress(msg.progress);
    if (msg.message) appendLog(`[job] ${msg.message}`);
    if (msg.status === "done") onJobFinished(msg.kind || state.currentKind);
  } else if (channel === "logs") {
    appendLog(`[${msg.level || "INFO"}] ${msg.message || JSON.stringify(msg)}`);
  } else if (channel === "status") {
    appendLog(`[status] ${JSON.stringify(msg)}`);
  }
}

async function boot() {
  loadProgress();
  try {
    await refreshCalibrationStatus();
  } catch (e) {
    appendLog("calibration status: " + e.message);
  }
  applyLocks();
  route();
  window.addEventListener("hashchange", route);
  $all(".nav button").forEach((b) => {
    b.onclick = () => openRoute(b.dataset.route);
  });
  $all("#pipeline-flow .pipeline-node").forEach((node) => {
    node.style.cursor = "pointer";
    node.onclick = () => openRoute(node.dataset.route);
  });
  bindActions();
  Live.connect(onLive);
  try {
    await loadOverview();
    await loadDatasets();
    await refreshPorts();
    renderArmChips();
    syncActionButtons();
  } catch (e) {
    toast("初始化失败: " + e.message);
    appendLog(String(e));
  }
}

document.addEventListener("DOMContentLoaded", boot);
