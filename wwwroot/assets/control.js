import { listCatalogs } from "./offline-store.js?v=20260922-workflows";
const $ = (id) => document.getElementById(id);
const settingNames = [
  "slideSeconds",
  "syncWorkers",
  "backgroundColor",
  "imageMode",
  "playbackOrder",
  "includeSubfolders",
  "startAtLogin",
];
let currentState,
  baseline,
  previewVersion,
  librarySignature,
  slideshowOverlay,
  toastTimer;
let busy = false,
  refreshing = false;

async function api(path, payload) {
  let response;
  try {
    response = await fetch(path, {
      signal: AbortSignal.timeout(15000),
      ...(payload !== undefined
        ? {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
          }
        : {}),
    });
  } catch {
    throw new Error(
      "Cannot reach Slide Show. Check that the app is running, then retry.",
    );
  }
  if (!response.ok)
    throw new Error(
      response.status === 403
        ? "Choose folders and change startup settings on the PC running Slide Show."
        : `Slide Show could not complete this action (${response.status}). Please retry.`,
    );
  return response.status === 204 ? null : response.json();
}
function showToast(message) {
  $("toast").textContent = message;
  $("toast").classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => $("toast").classList.remove("show"), 4000);
}
function values() {
  return Object.fromEntries(
    settingNames.map((name) => [
      name,
      $(name).type === "checkbox"
        ? $(name).checked
        : $(name).type === "number"
          ? Number($(name).value)
          : $(name).value,
    ]),
  );
}
function applyValues(value) {
  for (const name of settingNames) {
    if ($(name).type === "checkbox") $(name).checked = Boolean(value[name]);
    else $(name).value = value[name];
  }
}
function dirty() {
  return baseline && JSON.stringify(values()) !== JSON.stringify(baseline);
}
function pending() {
  $("saveSettings").disabled = busy || !dirty();
  $("discardSettings").hidden = !dirty();
  $("saveStatus").textContent = dirty() ? "Unsaved changes" : "";
}
function date(value) {
  return value
    ? new Date(value).toLocaleString(undefined, {
        month: "short",
        day: "numeric",
        hour: "numeric",
        minute: "2-digit",
      })
    : "never";
}
function folderName(path) {
  return (path || "")
    .replace(/[\\/]+$/, "")
    .split(/[\\/]/)
    .pop();
}
function render(state, saved = false) {
  const draft = !saved && dirty() ? values() : null;
  currentState = state;
  baseline = Object.fromEntries(
    settingNames.map((name) => [
      name,
      state[name] ??
        { playbackOrder: "shuffle", syncWorkers: 4, imageMode: "fit" }[name],
    ]),
  );
  applyValues(draft || baseline);
  pending();
  $("statusPill").textContent = "Connected";
  $("connectionError").hidden = true;
  $("currentSlideshowName").textContent =
    state.folderName || folderName(state.folderPath) || "Choose your photos";
  $("currentFolderDetail").textContent = state.folderPath
    ? state.imageCount
      ? "Ready to watch here or on another device."
      : "Choose a folder containing supported photos."
    : "Choose a folder on this PC to start a slideshow.";
  $("folderPath").textContent = state.folderPath || "";
  $("imageCount").textContent = (state.imageCount || 0).toLocaleString();
  $("lastScan").textContent = date(state.lastScannedAt);
  $("scanMessage").textContent = state.scanMessage || "";
  $("scanMessage").hidden = !state.scanMessage;
  $("playCurrent").disabled = !state.imageCount;
  $("playCurrent").classList.toggle("primary-button", Boolean(state.imageCount));
  $("chooseFolder").classList.toggle("primary-button", !state.imageCount);
  $("watchDevice").disabled = !state.imageCount;
  $("chooseFolder").textContent = state.folderPath
    ? "Change folder"
    : "Choose photos";
  $("chooseFolder").disabled = !state.canConfigure;
  $("rescan").disabled = !state.canConfigure || !state.folderPath;
  $("serverPort").textContent = state.port;
  $("computerName").textContent = state.computerName || "this PC";
  $("deviceStatus").textContent =
    `${state.connectedViewers || 0} connected viewer${state.connectedViewers === 1 ? "" : "s"}`;
  const addresses = state.lanSlideshowUrls?.length
    ? state.lanSlideshowUrls
    : [
        state.displaySlideshowUrl ||
          state.localSlideshowUrl ||
          location.origin + "/show",
      ];
  const selected = $("networkAddress").value;
  $("networkAddress").replaceChildren(
    ...addresses.map((address) => new Option(address, address)),
  );
  $("networkAddress").value = addresses.includes(selected)
    ? selected
    : addresses[0];
  $("deviceAddress").value = $("networkAddress").value;
  updatePreview(state);
  renderLibraries(state);
}
async function updatePreview(state) {
  const signature = `${state.folderPath}:${state.version}`;
  if (signature === previewVersion) return;
  previewVersion = signature;
  $("stagePreview").hidden = true;
  $("stageEmpty").hidden = false;
  if (!state.imageCount) return;
  try {
    const images = await api("/api/images?shuffle=false");
    if (previewVersion !== signature || !images.length) return;
    const img = $("stagePreview");
    img.onload = () => {
      if (previewVersion === signature) {
        img.hidden = false;
        $("stageEmpty").hidden = true;
      }
    };
    img.onerror = () => {
      img.hidden = true;
      $("stageEmpty").hidden = false;
      $("stageEmpty").querySelector("p").textContent =
        "Preview unavailable — try another photo";
    };
    img.src = images[0].url;
    img.alt = images[0].name;
  } catch {
    previewVersion = null;
  }
}
async function renderLibraries(state) {
  const catalogs = await listCatalogs().catch(() => []);
  if (state !== currentState) return;
  const recent = state.recentSlideshows || [],
    signature = JSON.stringify([recent, catalogs, state.folderPath]);
  if (signature === librarySignature) return;
  librarySignature = signature;
  $("libraryList").replaceChildren();
  const row = (name, meta, label, action, current = false) => {
    const element = document.createElement("article");
    element.className = "library-row" + (current ? " is-current" : "");
    const details = document.createElement("div"),
      title = document.createElement("h3"),
      description = document.createElement("p"),
      button = document.createElement("button");
    title.textContent = name;
    description.textContent = meta;
    button.textContent = label;
    button.type = "button";
    button.onclick = () => run(button, action);
    details.append(title, description);
    element.append(details, button);
    $("libraryList").append(element);
  };
  for (const item of recent)
    row(
      item.folderName || folderName(item.folderPath),
      `On this PC · ${item.folderPath}`,
      "Select",
      async () => {
        render(await api("/api/settings", { folderPath: item.folderPath }));
        document
          .querySelector(".current")
          .scrollIntoView({ behavior: "smooth" });
      },
      item.folderPath?.toLowerCase() === state.folderPath?.toLowerCase(),
    );
  for (const catalog of catalogs)
    row(
      catalog.displayName || "Saved slideshow",
      `Saved in this browser · ${catalog.imageCount} photos · ${date(catalog.syncedAt)}${catalog.protectionMode === "pin" ? " · PIN protected" : ""}`,
      "Play saved",
      () =>
        openPlayer(`/show?offlineCatalog=${encodeURIComponent(catalog.id)}`),
    );
  if (!recent.length && !catalogs.length) {
    const empty = document.createElement("p");
    empty.className = "library-empty";
    empty.textContent =
      "Choose photos above. Your folders and saved copies will appear here.";
    $("libraryList").append(empty);
  }
}
async function refresh() {
  if (refreshing || busy || document.hidden) return;
  refreshing = true;
  try {
    render(await api("/api/state"));
  } catch {
    $("statusPill").textContent = "Disconnected";
    $("connectionError").hidden = false;
  } finally {
    refreshing = false;
  }
}
async function run(button, action) {
  if (busy) return;
  busy = true;
  button.disabled = true;
  try {
    await action();
  } catch (error) {
    showToast(error.message);
  } finally {
    busy = false;
    button.disabled = false;
    pending();
  }
}
function library() {
  $("library").scrollIntoView({ behavior: "smooth" });
  $("library").focus({ preventScroll: true });
}
function removeOverlay() {
  slideshowOverlay?.remove();
  slideshowOverlay = null;
  $("playCurrent").focus();
}
async function openPlayer(url = "/show") {
  const overlay = document.createElement("div");
  overlay.className = "slideshow-overlay";
  document.body.append(overlay);
  slideshowOverlay = overlay;
  try {
    await overlay.requestFullscreen({ navigationUI: "hide" });
    const frame = document.createElement("iframe"),
      source = new URL(url, location.href);
    source.searchParams.set("embed", "1");
    frame.title = "Slide Show";
    frame.src = source.href;
    frame.allow = "fullscreen";
    overlay.append(frame);
    frame.focus();
  } catch {
    removeOverlay();
    location.href = url;
  }
}
$("playCurrent").onclick = () => {
  if (currentState?.imageCount) openPlayer();
};
$("openLibrary").onclick = library;
$("chooseFolder").onclick = () =>
  run($("chooseFolder"), async () =>
    render(await api("/api/choose-folder", {})),
  );
$("rescan").onclick = () =>
  run($("rescan"), async () => {
    render(await api("/api/rescan", {}));
    showToast("Photos checked");
  });
$("settingsForm").onsubmit = (event) => {
  event.preventDefault();
  run($("saveSettings"), async () => {
    const submitted = values();
    const result = await api("/api/settings", submitted);
    render(result, JSON.stringify(submitted) === JSON.stringify(values()));
    showToast("Changes saved");
  });
};
$("discardSettings").onclick = () => {
  applyValues(baseline);
  pending();
};
for (const name of settingNames) $(name).addEventListener("input", pending);
$("retry").onclick = refresh;
$("watchDevice").onclick = () => $("deviceDialog").showModal();
$("networkAddress").onchange = () =>
  ($("deviceAddress").value = $("networkAddress").value);
$("copyAddress").onclick = async () => {
  try {
    await navigator.clipboard.writeText($("deviceAddress").value);
    $("deviceStatus").textContent = "Link copied";
  } catch {
    $("deviceAddress").select();
    $("deviceStatus").textContent = "Select and copy this address.";
  }
};
document.addEventListener("fullscreenchange", () => {
  if (!document.fullscreenElement) removeOverlay();
});
window.addEventListener("message", async (event) => {
  if (
    event.origin === location.origin &&
    event.source === slideshowOverlay?.querySelector("iframe")?.contentWindow &&
    event.data === "slide-show-library"
  ) {
    if (document.fullscreenElement) await document.exitFullscreen();
    removeOverlay();
    library();
    librarySignature = null;
    refresh();
  }
});
window.addEventListener("beforeunload", (event) => {
  if (dirty()) {
    event.preventDefault();
    event.returnValue = "";
  }
});
document.addEventListener("visibilitychange", refresh);
window.addEventListener("focus", refresh);
setInterval(refresh, 5000);
refresh();
