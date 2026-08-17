import {
  PIN_ITERATIONS,
  bytesToBase64,
  createPinVerifier,
  decryptBlob,
  derivePinKey,
  encryptBlob,
  generateLocalKey,
  isCryptoAvailable,
  randomBytes,
  verifyPinKey
} from "./offline-crypto.js?v=20260529-offline2";
import {
  deleteCatalog,
  deleteLocalKey,
  estimateStorage,
  folderIdentityFor,
  getImagesForCatalog,
  getLocalKey,
  listCatalogs,
  makeServerKey,
  saveCatalogBundle,
  saveLocalKey,
  updateCatalog
} from "./offline-store.js?v=20260529-offline2";
import { getSyncPlan, syncCatalog } from "./offline-sync.js?v=20260603-auto-refresh";

const stage = document.querySelector("#stage");
let image = document.querySelector("#slideImage");
let nextImage = document.querySelector("#nextSlideImage");
const emptyState = document.querySelector("#emptyState");
const emptyOfflineLibrary = document.querySelector("#emptyOfflineLibrary");
const slideshowChrome = document.querySelector("#chrome");
const folderName = document.querySelector("#folderName");
const positionText = document.querySelector("#positionText");
const fullscreenToggle = document.querySelector("#fullscreenToggle");
const stageLibrary = document.querySelector("#stageLibrary");
const settingsToggle = document.querySelector("#settingsToggle");
const settingsClose = document.querySelector("#settingsClose");
const settingsPanel = document.querySelector("#settingsPanel");
const previousSlide = document.querySelector("#previousSlide");
const playbackToggle = document.querySelector("#playbackToggle");
const nextSlide = document.querySelector("#nextSlide");
const playbackDock = document.querySelector(".playback-dock");
const slideProgress = document.querySelector("#slideProgress");
const timerText = document.querySelector("#timerText");
const slower = document.querySelector("#slower");
const faster = document.querySelector("#faster");
const fitMode = document.querySelector("#fitMode");
const fullMode = document.querySelector("#fullMode");
const switchFolderGroup = document.querySelector("#switchFolderGroup");
const switchFolder = document.querySelector("#switchFolder");
const tapFeedback = document.querySelector("#tapFeedback");
const saveOffline = document.querySelector("#saveOffline");
const openOffline = document.querySelector("#openOffline");
const offlineStatus = document.querySelector("#offlineStatus");
const offlineManage = document.querySelector("#offlineManage");
const renameOffline = document.querySelector("#renameOffline");
const deleteOfflineButton = document.querySelector("#deleteOffline");
const offlineProtection = document.querySelector("#offlineProtection");
const pinOffline = document.querySelector("#pinOffline");
const removePinOffline = document.querySelector("#removePinOffline");
const lockOffline = document.querySelector("#lockOffline");
const offlineModal = document.querySelector("#offlineModal");

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
let renderToken = 0;
let mode = "online";
let activeOfflineCatalog = null;
let offlineSession = null;
let currentObjectUrl = null;
let preloadedImage = null;

async function listPlayableCatalogs() {
  if (!isCryptoAvailable()) {
    return [];
  }

  try {
    return await listCatalogs();
  } catch {
    return [];
  }
}

function syncStageLibraryButton(catalogs = [], desktopLibraries = getDesktopLibraries()) {
  if (!stageLibrary) {
    return;
  }

  const count = catalogs.length + desktopLibraries.length;
  stageLibrary.hidden = count === 0;
  const label = count > 1 ? `Open library, ${count} saved slideshows` : "Open library";
  stageLibrary.setAttribute("aria-label", label);
  stageLibrary.title = count > 1 ? `Library (${count})` : "Library";
}

async function refreshStageLibraryButton(catalogs = null) {
  const nextCatalogs = catalogs || await listPlayableCatalogs();
  syncStageLibraryButton(nextCatalogs, getDesktopLibraries());
  return nextCatalogs;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
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

function formatDate(value) {
  if (!value) {
    return "not synced";
  }
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit"
  }).format(new Date(value));
}

function formatRecentTime(value) {
  if (!value) {
    return "Last used recently";
  }

  return `Last used ${new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit"
  }).format(new Date(value))}`;
}

function normalizeFolderPath(value) {
  return (value || "").trim();
}

function folderNameFromPath(value) {
  const trimmed = normalizeFolderPath(value).replace(/[\\/]+$/, "");
  if (!trimmed) {
    return "";
  }

  const parts = trimmed.split(/[\\/]+/);
  return parts[parts.length - 1] || trimmed;
}

function getDesktopLibraries(currentState = state) {
  if (!currentState?.canConfigure || !Array.isArray(currentState.recentSlideshows)) {
    return [];
  }

  return currentState.recentSlideshows
    .filter(slideshow => normalizeFolderPath(slideshow.folderPath))
    .slice(0, 8);
}

function isCurrentDesktopLibrary(slideshow) {
  const currentPath = normalizeFolderPath(state?.folderPath).toLowerCase();
  const folderPath = normalizeFolderPath(slideshow?.folderPath).toLowerCase();
  return Boolean(currentPath && folderPath && currentPath === folderPath);
}

function etaText(completed, total, elapsedMs) {
  if (!completed || completed >= total) {
    return "";
  }
  const remaining = Math.max(0, (elapsedMs * (total - completed)) / completed);
  if (remaining < 1500) {
    return "ETA under 1s";
  }
  const seconds = Math.round(remaining / 1000);
  return seconds < 60 ? `ETA ${seconds}s` : `ETA ${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

function offlineRecordsMatchSource(records, imageList) {
  if (records.length !== imageList.length) {
    return false;
  }

  const recordsByKey = new Map(records.map(record => [record.cacheKey, record.name || "Image"]));
  return imageList.every(image => recordsByKey.get(image.cacheKey) === (image.name || "Image"));
}

function getCatalogMetadataUpdate(catalog, nextState) {
  return {
    ...catalog,
    folderName: nextState.folderName || catalog.folderName,
    folderPath: nextState.folderPath || "",
    imageMode: nextState.imageMode || catalog.imageMode || "fit",
    slideSeconds: nextState.slideSeconds || catalog.slideSeconds || 7,
    backgroundColor: nextState.backgroundColor || catalog.backgroundColor || "#05070a"
  };
}

function catalogMetadataChanged(catalog, nextCatalog) {
  return ["folderName", "folderPath", "imageMode", "slideSeconds", "backgroundColor"]
    .some(key => catalog[key] !== nextCatalog[key]);
}

async function fetchJson(path, options) {
  const response = await fetch(path, { cache: "no-store", ...options });
  if (!response.ok) {
    throw new Error("Unavailable");
  }
  const result = await response.json();
  if (result?.offline) {
    throw new Error("Offline");
  }
  return result;
}

function registerServiceWorker() {
  if (!("serviceWorker" in navigator) || !window.isSecureContext) {
    return;
  }

  navigator.serviceWorker.register("/service-worker.js").catch(() => {});
}

function isStandaloneDisplay() {
  return window.matchMedia?.("(display-mode: fullscreen)")?.matches ||
    window.matchMedia?.("(display-mode: standalone)")?.matches ||
    navigator.standalone === true;
}

function isNativeSlideshowWindow() {
  const search = new URLSearchParams(window.location.search);
  return search.get("window") === "1" ||
    search.get("embed") === "1" ||
    Boolean(window.chrome?.webview);
}

function getRequestedOfflineCatalogId() {
  return new URLSearchParams(window.location.search).get("offlineCatalog") || "";
}

async function openRequestedOfflineCatalog() {
  const catalogId = getRequestedOfflineCatalogId();
  if (!catalogId || !isCryptoAvailable()) {
    return false;
  }

  const catalogs = await listPlayableCatalogs();
  const catalog = catalogs.find(item => item.id === catalogId);
  if (!catalog) {
    return false;
  }

  await openOfflineCatalog(catalog);
  return true;
}

function isPlaybackFullscreen() {
  return Boolean(document.fullscreenElement || document.webkitFullscreenElement) || isStandaloneDisplay() || isNativeSlideshowWindow();
}

function shouldHideMouseCursor() {
  const chromeVisible = !slideshowChrome.classList.contains("hidden");
  const settingsVisible = !settingsPanel.hidden;
  const modalVisible = !offlineModal.hidden;
  return isPlaybackFullscreen() && playing && images.length > 0 && !chromeVisible && !settingsVisible && !modalVisible;
}

function updateFullscreenState() {
  if (fullscreenToggle) {
    const fullscreen = Boolean(document.fullscreenElement || document.webkitFullscreenElement);
    const label = fullscreen ? "Exit full screen" : "Open full screen";
    fullscreenToggle.setAttribute("aria-label", label);
    fullscreenToggle.title = label;
    fullscreenToggle.hidden = isStandaloneDisplay() || isNativeSlideshowWindow();
  }

  stage.classList.toggle("is-playback-fullscreen", shouldHideMouseCursor());
}

function updateMouseCursorVisibility() {
  stage.classList.toggle("is-playback-fullscreen", shouldHideMouseCursor());
}

async function openNativeSlideshowWindow() {
  try {
    const response = await fetch("/api/open-slideshow-window", { method: "POST" });
    return response.ok;
  } catch {
    return false;
  }
}

async function toggleFullscreen() {
  const fullscreenElement = document.fullscreenElement || document.webkitFullscreenElement;
  if (fullscreenElement) {
    const exitFullscreen = document.exitFullscreen || document.webkitExitFullscreen;
    if (!exitFullscreen) {
      throw new Error("Fullscreen unavailable.");
    }
    await exitFullscreen.call(document);
    return;
  }

  const requestFullscreen = stage.requestFullscreen || stage.webkitRequestFullscreen;
  if (requestFullscreen) {
    try {
      await requestFullscreen.call(stage);
      if (document.fullscreenElement || document.webkitFullscreenElement) {
        return;
      }
    } catch {
    }
  }

  if (await openNativeSlideshowWindow()) {
    showTapFeedback("Opened", "center");
    return;
  }

  throw new Error("Fullscreen unavailable.");
}

async function load() {
  try {
    state = await fetchJson("/api/state");
    images = await fetchJson("/api/images?shuffle=true");
    mode = "online";
    activeOfflineCatalog = null;
    offlineSession = null;
    index = 0;
    await refreshStageLibraryButton();
    if (await openRequestedOfflineCatalog()) {
      return;
    }
    startViewerHeartbeat();
    startRefreshWatcher();
    await render();
    showChromeTemporarily();
  } catch {
    await showOfflineStartup();
  }
}

async function reloadSlideshow(nextState, options = {}) {
  if (isReloading || mode !== "online") {
    return;
  }

  isReloading = true;
  try {
    state = nextState || await fetchJson("/api/state");
    images = await fetchJson("/api/images?shuffle=true");
    index = options.keepPosition ? Math.min(index, Math.max(0, images.length - 1)) : 0;
    await render();
  } finally {
    isReloading = false;
  }
}

function startRefreshWatcher() {
  clearInterval(refreshTimer);
  refreshTimer = setInterval(async () => {
    if (mode !== "online" || document.visibilityState === "hidden" || isReloading) {
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
        await render();
      }
    } catch {
      // Keep the current slideshow running if the local server is briefly unavailable.
    }
  }, 1000);
}

function sendViewerHeartbeat(active = true) {
  if (mode !== "online" || (active && document.visibilityState === "hidden")) {
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
  if (mode !== "online" || document.visibilityState === "hidden") {
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

window.addEventListener("pagehide", () => {
  stopViewerHeartbeat();
  revokeObjectUrl();
});

function revokeObjectUrl() {
  if (currentObjectUrl) {
    URL.revokeObjectURL(currentObjectUrl);
    currentObjectUrl = null;
  }
}

function clearPlaybackTimer() {
  clearTimeout(timer);
  timer = null;
  slideProgress.classList.remove("running", "paused");
}

function loadFrame(frame, source, alt) {
  return new Promise((resolve, reject) => {
    const settle = callback => {
      frame.removeEventListener("load", onLoad);
      frame.removeEventListener("error", onError);
      callback();
    };
    const onLoad = () => settle(resolve);
    const onError = () => settle(() => reject(new Error("Image failed to load.")));

    frame.classList.remove("visible");
    frame.alt = alt;
    frame.addEventListener("load", onLoad);
    frame.addEventListener("error", onError);
    frame.src = source;

    if (frame.complete && frame.naturalWidth > 0) {
      onLoad();
    }
  });
}

function preloadFollowingImage() {
  if (mode !== "online" || images.length < 2) {
    preloadedImage = null;
    return;
  }

  const following = images[(index + 1) % images.length];
  if (!following?.url) {
    return;
  }

  preloadedImage = new Image();
  preloadedImage.decoding = "async";
  preloadedImage.src = following.url;
}

async function render() {
  clearPlaybackTimer();
  const token = ++renderToken;
  const catalog = activeOfflineCatalog;
  const currentState = mode === "offline" && catalog ? catalog : state;
  stage.style.background = currentState?.backgroundColor || "#05070a";
  image.style.objectFit = currentState?.imageMode === "full" ? "cover" : "contain";
  nextImage.style.objectFit = currentState?.imageMode === "full" ? "cover" : "contain";
  folderName.textContent = mode === "offline" && catalog ? `${catalog.displayName} - Offline` : currentState?.folderName || "Slide Show";
  updateSettingsText();
  updateImageModeButtons();
  updateOfflinePanel();

  if (mode === "offline" && catalog?.protectionMode === "pin" && !offlineSession) {
    await showEmpty("Protected slideshow", "Enter the PIN to continue.", "Locked", { showOfflineLibrary: false });
    return;
  }

  if (!images.length) {
    playbackDock.hidden = true;
    const catalogs = await listPlayableCatalogs();
    const desktopLibraries = getDesktopLibraries();
    await refreshStageLibraryButton(catalogs);
    if (catalogs.length || desktopLibraries.length) {
      await showEmpty(
        "Slideshow libraries",
        currentState?.scanMessage ||
          (mode === "offline"
            ? "Pick a saved slideshow stored on this device."
            : "Pick a saved slideshow from this computer, or open an encrypted offline copy."),
        `${catalogs.length + desktopLibraries.length} saved`,
        { catalogs, desktopLibraries }
      );
    } else {
      await showEmpty("Choose an image folder", "Open the control center on this computer to pick a folder.", "0 / 0");
    }
    return;
  }

  playbackDock.hidden = false;
  emptyState.style.display = "none";
  renderEmptyOfflineLibrary([], []);
  const current = images[index];
  positionText.textContent = `${index + 1} / ${images.length}`;

  try {
    const source = await resolveImageSource(current);
    if (token !== renderToken) {
      if (source.owned) {
        URL.revokeObjectURL(source.url);
      }
      return;
    }

    const incomingFrame = nextImage;
    const outgoingFrame = image;
    const outgoingObjectUrl = currentObjectUrl;
    await loadFrame(incomingFrame, source.url, current.name || "Slide");

    if (token !== renderToken) {
      if (source.owned) {
        URL.revokeObjectURL(source.url);
      }
      return;
    }

    incomingFrame.classList.add("visible");
    outgoingFrame.classList.remove("visible");
    image = incomingFrame;
    nextImage = outgoingFrame;
    currentObjectUrl = source.owned ? source.url : null;

    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    if (token !== renderToken) {
      return;
    }

    schedule();
    preloadFollowingImage();

    window.setTimeout(() => {
      if (outgoingFrame !== image) {
        outgoingFrame.removeAttribute("src");
        outgoingFrame.alt = "";
      }
      if (outgoingObjectUrl) {
        URL.revokeObjectURL(outgoingObjectUrl);
      }
    }, 380);
  } catch {
    if (token === renderToken) {
      showTapFeedback("Image failed", "center");
      schedule();
    }
  }
}

async function showEmpty(title, message, position, options = {}) {
  clearPlaybackTimer();
  playbackDock.hidden = true;
  revokeObjectUrl();
  image.classList.remove("visible");
  nextImage.classList.remove("visible");
  image.removeAttribute("src");
  nextImage.removeAttribute("src");
  emptyState.style.display = "grid";
  emptyState.querySelector("h1").textContent = title;
  emptyState.querySelector("p").textContent = message;
  positionText.textContent = position;

  if (options.showOfflineLibrary === false) {
    renderEmptyOfflineLibrary([], []);
    return;
  }

  renderEmptyOfflineLibrary(
    options.catalogs || await listPlayableCatalogs(),
    options.desktopLibraries || getDesktopLibraries()
  );
}

function renderEmptyOfflineLibrary(catalogs = [], desktopLibraries = getDesktopLibraries()) {
  syncStageLibraryButton(catalogs, desktopLibraries);

  if (!emptyOfflineLibrary) {
    return;
  }

  const total = catalogs.length + desktopLibraries.length;
  emptyState.classList.toggle("has-offline-library", total > 0);
  emptyOfflineLibrary.hidden = total === 0;
  emptyOfflineLibrary.replaceChildren();

  if (!total) {
    return;
  }

  const heading = document.createElement("div");
  heading.className = "empty-library-heading";

  const title = document.createElement("strong");
  title.textContent = catalogs.length && desktopLibraries.length
    ? "Libraries"
    : desktopLibraries.length
      ? "Slideshow library"
      : "Offline library";

  const manage = document.createElement("button");
  manage.type = "button";
  manage.textContent = "Library";
  manage.addEventListener("click", event => {
    event.stopPropagation();
    showStageLibrary();
  });

  heading.append(title, manage);

  const list = document.createElement("div");
  list.className = "empty-catalog-list";

  desktopLibraries.slice(0, 4).forEach(slideshow => {
    list.append(createDesktopLibraryCard(slideshow));
  });

  catalogs.slice(0, 4).forEach(catalog => {
    list.append(createOfflineCatalogCard(catalog));
  });

  emptyOfflineLibrary.append(heading, list);
}

function createDesktopLibraryCard(slideshow) {
  const folderPath = normalizeFolderPath(slideshow.folderPath);
  const current = isCurrentDesktopLibrary(slideshow);
  const card = document.createElement("article");
  card.className = `empty-catalog-card${current ? " is-current" : ""}`;

  const details = document.createElement("div");
  const name = document.createElement("strong");
  name.textContent = slideshow.folderName || folderNameFromPath(folderPath) || "Slideshow";
  const meta = document.createElement("small");
  meta.textContent = `${current ? "Current - " : ""}${formatRecentTime(slideshow.lastUsedAt)} - ${folderPath}`;
  details.append(name, meta);

  const actions = document.createElement("div");
  actions.className = "empty-catalog-actions";
  const play = document.createElement("button");
  play.type = "button";
  play.className = "primary";
  play.textContent = current ? "Retry" : "Play";
  play.addEventListener("click", async event => {
    event.stopPropagation();
    play.disabled = true;
    await openDesktopLibrary(slideshow);
    play.disabled = false;
  });
  actions.append(play);

  card.append(details, actions);
  return card;
}

function createOfflineCatalogCard(catalog) {
  const card = document.createElement("article");
  card.className = "empty-catalog-card";

  const details = document.createElement("div");
  const name = document.createElement("strong");
  name.textContent = catalog.displayName;
  const meta = document.createElement("small");
  const protection = catalog.protectionMode === "pin" ? "PIN protected" : "local key";
  meta.textContent = `${catalog.imageCount || 0} images - ${protection} - ${formatBytes(catalog.sizeBytes)} - ${formatDate(catalog.syncedAt)}`;
  details.append(name, meta);

  const actions = document.createElement("div");
  actions.className = "empty-catalog-actions";
  const play = document.createElement("button");
  play.type = "button";
  play.className = "primary";
  play.textContent = "Play";
  play.addEventListener("click", async event => {
    event.stopPropagation();
    play.disabled = true;
    await openOfflineCatalog(catalog);
    play.disabled = false;
  });
  actions.append(play);

  card.append(details, actions);
  return card;
}

async function refreshEmptyOfflineLibrary() {
  if (!emptyOfflineLibrary || emptyState.style.display === "none") {
    return;
  }

  renderEmptyOfflineLibrary(await refreshStageLibraryButton(), getDesktopLibraries());
}

async function resolveImageSource(current) {
  if (mode !== "offline") {
    return { url: current.url, owned: false };
  }

  if (!offlineSession?.key || !activeOfflineCatalog) {
    throw new Error("Offline slideshow is locked.");
  }

  const record = current.encryptedBlob
    ? current
    : await getImagesForCatalog(activeOfflineCatalog.id).then(records => records[index]);
  const clearBlob = await decryptBlob(offlineSession.key, record.encryptedBlob, record.iv, record.mimeType);
  return { url: URL.createObjectURL(clearBlob), owned: true };
}

function schedule() {
  clearPlaybackTimer();
  updatePlaybackUI();
  if (!playing || !images.length) {
    return;
  }

  const duration = Math.max(2, currentSlideSeconds());
  slideProgress.style.setProperty("--slide-duration", `${duration}s`);
  void slideProgress.offsetWidth;
  slideProgress.classList.add("running");
  timer = setTimeout(() => advance(1), duration * 1000);
}

function currentSlideSeconds() {
  return mode === "offline" ? activeOfflineCatalog?.slideSeconds || 7 : state?.slideSeconds || 7;
}

function currentImageMode() {
  return mode === "offline" ? activeOfflineCatalog?.imageMode || "fit" : state?.imageMode || "fit";
}

async function advance(delta) {
  if (!images.length) {
    return;
  }

  clearPlaybackTimer();
  index = (index + delta + images.length) % images.length;
  await render();
}

function togglePlayback() {
  playing = !playing;
  schedule();
  updateMouseCursorVisibility();
}

function updatePlaybackUI() {
  stage.classList.toggle("is-paused", !playing);
  const label = playing ? "Pause slideshow" : "Play slideshow";
  playbackToggle.setAttribute("aria-label", label);
  playbackToggle.title = playing ? "Pause" : "Play";
}

function playbackStatusText() {
  return playing ? "Playing" : "Paused";
}

function showChrome() {
  slideshowChrome.classList.remove("hidden");
  clearTimeout(chromeTimer);
  updateMouseCursorVisibility();
}

function showChromeTemporarily() {
  showChrome();
  chromeTimer = setTimeout(() => {
    if (settingsPanel.hidden && images.length && playing) {
      slideshowChrome.classList.add("hidden");
    }
    updateMouseCursorVisibility();
  }, 2600);
}

function toggleSettingsPanel() {
  const shouldShow = settingsPanel.hidden;
  settingsPanel.hidden = !shouldShow;
  settingsToggle.setAttribute("aria-expanded", String(shouldShow));
  settingsToggle.setAttribute("aria-label", shouldShow ? "Close playback settings" : "Open playback settings");
  settingsToggle.title = shouldShow ? "Close settings" : "Settings";
  slideshowChrome.classList.toggle("panel-open", shouldShow);

  if (shouldShow) {
    updateOfflinePanel();
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
  settingsToggle.setAttribute("aria-label", "Open playback settings");
  settingsToggle.title = "Settings";
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
  timerText.textContent = `Advance every ${Math.max(2, currentSlideSeconds())} seconds`;
}

function updateImageModeButtons() {
  const full = currentImageMode() === "full";
  fitMode.classList.toggle("active", !full);
  fullMode.classList.toggle("active", full);
  switchFolderGroup.hidden = mode !== "online" || !state?.canConfigure;
}

function updateOfflinePanel() {
  const available = isCryptoAvailable();
  saveOffline.disabled = !available || mode !== "online";
  openOffline.disabled = !available;
  offlineManage.hidden = !activeOfflineCatalog;
  offlineProtection.hidden = !activeOfflineCatalog;
  removePinOffline.hidden = activeOfflineCatalog?.protectionMode !== "pin";
  lockOffline.disabled = activeOfflineCatalog?.protectionMode !== "pin" || !offlineSession;
  pinOffline.textContent = activeOfflineCatalog?.protectionMode === "pin" ? "Change PIN" : "Set PIN";

  if (!available) {
    offlineStatus.textContent = "Open the HTTPS slideshow link to enable encrypted offline playback.";
  } else if (mode === "offline" && activeOfflineCatalog) {
    const protection = activeOfflineCatalog.protectionMode === "pin" ? "PIN protected" : "local key";
    offlineStatus.textContent = `${activeOfflineCatalog.imageCount || images.length} images - ${protection} - ${formatBytes(activeOfflineCatalog.sizeBytes)}`;
  } else {
    offlineStatus.textContent = state?.httpsEnabled || window.location.protocol === "https:"
      ? "Encrypted offline copies stay on this device."
      : "Use the HTTPS mobile link before saving offline on another device.";
  }
}

document.addEventListener("fullscreenchange", updateFullscreenState);
document.addEventListener("webkitfullscreenchange", updateFullscreenState);

function applyPlaybackSettingsToView() {
  const objectFit = currentImageMode() === "full" ? "cover" : "contain";
  image.style.objectFit = objectFit;
  nextImage.style.objectFit = objectFit;
  updateSettingsText();
  updateImageModeButtons();
  updateOfflinePanel();
  schedule();
}

async function postPlaybackSettings(update) {
  if (mode === "offline") {
    if (activeOfflineCatalog) {
      activeOfflineCatalog = {
        ...activeOfflineCatalog,
        ...update
      };
      await updateCatalog(activeOfflineCatalog);
    }
    applyPlaybackSettingsToView();
    return;
  }

  state = {
    ...state,
    ...update
  };
  applyPlaybackSettingsToView();

  try {
    const nextState = await fetchJson("/api/playback-settings", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(update)
    });
    state = nextState;
    applyPlaybackSettingsToView();
  } catch {
    updateSettingsText();
    updateImageModeButtons();
  }
}

async function switchFolderWhilePlaying() {
  if (mode !== "online" || !state?.canConfigure || switchFolder.disabled) {
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

async function openDesktopLibrary(slideshow) {
  const folderPath = normalizeFolderPath(slideshow?.folderPath);
  if (!folderPath || !state?.canConfigure) {
    showTapFeedback("Unavailable", "center");
    return;
  }

  showChrome();
  try {
    const currentPath = normalizeFolderPath(state?.folderPath).toLowerCase();
    const nextPath = folderPath.toLowerCase();
    const nextState = currentPath && currentPath === nextPath
      ? await fetchJson("/api/rescan", { method: "POST" })
      : await fetchJson("/api/settings", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ folderPath })
      });

    mode = "online";
    activeOfflineCatalog = null;
    offlineSession = null;
    await reloadSlideshow(nextState);
    showTapFeedback(images.length ? "Playing" : "Unavailable", "center");
    showChromeTemporarily();
  } catch {
    showTapFeedback("Unavailable", "center");
  }
}

function updateTimer(seconds) {
  postPlaybackSettings({ slideSeconds: Math.max(2, Math.min(120, seconds)) });
}

function updateImageMode(nextMode) {
  postPlaybackSettings({ imageMode: nextMode === "full" ? "full" : "fit" });
}

function showModal(html, bind, options = {}) {
  return new Promise(resolve => {
    offlineModal.innerHTML = `<section class="modal-card">${html}</section>`;
    offlineModal.hidden = false;
    updateMouseCursorVisibility();
    const card = offlineModal.querySelector(".modal-card");
    const close = value => {
      offlineModal.removeEventListener("click", handleBackdropClick);
      offlineModal.hidden = true;
      offlineModal.innerHTML = "";
      updateMouseCursorVisibility();
      resolve(value);
    };
    const handleBackdropClick = event => {
      if (options.dismissOnBackdrop && event.target === offlineModal) {
        close(null);
      }
    };
    offlineModal.addEventListener("click", handleBackdropClick);
    offlineModal.querySelectorAll("[data-cancel]").forEach(button => {
      button.addEventListener("click", () => close(null));
    });
    bind?.(card, close);
  });
}

function messageModal(title, message) {
  return showModal(`
    <h2>${escapeHtml(title)}</h2>
    <p>${escapeHtml(message)}</p>
    <div class="modal-actions">
      <button class="primary" data-cancel type="button">OK</button>
    </div>
  `);
}

function confirmModal(title, message, confirmText = "Confirm", danger = false) {
  return showModal(`
    <h2>${escapeHtml(title)}</h2>
    <p>${escapeHtml(message)}</p>
    <div class="modal-actions">
      <button data-cancel type="button">Cancel</button>
      <button class="${danger ? "danger" : "primary"}" data-confirm type="button">${escapeHtml(confirmText)}</button>
    </div>
  `, (card, close) => {
    card.querySelector("[data-confirm]").addEventListener("click", () => close(true));
  });
}

function promptModal({ title, message, fields, primary = "Save" }) {
  const fieldHtml = fields.map(field => `
    <label class="modal-field">
      <span>${escapeHtml(field.label)}</span>
      <input name="${escapeHtml(field.name)}" type="${field.type || "text"}" value="${escapeHtml(field.value || "")}" ${field.autocomplete ? `autocomplete="${escapeHtml(field.autocomplete)}"` : ""}>
    </label>
  `).join("");

  return showModal(`
    <h2>${escapeHtml(title)}</h2>
    <p>${escapeHtml(message)}</p>
    <form class="modal-stack">
      ${fieldHtml}
      <div class="modal-actions">
        <button data-cancel type="button">Cancel</button>
        <button class="primary" type="submit">${escapeHtml(primary)}</button>
      </div>
    </form>
  `, (card, close) => {
    const form = card.querySelector("form");
    form.addEventListener("submit", event => {
      event.preventDefault();
      const data = new FormData(form);
      const values = {};
      for (const field of fields) {
        values[field.name] = data.get(field.name)?.toString() || "";
      }
      close(values);
    });
    form.querySelector("input")?.focus();
  });
}

async function promptOptionalPin() {
  const values = await promptModal({
    title: "Save offline",
    message: "Leave PIN blank for transparent local unlock. Add a PIN to require it before playback.",
    fields: [
      { name: "pin", label: "PIN (optional)", type: "password", autocomplete: "new-password" },
      { name: "confirm", label: "Confirm PIN", type: "password", autocomplete: "new-password" }
    ],
    primary: "Save"
  });

  if (!values) {
    return null;
  }

  const pin = values.pin.trim();
  if (!pin) {
    return "";
  }
  if (pin.length < 4) {
    await messageModal("PIN too short", "Use at least 4 digits.");
    return promptOptionalPin();
  }
  if (pin !== values.confirm.trim()) {
    await messageModal("PINs do not match", "Enter the same PIN twice.");
    return promptOptionalPin();
  }
  return pin;
}

async function promptUnlockPin(catalog) {
  const values = await promptModal({
    title: "Unlock slideshow",
    message: `Enter the PIN for ${catalog.displayName}.`,
    fields: [
      { name: "pin", label: "PIN", type: "password", autocomplete: "current-password" }
    ],
    primary: "Unlock"
  });
  return values?.pin.trim() || null;
}

async function chooseReplacement(catalogs) {
  const items = catalogs.map(catalog => `
    <article class="catalog-card">
      <strong>${escapeHtml(catalog.displayName)}</strong>
      <small>${escapeHtml(catalog.imageCount || 0)} images - ${escapeHtml(formatBytes(catalog.sizeBytes))} - ${escapeHtml(formatDate(catalog.syncedAt))}</small>
      <div class="catalog-actions">
        <button class="danger" data-replace="${escapeHtml(catalog.id)}" type="button">Replace</button>
      </div>
    </article>
  `).join("");

  return showModal(`
    <h2>Replace a saved slideshow</h2>
    <p>This device keeps up to 4 encrypted offline slideshows.</p>
    <div class="modal-stack catalog-list">${items}</div>
    <div class="modal-actions">
      <button data-cancel type="button">Cancel</button>
    </div>
  `, (card, close) => {
    card.querySelectorAll("[data-replace]").forEach(button => {
      button.addEventListener("click", () => close(button.dataset.replace));
    });
  });
}

function showProgressModal(title, message) {
  offlineModal.innerHTML = `
    <section class="modal-card">
      <h2>${escapeHtml(title)}</h2>
      <p data-progress-message>${escapeHtml(message)}</p>
      <div class="modal-stack">
        <div class="sync-progress"><span data-progress-bar></span></div>
      </div>
    </section>
  `;
  offlineModal.hidden = false;
  updateMouseCursorVisibility();
  return {
    update(progress) {
      const percent = progress.total ? Math.round((progress.completed / progress.total) * 100) : 0;
      offlineModal.querySelector("[data-progress-bar]").style.width = `${percent}%`;
      offlineModal.querySelector("[data-progress-message]").textContent =
        `Saved ${progress.completed} of ${progress.total} images - ${etaText(progress.completed, progress.total, progress.elapsedMs)}`;
    },
    close() {
      offlineModal.hidden = true;
      offlineModal.innerHTML = "";
      updateMouseCursorVisibility();
    }
  };
}

async function saveCurrentOffline() {
  if (!isCryptoAvailable()) {
    await messageModal("HTTPS required", "Open the HTTPS slideshow link to enable encrypted offline playback.");
    return;
  }

  let nextState;
  let imageList;
  try {
    const source = await fetchJson("/api/offline-source");
    nextState = source.state;
    imageList = source.images || [];
  } catch {
    await messageModal("Server unavailable", "Reconnect to Slide Show before saving an offline copy.");
    return;
  }

  if (!imageList.length) {
    await messageModal("No images", "Choose a folder with images before saving offline.");
    return;
  }

  const plan = await getSyncPlan(nextState);
  let replaceCatalogId = null;
  if (plan.needsReplacement) {
    replaceCatalogId = await chooseReplacement(plan.catalogs);
    if (!replaceCatalogId) {
      return;
    }
  }

  let pin = "";
  let protectionKey = null;
  if (plan.existing?.protectionMode === "pin") {
    protectionKey = await unlockCatalogKey(plan.existing);
    if (!protectionKey) {
      return;
    }
  } else {
    pin = await promptOptionalPin();
    if (pin === null) {
      return;
    }
  }

  const progress = showProgressModal("Saving offline", "Encrypting images on this device.");
  try {
    const catalog = await syncCatalog({
      state: nextState,
      imageList,
      pin,
      protectionKey,
      replaceCatalogId,
      onProgress: value => progress.update(value)
    });
    progress.close();
    showTapFeedback("Saved", "center");
    await messageModal(plan.existing ? "Updated offline" : "Saved offline", `${catalog.displayName} is ready for encrypted offline playback.`);
    updateOfflinePanel();
    await refreshStageLibraryButton();
  } catch (error) {
    progress.close();
    await messageModal("Save failed", error.message || "The offline copy could not be saved.");
  }
}

async function unlockCatalogKey(catalog) {
  if (catalog.protectionMode === "pin") {
    const pin = await promptUnlockPin(catalog);
    if (!pin) {
      return null;
    }
    try {
      const key = await derivePinKey(pin, catalog.salt, catalog.iterations || PIN_ITERATIONS);
      if (await verifyPinKey(key, catalog.verifierData, catalog.verifierIv)) {
        return key;
      }
    } catch {
    }
    await messageModal("Wrong PIN", "That PIN did not unlock the saved slideshow.");
    return null;
  }

  const key = await getLocalKey(catalog.id);
  if (!key) {
    await messageModal("Offline key missing", "This saved slideshow can no longer be opened. Delete it and sync again.");
  }
  return key;
}

async function showOfflineStartup() {
  mode = "offline";
  state = {
    folderName: "Offline",
    backgroundColor: "#05070a",
    imageMode: "fit",
    slideSeconds: 7,
    canConfigure: false
  };
  activeOfflineCatalog = null;
  offlineSession = null;
  images = [];
  if (await openRequestedOfflineCatalog()) {
    return;
  }
  const catalogs = await listPlayableCatalogs();
  if (catalogs.length) {
    await showEmpty(
      "Offline libraries",
      "Pick a saved slideshow stored on this device.",
      `${catalogs.length} saved`,
      { catalogs }
    );
  } else {
    await showEmpty("Slideshow unavailable", "Reconnect to Slide Show to save an encrypted offline copy.", "0 / 0");
  }
}

async function fetchRefreshSourceForCatalog(catalog, showErrors) {
  let nextState;
  let imageList;
  try {
    const source = await fetchJson("/api/offline-source");
    nextState = source.state;
    imageList = source.images || [];
  } catch {
    if (showErrors) {
      await messageModal("Server unavailable", "Reconnect to Slide Show before refreshing this offline copy.");
    }
    return null;
  }

  if (!imageList.length) {
    if (showErrors) {
      await messageModal("No images", "Choose a folder with images before refreshing this offline copy.");
    }
    return null;
  }

  const plan = await getSyncPlan(nextState);
  if (plan.existing?.id !== catalog.id) {
    if (showErrors) {
      await messageModal("Different folder", "Select this slideshow folder on the PC before refreshing its offline copy.");
    }
    return null;
  }

  return { nextState, imageList };
}

async function refreshOfflineCatalog(catalog, options = {}) {
  const showErrors = options.showErrors !== false;
  const source = await fetchRefreshSourceForCatalog(catalog, showErrors);
  if (!source) {
    return { catalog, records: options.records || await getImagesForCatalog(catalog.id), refreshed: false };
  }

  const records = options.records || await getImagesForCatalog(catalog.id);
  if (offlineRecordsMatchSource(records, source.imageList)) {
    const nextCatalog = getCatalogMetadataUpdate(catalog, source.nextState);
    if (catalogMetadataChanged(catalog, nextCatalog)) {
      await updateCatalog(nextCatalog);
      return { catalog: nextCatalog, records, refreshed: false };
    }
    return { catalog, records, refreshed: false };
  }

  const protectionKey = options.protectionKey || await unlockCatalogKey(catalog);
  if (!protectionKey) {
    return { catalog, records, refreshed: false };
  }

  const progress = showProgressModal("Refreshing offline", "Updating the encrypted images on this device.");
  try {
    const nextCatalog = await syncCatalog({
      state: source.nextState,
      imageList: source.imageList,
      pin: "",
      protectionKey,
      onProgress: value => progress.update(value)
    });
    const nextRecords = await getImagesForCatalog(nextCatalog.id);
    progress.close();
    showTapFeedback("Refreshed", "center");
    return { catalog: nextCatalog, records: nextRecords, refreshed: true };
  } catch (error) {
    progress.close();
    if (showErrors) {
      await messageModal("Refresh failed", error.message || "The offline copy could not be refreshed.");
    }
    return { catalog, records, refreshed: false };
  }
}

async function openOfflineCatalog(catalog) {
  if (!catalog) {
    showTapFeedback("Unavailable", "center");
    return;
  }

  const key = await unlockCatalogKey(catalog);
  if (!key) {
    return;
  }

  let records = await getImagesForCatalog(catalog.id);
  if (!records.length) {
    await messageModal("No saved images", "This catalog has no playable offline images.");
    return;
  }

  const refreshResult = await refreshOfflineCatalog(catalog, {
    protectionKey: key,
    records,
    showErrors: false
  });
  catalog = refreshResult.catalog;
  records = refreshResult.records;
  if (!records.length) {
    await messageModal("No saved images", "This catalog has no playable offline images.");
    return;
  }

  clearInterval(refreshTimer);
  stopViewerHeartbeat();
  mode = "offline";
  activeOfflineCatalog = catalog;
  offlineSession = { key };
  state = {
    ...catalog,
    canConfigure: false
  };
  images = records.sort(() => Math.random() - 0.5);
  index = 0;
  playing = true;
  await render();
  showChromeTemporarily();
}

async function showStageLibrary() {
  const desktopLibraries = getDesktopLibraries();
  const catalogs = await listPlayableCatalogs();
  await refreshStageLibraryButton(catalogs);

  if (!desktopLibraries.length && !catalogs.length) {
    await messageModal("No saved slideshows", "Choose a slideshow from the control center or save one offline first.");
    return;
  }

  const desktopItems = desktopLibraries.map((slideshow, index) => {
    const folderPath = normalizeFolderPath(slideshow.folderPath);
    const current = isCurrentDesktopLibrary(slideshow);
    const title = slideshow.folderName || folderNameFromPath(folderPath) || "Slideshow";
    return `
      <article class="catalog-card${current ? " is-current" : ""}">
        <strong>${escapeHtml(title)}</strong>
        <small>${escapeHtml(current ? "Current - " : "")}${escapeHtml(formatRecentTime(slideshow.lastUsedAt))} - ${escapeHtml(folderPath)}</small>
        <div class="catalog-actions">
          <button class="primary" data-desktop="${index}" type="button">${current ? "Retry" : "Play"}</button>
        </div>
      </article>
    `;
  }).join("");

  const offlineItems = catalogs.map(catalog => {
    const protection = catalog.protectionMode === "pin" ? "PIN protected" : "local key";
    return `
      <article class="catalog-card">
        <strong>${escapeHtml(catalog.displayName)}</strong>
        <small>${escapeHtml(catalog.imageCount || 0)} images - ${escapeHtml(protection)} - ${escapeHtml(formatBytes(catalog.sizeBytes))} - ${escapeHtml(formatDate(catalog.syncedAt))}</small>
        <div class="catalog-actions">
          <button class="primary" data-offline="${escapeHtml(catalog.id)}" type="button">Play</button>
        </div>
      </article>
    `;
  }).join("");

  return showModal(`
    <h2>Library</h2>
    <p>Pick a slideshow to play from this stage.</p>
    <div class="modal-stack catalog-list">
      ${desktopItems ? `<h3>Slideshow library</h3>${desktopItems}` : ""}
      ${offlineItems ? `<h3>Offline copies</h3>${offlineItems}` : ""}
    </div>
    <div class="modal-actions">
      <button data-cancel type="button">Close</button>
    </div>
  `, (card, close) => {
    card.querySelectorAll("[data-desktop]").forEach(button => {
      button.addEventListener("click", async () => {
        close(true);
        await openDesktopLibrary(desktopLibraries[Number(button.dataset.desktop)]);
      });
    });
    card.querySelectorAll("[data-offline]").forEach(button => {
      button.addEventListener("click", async () => {
        close(true);
        await openOfflineCatalog(catalogs.find(catalog => catalog.id === button.dataset.offline));
      });
    });
  }, { dismissOnBackdrop: true });
}

async function showOfflineLibrary() {
  if (!isCryptoAvailable()) {
    await refreshStageLibraryButton([]);
    await messageModal("HTTPS required", "Open the HTTPS slideshow link before using encrypted offline playback.");
    return;
  }

  const catalogs = await listCatalogs();
  await refreshStageLibraryButton(catalogs);
  if (!catalogs.length) {
    await messageModal("No saved slideshows", "Save a slideshow offline while connected to this PC.");
    return;
  }

  const storage = await estimateStorage();
  const storageText = storage?.usage && storage?.quota
    ? `Storage used: ${formatBytes(storage.usage)} of ${formatBytes(storage.quota)}.`
    : "Encrypted copies are stored in this browser.";
  const currentFolderIdentity = mode === "online" && state
    ? folderIdentityFor(makeServerKey(), state)
    : null;

  const items = catalogs.map(catalog => {
    const protection = catalog.protectionMode === "pin" ? "PIN protected" : "local key";
    const canRefresh = currentFolderIdentity && catalog.folderIdentity === currentFolderIdentity;
    return `
      <article class="catalog-card">
        <strong>${escapeHtml(catalog.displayName)}</strong>
        <small>${escapeHtml(catalog.imageCount || 0)} images - ${escapeHtml(protection)} - ${escapeHtml(formatBytes(catalog.sizeBytes))} - ${escapeHtml(formatDate(catalog.syncedAt))}</small>
        <div class="catalog-actions">
          <button class="primary" data-open="${escapeHtml(catalog.id)}" type="button">Play</button>
          ${canRefresh ? `<button data-refresh="${escapeHtml(catalog.id)}" type="button">Refresh</button>` : ""}
          <button data-rename="${escapeHtml(catalog.id)}" type="button">Rename</button>
          <button data-pin="${escapeHtml(catalog.id)}" type="button">${catalog.protectionMode === "pin" ? "Change PIN" : "Set PIN"}</button>
          <button class="danger" data-delete="${escapeHtml(catalog.id)}" type="button">Delete</button>
        </div>
      </article>
    `;
  }).join("");

  await showModal(`
    <h2>Offline library</h2>
    <p>${escapeHtml(storageText)}</p>
    <div class="modal-stack catalog-list">${items}</div>
    <div class="modal-actions">
      <button data-cancel type="button">Close</button>
    </div>
  `, (card, close) => {
    const byId = id => catalogs.find(catalog => catalog.id === id);
    card.querySelectorAll("[data-open]").forEach(button => {
      button.addEventListener("click", async () => {
        close(true);
        await openOfflineCatalog(byId(button.dataset.open));
      });
    });
    card.querySelectorAll("[data-refresh]").forEach(button => {
      button.addEventListener("click", async () => {
        close(true);
        const result = await refreshOfflineCatalog(byId(button.dataset.refresh));
        if (result.refreshed) {
          await showOfflineLibrary();
          await refreshEmptyOfflineLibrary();
        } else {
          showTapFeedback("Current", "center");
        }
      });
    });
    card.querySelectorAll("[data-rename]").forEach(button => {
      button.addEventListener("click", async () => {
        close(true);
        await renameCatalog(byId(button.dataset.rename));
        await showOfflineLibrary();
        await refreshEmptyOfflineLibrary();
      });
    });
    card.querySelectorAll("[data-pin]").forEach(button => {
      button.addEventListener("click", async () => {
        close(true);
        await changeCatalogPin(byId(button.dataset.pin));
        await showOfflineLibrary();
        await refreshEmptyOfflineLibrary();
      });
    });
    card.querySelectorAll("[data-delete]").forEach(button => {
      button.addEventListener("click", async () => {
        close(true);
        await deleteSavedCatalog(byId(button.dataset.delete));
        await showOfflineLibrary();
        await refreshEmptyOfflineLibrary();
      });
    });
  });
}

async function renameCatalog(catalog = activeOfflineCatalog) {
  if (!catalog) {
    return;
  }

  const values = await promptModal({
    title: "Rename saved slideshow",
    message: "This only changes the local offline name.",
    fields: [
      { name: "name", label: "Name", value: catalog.displayName }
    ],
    primary: "Rename"
  });
  const name = values?.name.trim();
  if (!name) {
    return;
  }

  const renamed = {
    ...catalog,
    displayName: name
  };
  await updateCatalog(renamed);
  if (activeOfflineCatalog?.id === catalog.id) {
    activeOfflineCatalog = renamed;
    state = {
      ...state,
      displayName: name,
      folderName: name
    };
    await render();
  }
}

async function deleteSavedCatalog(catalog = activeOfflineCatalog) {
  if (!catalog) {
    return;
  }

  const confirmed = await confirmModal("Delete saved slideshow?", `${catalog.displayName} will be removed from this browser.`, "Delete", true);
  if (!confirmed) {
    return;
  }

  await deleteCatalog(catalog.id);
  if (activeOfflineCatalog?.id === catalog.id) {
    activeOfflineCatalog = null;
    offlineSession = null;
    images = [];
    await showOfflineStartup();
  }
}

async function changeCatalogPin(catalog = activeOfflineCatalog, forcedNewPin = undefined) {
  if (!catalog) {
    return;
  }

  let oldKey = null;
  if (catalog.protectionMode === "pin") {
    oldKey = await unlockCatalogKey(catalog);
    if (!oldKey) {
      return;
    }
  } else {
    oldKey = await getLocalKey(catalog.id);
    if (!oldKey) {
      await messageModal("Offline key missing", "Delete this saved slideshow and sync it again.");
      return;
    }
  }

  let nextPin = forcedNewPin;
  if (nextPin === undefined) {
    const values = await promptModal({
      title: catalog.protectionMode === "pin" ? "Change PIN" : "Set PIN",
      message: "Add a PIN to require it before playback.",
      fields: [
        { name: "pin", label: "New PIN", type: "password", autocomplete: "new-password" },
        { name: "confirm", label: "Confirm new PIN", type: "password", autocomplete: "new-password" }
      ],
      primary: "Apply"
    });
    if (!values) {
      return;
    }

    nextPin = values.pin.trim();
    if (nextPin && nextPin.length < 4) {
      await messageModal("PIN too short", "Use at least 4 digits.");
      return changeCatalogPin(catalog);
    }
    if (nextPin !== values.confirm.trim()) {
      await messageModal("PINs do not match", "Enter the same PIN twice.");
      return changeCatalogPin(catalog);
    }
  }

  if (!nextPin && catalog.protectionMode !== "pin") {
    return;
  }

  const records = await getImagesForCatalog(catalog.id);
  const progress = showProgressModal("Updating protection", "Re-encrypting saved images.");
  let nextKey;
  let localKey = null;
  let nextCatalog;

  if (nextPin) {
    const salt = bytesToBase64(randomBytes(16));
    nextKey = await derivePinKey(nextPin, salt, PIN_ITERATIONS);
    const verifier = await createPinVerifier(nextKey);
    nextCatalog = {
      ...catalog,
      protectionMode: "pin",
      salt,
      kdf: "PBKDF2-SHA-256",
      iterations: PIN_ITERATIONS,
      verifierData: verifier.verifierData,
      verifierIv: verifier.verifierIv
    };
  } else {
    nextKey = await generateLocalKey();
    localKey = nextKey;
    nextCatalog = {
      ...catalog,
      protectionMode: "local",
      salt: "",
      kdf: "",
      iterations: 0,
      verifierData: "",
      verifierIv: ""
    };
  }

  try {
    const nextRecords = [];
    for (let recordIndex = 0; recordIndex < records.length; recordIndex += 1) {
      const record = records[recordIndex];
      const clear = await decryptBlob(oldKey, record.encryptedBlob, record.iv, record.mimeType);
      const encrypted = await encryptBlob(nextKey, clear);
      nextRecords.push({
        ...record,
        encryptedBlob: encrypted.encryptedBlob,
        iv: encrypted.iv
      });
      progress.update({
        completed: recordIndex + 1,
        total: records.length,
        elapsedMs: 0
      });
    }

    await saveCatalogBundle(nextCatalog, nextRecords, localKey);
    if (nextPin) {
      await deleteLocalKey(catalog.id);
    } else {
      await saveLocalKey(catalog.id, nextKey);
    }

    progress.close();
    if (activeOfflineCatalog?.id === catalog.id) {
      activeOfflineCatalog = nextCatalog;
      offlineSession = { key: nextKey };
      await render();
    }
    showTapFeedback(nextPin ? "PIN set" : "PIN removed", "center");
  } catch (error) {
    progress.close();
    await messageModal("Protection update failed", error.message || "The saved slideshow could not be re-encrypted.");
  }
}

async function removeCatalogPin(catalog = activeOfflineCatalog) {
  if (!catalog || catalog.protectionMode !== "pin") {
    return;
  }

  const confirmed = await confirmModal("Remove PIN?", `${catalog.displayName} will still be encrypted, but it will open transparently on this browser.`, "Remove PIN");
  if (!confirmed) {
    return;
  }

  await changeCatalogPin(catalog, "");
}

async function lockActiveOfflineCatalog() {
  if (!activeOfflineCatalog || activeOfflineCatalog.protectionMode !== "pin") {
    return;
  }

  offlineSession = null;
  revokeObjectUrl();
  await render();
  showTapFeedback("Locked", "center");
}

settingsToggle.addEventListener("click", event => {
  event.stopPropagation();
  toggleSettingsPanel();
});

settingsClose.addEventListener("click", event => {
  event.stopPropagation();
  hideSettingsPanel();
});

fullscreenToggle.addEventListener("click", async event => {
  event.stopPropagation();
  try {
    await toggleFullscreen();
    showChromeTemporarily();
  } catch {
    showTapFeedback("Unavailable", "center");
  }
});

stageLibrary?.addEventListener("click", event => {
  event.stopPropagation();
  showChrome();
  showStageLibrary();
});

previousSlide.addEventListener("click", () => {
  advance(-1);
  showChromeTemporarily();
});

playbackToggle.addEventListener("click", () => {
  togglePlayback();
  showChromeTemporarily();
});

nextSlide.addEventListener("click", () => {
  advance(1);
  showChromeTemporarily();
});

slower.addEventListener("click", () => {
  updateTimer(currentSlideSeconds() - 1);
  showChrome();
});

faster.addEventListener("click", () => {
  updateTimer(currentSlideSeconds() + 1);
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
saveOffline.addEventListener("click", saveCurrentOffline);
openOffline.addEventListener("click", showOfflineLibrary);
renameOffline.addEventListener("click", () => renameCatalog());
deleteOfflineButton.addEventListener("click", () => deleteSavedCatalog());
pinOffline.addEventListener("click", () => changeCatalogPin());
removePinOffline.addEventListener("click", () => removeCatalogPin());
lockOffline.addEventListener("click", lockActiveOfflineCatalog);

stage.addEventListener("click", event => {
  if (event.target.closest("button, input")) {
    showChrome();
    return;
  }

  if (!offlineModal.hidden) {
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

  if (mode === "offline" && activeOfflineCatalog?.protectionMode === "pin" && !offlineSession) {
    openOfflineCatalog(activeOfflineCatalog);
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
    showTapFeedback("<", "start");
  } else if (x > third * 2) {
    advance(1);
    showTapFeedback(">", "end");
  } else {
    togglePlayback();
    showTapFeedback(playbackStatusText(), "center");
  }

  showChromeTemporarily();
});

stage.addEventListener("pointermove", showChromeTemporarily);

document.addEventListener("keydown", event => {
  if (event.key === "Escape" && (document.fullscreenElement || document.webkitFullscreenElement)) {
    const exitFullscreen = document.exitFullscreen || document.webkitExitFullscreen;
    Promise.resolve(exitFullscreen?.call(document)).catch(() => {});
    return;
  }

  if (!offlineModal.hidden) {
    if (event.key === "Escape") {
      offlineModal.querySelector("[data-cancel]")?.click();
    }
    return;
  }

  if (event.key === "Escape") {
    hideSettingsPanel();
  } else if (event.key === "ArrowRight") {
    advance(1);
    showTapFeedback(">", "end");
  } else if (event.key === "ArrowLeft") {
    advance(-1);
    showTapFeedback("<", "start");
  } else if (event.key === " ") {
    event.preventDefault();
    togglePlayback();
    showTapFeedback(playbackStatusText(), "center");
  }
  showChromeTemporarily();
});

registerServiceWorker();
updatePlaybackUI();
updateFullscreenState();
load();
