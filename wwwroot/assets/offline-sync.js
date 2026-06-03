import {
  PIN_ITERATIONS,
  bytesToBase64,
  createPinVerifier,
  derivePinKey,
  encryptBlob,
  generateLocalKey,
  randomBytes
} from "./offline-crypto.js?v=20260529-offline2";
import {
  MAX_CATALOGS,
  deleteCatalog,
  findCatalogByIdentity,
  folderIdentityFor,
  listCatalogs,
  makeServerKey,
  saveCatalogBundle
} from "./offline-store.js?v=20260529-offline2";

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
  replaceCatalogId,
  onProgress
}) {
  if (!Array.isArray(imageList) || imageList.length === 0) {
    throw new Error("No images are available to save.");
  }

  const plan = await getSyncPlan(state);
  const targetId = replaceCatalogId || plan.existing?.id || crypto.randomUUID();
  const hasPin = Boolean(pin && pin.trim());
  let key;
  let localKey = null;
  let salt = "";
  let verifierData = "";
  let verifierIv = "";

  if (hasPin) {
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
    const response = await fetch(image.url, { cache: "no-store" });
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
        elapsedMs: performance.now() - startedAt
      });
    }
  }

  if (replaceCatalogId && replaceCatalogId !== plan.existing?.id) {
    await deleteCatalog(replaceCatalogId);
  }

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
    slideSeconds: state.slideSeconds || 7,
    backgroundColor: state.backgroundColor || "#05070a",
    imageCount: imageRecords.length,
    sizeBytes,
    syncedAt: now,
    protectionMode: hasPin ? "pin" : "local",
    salt,
    kdf: hasPin ? "PBKDF2-SHA-256" : "",
    iterations: hasPin ? PIN_ITERATIONS : 0,
    verifierData,
    verifierIv
  };

  await saveCatalogBundle(catalog, imageRecords, localKey);
  return catalog;
}
