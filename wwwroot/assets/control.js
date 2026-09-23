import { listCatalogs } from "./offline-store.js?v=20260923-collections";
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
let selectedCollection = new URLSearchParams(location.search).get("collection");
try { selectedCollection ||= localStorage.getItem("slideshow.selectedCollection"); } catch {}

async function api(path, payload, options = {}) {
  const url = new URL(path, location.href);
  if (selectedCollection && ["/api/state", "/api/images", "/api/settings", "/api/rescan"].includes(url.pathname))
    url.searchParams.set("collection", selectedCollection);
  let response;
  try {
    response = await fetch(url, {
      signal: AbortSignal.timeout(15000),
      ...(payload !== undefined
        ? {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
          }
        : {}),
      ...options,
    });
  } catch {
    throw new Error(
      "Cannot reach Slide Show. Check that the app is running, then retry.",
    );
  }
  if (response.status === 404 && path === "/api/state" && selectedCollection) {
    selectedCollection = null;
    return api(path);
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
  const draft = values();
  $("playbackSummary").textContent = `${draft.slideSeconds || 7} sec · ${draft.imageMode === "full" ? "Fill screen" : "Entire photo"} · ${{ shuffle: "Shuffle", name: "File name", date: "Date" }[draft.playbackOrder] || "Shuffle"}`;
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
  const available = (state.collections || []).some(item => item.id === state.collectionId);
  if (!available) state = { ...state, folderPath: null, folderName: null, imageCount: 0, scanMessage: null, shared: false };
  for (const selector of [".source", ".sharing-row", "#playbackOptions", ".folder-tools"])
    document.querySelector(selector).hidden = !available;
  $("stageEmpty").querySelector("span").textContent = state.collections?.length ? "Select a collection to start." : "Add a collection to start.";
  const draft = !saved && currentState?.collectionId === state.collectionId && dirty() ? values() : null;
  currentState = state;
  selectedCollection = state.collectionId || null;
  try { if (selectedCollection) localStorage.setItem("slideshow.selectedCollection", selectedCollection); else localStorage.removeItem("slideshow.selectedCollection"); } catch {}
  baseline = Object.fromEntries(
    settingNames.map((name) => [
      name,
      state[name] ??
        { playbackOrder: "shuffle", syncWorkers: 4, imageMode: "fit" }[name],
    ]),
  );
  applyValues(draft || baseline);
  pending();
  const sharedCount = (state.collections || []).filter(item => item.shared).length;
  $("statusPill").textContent = `${state.computerName || "This PC"} · ${sharedCount} collection${sharedCount === 1 ? "" : "s"} shared`;
  $("statusPill").classList.add("is-connected");
  $("connectionError").hidden = true;
  $("currentSlideshowName").textContent =
    state.folderName || folderName(state.folderPath) || "Add a collection";
  $("currentFolderDetail").textContent = state.folderPath
    ? state.imageCount
      ? "On this PC"
      : "No supported photos"
    : "No folder selected";
  $("folderPath").textContent = state.folderPath || "";
  $("imageCount").textContent = (state.imageCount || 0).toLocaleString();
  $("lastScan").textContent = date(state.lastScannedAt);
  $("scanMessage").textContent = state.scanMessage || "";
  $("scanMessage").hidden = !state.scanMessage;
  $("playCurrent").disabled = !state.imageCount;
  $("playCurrent").classList.toggle("primary-button", Boolean(state.imageCount));
  $("watchDevice").disabled = !state.collectionId || !state.shared;
  $("watchDevice").title = state.shared ? "Copy this collection's link" : "Make this collection available to other devices to copy its link";
  $("collectionShared").checked = Boolean(state.shared);
  $("collectionShared").disabled = !state.collectionId || !state.canConfigure;
  $("sharingDetail").textContent = state.shared ? `Shared from ${state.computerName} on this Wi-Fi` : "Only available on this PC";
  if (document.activeElement !== $("collectionName")) $("collectionName").value = state.folderName || "";
  $("renameCollection").disabled = $("removeCollection").disabled = !state.collectionId || !state.canConfigure;
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
  const collections = state.collections || [],
    signature = JSON.stringify([collections, catalogs, state.collectionId]);
  if (signature === librarySignature) return;
  librarySignature = signature;
  $("libraryList").replaceChildren();
  $("libraryCount").textContent = "";
  const group = (label) => {
    const heading = document.createElement("p");
    heading.className = "library-group";
    heading.textContent = label;
    $("libraryList").append(heading);
  };
  const row = (name, meta, label, action, current = false, previewUrl = null) => {
    const element = document.createElement("article");
    element.className = "library-row" + (current ? " is-current" : "");
    const details = document.createElement("div"),
      title = document.createElement("h3"),
      description = document.createElement("p"),
      button = document.createElement("button");
    const icon = document.createElementNS("http://www.w3.org/2000/svg", "svg"),
      use = document.createElementNS("http://www.w3.org/2000/svg", "use"),
      arrow = document.createElement("span");
    icon.setAttribute("class", "icon");
    icon.setAttribute("aria-hidden", "true");
    use.setAttribute("href", label === "Play saved" ? "#icon-photo" : "#icon-folder");
    icon.append(use);
    title.textContent = name;
    description.textContent = meta.replace(/^On this PC · /, "").replace(/^Saved in this browser · /, "");
    description.title = meta;
    arrow.className = "library-arrow";
    arrow.textContent = current ? "✓" : "›";
    arrow.setAttribute("aria-hidden", "true");
    button.className = "library-item";
    button.setAttribute("aria-label", `${label} ${name}`);
    if (current) button.setAttribute("aria-current", "true");
    button.type = "button";
    button.onclick = () => run(button, action);
    details.append(title, description);
    if (previewUrl) {
      const preview = document.createElement("img");
      preview.className = "collection-thumbnail"; preview.src = previewUrl; preview.alt = "";
      preview.onerror = () => preview.replaceWith(icon);
      button.append(preview);
    } else button.append(icon);
    button.append(details, arrow);
    element.append(button);
    $("libraryList").append(element);
  };
  for (const item of collections)
    row(
      item.name,
      `${item.imageCount} photos · ${!item.available ? "Folder unavailable" : item.shared ? "Shared" : "This PC only"}`,
      "Select",
      async () => {
        if (dirty() && !confirm("Discard unsaved playback changes and select this collection?")) return;
        const previous = selectedCollection;
        selectedCollection = item.id;
        try { render(await api("/api/state"), true); } catch (error) { selectedCollection = previous; throw error; }
        document
          .querySelector(".current")
          .scrollIntoView({ behavior: "smooth" });
      },
      item.id === state.collectionId,
      item.previewUrl,
    );
  if (catalogs.length) group("Saved in this browser");
  for (const catalog of catalogs)
    row(
      catalog.displayName || "Saved slideshow",
      `Saved in this browser · ${catalog.imageCount} photos · ${date(catalog.syncedAt)}${catalog.protectionMode === "pin" ? " · PIN protected" : ""}`,
      "Play saved",
      () =>
        openPlayer(`/show?offlineCatalog=${encodeURIComponent(catalog.id)}`),
    );
  if (!collections.length && !catalogs.length) {
    const empty = document.createElement("p");
    empty.className = "library-empty";
    empty.textContent =
      "Add your first collection.";
    $("libraryList").append(empty);
  }
}
async function refresh() {
  if (refreshing || busy || document.hidden) return;
  refreshing = true;
  const requestedCollection = selectedCollection;
  try {
    const state = await api("/api/state");
    if (!busy && (requestedCollection === selectedCollection || !selectedCollection)) render(state);
  } catch {
    $("statusPill").textContent = "Disconnected";
    $("statusPill").classList.remove("is-connected");
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
async function openPlayer(url = `/show?collection=${encodeURIComponent(currentState?.collectionId || "")}`) {
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
    $("preferencesDialog").close();
  });
};
$("discardSettings").onclick = () => {
  applyValues(baseline);
  pending();
};
for (const name of settingNames) $(name).addEventListener("input", pending);
$("retry").onclick = refresh;
$("watchDevice").onclick = async () => {
  try { await navigator.clipboard.writeText($("deviceAddress").value); showToast("Collection link copied"); }
  catch { $("deviceDialog").showModal(); }
};
$("openPreferences").onclick = () => $("preferencesDialog").showModal();
$("collectionShared").onchange = () => run($("collectionShared"), async () => {
  try { render(await api(`/api/collections/${selectedCollection}`, { shared: $("collectionShared").checked })); }
  catch (error) { $("collectionShared").checked = Boolean(currentState?.shared); throw error; }
});
$("renameCollection").onclick = () => run($("renameCollection"), async () => {
  render(await api(`/api/collections/${selectedCollection}`, { name: $("collectionName").value }));
});
$("removeCollection").onclick = () => run($("removeCollection"), async () => {
  if (!confirm(`Remove ${currentState.folderName} from Slide Show? Photo files and saved copies will be kept.`)) return;
  render(await api(`/api/collections/${selectedCollection}`, undefined, { method: "DELETE" }), true);
});
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
