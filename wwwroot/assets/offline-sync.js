import {
  PIN_ITERATIONS,
  bytesToBase64,
  createPinVerifier,
  derivePinKey,
  encryptBlob,
  generateLocalKey,
  randomBytes
} from "./offline-crypto.js?v=20260922-gallery";
import {
  MAX_CATALOGS,
  findCatalogByIdentity,
  folderIdentityFor,
  listCatalogs,
  makeServerKey,
  saveCatalogBundle
} from "./offline-store.js?v=20260922-gallery";

function cleanName(value, fallback = "Slide Show") {
  return (value || "").trim() || fallback;
}

export async function getSyncPlan(state) {
  const serverKey = makeServerKey();
  const folderIdentity = folderIdentityFor(serverKey, state);
  const existing = await findCatalogByIdentity(folderIdentity);
  const catalogs = await listCatalogs();
  return {
    serverKey,
    folderIdentity,
    existing,
    catalogs,
    needsReplacement: !existing && catalogs.length >= MAX_CATALOGS
  };
}

export async function syncCatalog({
  state,
  imageList,
  pin,
  protectionKey,
  replaceCatalogId,
  onProgress,
  signal
}) {
  if (!Array.isArray(imageList) || imageList.length === 0) {
    throw new Error("No images are available to save.");
  }

  const plan = await getSyncPlan(state);
  signal?.throwIfAborted();
  const estimate = await navigator.storage?.estimate?.();
  const requiredBytes = imageList.reduce((sum, image) => sum + (image.sizeBytes || 0) + 64, 0);
  if (estimate?.quota && requiredBytes > estimate.quota - estimate.usage) {
    throw new Error("Not enough storage for this copy. Free space or choose a smaller folder. Your existing saved photos are unchanged.");
  }
  const targetId = replaceCatalogId || plan.existing?.id || crypto.randomUUID();
  const preserveExistingProtection = Boolean(protectionKey && plan.existing);
  const preserveExistingPin = preserveExistingProtection && plan.existing.protectionMode === "pin";
  const hasPin = preserveExistingPin || Boolean(pin && pin.trim());
  let key;
  let localKey = null;
  let salt = "";
  let verifierData = "";
  let verifierIv = "";

  if (preserveExistingProtection) {
    key = protectionKey;
    if (preserveExistingPin) {
      salt = plan.existing.salt || "";
      verifierData = plan.existing.verifierData || "";
      verifierIv = plan.existing.verifierIv || "";
    } else {
      localKey = protectionKey;
    }
  } else if (hasPin) {
    salt = bytesToBase64(randomBytes(16));
    key = await derivePinKey(pin.trim(), salt, PIN_ITERATIONS);
    const verifier = await createPinVerifier(key);
    verifierData = verifier.verifierData;
    verifierIv = verifier.verifierIv;
  } else {
    key = await generateLocalKey();
    localKey = key;
  }

  const imageRecords = [];
  let sizeBytes = 0;
  const startedAt = performance.now();

  for (let index = 0; index < imageList.length; index += 1) {
    const image = imageList[index];
    signal?.throwIfAborted();
    const response = await fetch(image.url, { cache: "no-store", signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(60000)]) : AbortSignal.timeout(60000) });
    if (!response.ok) {
      throw new Error(`Could not save ${image.name || "image"}.`);
    }

    const clearBlob = await response.blob();
    const encrypted = await encryptBlob(key, clearBlob);
    const cacheKey = image.cacheKey || `${image.url}\n${image.name || "Image"}`;
    const record = {
      id: `${targetId}:${cacheKey}`,
      catalogId: targetId,
      order: index,
      cacheKey,
      name: cleanName(image.name, "Image"),
      modifiedAt: image.modifiedAt || 0,
      mimeType: clearBlob.type || response.headers.get("Content-Type") || "application/octet-stream",
      encryptedBlob: encrypted.encryptedBlob,
      iv: encrypted.iv,
      originalSize: clearBlob.size
    };
    sizeBytes += encrypted.encryptedBlob.size;
    imageRecords.push(record);

    if (onProgress) {
      onProgress({
        completed: index + 1,
        total: imageList.length,
        name: record.name,
        sizeBytes,
        elapsedMs: performance.now() - startedAt
      });
    }
  }

  signal?.throwIfAborted();

  const now = Date.now();
  const catalog = {
    id: targetId,
    schemaVersion: 1,
    displayName: cleanName(plan.existing?.displayName || state.folderName, "Slide Show"),
    folderName: cleanName(state.folderName, "Slide Show"),
    folderPath: state.folderPath || "",
    serverKey: plan.serverKey,
    folderIdentity: plan.folderIdentity,
    serverVersion: state.version || 0,
    imageMode: state.imageMode || "fit",
    playbackOrder: state.playbackOrder || "shuffle",
    slideSeconds: state.slideSeconds || 7,
    backgroundColor: state.backgroundColor || "#05070a",
    imageCount: imageRecords.length,
    sizeBytes,
    syncedAt: now,
    protectionMode: hasPin ? "pin" : "local",
    salt,
    kdf: hasPin ? plan.existing?.kdf || "PBKDF2-SHA-256" : "",
    iterations: hasPin ? plan.existing?.iterations || PIN_ITERATIONS : 0,
    verifierData,
    verifierIv
  };

  await saveCatalogBundle(catalog, imageRecords, localKey);
  return catalog;
}
