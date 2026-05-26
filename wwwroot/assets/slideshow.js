const stage = document.querySelector("#stage");
const image = document.querySelector("#slideImage");
const emptyState = document.querySelector("#emptyState");
const slideshowChrome = document.querySelector("#chrome");
const folderName = document.querySelector("#folderName");
const positionText = document.querySelector("#positionText");
const settingsToggle = document.querySelector("#settingsToggle");
const settingsPanel = document.querySelector("#settingsPanel");
const timerText = document.querySelector("#timerText");
const slower = document.querySelector("#slower");
const faster = document.querySelector("#faster");
const fitMode = document.querySelector("#fitMode");
const fullMode = document.querySelector("#fullMode");
const switchFolderGroup = document.querySelector("#switchFolderGroup");
const switchFolder = document.querySelector("#switchFolder");
const tapFeedback = document.querySelector("#tapFeedback");

let state = null;
let images = [];
let index = 0;
let playing = true;
let timer = null;
let chromeTimer = null;
let feedbackTimer = null;
let heartbeatTimer = null;
let refreshTimer = null;
let isReloading = false;

async function fetchJson(path, options) {
  const response = await fetch(path, { cache: "no-store", ...options });
  if (!response.ok) {
    throw new Error("Unavailable");
  }
  return response.json();
}

async function load() {
  state = await fetchJson("/api/state");
  images = await fetchJson("/api/images?shuffle=true");
  index = 0;
  startViewerHeartbeat();
  startRefreshWatcher();
  render();
  showChromeTemporarily();
}

async function reloadSlideshow(nextState, options = {}) {
  if (isReloading) {
    return;
  }

  isReloading = true;
  try {
    state = nextState || await fetchJson("/api/state");
    images = await fetchJson("/api/images?shuffle=true");
    index = options.keepPosition ? Math.min(index, Math.max(0, images.length - 1)) : 0;
    render();
  } finally {
    isReloading = false;
  }
}

function startRefreshWatcher() {
  clearInterval(refreshTimer);
  refreshTimer = setInterval(async () => {
    if (document.visibilityState === "hidden" || isReloading) {
      return;
    }

    try {
      const nextState = await fetchJson("/api/state");
      if (nextState.version !== state?.version || nextState.folderPath !== state?.folderPath) {
        await reloadSlideshow(nextState);
        showTapFeedback("Switched", "center");
        showChromeTemporarily();
      } else if (
        nextState.slideSeconds !== state?.slideSeconds ||
        nextState.backgroundColor !== state?.backgroundColor ||
        nextState.imageMode !== state?.imageMode
      ) {
        state = nextState;
        render();
      }
    } catch {
      // Keep the current slideshow running if the local server is briefly unavailable.
    }
  }, 1000);
}

function sendViewerHeartbeat(active = true) {
  if (active && document.visibilityState === "hidden") {
    return;
  }

  fetch("/api/viewer-heartbeat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ active }),
    keepalive: true
  }).catch(() => {});
}

function startViewerHeartbeat() {
  if (document.visibilityState === "hidden") {
    return;
  }

  clearInterval(heartbeatTimer);
  sendViewerHeartbeat();
  heartbeatTimer = setInterval(sendViewerHeartbeat, 20000);
}

function stopViewerHeartbeat() {
  clearInterval(heartbeatTimer);
  heartbeatTimer = null;
  sendViewerHeartbeat(false);
}

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") {
    stopViewerHeartbeat();
  } else {
    startViewerHeartbeat();
  }
});

window.addEventListener("pagehide", stopViewerHeartbeat);

function render() {
  stage.style.background = state?.backgroundColor || "#05070a";
  image.style.objectFit = state?.imageMode === "full" ? "cover" : "contain";
  folderName.textContent = state?.folderName || "Slide Show";
  updateSettingsText();
  updateImageModeButtons();

  if (!images.length) {
    image.classList.remove("visible");
    emptyState.style.display = "grid";
    positionText.textContent = "0 / 0";
    return;
  }

  emptyState.style.display = "none";
  const current = images[index];
  positionText.textContent = `${index + 1} / ${images.length}`;
  image.classList.remove("visible");
  const nextSource = current.url;

  window.setTimeout(() => {
    image.src = nextSource;
    image.alt = current.name;
  }, 80);

  schedule();
}

image.addEventListener("load", () => {
  image.classList.add("visible");
});

function schedule() {
  clearTimeout(timer);
  if (!playing || !images.length) {
    return;
  }

  timer = setTimeout(() => advance(1), Math.max(2, state?.slideSeconds || 7) * 1000);
}

function advance(delta) {
  if (!images.length) {
    return;
  }

  index = (index + delta + images.length) % images.length;
  render();
}

function togglePlayback() {
  playing = !playing;
  schedule();
}

function playbackStatusText() {
  return playing ? "Playing" : "Paused";
}

function showChrome() {
  slideshowChrome.classList.remove("hidden");
  clearTimeout(chromeTimer);
}

function showChromeTemporarily() {
  showChrome();
  chromeTimer = setTimeout(() => {
    if (settingsPanel.hidden) {
      slideshowChrome.classList.add("hidden");
    }
  }, 2600);
}

function toggleSettingsPanel() {
  const shouldShow = settingsPanel.hidden;
  settingsPanel.hidden = !shouldShow;
  settingsToggle.setAttribute("aria-expanded", String(shouldShow));
  slideshowChrome.classList.toggle("panel-open", shouldShow);

  if (shouldShow) {
    showChrome();
  } else {
    showChromeTemporarily();
  }
}

function hideSettingsPanel() {
  if (settingsPanel.hidden) {
    return;
  }

  settingsPanel.hidden = true;
  settingsToggle.setAttribute("aria-expanded", "false");
  slideshowChrome.classList.remove("panel-open");
  showChromeTemporarily();
}

function showTapFeedback(value, position) {
  clearTimeout(feedbackTimer);
  tapFeedback.textContent = value;
  tapFeedback.dataset.position = position;
  tapFeedback.dataset.symbol = String(value.length === 1);
  tapFeedback.hidden = false;
  feedbackTimer = setTimeout(() => {
    tapFeedback.hidden = true;
  }, 1200);
}

function updateSettingsText() {
  timerText.textContent = `Advance every ${Math.max(2, state?.slideSeconds || 7)} seconds`;
}

function updateImageModeButtons() {
  const full = state?.imageMode === "full";
  fitMode.classList.toggle("active", !full);
  fullMode.classList.toggle("active", full);
  switchFolderGroup.hidden = !state?.canConfigure;
}

async function postPlaybackSettings(update) {
  state = {
    ...state,
    ...update
  };
  render();

  try {
    const nextState = await fetchJson("/api/playback-settings", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(update)
    });
    state = nextState;
    render();
  } catch {
    updateSettingsText();
    updateImageModeButtons();
  }
}

async function switchFolderWhilePlaying() {
  if (!state?.canConfigure || switchFolder.disabled) {
    return;
  }

  switchFolder.disabled = true;
  switchFolder.textContent = "Choosing...";
  showChrome();

  try {
    const nextState = await fetchJson("/api/choose-folder", { method: "POST" });
    await reloadSlideshow(nextState);
    showTapFeedback(images.length ? "Switched" : "No images", "center");
    showChrome();
  } catch {
    showTapFeedback("Unavailable", "center");
  } finally {
    switchFolder.textContent = "Switch folder";
    switchFolder.disabled = false;
  }
}

function updateTimer(seconds) {
  postPlaybackSettings({ slideSeconds: Math.max(2, Math.min(120, seconds)) });
}

function updateImageMode(mode) {
  postPlaybackSettings({ imageMode: mode === "full" ? "full" : "fit" });
}

settingsToggle.addEventListener("click", event => {
  event.stopPropagation();
  toggleSettingsPanel();
});

slower.addEventListener("click", () => {
  updateTimer((state?.slideSeconds || 7) - 1);
  showChrome();
});

faster.addEventListener("click", () => {
  updateTimer((state?.slideSeconds || 7) + 1);
  showChrome();
});

fitMode.addEventListener("click", () => {
  updateImageMode("fit");
  showChrome();
});

fullMode.addEventListener("click", () => {
  updateImageMode("full");
  showChrome();
});

switchFolder.addEventListener("click", switchFolderWhilePlaying);

stage.addEventListener("click", event => {
  if (event.target.closest("button")) {
    showChrome();
    return;
  }

  if (!settingsPanel.hidden && !event.target.closest(".settings-panel")) {
    hideSettingsPanel();
    return;
  }

  if (event.target.closest(".settings-panel")) {
    showChrome();
    return;
  }

  if (!images.length) {
    showChromeTemporarily();
    return;
  }

  const bounds = stage.getBoundingClientRect();
  const x = event.clientX - bounds.left;
  const third = bounds.width / 3;

  if (x < third) {
    advance(-1);
    showTapFeedback("\u2039", "start");
  } else if (x > third * 2) {
    advance(1);
    showTapFeedback("\u203a", "end");
  } else {
    togglePlayback();
    showTapFeedback(playbackStatusText(), "center");
  }

  showChromeTemporarily();
});

stage.addEventListener("pointermove", showChromeTemporarily);

document.addEventListener("keydown", event => {
  if (event.key === "Escape") {
    hideSettingsPanel();
  } else if (event.key === "ArrowRight") {
    advance(1);
    showTapFeedback("\u203a", "end");
  } else if (event.key === "ArrowLeft") {
    advance(-1);
    showTapFeedback("\u2039", "start");
  } else if (event.key === " ") {
    event.preventDefault();
    togglePlayback();
    showTapFeedback(playbackStatusText(), "center");
  }
  showChromeTemporarily();
});

load().catch(() => {
  emptyState.style.display = "grid";
  emptyState.querySelector("h1").textContent = "Slideshow unavailable";
  emptyState.querySelector("p").textContent = "Make sure Slide Show is running on this computer.";
});
