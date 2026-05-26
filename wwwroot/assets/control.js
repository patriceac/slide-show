const fields = {
  statusPill: document.querySelector("#statusPill"),
  openShow: document.querySelector("#openShow"),
  chooseFolder: document.querySelector("#chooseFolder"),
  folderPath: document.querySelector("#folderPath"),
  imageCount: document.querySelector("#imageCount"),
  lastScan: document.querySelector("#lastScan"),
  scanMessage: document.querySelector("#scanMessage"),
  saveSettings: document.querySelector("#saveSettings"),
  rescan: document.querySelector("#rescan"),
  slideSeconds: document.querySelector("#slideSeconds"),
  syncWorkers: document.querySelector("#syncWorkers"),
  backgroundColor: document.querySelector("#backgroundColor"),
  imageMode: Array.from(document.querySelectorAll("input[name='imageMode']")),
  includeSubfolders: document.querySelector("#includeSubfolders"),
  startAtLogin: document.querySelector("#startAtLogin"),
  backgroundValue: document.querySelector("#backgroundValue"),
  stagePreview: document.querySelector("#stagePreview"),
  serverPort: document.querySelector("#serverPort"),
  footerShowUrl: document.querySelector("#footerShowUrl"),
  toast: document.querySelector("#toast")
};

let currentState = null;
let toastTimer = null;
let hasUnsavedChanges = false;
let slideshowOverlay = null;

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options
  });

  if (!response.ok) {
    throw new Error("That change can only be made on this computer.");
  }

  return response.json();
}

function showToast(message) {
  fields.toast.textContent = message;
  fields.toast.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => fields.toast.classList.remove("show"), 2200);
}

function formatScanTime(value) {
  if (!value) {
    return "Not scanned yet";
  }

  return new Intl.DateTimeFormat(undefined, {
    hour: "numeric",
    minute: "2-digit",
    month: "short",
    day: "numeric"
  }).format(new Date(value));
}

function render(state) {
  currentState = state;
  fields.statusPill.textContent = "Running";
  fields.openShow.href = state.localSlideshowUrl || "/show";
  fields.folderPath.value = state.folderPath || "";
  fields.imageCount.textContent = state.imageCount.toLocaleString();
  fields.lastScan.textContent = formatScanTime(state.lastScannedAt);
  fields.scanMessage.textContent = state.scanMessage || (state.imageCount === 0 && state.folderPath ? "No supported images found in this folder." : "");
  fields.slideSeconds.value = state.slideSeconds;
  fields.syncWorkers.value = state.syncWorkers || 4;
  fields.backgroundColor.value = state.backgroundColor;
  if (fields.backgroundValue) {
    fields.backgroundValue.textContent = state.backgroundColor;
  }
  setImageMode(state.imageMode || "fit");
  fields.includeSubfolders.checked = state.includeSubfolders;
  fields.startAtLogin.checked = state.startAtLogin;
  if (fields.serverPort) {
    fields.serverPort.textContent = state.port;
  }
  if (fields.footerShowUrl) {
    fields.footerShowUrl.href = state.localSlideshowUrl || "/show";
    fields.footerShowUrl.textContent = state.localSlideshowUrl || "/show";
  }
  setSavePending(false);
}

function getImageMode() {
  return fields.imageMode.find(field => field.checked)?.value || "fit";
}

function setImageMode(value) {
  const mode = value === "full" ? "full" : "fit";
  fields.imageMode.forEach(field => {
    field.checked = field.value === mode;
  });
}

function getSettingsPayload() {
  return {
    folderPath: fields.folderPath.value,
    includeSubfolders: fields.includeSubfolders.checked,
    slideSeconds: Number(fields.slideSeconds.value),
    syncWorkers: Number(fields.syncWorkers.value),
    backgroundColor: fields.backgroundColor.value,
    imageMode: getImageMode(),
    startAtLogin: fields.startAtLogin.checked
  };
}

function normalizeFolderPath(value) {
  return (value || "").trim();
}

function normalizeColor(value) {
  return (value || "").toLowerCase();
}

async function refresh() {
  render(await api("/api/state"));
}

function setSavePending(pending) {
  hasUnsavedChanges = pending;
  fields.saveSettings.disabled = !pending;
}

function markUnsavedChanges() {
  if (currentState) {
    const payload = getSettingsPayload();
    setSavePending(
      normalizeFolderPath(payload.folderPath) !== (currentState.folderPath || "") ||
      payload.includeSubfolders !== currentState.includeSubfolders ||
      payload.slideSeconds !== currentState.slideSeconds ||
      payload.syncWorkers !== (currentState.syncWorkers || 4) ||
      normalizeColor(payload.backgroundColor) !== normalizeColor(currentState.backgroundColor) ||
      payload.imageMode !== (currentState.imageMode || "fit") ||
      payload.startAtLogin !== currentState.startAtLogin
    );
  }
}

function setChooseFolderLabel(label) {
  const labelElement = fields.chooseFolder.querySelector("span");
  if (labelElement) {
    labelElement.textContent = label;
  } else {
    fields.chooseFolder.textContent = label;
  }
}

function removeSlideshowOverlay() {
  if (!slideshowOverlay) {
    return;
  }

  slideshowOverlay.remove();
  slideshowOverlay = null;
}

function createSlideshowOverlay() {
  removeSlideshowOverlay();

  const overlay = document.createElement("div");
  overlay.className = "slideshow-overlay";
  overlay.setAttribute("aria-label", "Slideshow");
  document.body.append(overlay);
  slideshowOverlay = overlay;
  return overlay;
}

function mountSlideshowFrame() {
  if (!slideshowOverlay || slideshowOverlay.querySelector("iframe")) {
    return;
  }

  const frame = document.createElement("iframe");
  frame.title = "Slide Show";
  frame.src = fields.openShow.href || "/show";
  frame.allow = "fullscreen";
  slideshowOverlay.append(frame);
}

async function openSlideshowFullscreen() {
  const overlay = createSlideshowOverlay();

  if (!overlay.requestFullscreen) {
    window.location.href = fields.openShow.href || "/show";
    return;
  }

  try {
    await overlay.requestFullscreen({ navigationUI: "hide" });
    mountSlideshowFrame();
  } catch {
    removeSlideshowOverlay();
    window.location.href = fields.openShow.href || "/show";
  }
}

async function openNativeSlideshowWindow() {
  const response = await fetch("/api/open-slideshow-window", { method: "POST" });
  if (!response.ok) {
    throw new Error("Could not open full-screen slideshow.");
  }
}

document.addEventListener("fullscreenchange", () => {
  if (!document.fullscreenElement) {
    removeSlideshowOverlay();
  }
});

fields.openShow.addEventListener("click", async event => {
  event.preventDefault();
  try {
    await openNativeSlideshowWindow();
  } catch {
    openSlideshowFullscreen();
  }
});

fields.chooseFolder.addEventListener("click", async () => {
  fields.chooseFolder.disabled = true;
  setChooseFolderLabel("Choosing...");
  try {
    const result = await api("/api/choose-folder", { method: "POST" });
    const state = result.state || result;
    const selected = result.selected ?? normalizeFolderPath(state.folderPath) !== normalizeFolderPath(currentState?.folderPath);
    render(state);
    showToast(selected ? "Folder selected" : "No folder selected");
  } catch (error) {
    showToast(error.message);
  } finally {
    setChooseFolderLabel("Choose folder");
    fields.chooseFolder.disabled = false;
  }
});

fields.saveSettings.addEventListener("click", async () => {
  if (!hasUnsavedChanges) {
    return;
  }

  fields.saveSettings.disabled = true;
  try {
    render(await api("/api/settings", {
      method: "POST",
      body: JSON.stringify(getSettingsPayload())
    }));
    showToast("Changes saved");
  } catch (error) {
    showToast(error.message);
    markUnsavedChanges();
  } finally {
    fields.saveSettings.disabled = !hasUnsavedChanges;
  }
});

fields.rescan.addEventListener("click", async () => {
  fields.rescan.disabled = true;
  try {
    render(await api("/api/rescan", { method: "POST" }));
    showToast("Images rescanned");
  } catch (error) {
    showToast(error.message);
  } finally {
    fields.rescan.disabled = false;
  }
});

fields.startAtLogin.addEventListener("change", async () => {
  if (!currentState) {
    return;
  }

  try {
    render(await api("/api/settings", {
      method: "POST",
      body: JSON.stringify({ startAtLogin: fields.startAtLogin.checked })
    }));
  } catch (error) {
    fields.startAtLogin.checked = currentState.startAtLogin;
    showToast(error.message);
  }
});

[
  fields.folderPath,
  fields.slideSeconds,
  fields.syncWorkers,
  fields.backgroundColor,
  ...fields.imageMode,
  fields.includeSubfolders
].forEach(field => {
  field.addEventListener("input", markUnsavedChanges);
  field.addEventListener("change", markUnsavedChanges);
});

document.querySelectorAll(".step-button").forEach(button => {
  button.addEventListener("click", () => {
    const target = fields[button.dataset.stepTarget];
    if (!target) {
      return;
    }

    const step = Number(button.dataset.step || 1);
    const min = Number(target.min || Number.NEGATIVE_INFINITY);
    const max = Number(target.max || Number.POSITIVE_INFINITY);
    const next = Math.min(max, Math.max(min, Number(target.value || 0) + step));
    target.value = next;
    target.dispatchEvent(new Event("input", { bubbles: true }));
  });
});

setSavePending(false);
refresh().catch(() => {
  fields.statusPill.textContent = "Starting";
});
