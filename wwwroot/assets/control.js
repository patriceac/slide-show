import { isCryptoAvailable } from "./offline-crypto.js?v=20260529-offline2";
import { listCatalogs } from "./offline-store.js?v=20260529-offline2";

const fields = {
  statusPill: document.querySelector("#statusPill"),
  openShow: document.querySelector("#openShow"),
  openLibrary: document.querySelector("#openLibrary"),
  playCurrent: document.querySelector("#playCurrent"),
  chooseFolder: document.querySelector("#chooseFolder"),
  currentSlideshowName: document.querySelector("#currentSlideshowName"),
  currentFolderDetail: document.querySelector("#currentFolderDetail"),
  folderPath: document.querySelector("#folderPath"),
  imageCount: document.querySelector("#imageCount"),
  lastScan: document.querySelector("#lastScan"),
  scanMessage: document.querySelector("#scanMessage"),
  libraryList: document.querySelector("#libraryList"),
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

function formatRecentTime(value) {
  if (!value) {
    return "Last used recently";
  }

  return `Last used ${new Intl.DateTimeFormat(undefined, {
    hour: "numeric",
    minute: "2-digit",
    month: "short",
    day: "numeric"
  }).format(new Date(value))}`;
}

function formatBytes(bytes) {
  if (!bytes) {
    return "0 B";
  }

  const units = ["B", "KB", "MB", "GB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}

function formatCatalogDate(value) {
  if (!value) {
    return "not synced";
  }

  return new Intl.DateTimeFormat(undefined, {
    hour: "numeric",
    minute: "2-digit",
    month: "short",
    day: "numeric"
  }).format(new Date(value));
}

function folderNameFromPath(value) {
  const trimmed = normalizeFolderPath(value).replace(/[\\/]+$/, "");
  if (!trimmed) {
    return "";
  }

  const parts = trimmed.split(/[\\/]+/);
  return parts[parts.length - 1] || trimmed;
}

function render(state) {
  currentState = state;
  const hasFolder = Boolean(normalizeFolderPath(state.folderPath));
  const slideshowName = state.folderName || folderNameFromPath(state.folderPath) || "No slideshow selected";
  fields.statusPill.textContent = "Running";
  fields.openShow.href = state.localSlideshowUrl || "/show";
  fields.openShow.classList.toggle("is-disabled", !hasFolder);
  fields.openShow.setAttribute("aria-disabled", String(!hasFolder));
  fields.playCurrent.disabled = !hasFolder;
  fields.currentSlideshowName.textContent = slideshowName;
  fields.currentFolderDetail.textContent = hasFolder
    ? "This is the slideshow that will play on desktop."
    : "Choose an image folder to begin.";
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
    const showUrl = state.httpsEnabled ? state.httpsDisplaySlideshowUrl : state.localSlideshowUrl;
    fields.footerShowUrl.href = showUrl || "/show";
    fields.footerShowUrl.textContent = showUrl || "/show";
  }
  renderLibraries(state);
  setSavePending(false);
}

async function getOfflineCatalogs() {
  if (!isCryptoAvailable()) {
    return [];
  }

  try {
    return await listCatalogs();
  } catch {
    return [];
  }
}

async function renderLibraries(state) {
  fields.libraryList.replaceChildren();
  const currentPath = normalizeFolderPath(state.folderPath).toLowerCase();
  const recent = Array.isArray(state.recentSlideshows)
    ? state.recentSlideshows.filter(slideshow => normalizeFolderPath(slideshow.folderPath))
    : [];
  const offlineCatalogs = await getOfflineCatalogs();

  if (currentState !== state) {
    return;
  }

  if (recent.length === 0 && offlineCatalogs.length === 0) {
    const empty = document.createElement("div");
    empty.className = "library-empty";
    empty.textContent = "Libraries will appear here after you choose or save a slideshow.";
    fields.libraryList.append(empty);
    return;
  }

  recent.slice(0, 4).forEach(slideshow => {
    const folderPath = normalizeFolderPath(slideshow.folderPath);
    const isCurrent = currentPath && folderPath.toLowerCase() === currentPath;
    const row = document.createElement("article");
    row.className = `library-row${isCurrent ? " is-current" : ""}`;

    const details = document.createElement("div");
    const title = document.createElement("h4");
    title.textContent = slideshow.folderName || folderNameFromPath(folderPath) || "Slideshow";
    const meta = document.createElement("p");
    meta.textContent = `${isCurrent ? "Current - " : ""}${formatRecentTime(slideshow.lastUsedAt)} - ${folderPath}`;
    details.append(title, meta);

    const actions = document.createElement("div");
    actions.className = "library-actions";

    const playButton = document.createElement("button");
    playButton.type = "button";
    playButton.className = "primary-button";
    playButton.textContent = isCurrent && state.imageCount === 0 ? "Retry" : "Play";
    playButton.addEventListener("click", () => selectRecentSlideshow(folderPath, true));
    actions.append(playButton);

    row.append(details, actions);
    fields.libraryList.append(row);
  });

  offlineCatalogs.slice(0, 4).forEach(catalog => {
    const row = document.createElement("article");
    row.className = "library-row";

    const details = document.createElement("div");
    const title = document.createElement("h4");
    title.textContent = catalog.displayName || "Offline slideshow";
    const meta = document.createElement("p");
    const protection = catalog.protectionMode === "pin" ? "PIN protected" : "local key";
    meta.textContent = `${catalog.imageCount || 0} images - ${protection} - ${formatBytes(catalog.sizeBytes)} - ${formatCatalogDate(catalog.syncedAt)}`;
    details.append(title, meta);

    const actions = document.createElement("div");
    actions.className = "library-actions";
    const playButton = document.createElement("button");
    playButton.type = "button";
    playButton.className = "primary-button";
    playButton.textContent = "Play";
    playButton.addEventListener("click", () => openOfflineSlideshow(catalog.id));
    actions.append(playButton);

    row.append(details, actions);
    fields.libraryList.append(row);
  });
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

function mountSlideshowFrame(url = fields.openShow.href || "/show") {
  if (!slideshowOverlay || slideshowOverlay.querySelector("iframe")) {
    return;
  }

  const source = new URL(url, window.location.href);
  source.searchParams.set("embed", "1");

  const frame = document.createElement("iframe");
  frame.title = "Slide Show";
  frame.src = source.href;
  frame.allow = "fullscreen";
  slideshowOverlay.append(frame);
}

async function openSlideshowFullscreen(url) {
  const overlay = createSlideshowOverlay();

  if (!overlay.requestFullscreen) {
    removeSlideshowOverlay();
    return false;
  }

  try {
    await overlay.requestFullscreen({ navigationUI: "hide" });
    mountSlideshowFrame(url);
    return true;
  } catch {
    removeSlideshowOverlay();
    return false;
  }
}

async function openNativeSlideshowWindow() {
  const response = await fetch("/api/open-slideshow-window", { method: "POST" });
  if (!response.ok) {
    throw new Error("Could not open full-screen slideshow.");
  }
}

async function openCurrentSlideshow() {
  if (!currentState?.folderPath) {
    showToast("Choose a slideshow first");
    return;
  }

  if (await openSlideshowFullscreen(fields.openShow.href || "/show")) {
    return;
  }

  try {
    await openNativeSlideshowWindow();
  } catch {
    window.location.href = fields.openShow.href || "/show";
  }
}

async function openStageLibrary() {
  if (await openSlideshowFullscreen("/show")) {
    return;
  }

  window.location.href = "/show";
}

async function openOfflineSlideshow(catalogId) {
  if (!catalogId) {
    return;
  }

  const url = `/show?offlineCatalog=${encodeURIComponent(catalogId)}`;
  if (await openSlideshowFullscreen(url)) {
    return;
  }

  window.location.href = url;
}

function setRecentControlsDisabled(disabled) {
  fields.libraryList.querySelectorAll("button").forEach(button => {
    button.disabled = disabled;
  });
}

async function selectRecentSlideshow(folderPath, playAfter) {
  const selectedPath = normalizeFolderPath(folderPath);
  if (!selectedPath) {
    return;
  }

  if (selectedPath.toLowerCase() === normalizeFolderPath(currentState?.folderPath).toLowerCase()) {
    if (playAfter) {
      if (currentState.imageCount === 0) {
        const state = await api("/api/rescan", { method: "POST" });
        render(state);
        if (state.imageCount === 0) {
          showToast(state.scanMessage || "Slideshow unavailable");
          return;
        }
      }
      await openCurrentSlideshow();
    }
    return;
  }

  setRecentControlsDisabled(true);
  try {
    const state = await api("/api/settings", {
      method: "POST",
      body: JSON.stringify({ folderPath: selectedPath })
    });
    render(state);
    showToast("Slideshow selected");
    if (playAfter) {
      await openCurrentSlideshow();
    }
  } catch (error) {
    showToast(error.message);
  } finally {
    setRecentControlsDisabled(false);
  }
}

document.addEventListener("fullscreenchange", () => {
  if (!document.fullscreenElement) {
    removeSlideshowOverlay();
  }
});

fields.openShow.addEventListener("click", async event => {
  event.preventDefault();
  await openCurrentSlideshow();
});

fields.openLibrary.addEventListener("click", openStageLibrary);

fields.playCurrent.addEventListener("click", openCurrentSlideshow);

fields.chooseFolder.addEventListener("click", async () => {
  fields.chooseFolder.disabled = true;
  setChooseFolderLabel("Choosing...");
  try {
    const result = await api("/api/choose-folder", { method: "POST" });
    const state = result.state || result;
    const selected = result.selected ?? normalizeFolderPath(state.folderPath) !== normalizeFolderPath(currentState?.folderPath);
    render(state);
    showToast(selected ? "Slideshow selected" : "No folder selected");
  } catch (error) {
    showToast(error.message);
  } finally {
    setChooseFolderLabel("Switch folder");
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
