const DB_NAME = "slide-show-offline-web";
const DB_VERSION = 1;

export const MAX_CATALOGS = 4;

let databasePromise = null;

function requestToPromise(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function transactionDone(transaction) {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onabort = () => reject(transaction.error || new Error("Storage transaction failed."));
    transaction.onerror = () => reject(transaction.error || new Error("Storage transaction failed."));
  });
}

export function openDatabase() {
  if (databasePromise) {
    return databasePromise;
  }

  databasePromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains("catalogs")) {
        const catalogs = db.createObjectStore("catalogs", { keyPath: "id" });
        catalogs.createIndex("folderIdentity", "folderIdentity", { unique: false });
        catalogs.createIndex("syncedAt", "syncedAt", { unique: false });
      }
      if (!db.objectStoreNames.contains("images")) {
        const images = db.createObjectStore("images", { keyPath: "id" });
        images.createIndex("catalogId", "catalogId", { unique: false });
        images.createIndex("cacheKey", "cacheKey", { unique: false });
      }
      if (!db.objectStoreNames.contains("keys")) {
        db.createObjectStore("keys", { keyPath: "id" });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });

  return databasePromise;
}

export function makeServerKey() {
  return window.location.host;
}

export function folderIdentityFor(serverKey, state) {
  const folderPath = (state?.folderPath || "").trim();
  const folderName = (state?.folderName || "Slide Show").trim() || "Slide Show";
  return `${serverKey}\n${folderPath || folderName}`;
}

export async function listCatalogs() {
  const db = await openDatabase();
  const transaction = db.transaction("catalogs", "readonly");
  const catalogs = await requestToPromise(transaction.objectStore("catalogs").getAll());
  return catalogs.sort((left, right) => (right.syncedAt || 0) - (left.syncedAt || 0));
}

export async function getCatalog(id) {
  const db = await openDatabase();
  const transaction = db.transaction("catalogs", "readonly");
  return requestToPromise(transaction.objectStore("catalogs").get(id));
}

export async function findCatalogByIdentity(folderIdentity) {
  const db = await openDatabase();
  const transaction = db.transaction("catalogs", "readonly");
  const index = transaction.objectStore("catalogs").index("folderIdentity");
  return requestToPromise(index.get(folderIdentity));
}

export async function getImagesForCatalog(catalogId) {
  const db = await openDatabase();
  const transaction = db.transaction("images", "readonly");
  const index = transaction.objectStore("images").index("catalogId");
  const images = await requestToPromise(index.getAll(catalogId));
  return images.sort((left, right) => (left.order || 0) - (right.order || 0));
}

export async function getImageRecord(catalogId, cacheKey) {
  const db = await openDatabase();
  const transaction = db.transaction("images", "readonly");
  return requestToPromise(transaction.objectStore("images").get(`${catalogId}:${cacheKey}`));
}

export async function getLocalKey(catalogId) {
  const db = await openDatabase();
  const transaction = db.transaction("keys", "readonly");
  const record = await requestToPromise(transaction.objectStore("keys").get(catalogId));
  return record?.key || null;
}

async function deleteImagesForCatalog(catalogId) {
  const db = await openDatabase();
  const transaction = db.transaction("images", "readwrite");
  const images = transaction.objectStore("images");
  const index = images.index("catalogId");

  await new Promise((resolve, reject) => {
    const cursorRequest = index.openCursor(IDBKeyRange.only(catalogId));
    cursorRequest.onsuccess = () => {
      const cursor = cursorRequest.result;
      if (!cursor) {
        resolve();
        return;
      }
      cursor.delete();
      cursor.continue();
    };
    cursorRequest.onerror = () => reject(cursorRequest.error);
  });

  await transactionDone(transaction);
}

export async function deleteCatalog(catalogId) {
  await deleteImagesForCatalog(catalogId);
  const db = await openDatabase();
  const transaction = db.transaction(["catalogs", "keys"], "readwrite");
  transaction.objectStore("catalogs").delete(catalogId);
  transaction.objectStore("keys").delete(catalogId);
  await transactionDone(transaction);
}

export async function saveCatalogBundle(catalog, imageRecords, localKey) {
  await deleteImagesForCatalog(catalog.id);
  const db = await openDatabase();
  const transaction = db.transaction(["catalogs", "images", "keys"], "readwrite");
  transaction.objectStore("catalogs").put(catalog);
  if (localKey) {
    transaction.objectStore("keys").put({ id: catalog.id, key: localKey });
  } else {
    transaction.objectStore("keys").delete(catalog.id);
  }
  const images = transaction.objectStore("images");
  for (const record of imageRecords) {
    images.put(record);
  }
  await transactionDone(transaction);
}

export async function updateCatalog(catalog) {
  const db = await openDatabase();
  const transaction = db.transaction("catalogs", "readwrite");
  transaction.objectStore("catalogs").put(catalog);
  await transactionDone(transaction);
}

export async function saveLocalKey(catalogId, key) {
  const db = await openDatabase();
  const transaction = db.transaction("keys", "readwrite");
  transaction.objectStore("keys").put({ id: catalogId, key });
  await transactionDone(transaction);
}

export async function deleteLocalKey(catalogId) {
  const db = await openDatabase();
  const transaction = db.transaction("keys", "readwrite");
  transaction.objectStore("keys").delete(catalogId);
  await transactionDone(transaction);
}

export async function estimateStorage() {
  if (!navigator.storage?.estimate) {
    return null;
  }

  return navigator.storage.estimate();
}
