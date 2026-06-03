const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder();
const PIN_CHECK_TEXT = "slide-show-pin-check-v1";

export const PIN_ITERATIONS = 250000;

export function isCryptoAvailable() {
  return Boolean(window.isSecureContext && window.crypto?.subtle && window.indexedDB);
}

export function randomBytes(length) {
  const bytes = new Uint8Array(length);
  window.crypto.getRandomValues(bytes);
  return bytes;
}

export function bytesToBase64(value) {
  const bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
  let output = "";
  for (let index = 0; index < bytes.length; index += 0x8000) {
    output += String.fromCharCode(...bytes.subarray(index, index + 0x8000));
  }
  return btoa(output);
}

export function base64ToBytes(value) {
  const binary = atob(value || "");
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

export async function generateLocalKey() {
  return window.crypto.subtle.generateKey(
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"]
  );
}

export async function derivePinKey(pin, saltBase64, iterations = PIN_ITERATIONS) {
  const salt = typeof saltBase64 === "string" ? base64ToBytes(saltBase64) : saltBase64;
  const baseKey = await window.crypto.subtle.importKey(
    "raw",
    textEncoder.encode(pin),
    "PBKDF2",
    false,
    ["deriveKey"]
  );

  return window.crypto.subtle.deriveKey(
    {
      name: "PBKDF2",
      salt,
      iterations,
      hash: "SHA-256"
    },
    baseKey,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"]
  );
}

export async function encryptArrayBuffer(key, clearBytes) {
  const iv = randomBytes(12);
  const encrypted = await window.crypto.subtle.encrypt(
    { name: "AES-GCM", iv },
    key,
    clearBytes
  );

  return {
    encrypted,
    iv: bytesToBase64(iv)
  };
}

export async function decryptArrayBuffer(key, encryptedBytes, ivBase64) {
  const iv = base64ToBytes(ivBase64);
  return window.crypto.subtle.decrypt(
    { name: "AES-GCM", iv },
    key,
    encryptedBytes
  );
}

export async function encryptBlob(key, blob) {
  const encrypted = await encryptArrayBuffer(key, await blob.arrayBuffer());
  return {
    encryptedBlob: new Blob([encrypted.encrypted], { type: "application/octet-stream" }),
    iv: encrypted.iv
  };
}

export async function decryptBlob(key, encryptedBlob, ivBase64, mimeType) {
  const clearBytes = await decryptArrayBuffer(key, await encryptedBlob.arrayBuffer(), ivBase64);
  return new Blob([clearBytes], { type: mimeType || "application/octet-stream" });
}

export async function createPinVerifier(key) {
  const encrypted = await encryptArrayBuffer(key, textEncoder.encode(PIN_CHECK_TEXT));
  return {
    verifierData: bytesToBase64(encrypted.encrypted),
    verifierIv: encrypted.iv
  };
}

export async function verifyPinKey(key, verifierData, verifierIv) {
  const decrypted = await decryptArrayBuffer(key, base64ToBytes(verifierData), verifierIv);
  return textDecoder.decode(decrypted) === PIN_CHECK_TEXT;
}
