package com.local.slideshow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.StatFs;
import android.system.Os;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class OfflineImageStore {
    static final int MAX_SLOTS = 4;

    private enum ImageStorageKind {
        MISSING,
        MEDIA_ENCRYPTED,
        LEGACY_ENCRYPTED,
        PLAIN
    }

    private static final String KEY_ALIAS = "SlideShowOfflineImages";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final byte[] MAGIC = new byte[] { 'S', 'S', 'I', 'M', 'G' };
    private static final byte[] MEDIA_MAGIC = new byte[] { 'S', 'S', 'I', 'M', '2' };
    private static final int FORMAT_VERSION = 1;
    private static final int DEFAULT_SYNC_WORKERS = 4;
    private static final int MEDIA_KEY_BYTES = 32;
    private static final int PIN_SALT_BYTES = 16;
    private static final int PIN_HASH_BYTES = 32;
    private static final int PIN_HASH_ITERATIONS = 120000;
    private static final String OFFLINE_IMAGE_FORMAT_VERSION = "webp-q90-native-v1";
    private static final int OFFLINE_WEBP_QUALITY = 90;
    private static final int MAX_OPTIMIZE_SOURCE_BYTES = 64 * 1024 * 1024;

    private final File rootDirectory;
    private final File legacyImageDirectory;
    private final File legacyCatalogFile;
    private final File slotsDirectory;
    private final File mediaKeyFile;
    private SecretKey cachedKey;
    private SecretKey cachedMediaKey;

    OfflineImageStore(Context context) {
        rootDirectory = new File(context.getFilesDir(), "offline-image-store");
        legacyImageDirectory = new File(rootDirectory, "images");
        legacyCatalogFile = new File(rootDirectory, "catalog.enc");
        slotsDirectory = new File(rootDirectory, "slots");
        mediaKeyFile = new File(rootDirectory, "media.key");
    }

    List<OfflineCatalog> loadCatalogs() {
        migrateLegacyCatalog();
        List<OfflineCatalog> catalogs = new ArrayList<>();
        for (int slotId = 1; slotId <= MAX_SLOTS; slotId++) {
            OfflineCatalog catalog = loadCatalog(slotId);
            if (catalog != null && (!catalog.images.isEmpty() || catalog.hasPin())) {
                catalogs.add(catalog);
            }
        }
        return catalogs;
    }

    OfflineCatalog loadCatalog(int slotId) {
        try {
            if (!isValidSlot(slotId)) {
                return null;
            }

            File catalogFile = catalogFile(slotId);
            if (!catalogFile.isFile()) {
                return null;
            }

            byte[] bytes = readEncrypted(catalogFile);
            OfflineCatalog loaded = OfflineCatalog.fromJson(new JSONObject(new String(bytes, StandardCharsets.UTF_8)), slotId);
            OfflineCatalog catalog = loaded.withRecoveredImages(cachedImages(slotId));
            long sizeBytes = catalogSizeBytes(slotId);
            if (catalog.sizeBytes != sizeBytes || (loaded.images.isEmpty() && !catalog.images.isEmpty())) {
                catalog = catalog.withSize(sizeBytes);
                try {
                    writeCatalog(catalog);
                } catch (Exception ignored) {
                }
            }
            return catalog;
        } catch (Exception ignored) {
            return null;
        }
    }

    OfflineCatalog loadLastCatalog() {
        List<OfflineCatalog> catalogs = loadCatalogs();
        OfflineCatalog newest = null;
        for (OfflineCatalog catalog : catalogs) {
            if (newest == null || catalog.syncedAt > newest.syncedAt) {
                newest = catalog;
            }
        }
        return newest;
    }

    OfflineCatalog loadLastPlayableCatalog() {
        List<OfflineCatalog> catalogs = loadCatalogs();
        OfflineCatalog newest = null;
        for (OfflineCatalog catalog : catalogs) {
            if (catalog.images.isEmpty()) {
                continue;
            }

            if (newest == null || catalog.syncedAt > newest.syncedAt) {
                newest = catalog;
            }
        }
        return newest;
    }

    OfflineCatalog loadPlayableCatalogFor(String serverKey, JSONObject state) {
        String folderName = cleanString(state.optString("folderName", "Slide Show"), "Slide Show");
        String folderIdentity = folderIdentityFor(serverKey, state);
        String legacyFolderIdentity = folderIdentity(serverKey, folderName);
        for (int slotId = 1; slotId <= MAX_SLOTS; slotId++) {
            OfflineCatalog catalog = loadCatalog(slotId);
            if (catalog == null || catalog.images.isEmpty()) {
                continue;
            }

            if (folderIdentity.equals(catalog.folderIdentity) || legacyFolderIdentity.equals(catalog.folderIdentity)) {
                return catalog;
            }
        }

        return null;
    }

    int chooseSlotForSync(String serverKey, JSONObject state) {
        migrateLegacyCatalog();
        String folderName = cleanString(state.optString("folderName", "Slide Show"), "Slide Show");
        String folderIdentity = folderIdentityFor(serverKey, state);
        String legacyFolderIdentity = folderIdentity(serverKey, folderName);
        int firstEmpty = 0;

        for (int slotId = 1; slotId <= MAX_SLOTS; slotId++) {
            OfflineCatalog catalog = loadCatalog(slotId);
            if (catalog == null) {
                if (firstEmpty == 0) {
                    firstEmpty = slotId;
                }
                continue;
            }

            if (folderIdentity.equals(catalog.folderIdentity) || legacyFolderIdentity.equals(catalog.folderIdentity)) {
                return slotId;
            }

            if (catalog.images.isEmpty() && !catalog.hasPin() && firstEmpty == 0) {
                firstEmpty = slotId;
            }
        }

        return firstEmpty;
    }

    boolean hasAvailableSlotForSync(String serverKey, JSONObject state) {
        return chooseSlotForSync(serverKey, state) != 0;
    }

    OfflineCatalog sync(int slotId, String serverKey, String serverName, String baseUrl, JSONObject state, JSONArray imageList, int syncWorkers, ProgressListener listener) throws Exception {
        return sync(slotId, serverKey, serverName, baseUrl, state, imageList, syncWorkers, listener, new AtomicBoolean());
    }

    OfflineCatalog sync(int slotId, String serverKey, String serverName, String baseUrl, JSONObject state, JSONArray imageList, int syncWorkers, ProgressListener listener, AtomicBoolean canceled) throws Exception {
        if (!isValidSlot(slotId)) {
            throw new IllegalArgumentException("Invalid offline slot.");
        }

        ensureDirectories(slotId);
        removePartialDownloads(slotId);

        String folderName = cleanString(state.optString("folderName", "Slide Show"), "Slide Show");
        OfflineCatalog existing = loadCatalog(slotId);
        String folderIdentity = folderIdentityFor(serverKey, state);
        String legacyFolderIdentity = folderIdentity(serverKey, folderName);
        String displayName = existing != null && (folderIdentity.equals(existing.folderIdentity) || legacyFolderIdentity.equals(existing.folderIdentity))
            ? cleanString(existing.displayName, folderName)
            : folderName;

        List<MainActivity.SlideImage> nextImages = imagesForSource(serverKey, imageList);

        long required = 0;
        for (int i=0; i<imageList.length(); i++) required += imageList.getJSONObject(i).optLong("sizeBytes",0) + 64;
        if (required > new StatFs(rootDirectory.getAbsolutePath()).getAvailableBytes()) throw new IllegalStateException("Not enough storage. Free space or choose a smaller folder. Existing saved photos are unchanged.");
        checkCanceled(canceled);
        AtomicInteger completedCount = new AtomicInteger();
        int workerCount = normalizeSyncWorkers(syncWorkers);
        try {
            downloadImages(slotId, baseUrl, nextImages, workerCount, listener, completedCount, canceled);
            checkCanceled(canceled);
        } catch (Exception error) {
            Set<String> retained = new HashSet<>();
            if (existing != null) for (MainActivity.SlideImage image : existing.images) retained.add(image.encryptedFileName);
            for (MainActivity.SlideImage image : nextImages) if (!retained.contains(image.encryptedFileName)) imageFile(slotId, image).delete();
            throw error;
        }

        boolean preserveProtection = existing != null && (folderIdentity.equals(existing.folderIdentity) || legacyFolderIdentity.equals(existing.folderIdentity));
        OfflineCatalog catalog = new OfflineCatalog(
            slotId,
            serverKey,
            serverName,
            folderIdentity,
            displayName,
            folderName,
            cleanString(state.optString("backgroundColor", "#05070a"), "#05070a"),
            cleanString(state.optString("imageMode", "fit"), "fit"),
            state.optInt("slideSeconds", 7),
            state.optLong("version", 0),
            System.currentTimeMillis(),
            0,
            OFFLINE_IMAGE_FORMAT_VERSION,
            preserveProtection ? existing.pinSalt : "",
            preserveProtection ? existing.pinHash : "",
            nextImages);

        catalog.playbackOrder = state.optString("playbackOrder", "shuffle");
        catalog.collectionId = state.optString("collectionId", "");
        long savedBytes = 0;
        for (MainActivity.SlideImage image : nextImages) savedBytes += imageFile(slotId, image).length();
        catalog = catalog.withSize(savedBytes);
        checkCanceled(canceled);
        writeCatalog(catalog);
        purgeRemovedImages(slotId, nextImages);
        return catalog;
    }

    List<MainActivity.SlideImage> imagesForSource(String serverKey, JSONArray imageList) throws Exception {
        List<MainActivity.SlideImage> nextImages = new ArrayList<>();
        for (int i = 0; i < imageList.length(); i++) {
            JSONObject item = imageList.optJSONObject(i);
            if (item == null) {
                continue;
            }

            String path = item.optString("url", "");
            String name = item.optString("name", "Image");
            String cacheKey = item.optString("cacheKey", "");
            if (path.isEmpty()) {
                continue;
            }

            String sourceCacheKey = cacheKey.isEmpty() ? legacyCacheKey(path, name) : cacheKey;
            nextImages.add(new MainActivity.SlideImage(name, path, encryptedFileName(serverKey, offlineCacheKey(sourceCacheKey)), item.optLong("modifiedAt",0)));
        }

        return nextImages;
    }

    boolean catalogMatchesSource(OfflineCatalog catalog, String serverKey, JSONArray imageList) throws Exception {
        if (catalog == null) {
            return false;
        }

        List<MainActivity.SlideImage> nextImages = imagesForSource(serverKey, imageList);
        if (catalog.images.size() != nextImages.size()) {
            return false;
        }

        for (int i = 0; i < nextImages.size(); i++) {
            MainActivity.SlideImage current = catalog.images.get(i);
            MainActivity.SlideImage next = nextImages.get(i);
            if (!current.encryptedFileName.equals(next.encryptedFileName) || !current.name.equals(next.name)) {
                return false;
            }
        }

        return true;
    }

    boolean catalogMatchesMetadata(OfflineCatalog catalog, String serverKey, JSONObject state) {
        if (catalog == null) {
            return false;
        }

        String folderIdentity = folderIdentityFor(serverKey, state);
        String folderName = cleanString(state.optString("folderName", "Slide Show"), "Slide Show");
        String legacyFolderIdentity = folderIdentity(serverKey, folderName);
        return (folderIdentity.equals(catalog.folderIdentity) || legacyFolderIdentity.equals(catalog.folderIdentity))
            && folderName.equals(catalog.folderName)
            && cleanString(state.optString("backgroundColor", "#05070a"), "#05070a").equals(catalog.backgroundColor)
            && cleanString(state.optString("imageMode", "fit"), "fit").equals(catalog.imageMode)
            && state.optInt("slideSeconds", 7) == catalog.slideSeconds;
    }

    OfflineCatalog updateCatalogMetadata(int slotId, String serverKey, String serverName, JSONObject state) throws Exception {
        OfflineCatalog existing = loadCatalog(slotId);
        if (existing == null) {
            return null;
        }

        String folderName = cleanString(state.optString("folderName", "Slide Show"), "Slide Show");
        String folderIdentity = folderIdentityFor(serverKey, state);
        String legacyFolderIdentity = folderIdentity(serverKey, folderName);
        String displayName = folderIdentity.equals(existing.folderIdentity) || legacyFolderIdentity.equals(existing.folderIdentity)
            ? cleanString(existing.displayName, folderName)
            : folderName;
        OfflineCatalog catalog = new OfflineCatalog(
            slotId,
            serverKey,
            serverName,
            folderIdentity,
            displayName,
            folderName,
            cleanString(state.optString("backgroundColor", "#05070a"), "#05070a"),
            cleanString(state.optString("imageMode", "fit"), "fit"),
            state.optInt("slideSeconds", 7),
            state.optLong("version", existing.serverVersion),
            existing.syncedAt,
            existing.sizeBytes,
            existing.offlineImageFormat,
            existing.pinSalt,
            existing.pinHash,
            existing.images).withOrder(state.optString("playbackOrder", existing.playbackOrder))
                .withCollectionId(state.optString("collectionId", existing.collectionId));
        writeCatalog(catalog);
        return catalog;
    }

    void renameCatalog(int slotId, String displayName) throws Exception {
        OfflineCatalog catalog = loadCatalog(slotId);
        if (catalog == null) {
            return;
        }

        writeCatalog(catalog.withDisplayName(cleanString(displayName, catalog.folderName)));
    }

    void setCatalogPin(int slotId, String pin) throws Exception {
        OfflineCatalog catalog = loadCatalog(slotId);
        if (catalog == null) {
            return;
        }

        String cleanPin = cleanPin(pin);
        if (cleanPin.length() < 4) {
            throw new IllegalArgumentException("PIN must be at least 4 digits.");
        }

        byte[] salt = new byte[PIN_SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        writeCatalog(catalog.withPin(
            Base64.encodeToString(salt, Base64.NO_WRAP),
            Base64.encodeToString(hashPin(cleanPin, salt), Base64.NO_WRAP)));
    }

    void clearCatalogPin(int slotId) throws Exception {
        OfflineCatalog catalog = loadCatalog(slotId);
        if (catalog == null) {
            return;
        }

        writeCatalog(catalog.withPin("", ""));
    }

    boolean verifyCatalogPin(OfflineCatalog catalog, String pin) {
        try {
            if (catalog == null || !catalog.hasPin()) {
                return true;
            }

            byte[] salt = Base64.decode(catalog.pinSalt, Base64.NO_WRAP);
            byte[] expected = Base64.decode(catalog.pinHash, Base64.NO_WRAP);
            byte[] actual = hashPin(cleanPin(pin), salt);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception ignored) {
            return false;
        }
    }

    void deleteCatalog(int slotId) {
        if (!isValidSlot(slotId)) {
            return;
        }
        deleteRecursively(slotDirectory(slotId));
    }

    long catalogSizeBytes(int slotId) {
        return directorySize(slotDirectory(slotId));
    }

    Bitmap decodeBitmap(OfflineCatalog catalog, MainActivity.SlideImage image) throws Exception {
        return decodeBitmap(catalog, image, 0, 0);
    }

    Bitmap decodeBitmap(OfflineCatalog catalog, MainActivity.SlideImage image, int targetWidth, int targetHeight) throws Exception {
        byte[] bytes = readImageBytes(imageFile(catalog.slotId, image));
        BitmapFactory.Options options = new BitmapFactory.Options();
        if (targetWidth > 0 && targetHeight > 0) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            options.inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight);
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) {
            throw new IllegalStateException("Image could not be decoded: " + image.name);
        }

        return bitmap;
    }

    Bitmap decodeBitmap(MainActivity.SlideImage image) throws Exception {
        OfflineCatalog catalog = loadLastCatalog();
        if (catalog == null) {
            throw new IllegalStateException("No offline catalog is available.");
        }
        return decodeBitmap(catalog, image);
    }

    private static void checkCanceled(AtomicBoolean canceled) {
        if (canceled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException("Download canceled. Your previous saved copy is unchanged.");
    }

    Bitmap decodeOnlineBitmap(String address, int width, int height) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(4000); connection.setReadTimeout(15000);
        try (InputStream input = connection.getInputStream()) {
            byte[] bytes = readFully(input);
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calculateInSampleSize(bounds.outWidth,bounds.outHeight,width,height);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            if (bitmap == null) throw new IllegalStateException("Unsupported photo format. Try JPG, PNG or WebP.");
            return bitmap;
        } finally { connection.disconnect(); }
    }

    private void downloadImages(
        int slotId,
        String baseUrl,
        List<MainActivity.SlideImage> images,
        int syncWorkers,
        ProgressListener listener,
        AtomicInteger completedCount, AtomicBoolean canceled) throws Exception {
        int total = images.size();
        if (total == 0) {
            return;
        }

        int workerCount = Math.min(syncWorkers, total);
        ExecutorService downloadExecutor = Executors.newFixedThreadPool(workerCount);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (MainActivity.SlideImage image : images) {
                futures.add(downloadExecutor.submit(() -> {
                    checkCanceled(canceled);
                    File imageFile = imageFile(slotId, image);
                    ImageStorageKind storageKind = imageStorageKind(imageFile);
                    boolean needsDownload = storageKind != ImageStorageKind.MEDIA_ENCRYPTED;
                    if (needsDownload) {
                        try {
                            downloadOptimizedMediaEncrypted(baseUrl + image.path, imageFile, canceled);
                        } catch (Exception error) {
                            checkCanceled(canceled);
                            if (storageKind == ImageStorageKind.MISSING) {
                                throw error;
                            }

                            ensureMediaEncryptedImage(imageFile);
                            int completed = completedCount.incrementAndGet();
                            if (listener != null) {
                                listener.onProgress(completed, total, image.name);
                            }
                            return null;
                        }
                    }

                    int completed = completedCount.incrementAndGet();
                    if (listener != null) {
                        listener.onProgress(completed, total, image.name);
                    }
                    return null;
                }));
            }

            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    throw new RuntimeException(cause);
                }
            }
        } finally {
            downloadExecutor.shutdownNow();
            downloadExecutor.awaitTermination(25, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private void downloadOptimizedMediaEncrypted(String address, File destination, AtomicBoolean canceled) throws Exception {
        File partial = new File(destination.getParentFile(), destination.getName() + ".part");
        if (partial.exists() && !partial.delete()) {
            throw new IllegalStateException("Could not replace partial download.");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(20000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("Pragma", "no-cache");

        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Image download failed.");
            }

            try (InputStream input = new java.io.FilterInputStream(connection.getInputStream()) {
                @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                    checkCanceled(canceled); return super.read(bytes,offset,length);
                }
                @Override public int read() throws java.io.IOException { checkCanceled(canceled); return super.read(); }
            }) {
                int length = connection.getContentLength();
                if (length > 0 && length <= MAX_OPTIMIZE_SOURCE_BYTES) {
                    writeOptimizedMediaEncryptedFile(partial, readFully(input));
                } else {
                    writeMediaEncryptedFile(partial, input);
                }
                checkCanceled(canceled);
                Os.rename(partial.getAbsolutePath(), destination.getAbsolutePath());
            }
        } finally {
            connection.disconnect();
            if (partial.exists()) {
                partial.delete();
            }
        }
    }

    private void migrateLegacyCatalog() {
        if (!legacyCatalogFile.isFile() || catalogFile(1).isFile()) {
            return;
        }

        try {
            ensureDirectories(1);
            if (legacyImageDirectory.isDirectory()) {
                File[] files = legacyImageDirectory.listFiles();
                if (files != null) {
                    for (File file : files) {
                        File target = new File(imageDirectory(1), file.getName());
                        if (!target.exists()) {
                            file.renameTo(target);
                        }
                    }
                }
            }

            if (legacyCatalogFile.renameTo(catalogFile(1))) {
                OfflineCatalog catalog = loadCatalog(1);
                if (catalog != null) {
                    writeCatalog(catalog.withSize(catalogSizeBytes(1)));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void writeCatalog(OfflineCatalog catalog) throws Exception {
        ensureDirectories(catalog.slotId);
        File destination = catalogFile(catalog.slotId);
        File temporary = new File(destination.getParentFile(), "catalog.next");
        try {
            writeEncrypted(temporary, catalog.toJson().toString().getBytes(StandardCharsets.UTF_8));
            Os.rename(temporary.getAbsolutePath(), destination.getAbsolutePath());
        } finally { temporary.delete(); }
    }

    private void writeEncrypted(File destination, byte[] plaintext) throws Exception {
        try (InputStream input = new java.io.ByteArrayInputStream(plaintext)) {
            writeEncrypted(destination, input);
        }
    }

    private void writeEncrypted(File destination, InputStream plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();

        try (FileOutputStream file = new FileOutputStream(destination);
             CipherOutputStream encrypted = new CipherOutputStream(file, cipher)) {
            file.write(MAGIC);
            file.write(FORMAT_VERSION);
            file.write(iv.length);
            file.write(iv);

            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = plaintext.read(buffer)) != -1) {
                encrypted.write(buffer, 0, read);
            }
        }
    }

    private void ensureMediaEncryptedImage(File file) throws Exception {
        ImageStorageKind storageKind = imageStorageKind(file);
        if (storageKind == ImageStorageKind.MEDIA_ENCRYPTED || storageKind == ImageStorageKind.MISSING) {
            return;
        }

        readImageBytes(file);
    }

    private byte[] readImageBytes(File file) throws Exception {
        // Multiple previews can migrate the same saved image, including across activities.
        synchronized (OfflineImageStore.class) {
            ImageStorageKind storageKind = imageStorageKind(file);
            if (storageKind == ImageStorageKind.MEDIA_ENCRYPTED) return readMediaEncryptedImage(file);
            byte[] bytes = storageKind == ImageStorageKind.LEGACY_ENCRYPTED
                ? readEncrypted(file) : readPlainFile(file);
            replaceWithMediaEncryptedImage(file, bytes);
            return bytes;
        }
    }

    private void replaceWithMediaEncryptedImage(File destination, byte[] bytes) throws Exception {
        File partial = new File(destination.getParentFile(), destination.getName() + ".media");
        if (partial.exists()) {
            partial.delete();
        }

        try (InputStream input = new java.io.ByteArrayInputStream(bytes)) {
            writeMediaEncryptedFile(partial, input);
        }

        Os.rename(partial.getAbsolutePath(), destination.getAbsolutePath());
    }

    private void writeMediaEncryptedFile(File destination, InputStream plaintext) throws Exception {
        try (FileOutputStream file = new FileOutputStream(destination);
             CipherOutputStream encrypted = openMediaEncryptedOutput(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = plaintext.read(buffer)) != -1) {
                encrypted.write(buffer, 0, read);
            }
        }
    }

    private void writeOptimizedMediaEncryptedFile(File destination, byte[] sourceBytes) throws Exception {
        Bitmap bitmap = null;
        try {
            bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.length);
            if (bitmap == null) {
                writeMediaEncryptedFile(destination, new ByteArrayInputStream(sourceBytes));
                return;
            }

            try (FileOutputStream file = new FileOutputStream(destination);
                 CipherOutputStream encrypted = openMediaEncryptedOutput(file)) {
                if (!bitmap.compress(webpCompressFormat(), OFFLINE_WEBP_QUALITY, encrypted)) {
                    throw new IllegalStateException("Could not optimize image.");
                }
            }
        } catch (OutOfMemoryError error) {
            writeMediaEncryptedFile(destination, new ByteArrayInputStream(sourceBytes));
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private CipherOutputStream openMediaEncryptedOutput(FileOutputStream file) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMediaKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));

        file.write(MEDIA_MAGIC);
        file.write(FORMAT_VERSION);
        file.write(iv.length);
        file.write(iv);
        return new CipherOutputStream(file, cipher);
    }

    private ImageStorageKind imageStorageKind(File file) {
        if (file == null || !file.isFile() || file.length() == 0) {
            return ImageStorageKind.MISSING;
        }

        if (hasMagic(file, MEDIA_MAGIC)) {
            return ImageStorageKind.MEDIA_ENCRYPTED;
        }

        return hasMagic(file, MAGIC) ? ImageStorageKind.LEGACY_ENCRYPTED : ImageStorageKind.PLAIN;
    }

    private boolean hasMagic(File file, byte[] expectedMagic) {
        if (file == null || !file.isFile() || file.length() < expectedMagic.length) {
            return false;
        }

        try (InputStream input = new FileInputStream(file)) {
            byte[] magic = new byte[expectedMagic.length];
            if (input.read(magic) != expectedMagic.length) {
                return false;
            }

            for (int i = 0; i < expectedMagic.length; i++) {
                if (magic[i] != expectedMagic[i]) {
                    return false;
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private byte[] readPlainFile(File source) throws Exception {
        try (InputStream input = new FileInputStream(source)) {
            return readFully(input);
        }
    }

    private byte[] readMediaEncryptedImage(File source) throws Exception {
        try (InputStream input = openMediaDecryptedStream(source)) {
            return readFully(input);
        }
    }

    private byte[] readEncrypted(File source) throws Exception {
        try (InputStream input = openDecryptedStream(source)) {
            return readFully(input);
        }
    }

    private byte[] readFully(InputStream input) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private InputStream openDecryptedStream(File source) throws Exception {
        DataInputStream input = new DataInputStream(new FileInputStream(source));
        try {
            byte[] magic = new byte[MAGIC.length];
            input.readFully(magic);
            for (int i = 0; i < MAGIC.length; i++) {
                if (magic[i] != MAGIC[i]) {
                    throw new IllegalStateException("Encrypted image header is invalid.");
                }
            }

            int version = input.readUnsignedByte();
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException("Encrypted image version is unsupported.");
            }

            int ivLength = input.readUnsignedByte();
            if (ivLength <= 0 || ivLength > 32) {
                throw new IllegalStateException("Encrypted image IV is invalid.");
            }

            byte[] iv = new byte[ivLength];
            input.readFully(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new CipherInputStream(input, cipher);
        } catch (Exception error) {
            input.close();
            throw error;
        }
    }

    private InputStream openMediaDecryptedStream(File source) throws Exception {
        DataInputStream input = new DataInputStream(new FileInputStream(source));
        try {
            byte[] magic = new byte[MEDIA_MAGIC.length];
            input.readFully(magic);
            for (int i = 0; i < MEDIA_MAGIC.length; i++) {
                if (magic[i] != MEDIA_MAGIC[i]) {
                    throw new IllegalStateException("Encrypted media header is invalid.");
                }
            }

            int version = input.readUnsignedByte();
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException("Encrypted media version is unsupported.");
            }

            int ivLength = input.readUnsignedByte();
            if (ivLength <= 0 || ivLength > 32) {
                throw new IllegalStateException("Encrypted media IV is invalid.");
            }

            byte[] iv = new byte[ivLength];
            input.readFully(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateMediaKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new CipherInputStream(input, cipher);
        } catch (Exception error) {
            input.close();
            throw error;
        }
    }

    private synchronized SecretKey getOrCreateMediaKey() throws Exception {
        if (cachedMediaKey != null) {
            return cachedMediaKey;
        }

        if (mediaKeyFile.isFile()) {
            byte[] keyBytes = readEncrypted(mediaKeyFile);
            cachedMediaKey = new SecretKeySpec(keyBytes, KeyProperties.KEY_ALGORITHM_AES);
            return cachedMediaKey;
        }

        if (!rootDirectory.isDirectory()) {
            rootDirectory.mkdirs();
        }

        byte[] keyBytes = new byte[MEDIA_KEY_BYTES];
        new SecureRandom().nextBytes(keyBytes);
        writeEncrypted(mediaKeyFile, keyBytes);
        cachedMediaKey = new SecretKeySpec(keyBytes, KeyProperties.KEY_ALGORITHM_AES);
        return cachedMediaKey;
    }

    private synchronized SecretKey getOrCreateKey() throws Exception {
        if (cachedKey != null) {
            return cachedKey;
        }

        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);

        KeyStore.Entry existing = keyStore.getEntry(KEY_ALIAS, null);
        if (existing instanceof KeyStore.SecretKeyEntry) {
            cachedKey = ((KeyStore.SecretKeyEntry) existing).getSecretKey();
            return cachedKey;
        }

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build());
        cachedKey = generator.generateKey();
        return cachedKey;
    }

    private void ensureDirectories(int slotId) {
        File images = imageDirectory(slotId);
        if (!images.isDirectory()) {
            images.mkdirs();
        }
    }

    private void removePartialDownloads(int slotId) {
        File[] files = imageDirectory(slotId).listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.getName().endsWith(".part") || file.getName().endsWith(".plain") || file.getName().endsWith(".media")) {
                file.delete();
            }
        }
    }

    private void purgeRemovedImages(int slotId, List<MainActivity.SlideImage> images) {
        Set<String> keep = new HashSet<>();
        for (MainActivity.SlideImage image : images) {
            keep.add(image.encryptedFileName);
        }

        File[] files = imageDirectory(slotId).listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".enc") && !keep.contains(file.getName())) {
                file.delete();
            }
        }
    }

    private List<MainActivity.SlideImage> cachedImages(int slotId) {
        List<MainActivity.SlideImage> images = new ArrayList<>();
        File[] files = imageDirectory(slotId).listFiles();
        if (files == null) {
            return images;
        }

        Arrays.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        for (File file : files) {
            if (isCachedImageFile(file)) {
                images.add(new MainActivity.SlideImage("Saved image", "", file.getName()));
            }
        }
        return images;
    }

    private boolean isCachedImageFile(File file) {
        if (imageStorageKind(file) == ImageStorageKind.MISSING) {
            return false;
        }

        String name = file.getName();
        return !name.endsWith(".part") && !name.endsWith(".plain") && !name.endsWith(".media");
    }

    private File slotDirectory(int slotId) {
        return new File(slotsDirectory, "slot-" + slotId);
    }

    private File imageDirectory(int slotId) {
        return new File(slotDirectory(slotId), "images");
    }

    private File catalogFile(int slotId) {
        return new File(slotDirectory(slotId), "catalog.enc");
    }

    private File imageFile(int slotId, MainActivity.SlideImage image) {
        return new File(imageDirectory(slotId), image.encryptedFileName);
    }

    private boolean isValidSlot(int slotId) {
        return slotId >= 1 && slotId <= MAX_SLOTS;
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private long directorySize(File file) {
        if (file == null || !file.exists()) {
            return 0;
        }

        if (file.isFile()) {
            return file.length();
        }

        long total = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += directorySize(child);
            }
        }
        return total;
    }

    private static String encryptedFileName(String serverKey, String cacheKey) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest((serverKey + "\n" + cacheKey).getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder + ".enc";
    }

    private static String legacyCacheKey(String path, String name) {
        int queryIndex = path.indexOf('?');
        return (queryIndex >= 0 ? path.substring(0, queryIndex) : path) + "\n" + name;
    }

    private static String offlineCacheKey(String sourceCacheKey) {
        return sourceCacheKey + "\n" + OFFLINE_IMAGE_FORMAT_VERSION;
    }

    private static Bitmap.CompressFormat webpCompressFormat() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
    }

    static String folderIdentityFor(String serverKey, JSONObject state) {
        String folderPath = cleanString(state.optString("folderPath", ""), "");
        String folderName = cleanString(state.optString("folderName", "Slide Show"), "Slide Show");
        return serverKey + "\n" + (folderPath.isEmpty() ? folderName : folderPath);
    }

    private static String folderIdentity(String serverKey, String folderName) {
        return serverKey + "\n" + cleanString(folderName, "Slide Show");
    }

    private static String cleanString(String value, String fallback) {
        return value == null || value.trim().isEmpty() || "null".equals(value) ? fallback : value.trim();
    }

    private static String cleanPin(String pin) {
        return pin == null ? "" : pin.trim();
    }

    private static byte[] hashPin(String pin, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, PIN_HASH_ITERATIONS, PIN_HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static int normalizeSyncWorkers(int value) {
        if (value <= 0) {
            return DEFAULT_SYNC_WORKERS;
        }

        return Math.max(2, Math.min(4, value));
    }

    private static int calculateInSampleSize(int width, int height, int targetWidth, int targetHeight) {
        if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return 1;
        }

        int sample = 1;
        while ((width / (sample * 2)) >= targetWidth || (height / (sample * 2)) >= targetHeight) {
            sample *= 2;
        }
        return sample;
    }

    interface ProgressListener {
        void onProgress(int completed, int total, String name);
    }

    static final class OfflineCatalog {
        final int slotId;
        final String serverKey;
        final String serverName;
        final String folderIdentity;
        final String displayName;
        final String folderName;
        final String backgroundColor;
        final String imageMode;
        final int slideSeconds;
        String playbackOrder = "shuffle";
        String collectionId = "";
        final long serverVersion;
        final long syncedAt;
        final long sizeBytes;
        final String offlineImageFormat;
        final String pinSalt;
        final String pinHash;
        final List<MainActivity.SlideImage> images;

        OfflineCatalog(int slotId, String serverKey, String serverName, String folderIdentity, String displayName, String folderName, String backgroundColor, String imageMode, int slideSeconds, long serverVersion, long syncedAt, long sizeBytes, String offlineImageFormat, String pinSalt, String pinHash, List<MainActivity.SlideImage> images) {
            this.slotId = slotId;
            this.serverKey = serverKey;
            this.serverName = serverName;
            this.folderIdentity = folderIdentity;
            this.displayName = displayName;
            this.folderName = folderName;
            this.backgroundColor = backgroundColor;
            this.imageMode = imageMode;
            this.slideSeconds = slideSeconds;
            this.serverVersion = serverVersion;
            this.syncedAt = syncedAt;
            this.sizeBytes = sizeBytes;
            this.offlineImageFormat = offlineImageFormat == null ? "" : offlineImageFormat;
            this.pinSalt = pinSalt == null ? "" : pinSalt;
            this.pinHash = pinHash == null ? "" : pinHash;
            this.images = images;
        }

        OfflineCatalog withDisplayName(String nextDisplayName) {
            return new OfflineCatalog(slotId, serverKey, serverName, folderIdentity, nextDisplayName, folderName, backgroundColor, imageMode, slideSeconds, serverVersion, syncedAt, sizeBytes, offlineImageFormat, pinSalt, pinHash, images).withOrder(playbackOrder).withCollectionId(collectionId);
        }

        OfflineCatalog withSize(long nextSizeBytes) {
            return new OfflineCatalog(slotId, serverKey, serverName, folderIdentity, displayName, folderName, backgroundColor, imageMode, slideSeconds, serverVersion, syncedAt, nextSizeBytes, offlineImageFormat, pinSalt, pinHash, images).withOrder(playbackOrder).withCollectionId(collectionId);
        }

        OfflineCatalog withPin(String nextPinSalt, String nextPinHash) {
            return new OfflineCatalog(slotId, serverKey, serverName, folderIdentity, displayName, folderName, backgroundColor, imageMode, slideSeconds, serverVersion, syncedAt, sizeBytes, offlineImageFormat, nextPinSalt, nextPinHash, images).withOrder(playbackOrder).withCollectionId(collectionId);
        }

        OfflineCatalog withRecoveredImages(List<MainActivity.SlideImage> recoveredImages) {
            if (!images.isEmpty() || recoveredImages == null || recoveredImages.isEmpty()) {
                return this;
            }

            return new OfflineCatalog(slotId, serverKey, serverName, folderIdentity, displayName, folderName, backgroundColor, imageMode, slideSeconds, serverVersion, syncedAt, sizeBytes, offlineImageFormat, pinSalt, pinHash, recoveredImages).withOrder(playbackOrder).withCollectionId(collectionId);
        }

        OfflineCatalog withOrder(String order) { playbackOrder = "name".equals(order) || "date".equals(order) ? order : "shuffle"; return this; }
        OfflineCatalog withCollectionId(String id) { collectionId = id == null ? "" : id; return this; }

        boolean hasPin() {
            return !pinSalt.isEmpty() && !pinHash.isEmpty();
        }

        boolean usesCurrentImageFormat() {
            return OFFLINE_IMAGE_FORMAT_VERSION.equals(offlineImageFormat);
        }

        JSONObject toJson() throws Exception {
            JSONObject object = new JSONObject();
            object.put("slotId", slotId);
            object.put("serverKey", serverKey);
            object.put("serverName", serverName);
            object.put("folderIdentity", folderIdentity);
            object.put("collectionId", collectionId);
            object.put("displayName", displayName);
            object.put("folderName", folderName);
            object.put("backgroundColor", backgroundColor);
            object.put("imageMode", imageMode);
            object.put("playbackOrder", playbackOrder);
            object.put("slideSeconds", slideSeconds);
            object.put("serverVersion", serverVersion);
            object.put("syncedAt", syncedAt);
            object.put("sizeBytes", sizeBytes);
            object.put("offlineImageFormat", offlineImageFormat);
            object.put("pinSalt", pinSalt);
            object.put("pinHash", pinHash);

            JSONArray imageArray = new JSONArray();
            for (MainActivity.SlideImage image : images) {
                JSONObject item = new JSONObject();
                item.put("name", image.name);
                item.put("path", image.path);
                item.put("encryptedFileName", image.encryptedFileName);
                item.put("modifiedAt", image.modifiedAt);
                imageArray.put(item);
            }
            object.put("images", imageArray);
            return object;
        }

        static OfflineCatalog fromJson(JSONObject object, int fallbackSlotId) {
            JSONArray imageArray = object.optJSONArray("images");
            List<MainActivity.SlideImage> images = new ArrayList<>();
            if (imageArray != null) {
                for (int i = 0; i < imageArray.length(); i++) {
                    JSONObject item = imageArray.optJSONObject(i);
                    if (item != null) {
                        String path = item.optString("path", "");
                        String encryptedFileName = item.optString("encryptedFileName", "");
                        if (!encryptedFileName.isEmpty()) {
                            images.add(new MainActivity.SlideImage(item.optString("name", "Image"), path, encryptedFileName, item.optLong("modifiedAt",0)));
                        }
                    }
                }
            }

            String serverKey = object.optString("serverKey", "");
            String folderName = object.optString("folderName", "Slide Show");
            String displayName = object.optString("displayName", folderName);
            String folderIdentity = object.optString("folderIdentity", folderIdentity(serverKey, folderName));

            return new OfflineCatalog(
                object.optInt("slotId", fallbackSlotId),
                serverKey,
                object.optString("serverName", "Slide Show"),
                folderIdentity,
                displayName,
                folderName,
                object.optString("backgroundColor", "#05070a"),
                object.optString("imageMode", "fit"),
                object.optInt("slideSeconds", 7),
                object.optLong("serverVersion", 0),
                object.optLong("syncedAt", 0),
                object.optLong("sizeBytes", 0),
                object.optString("offlineImageFormat", ""),
                object.optString("pinSalt", ""),
                object.optString("pinHash", ""),
                images).withOrder(object.optString("playbackOrder", "shuffle")).withCollectionId(object.optString("collectionId", ""));
        }
    }
}
