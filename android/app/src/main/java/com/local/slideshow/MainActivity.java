package com.local.slideshow;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int WIFI_PERMISSION_REQUEST = 42;
    private static final int DISCOVERY_PORT = 51778;
    private static final String DISCOVERY_PROBE = "SLIDE_SHOW_DISCOVER_V1";
    private static final String LOG_TAG = "SlideShowAndroid";
    private static final long SERVER_FOLDER_WATCH_MS = 1000;
    private static final int INK = Color.rgb(23, 32, 29);
    private static final int MUTED = Color.rgb(99, 113, 108);
    private static final int ACCENT = Color.rgb(23, 108, 95);
    private static final int SURFACE = Color.rgb(246, 247, 244);

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService preloadExecutor = Executors.newFixedThreadPool(2);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, ServerInfo> discovered = new LinkedHashMap<>();
    private final List<SlideImage> images = new ArrayList<>();
    private final Map<String, Bitmap> bitmapCache = new LinkedHashMap<>();
    private final Set<String> preloadInFlight = new HashSet<>();
    private final Set<Integer> unlockedCatalogSlots = new HashSet<>();

    private OfflineImageStore offlineStore;
    private FrameLayout root;
    private LinearLayout discoveryList;
    private TextView discoveryTitle;
    private TextView discoveryMessage;
    private ProgressBar discoveryProgress;
    private ImageView slideImage;
    private TextView emptyTitle;
    private TextView emptyMessage;
    private TextView folderTitle;
    private TextView positionText;
    private TextView timerText;
    private TextView feedbackText;
    private LinearLayout chrome;
    private LinearLayout settingsPanel;
    private Button settingsButton;
    private Button fitButton;
    private Button fullButton;

    private ServerInfo connectedServer;
    private String folderName = "Slide Show";
    private String imageMode = "fit";
    private int slideSeconds = 7;
    private int currentIndex = 0;
    private boolean playing = true;
    private boolean showingSlideshow = false;
    private volatile int imageLoadToken = 0;
    private Bitmap currentBitmap;
    private OfflineImageStore.OfflineCatalog activeCatalog;
    private boolean activeOffline;
    private volatile boolean syncInProgress;
    private volatile boolean checkingServerFolder;
    private volatile boolean pendingServerFolderCheck;
    private boolean replacementPromptShowing;
    private boolean slideshowTapCandidate;
    private boolean protectedSlideshowPaused;
    private float slideshowTapStartX;
    private float slideshowTapStartY;
    private Runnable slideshowBackAction;

    private final Runnable viewerHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!showingSlideshow) {
                return;
            }

            postViewerHeartbeat(true);
            handler.postDelayed(this, 20000);
        }
    };

    private final Runnable advanceRunnable = new Runnable() {
        @Override
        public void run() {
            if (playing && !images.isEmpty()) {
                advance(1);
            }
        }
    };

    private final Runnable hideChromeRunnable = new Runnable() {
        @Override
        public void run() {
            if (settingsPanel == null || settingsPanel.getVisibility() != View.VISIBLE) {
                setChromeVisible(false);
            }
        }
    };

    private final Runnable hideFeedbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (feedbackText != null) {
                feedbackText.setVisibility(View.GONE);
            }
        }
    };

    private final Runnable serverFolderWatchRunnable = new Runnable() {
        @Override
        public void run() {
            checkServerFolderForChanges();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        offlineStore = new OfflineImageStore(this);
        enterFullscreen();
        showDiscoveryScreen("Looking for Slide Show on this Wi-Fi", "Make sure your phone and PC are on the same network.");
        if (needsWifiPermission()) {
            requestPermissions(new String[] { Manifest.permission.NEARBY_WIFI_DEVICES }, WIFI_PERMISSION_REQUEST);
        } else {
            discoverServers();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == WIFI_PERMISSION_REQUEST) {
            discoverServers();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        imageExecutor.shutdownNow();
        preloadExecutor.shutdownNow();
        executor.shutdownNow();
        recycleCurrentBitmap();
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!showingSlideshow || root == null) {
            return super.dispatchTouchEvent(event);
        }

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            slideshowTapStartX = event.getX();
            slideshowTapStartY = event.getY();
            slideshowTapCandidate = isSlideshowTapArea(event);
            if (slideshowTapCandidate) {
                return true;
            }
        } else if (slideshowTapCandidate) {
            if (action == MotionEvent.ACTION_UP) {
                float dx = Math.abs(event.getX() - slideshowTapStartX);
                float dy = Math.abs(event.getY() - slideshowTapStartY);
                if (dx <= dp(18) && dy <= dp(18)) {
                    handleSlideshowTap(event.getX());
                }
                slideshowTapCandidate = false;
                return true;
            }

            if (action == MotionEvent.ACTION_CANCEL) {
                slideshowTapCandidate = false;
                return true;
            }

            if (action == MotionEvent.ACTION_MOVE) {
                return true;
            }
        }

        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (showingSlideshow) {
            if (settingsPanel != null && settingsPanel.getVisibility() == View.VISIBLE) {
                setSettingsPanelVisible(false);
                showChromeTemporarily();
                return;
            }

            returnToSlideshowLauncher();
            return;
        }

        super.onBackPressed();
    }

    @Override
    protected void onStop() {
        if (activeCatalog != null && activeCatalog.hasPin()) {
            unlockedCatalogSlots.remove(activeCatalog.slotId);
            protectedSlideshowPaused = showingSlideshow;
            lockActiveSlideshow();
        }
        stopViewerHeartbeat();
        stopServerFolderWatch();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (protectedSlideshowPaused && activeCatalog != null && activeCatalog.hasPin() && !isCatalogUnlocked(activeCatalog)) {
            showPinUnlockDialog(activeCatalog, () -> {
                protectedSlideshowPaused = false;
                playing = true;
                renderCurrentSlide();
                scheduleNext();
                showChromeTemporarily();
            }, this::leaveProtectedSlideshow);
        }
        if (showingSlideshow && connectedServer != null) {
            startViewerHeartbeat();
            startServerFolderWatch();
        }
    }

    private void enterFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private boolean needsWifiPermission() {
        return Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED;
    }

    private void showDiscoveryScreen(String title, String message) {
        showingSlideshow = false;
        handler.removeCallbacks(advanceRunnable);
        handler.removeCallbacks(hideChromeRunnable);
        handler.removeCallbacks(hideFeedbackRunnable);
        stopViewerHeartbeat();
        stopServerFolderWatch();
        recycleCurrentBitmap();
        root = new FrameLayout(this);
        root.setBackgroundColor(SURFACE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(24), dp(42), dp(24), dp(28));
        scroll.addView(shell);

        TextView eyebrow = text("Local network", 13, MUTED, true);
        shell.addView(eyebrow);

        discoveryTitle = text(title, 30, INK, true);
        discoveryTitle.setPadding(0, dp(6), 0, dp(8));
        shell.addView(discoveryTitle);

        discoveryMessage = text(message, 16, MUTED, false);
        discoveryMessage.setLineSpacing(0, 1.15f);
        shell.addView(discoveryMessage);

        discoveryProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        discoveryProgress.setIndeterminate(true);
        discoveryProgress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        discoveryProgress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        discoveryProgress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(214, 220, 216)));
        discoveryProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(6));
        progressParams.setMargins(0, dp(24), 0, dp(16));
        shell.addView(discoveryProgress, progressParams);

        discoveryList = new LinearLayout(this);
        discoveryList.setOrientation(LinearLayout.VERTICAL);
        shell.addView(discoveryList);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dp(20), 0, 0);
        shell.addView(actions);

        Button searchAgain = button("Search again", false);
        searchAgain.setOnClickListener(v -> discoverServers());
        actions.addView(searchAgain, fullButtonParams());

        Button manual = button("Enter address manually", false);
        manual.setOnClickListener(v -> showManualAddressDialog());
        actions.addView(manual, fullButtonParams());

        root.addView(scroll);
        setContentView(root);
    }

    private void discoverServers() {
        discovered.clear();
        if (discoveryList != null) {
            discoveryList.removeAllViews();
        }
        if (discoveryTitle != null) {
            discoveryTitle.setText("Looking for Slide Show on this Wi-Fi");
            discoveryMessage.setText("Make sure your phone and PC are on the same network.");
            hideSyncProgress();
        }

        executor.execute(() -> {
            WifiManager.MulticastLock lock = null;
            DatagramSocket socket = null;
            try {
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wifi != null) {
                    lock = wifi.createMulticastLock("SlideShowDiscovery");
                    lock.setReferenceCounted(false);
                    lock.acquire();
                }

                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.setBroadcast(true);
                socket.bind(new InetSocketAddress(0));
                socket.setSoTimeout(700);

                byte[] probe = DISCOVERY_PROBE.getBytes(StandardCharsets.UTF_8);
                for (InetAddress address : broadcastAddresses(wifi)) {
                    socket.send(new DatagramPacket(probe, probe.length, address, DISCOVERY_PORT));
                }

                long stopAt = System.currentTimeMillis() + 2800;
                byte[] buffer = new byte[2048];
                while (System.currentTimeMillis() < stopAt) {
                    try {
                        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                        socket.receive(response);
                        String json = new String(response.getData(), 0, response.getLength(), StandardCharsets.UTF_8);
                        JSONObject object = new JSONObject(json);
                        if (!"SlideShow".equals(object.optString("Service"))) {
                            continue;
                        }

                        String host = response.getAddress().getHostAddress();
                        int port = object.optInt("Port", 5177);
                        ServerInfo info = new ServerInfo(object.optString("Name", "Slide Show"), host, port);
                        discovered.put(info.key(), info);
                        handler.post(this::renderDiscoveredServers);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
                List<OfflineImageStore.OfflineCatalog> cached = offlineStore.loadCatalogs();
                if (!cached.isEmpty()) {
                    handler.post(() -> showOfflineCatalogs(cached));
                    return;
                }
            } finally {
                if (socket != null) {
                    socket.close();
                }
                if (lock != null && lock.isHeld()) {
                    lock.release();
                }
            }

            handler.post(() -> {
                hideSyncProgress();
                if (discovered.size() == 1) {
                    connectTo(discovered.values().iterator().next());
                } else {
                    renderDiscoveredServers();
                }

                if (discovered.isEmpty()) {
                    List<OfflineImageStore.OfflineCatalog> cached = offlineStore.loadCatalogs();
                    if (!cached.isEmpty()) {
                        showOfflineCatalogs(cached);
                    } else {
                        discoveryTitle.setText("No Slide Show server found");
                        discoveryMessage.setText("Check that the Windows app is running and allowed through the firewall.");
                    }
                }
            });
        });
    }

    private void showSyncProgress(int completed, int total) {
        if (discoveryProgress == null) {
            return;
        }

        discoveryProgress.setVisibility(View.VISIBLE);
        if (total <= 0) {
            discoveryProgress.setIndeterminate(true);
            discoveryProgress.setMax(100);
            discoveryProgress.setProgress(0);
            return;
        }

        discoveryProgress.setIndeterminate(false);
        discoveryProgress.setMax(total);
        discoveryProgress.setProgress(Math.max(0, Math.min(completed, total)));
    }

    private void hideSyncProgress() {
        if (discoveryProgress == null) {
            return;
        }

        discoveryProgress.setIndeterminate(false);
        discoveryProgress.setProgress(0);
        discoveryProgress.setVisibility(View.GONE);
    }

    private String etaText(int completed, int total, long startedAtMs) {
        if (total <= 0 || completed <= 0 || completed >= total) {
            return "";
        }

        long elapsedMs = Math.max(1, SystemClock.elapsedRealtime() - startedAtMs);
        long remainingMs = Math.max(0, (elapsedMs * (total - completed)) / completed);
        if (remainingMs < 1500) {
            return " - ETA under 1s";
        }

        return " - ETA " + durationText(remainingMs);
    }

    private String durationText(long millis) {
        long seconds = Math.max(1, Math.round(millis / 1000.0));
        if (seconds < 60) {
            return seconds + "s";
        }

        long minutes = seconds / 60;
        long remainder = seconds % 60;
        if (minutes < 60) {
            return remainder == 0 ? minutes + "m" : minutes + "m " + remainder + "s";
        }

        long hours = minutes / 60;
        long minuteRemainder = minutes % 60;
        return minuteRemainder == 0 ? hours + "h" : hours + "h " + minuteRemainder + "m";
    }

    private String savedDateText(long syncedAt) {
        if (syncedAt <= 0) {
            return "unknown";
        }
        return new SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(new Date(syncedAt));
    }

    private String savedCatalogSummary(OfflineImageStore.OfflineCatalog catalog) {
        if (catalog.hasPin() && !isCatalogUnlocked(catalog)) {
            return "Protected saved slideshow";
        }

        String status = catalog.images.isEmpty() ? "Saved offline entry" : "Saved for offline playback";
        String fileCount = catalog.images.size() == 1 ? "1 image" : catalog.images.size() + " images";
        String details = status + " - " + fileCount + " - " + sizeText(catalog.sizeBytes);
        return catalog.hasPin() ? details + " - Protected" : details;
    }

    private String sizeText(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }

        double value = bytes / 1024.0;
        if (value < 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", value);
        }

        value = value / 1024.0;
        if (value < 1024) {
            return String.format(Locale.getDefault(), "%.1f MB", value);
        }

        return String.format(Locale.getDefault(), "%.1f GB", value / 1024.0);
    }

    private List<InetAddress> broadcastAddresses(WifiManager wifi) {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            addresses.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }

        try {
            if (wifi != null) {
                DhcpInfo dhcp = wifi.getDhcpInfo();
                int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
                byte[] bytes = new byte[] {
                    (byte) (broadcast & 0xff),
                    (byte) ((broadcast >> 8) & 0xff),
                    (byte) ((broadcast >> 16) & 0xff),
                    (byte) ((broadcast >> 24) & 0xff)
                };
                addresses.add(InetAddress.getByAddress(bytes));
            }
        } catch (Exception ignored) {
        }

        return addresses;
    }

    private void renderDiscoveredServers() {
        if (discoveryList == null) {
            return;
        }

        discoveryList.removeAllViews();
        int count = discovered.size();
        if (count == 1) {
            discoveryTitle.setText("Slide Show found");
        } else if (count > 1) {
            discoveryTitle.setText("Choose a Slide Show server");
        }

        for (ServerInfo info : discovered.values()) {
            LinearLayout row = card();
            TextView name = text(info.name, 19, INK, true);
            row.addView(name);
            TextView address = text(info.host + ":" + info.port, 14, MUTED, false);
            address.setPadding(0, dp(4), 0, dp(12));
            row.addView(address);
            Button connect = button("Connect to this PC", true);
            connect.setOnClickListener(v -> connectTo(info));
            row.addView(connect, fullButtonParams());
            discoveryList.addView(row, cardParams());
        }
    }

    private void showOfflineCatalogs(List<OfflineImageStore.OfflineCatalog> catalogs) {
        discoveryTitle.setText("Choose an offline slideshow");
        discoveryMessage.setText("No Slide Show server was found. Pick a saved slideshow to play on this device.");
        renderOfflineOptions(catalogs);
    }

    private void showOfflineCatalogLaunchScreen() {
        showDiscoveryScreen("Choose an offline slideshow", "No Slide Show server was found. Pick a saved slideshow to play on this device.");
        List<OfflineImageStore.OfflineCatalog> catalogs = offlineStore.loadCatalogs();
        if (catalogs.isEmpty()) {
            discoveryTitle.setText("No saved slideshows");
            discoveryMessage.setText("Connect to Slide Show to save a slideshow for offline use.");
            return;
        }

        showOfflineCatalogs(catalogs);
    }

    private void showServerLaunchScreen() {
        showDiscoveryScreen("Slide Show", "Choose a PC to start the slideshow again.");
        if (discovered.isEmpty()) {
            discoveryMessage.setText("Search again to find a PC or enter its address manually.");
            return;
        }

        renderDiscoveredServers();
    }

    private void renderOfflineOptions(List<OfflineImageStore.OfflineCatalog> catalogs) {
        if (discoveryList == null) {
            return;
        }

        discoveryList.removeAllViews();
        for (OfflineImageStore.OfflineCatalog catalog : catalogs) {
            discoveryList.addView(offlineCatalogCard(catalog, true), cardParams());
        }
    }

    private LinearLayout offlineCatalogCard(OfflineImageStore.OfflineCatalog catalog, boolean allowDelete) {
        LinearLayout row = card();
        TextView name = text(cleanFolderName(catalog.displayName), 19, INK, true);
        row.addView(name);
        TextView details = text(savedCatalogSummary(catalog), 14, MUTED, false);
        details.setPadding(0, dp(4), 0, dp(4));
        row.addView(details);
        TextView saved = text("Saved " + savedDateText(catalog.syncedAt), 14, MUTED, false);
        saved.setPadding(0, 0, 0, dp(12));
        row.addView(saved);

        if (canTryOpenOffline(catalog)) {
            Button open = button("Play offline", true);
            open.setOnClickListener(v -> openOfflineCatalog(catalog));
            row.addView(open, fullButtonParams());
        }

        if (allowDelete) {
            Button delete = button("Delete saved slideshow", false);
            delete.setOnClickListener(v -> ensureCatalogUnlocked(catalog, () -> confirmDeleteCatalog(reloadCatalog(catalog), false)));
            row.addView(delete, fullButtonParams());
        }
        return row;
    }

    private void showManualAddressDialog() {
        EditText input = new EditText(this);
        input.setHint("192.168.1.142:5177");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setPadding(dp(16), dp(10), dp(16), dp(10));

        new AlertDialog.Builder(this)
            .setTitle("Enter server address")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Connect", (dialog, which) -> {
                String value = input.getText().toString().trim();
                if (value.isEmpty()) {
                    return;
                }
                if (!value.startsWith("http://") && !value.startsWith("https://")) {
                    value = "http://" + value;
                }

                try {
                    URL url = new URL(value);
                    int port = url.getPort() > 0 ? url.getPort() : 5177;
                    connectTo(new ServerInfo("Slide Show", url.getHost(), port));
                } catch (Exception ignored) {
                    showDiscoveryScreen("Couldn't connect to Slide Show", "Check the address and try again.");
                }
            })
            .show();
    }

    private void connectTo(ServerInfo info) {
        connectedServer = info;
        showDiscoveryScreen("Connecting to " + info.name, "Opening the slideshow from this PC.");
        hideSyncProgress();

        executor.execute(() -> {
            try {
                OfflineSource source = getOfflineSource(info);
                JSONObject state = source.state;
                JSONArray imageList = source.imageList;
                if (imageList.length() == 0) {
                    OfflineImageStore.OfflineCatalog cached = cachedFallbackFor(info, state);
                    if (cached != null) {
                        handler.post(() -> openOfflineCatalog(cached, () -> showOfflineCatalogs(offlineStore.loadCatalogs())));
                    } else {
                        handler.post(() -> showServerEmptyState(state));
                    }
                    return;
                }
                int syncWorkers = Math.max(2, Math.min(4, state.optInt("syncWorkers", 4)));
                int slotId = offlineStore.chooseSlotForSync(info.key(), state);
                if (slotId == 0) {
                    handler.post(() -> showReplacementPicker(info, state, imageList, syncWorkers, false));
                } else {
                    syncToSlot(info, state, imageList, syncWorkers, slotId, false);
                }
            } catch (Exception ignored) {
                OfflineImageStore.OfflineCatalog cached = offlineStore.loadLastPlayableCatalog();
                if (cached != null) {
                    handler.post(() -> openOfflineCatalog(cached, () -> showOfflineCatalogs(offlineStore.loadCatalogs())));
                    return;
                }
                handler.post(() -> showDiscoveryScreen("Couldn't connect to Slide Show", "Check that the Windows app is running and allowed through the firewall."));
            }
        });
    }

    private void syncToSlot(ServerInfo info, JSONObject state, JSONArray imageList, int syncWorkers, int slotId) {
        syncToSlot(info, state, imageList, syncWorkers, slotId, false);
    }

    private void syncToSlot(ServerInfo info, JSONObject state, JSONArray imageList, int syncWorkers, int slotId, boolean keepCurrentOnFailure) {
        if (syncInProgress) {
            return;
        }
        syncInProgress = true;
        executor.execute(() -> {
            try {
                if (imageList.length() == 0) {
                    handler.post(() -> {
                        if (keepCurrentOnFailure && showingSlideshow) {
                            restoreActiveHeader();
                            showTapFeedback("Using saved copy", Gravity.CENTER);
                        } else {
                            OfflineImageStore.OfflineCatalog cached = cachedFallbackFor(info, state);
                            if (cached != null) {
                                openOfflineCatalog(cached, () -> showOfflineCatalogs(offlineStore.loadCatalogs()));
                            } else {
                                showServerEmptyState(state);
                            }
                        }
                    });
                    return;
                }

                long syncUiStartedAt = SystemClock.elapsedRealtime();
                handler.post(() -> {
                    showSyncStatus(state, 0, imageList.length(), syncUiStartedAt);
                });
                OfflineImageStore.OfflineCatalog catalog = offlineStore.sync(
                    slotId,
                    info.key(),
                    info.name,
                    info.baseUrl(),
                    state,
                    imageList,
                    syncWorkers,
                    (completed, total, name) -> handler.post(() -> {
                        showSyncStatus(state, completed, total, syncUiStartedAt);
                    }));
                handler.post(() -> openSyncedCatalog(info, catalog));
            } catch (Exception ignored) {
                if (keepCurrentOnFailure) {
                    handler.post(() -> {
                        restoreActiveHeader();
                        showTapFeedback("Sync failed", Gravity.CENTER);
                    });
                    return;
                }
                OfflineImageStore.OfflineCatalog cached = offlineStore.loadLastPlayableCatalog();
                if (cached != null) {
                    handler.post(() -> openOfflineCatalog(cached, () -> showOfflineCatalogs(offlineStore.loadCatalogs())));
                    return;
                }
                handler.post(() -> showDiscoveryScreen("Couldn't connect to Slide Show", "Check that the Windows app is running and allowed through the firewall."));
            } finally {
                syncInProgress = false;
                handler.post(() -> {
                    if (pendingServerFolderCheck) {
                        pendingServerFolderCheck = false;
                        checkServerFolderForChanges();
                    }
                });
            }
        });
    }

    private void showSyncStatus(JSONObject state, int completed, int total, long startedAtMs) {
        String nextFolder = cleanFolderName(state.optString("folderName", "Slide Show"));
        if (showingSlideshow && folderTitle != null && positionText != null) {
            folderTitle.setText("Saving " + nextFolder);
            positionText.setText(total <= 0 ? "" : completed + " / " + total);
            if (completed == 0) {
                showTapFeedback("Syncing", Gravity.CENTER);
            }
            return;
        }

        if (discoveryTitle != null && discoveryMessage != null) {
            discoveryTitle.setText("Saving images");
            discoveryMessage.setText(completed <= 0
                ? "Downloading the slideshow for offline use."
                : "Saved " + completed + " of " + total + " images" + etaText(completed, total, startedAtMs));
            showSyncProgress(completed, total);
        }
    }

    private OfflineImageStore.OfflineCatalog cachedFallbackFor(ServerInfo info, JSONObject state) {
        OfflineImageStore.OfflineCatalog matching = offlineStore.loadPlayableCatalogFor(info.key(), state);
        return matching != null ? matching : offlineStore.loadLastPlayableCatalog();
    }

    private OfflineSource getOfflineSource(ServerInfo info) throws Exception {
        try {
            JSONObject source = getJson(info.baseUrl() + "/api/offline-source");
            JSONObject state = source.optJSONObject("state");
            JSONArray imageList = source.optJSONArray("images");
            if (state != null && imageList != null) {
                return new OfflineSource(state, imageList);
            }
        } catch (Exception ignored) {
        }

        return new OfflineSource(
            getJson(info.baseUrl() + "/api/state"),
            getJsonArray(info.baseUrl() + "/api/images?shuffle=false"));
    }

    private ServerInfo serverInfoForCatalog(OfflineImageStore.OfflineCatalog catalog) {
        if (catalog == null || catalog.serverKey == null || catalog.serverKey.trim().isEmpty()) {
            return null;
        }

        int separator = catalog.serverKey.lastIndexOf(':');
        if (separator <= 0 || separator >= catalog.serverKey.length() - 1) {
            return null;
        }

        try {
            String host = catalog.serverKey.substring(0, separator);
            int port = Integer.parseInt(catalog.serverKey.substring(separator + 1));
            return new ServerInfo(catalog.serverName, host, port);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void showServerEmptyState(JSONObject state) {
        List<OfflineImageStore.OfflineCatalog> catalogs = offlineStore.loadCatalogs();
        String message = cleanString(state.optString("scanMessage", ""), "The selected folder does not have any images.");
        if (!catalogs.isEmpty()) {
            message += hasPlayableCatalog(catalogs) ? " Pick a saved slideshow below." : " Saved entries are listed below.";
        }

        showDiscoveryScreen("No images to show", message);
        if (!catalogs.isEmpty()) {
            renderOfflineOptions(catalogs);
        }
    }

    private void restoreActiveHeader() {
        if (activeCatalog == null || folderTitle == null || positionText == null) {
            return;
        }

        folderName = cleanFolderName(activeCatalog.displayName);
        folderTitle.setText(activeOffline ? folderName + " - Offline" : folderName);
        positionText.setText(images.isEmpty() ? "0 / 0" : (currentIndex + 1) + " / " + images.size());
    }

    private void showReplacementPicker(ServerInfo info, JSONObject state, JSONArray imageList, int syncWorkers, boolean keepCurrentOnFailure) {
        if (replacementPromptShowing) {
            return;
        }
        replacementPromptShowing = true;
        List<OfflineImageStore.OfflineCatalog> catalogs = offlineStore.loadCatalogs();
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(8), dp(12), dp(8));
        scroll.addView(list);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Replace a saved slideshow")
            .setNegativeButton("Cancel", null)
            .setView(scroll)
            .create();
        dialog.setOnDismissListener(d -> replacementPromptShowing = false);

        for (OfflineImageStore.OfflineCatalog catalog : catalogs) {
            if (catalog.images.isEmpty()) {
                continue;
            }

            LinearLayout row = card();
            TextView name = text(cleanFolderName(catalog.displayName), 18, INK, true);
            row.addView(name);
            TextView details = text(savedCatalogSummary(catalog), 14, MUTED, false);
            details.setPadding(0, dp(4), 0, dp(4));
            row.addView(details);
            TextView saved = text("Saved " + savedDateText(catalog.syncedAt), 14, MUTED, false);
            saved.setPadding(0, 0, 0, dp(10));
            row.addView(saved);
            Button replace = button("Replace this slideshow", true);
            replace.setOnClickListener(v -> {
                ensureCatalogUnlocked(catalog, () -> {
                    replacementPromptShowing = false;
                    dialog.dismiss();
                    syncToSlot(info, state, imageList, syncWorkers, catalog.slotId, keepCurrentOnFailure);
                });
            });
            row.addView(replace, fullButtonParams());
            list.addView(row, cardParams());
        }

        dialog.show();
    }

    private void openOfflineCatalog(OfflineImageStore.OfflineCatalog catalog) {
        openOfflineCatalog(catalog, () -> { });
    }

    private void openOfflineCatalog(OfflineImageStore.OfflineCatalog catalog, Runnable onCanceled) {
        openOfflineCatalog(catalog, onCanceled, () -> { });
    }

    private void openOfflineCatalog(OfflineImageStore.OfflineCatalog catalog, Runnable onCanceled, Runnable beforeOpen) {
        ensureCatalogUnlocked(catalog, () -> {
            OfflineImageStore.OfflineCatalog current = reloadCatalog(catalog);
            if (current.images.isEmpty()) {
                showSavedSlideshowUnavailable(current);
                return;
            }

            refreshOfflineCatalogBeforeOpen(current, beforeOpen);
        }, onCanceled);
    }

    private void refreshOfflineCatalogBeforeOpen(OfflineImageStore.OfflineCatalog catalog, Runnable beforeOpen) {
        ServerInfo info = serverInfoForCatalog(catalog);
        if (info == null || syncInProgress) {
            openUnlockedOfflineCatalog(catalog, beforeOpen);
            return;
        }

        syncInProgress = true;
        executor.execute(() -> {
            OfflineImageStore.OfflineCatalog current = catalog;
            try {
                OfflineSource source = getOfflineSource(info);
                if (source.imageList.length() > 0 && offlineStore.chooseSlotForSync(info.key(), source.state) == catalog.slotId) {
                    OfflineImageStore.OfflineCatalog latest = reloadCatalog(catalog);
                    if (offlineStore.catalogMatchesSource(latest, info.key(), source.imageList) && latest.usesCurrentImageFormat()) {
                        if (!offlineStore.catalogMatchesMetadata(latest, info.key(), source.state)) {
                            OfflineImageStore.OfflineCatalog updated = offlineStore.updateCatalogMetadata(latest.slotId, info.key(), info.name, source.state);
                            if (updated != null) {
                                current = updated;
                            }
                        } else {
                            current = latest;
                        }
                    } else {
                        int syncWorkers = Math.max(2, Math.min(4, source.state.optInt("syncWorkers", 4)));
                        long syncUiStartedAt = SystemClock.elapsedRealtime();
                        handler.post(() -> showSyncStatus(source.state, 0, source.imageList.length(), syncUiStartedAt));
                        current = offlineStore.sync(
                            latest.slotId,
                            info.key(),
                            info.name,
                            info.baseUrl(),
                            source.state,
                            source.imageList,
                            syncWorkers,
                            (completed, total, name) -> handler.post(() -> showSyncStatus(source.state, completed, total, syncUiStartedAt)));
                    }
                }
            } catch (Exception ignored) {
            } finally {
                syncInProgress = false;
            }

            OfflineImageStore.OfflineCatalog catalogToOpen = current;
            handler.post(() -> openUnlockedOfflineCatalog(catalogToOpen, beforeOpen));
        });
    }

    private void openUnlockedOfflineCatalog(OfflineImageStore.OfflineCatalog catalog, Runnable beforeOpen) {
        OfflineImageStore.OfflineCatalog current = reloadCatalog(catalog);
        if (current.images.isEmpty()) {
            showSavedSlideshowUnavailable(current);
            return;
        }

        beforeOpen.run();
        showSlideshow(null, current, true, this::showOfflineCatalogLaunchScreen);
    }

    private void showSavedSlideshowUnavailable(OfflineImageStore.OfflineCatalog catalog) {
        new AlertDialog.Builder(this)
            .setTitle("Saved slideshow unavailable")
            .setMessage("The PIN was accepted, but no saved media for " + cleanFolderName(catalog.displayName) + " could be found on this device.")
            .setPositiveButton("OK", null)
            .show();
    }

    private void openSyncedCatalog(ServerInfo info, OfflineImageStore.OfflineCatalog catalog) {
        ensureCatalogUnlocked(catalog, () -> showSlideshow(info, reloadCatalog(catalog), false, this::showServerLaunchScreen), this::leaveProtectedSlideshow);
    }

    private OfflineImageStore.OfflineCatalog reloadCatalog(OfflineImageStore.OfflineCatalog catalog) {
        OfflineImageStore.OfflineCatalog reloaded = offlineStore.loadCatalog(catalog.slotId);
        return reloaded == null ? catalog : reloaded;
    }

    private boolean isCatalogUnlocked(OfflineImageStore.OfflineCatalog catalog) {
        return catalog == null || !catalog.hasPin() || unlockedCatalogSlots.contains(catalog.slotId);
    }

    private void ensureCatalogUnlocked(OfflineImageStore.OfflineCatalog catalog, Runnable onUnlocked) {
        ensureCatalogUnlocked(catalog, onUnlocked, () -> { });
    }

    private void ensureCatalogUnlocked(OfflineImageStore.OfflineCatalog catalog, Runnable onUnlocked, Runnable onCanceled) {
        OfflineImageStore.OfflineCatalog current = reloadCatalog(catalog);
        if (isCatalogUnlocked(current)) {
            onUnlocked.run();
            return;
        }

        showPinUnlockDialog(current, onUnlocked, onCanceled);
    }

    private void showPinUnlockDialog(OfflineImageStore.OfflineCatalog catalog, Runnable onUnlocked) {
        showPinUnlockDialog(catalog, onUnlocked, () -> { });
    }

    private void showPinUnlockDialog(OfflineImageStore.OfflineCatalog catalog, Runnable onUnlocked, Runnable onCanceled) {
        EditText input = pinInput();
        input.setHint("PIN");
        boolean[] unlocked = new boolean[] { false };
        boolean[] canceled = new boolean[] { false };

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Protected slideshow")
            .setMessage("Enter the PIN for " + cleanFolderName(catalog.displayName) + ".")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Unlock", null)
            .create();

        dialog.setOnShowListener(d -> {
            Button unlock = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            focusPinInput(dialog, input);
            unlock.setOnClickListener(v -> {
                OfflineImageStore.OfflineCatalog current = reloadCatalog(catalog);
                if (offlineStore.verifyCatalogPin(current, input.getText().toString())) {
                    unlocked[0] = true;
                    unlockedCatalogSlots.add(current.slotId);
                    dialog.dismiss();
                    onUnlocked.run();
                } else {
                    input.setError("Wrong PIN");
                    input.selectAll();
                }
            });
        });
        dialog.setOnCancelListener(d -> {
            if (!unlocked[0] && !canceled[0]) {
                canceled[0] = true;
                onCanceled.run();
            }
        });
        dialog.setOnDismissListener(d -> {
            if (!unlocked[0] && !canceled[0]) {
                canceled[0] = true;
                onCanceled.run();
            }
        });
        dialog.show();
    }

    private void showSetPinDialog(OfflineImageStore.OfflineCatalog catalog) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(4), 0, dp(4), 0);

        EditText pin = pinInput();
        pin.setHint("PIN");
        form.addView(pin);

        EditText confirm = pinInput();
        confirm.setHint("Confirm PIN");
        form.addView(confirm);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(catalog.hasPin() ? "Change PIN" : "Set PIN")
            .setMessage("Use at least 4 digits.")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create();

        dialog.setOnShowListener(d -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            focusPinInput(dialog, pin);
            save.setOnClickListener(v -> {
                String nextPin = pin.getText().toString().trim();
                if (nextPin.length() < 4) {
                    pin.setError("Use at least 4 digits");
                    return;
                }
                if (!nextPin.equals(confirm.getText().toString().trim())) {
                    confirm.setError("PINs do not match");
                    return;
                }

                try {
                    offlineStore.setCatalogPin(catalog.slotId, nextPin);
                    unlockedCatalogSlots.add(catalog.slotId);
                    activeCatalog = activeCatalog != null && activeCatalog.slotId == catalog.slotId
                        ? reloadCatalog(catalog)
                        : activeCatalog;
                    dialog.dismiss();
                    if (settingsPanel != null) {
                        buildSettingsPanel();
                    }
                    if (discoveryList != null) {
                        renderOfflineOptions(offlineStore.loadCatalogs());
                    }
                } catch (Exception ignored) {
                    pin.setError("Could not save PIN");
                }
            });
        });
        dialog.show();
    }

    private void clearCatalogPin(OfflineImageStore.OfflineCatalog catalog) {
        try {
            offlineStore.clearCatalogPin(catalog.slotId);
            unlockedCatalogSlots.remove(catalog.slotId);
            activeCatalog = activeCatalog != null && activeCatalog.slotId == catalog.slotId
                ? reloadCatalog(catalog)
                : activeCatalog;
            if (settingsPanel != null) {
                buildSettingsPanel();
            }
            if (discoveryList != null) {
                renderOfflineOptions(offlineStore.loadCatalogs());
            }
        } catch (Exception ignored) {
        }
    }

    private void focusPinInput(AlertDialog dialog, EditText input) {
        input.requestFocus();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        input.post(() -> {
            InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) {
                keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private EditText pinInput() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setPadding(dp(16), dp(10), dp(16), dp(10));
        return input;
    }

    private void showSlideshow(ServerInfo info, OfflineImageStore.OfflineCatalog catalog, boolean offline, Runnable backAction) {
        handler.removeCallbacks(advanceRunnable);
        imageLoadToken++;
        synchronized (preloadInFlight) {
            preloadInFlight.clear();
        }
        clearBitmapCache();

        connectedServer = info;
        activeCatalog = catalog;
        activeOffline = offline;
        showingSlideshow = true;
        slideshowBackAction = backAction;
        images.clear();
        images.addAll(catalog.images);
        Collections.shuffle(images);

        folderName = cleanFolderName(catalog.displayName);
        slideSeconds = catalog.slideSeconds;
        imageMode = normalizeImageMode(catalog.imageMode);
        currentIndex = 0;
        playing = true;

        root = new FrameLayout(this);
        root.setBackgroundColor(parseColor(catalog.backgroundColor));

        slideImage = new ImageView(this);
        slideImage.setBackgroundColor(Color.TRANSPARENT);
        slideImage.setAdjustViewBounds(false);
        applyImageMode();
        root.addView(slideImage, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(24), dp(24), dp(24), dp(24));
        emptyTitle = text("Choose an image folder", 28, Color.WHITE, true);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyMessage = text("Open the control center on your PC to pick a folder.", 16, Color.argb(190, 255, 255, 255), false);
        emptyMessage.setGravity(Gravity.CENTER);
        emptyMessage.setPadding(0, dp(8), 0, 0);
        empty.addView(emptyTitle);
        empty.addView(emptyMessage);
        root.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        chrome = new LinearLayout(this);
        chrome.setOrientation(LinearLayout.VERTICAL);
        chrome.setPadding(dp(14), dp(26), dp(14), dp(14));
        root.addView(chrome, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout folderPill = pillRow();
        folderTitle = text(offline ? folderName + " - Offline" : folderName, 16, Color.WHITE, true);
        folderTitle.setSingleLine(true);
        folderTitle.setEllipsize(TextUtils.TruncateAt.END);
        folderPill.addView(folderTitle);

        LinearLayout counterPill = pillRow();
        positionText = text("0 / 0", 15, Color.argb(190, 255, 255, 255), false);
        counterPill.addView(positionText);

        LinearLayout.LayoutParams folderParams = new LinearLayout.LayoutParams(0, -2, 1);
        folderParams.setMargins(0, 0, dp(12), 0);
        top.addView(folderPill, folderParams);
        top.addView(counterPill, new LinearLayout.LayoutParams(-2, -2));
        chrome.addView(top, new LinearLayout.LayoutParams(-1, -2));

        View spacer = new View(this);
        chrome.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

        settingsButton = overlayButton("Settings");
        settingsButton.setOnClickListener(v -> toggleSettingsPanel());
        root.addView(settingsButton, settingsButtonOverlayParams());

        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setPadding(dp(18), dp(16), dp(18), dp(18));
        settingsPanel.setBackgroundColor(Color.argb(235, 9, 13, 16));
        settingsPanel.setClickable(true);
        settingsPanel.setVisibility(View.GONE);
        root.addView(settingsPanel, settingsPanelParams());
        buildSettingsPanel();

        feedbackText = text("", 44, Color.WHITE, true);
        feedbackText.setGravity(Gravity.CENTER);
        feedbackText.setMinWidth(dp(118));
        feedbackText.setMinHeight(dp(86));
        feedbackText.setPadding(dp(22), dp(12), dp(22), dp(14));
        feedbackText.setBackgroundColor(Color.argb(170, 0, 0, 0));
        feedbackText.setVisibility(View.GONE);
        root.addView(feedbackText, feedbackParams(Gravity.CENTER));

        setContentView(root);
        if (connectedServer != null) {
            startViewerHeartbeat();
            startServerFolderWatch();
        } else {
            stopViewerHeartbeat();
            stopServerFolderWatch();
        }
        renderCurrentSlide();
        showChromeTemporarily();
    }

    private void returnToSlideshowLauncher() {
        Runnable backAction = slideshowBackAction;
        slideshowBackAction = null;

        handler.removeCallbacks(advanceRunnable);
        handler.removeCallbacks(hideChromeRunnable);
        handler.removeCallbacks(hideFeedbackRunnable);
        imageLoadToken++;
        slideshowTapCandidate = false;
        protectedSlideshowPaused = false;
        showingSlideshow = false;
        stopViewerHeartbeat();
        stopServerFolderWatch();
        recycleCurrentBitmap();
        activeCatalog = null;
        activeOffline = false;
        connectedServer = null;

        if (backAction != null) {
            backAction.run();
        } else {
            showServerLaunchScreen();
        }
    }

    private void buildSettingsPanel() {
        settingsPanel.removeAllViews();
        TextView title = text("Playback", 20, Color.WHITE, true);
        settingsPanel.addView(title);

        timerText = text("", 16, Color.argb(220, 255, 255, 255), false);
        timerText.setPadding(0, dp(14), 0, dp(8));
        settingsPanel.addView(timerText);

        LinearLayout timerControls = new LinearLayout(this);
        timerControls.setOrientation(LinearLayout.HORIZONTAL);
        settingsPanel.addView(timerControls);

        Button slower = overlayButton("- 1 sec");
        slower.setOnClickListener(v -> updateTimer(slideSeconds - 1));
        timerControls.addView(slower, controlParams());

        Button faster = overlayButton("+ 1 sec");
        faster.setOnClickListener(v -> updateTimer(slideSeconds + 1));
        timerControls.addView(faster, controlParams());

        TextView imageSize = text("Image size", 16, Color.argb(220, 255, 255, 255), false);
        imageSize.setPadding(0, dp(18), 0, dp(8));
        settingsPanel.addView(imageSize);

        LinearLayout modeControls = new LinearLayout(this);
        modeControls.setOrientation(LinearLayout.HORIZONTAL);
        settingsPanel.addView(modeControls);

        fitButton = overlayButton("Fit");
        fitButton.setOnClickListener(v -> updateImageMode("fit"));
        modeControls.addView(fitButton, controlParams());

        fullButton = overlayButton("Full");
        fullButton.setOnClickListener(v -> updateImageMode("full"));
        modeControls.addView(fullButton, controlParams());

        TextView offlineTitle = text("Offline slideshows", 16, Color.argb(220, 255, 255, 255), false);
        offlineTitle.setPadding(0, dp(18), 0, dp(8));
        settingsPanel.addView(offlineTitle);

        TextView offlineDetails = text(activeCatalog == null ? "No saved slideshow selected" : cleanFolderName(activeCatalog.displayName) + " - " + savedCatalogSummary(activeCatalog), 14, Color.argb(190, 255, 255, 255), false);
        offlineDetails.setPadding(0, 0, 0, dp(8));
        settingsPanel.addView(offlineDetails);

        List<OfflineImageStore.OfflineCatalog> catalogs = offlineStore.loadCatalogs();
        if (catalogs.size() > 1) {
            Button switchOffline = overlayButton("Switch offline slideshow");
            switchOffline.setOnClickListener(v -> showOfflineSwitchDialog());
            settingsPanel.addView(switchOffline, fullButtonParams());
        }

        if (activeCatalog != null) {
            TextView protectionTitle = text("Protection", 16, Color.argb(220, 255, 255, 255), false);
            protectionTitle.setPadding(0, dp(18), 0, dp(8));
            settingsPanel.addView(protectionTitle);

            Button pin = overlayButton(activeCatalog.hasPin() ? "Change PIN" : "Set PIN");
            pin.setOnClickListener(v -> {
                OfflineImageStore.OfflineCatalog catalog = activeCatalog;
                if (catalog == null) {
                    return;
                }
                ensureCatalogUnlocked(catalog, () -> showSetPinDialog(reloadCatalog(catalog)));
            });
            settingsPanel.addView(pin, fullButtonParams());

            if (activeCatalog.hasPin()) {
                Button removePin = overlayButton("Remove PIN");
                removePin.setOnClickListener(v -> {
                    OfflineImageStore.OfflineCatalog catalog = activeCatalog;
                    if (catalog != null) {
                        ensureCatalogUnlocked(catalog, () -> clearCatalogPin(reloadCatalog(catalog)));
                    }
                });
                settingsPanel.addView(removePin, fullButtonParams());

                Button lockNow = overlayButton("Lock now");
                lockNow.setOnClickListener(v -> {
                    OfflineImageStore.OfflineCatalog catalog = activeCatalog;
                    if (catalog == null) {
                        return;
                    }
                    unlockedCatalogSlots.remove(catalog.slotId);
                    protectedSlideshowPaused = true;
                    lockActiveSlideshow();
                    setSettingsPanelVisible(false);
                    showPinUnlockDialog(catalog, () -> {
                        protectedSlideshowPaused = false;
                        playing = true;
                        renderCurrentSlide();
                        scheduleNext();
                        showChromeTemporarily();
                    }, this::leaveProtectedSlideshow);
                });
                settingsPanel.addView(lockNow, fullButtonParams());
            }

            Button rename = overlayButton("Rename saved slideshow");
            rename.setOnClickListener(v -> {
                OfflineImageStore.OfflineCatalog catalog = activeCatalog;
                if (catalog != null) {
                    ensureCatalogUnlocked(catalog, () -> showRenameCatalogDialog(reloadCatalog(catalog)));
                }
            });
            settingsPanel.addView(rename, fullButtonParams());

            Button delete = overlayButton("Delete saved slideshow");
            delete.setOnClickListener(v -> {
                OfflineImageStore.OfflineCatalog catalog = activeCatalog;
                if (catalog != null) {
                    ensureCatalogUnlocked(catalog, () -> confirmDeleteCatalog(reloadCatalog(catalog), true));
                }
            });
            settingsPanel.addView(delete, fullButtonParams());
        }

        Button changeServer = overlayButton("Change server");
        changeServer.setOnClickListener(v -> {
            handler.removeCallbacks(advanceRunnable);
            showDiscoveryScreen("Looking for Slide Show on this Wi-Fi", "Make sure your phone and PC are on the same network.");
            discoverServers();
        });
        LinearLayout.LayoutParams params = fullButtonParams();
        params.setMargins(0, dp(18), 0, 0);
        settingsPanel.addView(changeServer, params);
        updateSettingsText();
        updateImageModeButtons();
    }

    private void showOfflineSwitchDialog() {
        List<OfflineImageStore.OfflineCatalog> catalogs = offlineStore.loadCatalogs();
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(8), dp(12), dp(8));
        scroll.addView(list);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Choose an offline slideshow")
            .setNegativeButton("Cancel", null)
            .setView(scroll)
            .create();

        for (OfflineImageStore.OfflineCatalog catalog : catalogs) {
            LinearLayout row = offlineCatalogCard(catalog, false);
            if (canTryOpenOffline(catalog)) {
                Button open = (Button) row.getChildAt(row.getChildCount() - 1);
                open.setOnClickListener(v -> openOfflineCatalog(catalog, () -> { }, dialog::dismiss));
            }
            list.addView(row, cardParams());
        }

        dialog.show();
    }

    private boolean canTryOpenOffline(OfflineImageStore.OfflineCatalog catalog) {
        return catalog != null && (!catalog.images.isEmpty() || catalog.hasPin());
    }

    private boolean hasPlayableCatalog(List<OfflineImageStore.OfflineCatalog> catalogs) {
        for (OfflineImageStore.OfflineCatalog catalog : catalogs) {
            if (!catalog.images.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void showRenameCatalogDialog(OfflineImageStore.OfflineCatalog catalog) {
        EditText input = new EditText(this);
        input.setText(cleanFolderName(catalog.displayName));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setPadding(dp(16), dp(10), dp(16), dp(10));

        new AlertDialog.Builder(this)
            .setTitle("Rename saved slideshow")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", (dialog, which) -> {
                String nextName = input.getText().toString().trim();
                if (nextName.isEmpty()) {
                    return;
                }

                try {
                    offlineStore.renameCatalog(catalog.slotId, nextName);
                    OfflineImageStore.OfflineCatalog renamed = offlineStore.loadCatalog(catalog.slotId);
                    if (renamed != null && activeCatalog != null && renamed.slotId == activeCatalog.slotId) {
                        activeCatalog = renamed;
                        folderName = cleanFolderName(renamed.displayName);
                        if (folderTitle != null) {
                            folderTitle.setText(activeOffline ? folderName + " - Offline" : folderName);
                        }
                        buildSettingsPanel();
                    }
                } catch (Exception ignored) {
                }
            })
            .show();
    }

    private void confirmDeleteCatalog(OfflineImageStore.OfflineCatalog catalog, boolean fromSettings) {
        new AlertDialog.Builder(this)
            .setTitle("Delete this saved slideshow?")
            .setMessage(cleanFolderName(catalog.displayName) + "\n" + savedCatalogSummary(catalog))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dialog, which) -> deleteCatalog(catalog, fromSettings))
            .show();
    }

    private void deleteCatalog(OfflineImageStore.OfflineCatalog catalog, boolean fromSettings) {
        boolean deletingActive = activeCatalog != null && activeCatalog.slotId == catalog.slotId;
        offlineStore.deleteCatalog(catalog.slotId);

        if (deletingActive) {
            handler.removeCallbacks(advanceRunnable);
            imageLoadToken++;
            recycleCurrentBitmap();
            activeCatalog = null;
            activeOffline = false;
            List<OfflineImageStore.OfflineCatalog> catalogs = offlineStore.loadCatalogs();
            showDiscoveryScreen("Looking for Slide Show on this Wi-Fi", "Make sure your phone and PC are on the same network.");
            if (catalogs.isEmpty()) {
                discoveryTitle.setText("No saved slideshows");
                discoveryMessage.setText("Connect to Slide Show to save a slideshow for offline use.");
            } else {
                showOfflineCatalogs(catalogs);
            }
            return;
        }

        if (fromSettings && settingsPanel != null) {
            buildSettingsPanel();
        }

        if (discoveryList != null) {
            List<OfflineImageStore.OfflineCatalog> catalogs = offlineStore.loadCatalogs();
            if (!catalogs.isEmpty()) {
                renderOfflineOptions(catalogs);
            } else {
                discoveryList.removeAllViews();
                discoveryTitle.setText("No saved slideshows");
                discoveryMessage.setText("Connect to Slide Show to save a slideshow for offline use.");
            }
        }
    }

    private void lockActiveSlideshow() {
        playing = false;
        handler.removeCallbacks(advanceRunnable);
        imageLoadToken++;
        clearBitmapCache();
        if (slideImage != null) {
            slideImage.setImageDrawable(null);
            slideImage.setVisibility(View.GONE);
        }
        if (emptyTitle != null) {
            emptyTitle.setText("Protected slideshow");
            emptyTitle.setVisibility(View.VISIBLE);
        }
        if (emptyMessage != null) {
            emptyMessage.setText("Enter the PIN to continue.");
            emptyMessage.setVisibility(View.VISIBLE);
        }
        if (positionText != null) {
            positionText.setText("Locked");
        }
        if (settingsPanel != null) {
            buildSettingsPanel();
            setSettingsPanelVisible(false);
        }
    }

    private void leaveProtectedSlideshow() {
        protectedSlideshowPaused = false;
        lockActiveSlideshow();
        activeCatalog = null;
        activeOffline = false;
        connectedServer = null;
        List<OfflineImageStore.OfflineCatalog> catalogs = offlineStore.loadCatalogs();
        showDiscoveryScreen("Looking for Slide Show on this Wi-Fi", "Make sure your phone and PC are on the same network.");
        if (catalogs.isEmpty()) {
            discoveryTitle.setText("No saved slideshows");
            discoveryMessage.setText("Connect to Slide Show to save a slideshow for offline use.");
        } else {
            showOfflineCatalogs(catalogs);
        }
    }

    private void renderCurrentSlide() {
        handler.removeCallbacks(advanceRunnable);
        int token = ++imageLoadToken;
        if (activeCatalog != null && activeCatalog.hasPin() && !isCatalogUnlocked(activeCatalog)) {
            slideImage.setImageDrawable(null);
            slideImage.setVisibility(View.GONE);
            emptyTitle.setText("Protected slideshow");
            emptyMessage.setText("Enter the PIN to continue.");
            emptyTitle.setVisibility(View.VISIBLE);
            emptyMessage.setVisibility(View.VISIBLE);
            positionText.setText("Locked");
            return;
        }

        if (images.isEmpty()) {
            slideImage.setVisibility(View.GONE);
            emptyTitle.setVisibility(View.VISIBLE);
            emptyMessage.setVisibility(View.VISIBLE);
            positionText.setText("0 / 0");
            return;
        }

        slideImage.setVisibility(View.VISIBLE);
        emptyTitle.setVisibility(View.GONE);
        emptyMessage.setVisibility(View.GONE);
        int index = currentIndex;
        SlideImage item = images.get(index);
        Bitmap cached = cachedBitmap(item);
        if (cached != null) {
            showDecodedSlide(index, item, cached);
            return;
        }

        int targetWidth = imageTargetWidth();
        int targetHeight = imageTargetHeight();

        imageExecutor.execute(() -> {
            if (token != imageLoadToken) {
                return;
            }

            try {
                OfflineImageStore.OfflineCatalog catalog = activeCatalog;
                if (catalog == null) {
                    return;
                }
                Bitmap bitmap = cacheBitmap(item, offlineStore.decodeBitmap(catalog, item, targetWidth, targetHeight));
                handler.post(() -> {
                    if (token == imageLoadToken && index == currentIndex) {
                        showDecodedSlide(index, item, bitmap);
                    } else {
                        trimBitmapCache();
                    }
                });
            } catch (Exception error) {
                Log.e(LOG_TAG, "Could not render slide index=" + (index + 1), error);
                handler.post(() -> {
                    if (token == imageLoadToken) {
                        showTapFeedback("Image failed", Gravity.CENTER);
                        scheduleNext();
                    }
                });
            }
        });
    }

    private void showDecodedSlide(int index, SlideImage item, Bitmap bitmap) {
        if (activeCatalog != null && activeCatalog.hasPin() && !isCatalogUnlocked(activeCatalog)) {
            return;
        }

        currentBitmap = bitmap;
        slideImage.setImageBitmap(bitmap);
        slideImage.invalidate();
        positionText.setText((index + 1) + " / " + images.size());
        scheduleNext();
        preloadNeighborSlides(index);
        trimBitmapCache();
    }

    private void preloadNeighborSlides(int index) {
        if (images.size() < 2) {
            return;
        }

        for (int offset = 1; offset <= 3; offset++) {
            preloadSlideAt(index + offset);
            preloadSlideAt(index - offset);
        }
    }

    private void preloadSlideAt(int index) {
        if (images.isEmpty()) {
            return;
        }

        if (activeCatalog != null && activeCatalog.hasPin() && !isCatalogUnlocked(activeCatalog)) {
            return;
        }

        int normalizedIndex = (index + images.size()) % images.size();
        SlideImage item = images.get(normalizedIndex);
        if (cachedBitmap(item) != null || !beginPreload(item)) {
            return;
        }

        int targetWidth = imageTargetWidth();
        int targetHeight = imageTargetHeight();
        preloadExecutor.execute(() -> {
            try {
                OfflineImageStore.OfflineCatalog catalog = activeCatalog;
                if (catalog != null && cachedBitmap(item) == null) {
                    cacheBitmap(item, offlineStore.decodeBitmap(catalog, item, targetWidth, targetHeight));
                    handler.post(this::trimBitmapCache);
                }
            } catch (Exception error) {
                Log.e(LOG_TAG, "Could not preload slide index=" + (normalizedIndex + 1), error);
            } finally {
                endPreload(item);
            }
        });
    }

    private Bitmap cachedBitmap(SlideImage item) {
        synchronized (bitmapCache) {
            Bitmap bitmap = bitmapCache.get(item.encryptedFileName);
            return bitmap == null || bitmap.isRecycled() ? null : bitmap;
        }
    }

    private Bitmap cacheBitmap(SlideImage item, Bitmap bitmap) {
        synchronized (bitmapCache) {
            Bitmap existing = bitmapCache.get(item.encryptedFileName);
            if (existing != null && !existing.isRecycled()) {
                if (existing != bitmap && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                return existing;
            }
            bitmapCache.put(item.encryptedFileName, bitmap);
            return bitmap;
        }
    }

    private boolean beginPreload(SlideImage item) {
        synchronized (preloadInFlight) {
            if (preloadInFlight.contains(item.encryptedFileName)) {
                return false;
            }

            preloadInFlight.add(item.encryptedFileName);
            return true;
        }
    }

    private void endPreload(SlideImage item) {
        synchronized (preloadInFlight) {
            preloadInFlight.remove(item.encryptedFileName);
        }
    }

    private void trimBitmapCache() {
        if (images.isEmpty()) {
            clearBitmapCache();
            return;
        }

        Set<String> keep = new HashSet<>();
        for (int offset = -3; offset <= 3; offset++) {
            SlideImage image = images.get((currentIndex + offset + images.size()) % images.size());
            keep.add(image.encryptedFileName);
        }

        synchronized (bitmapCache) {
            List<String> remove = new ArrayList<>();
            for (String key : bitmapCache.keySet()) {
                if (!keep.contains(key)) {
                    remove.add(key);
                }
            }

            for (String key : remove) {
                Bitmap bitmap = bitmapCache.remove(key);
                if (bitmap != null && bitmap != currentBitmap && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
        }
    }

    private void clearBitmapCache() {
        synchronized (bitmapCache) {
            for (Bitmap bitmap : bitmapCache.values()) {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
            bitmapCache.clear();
        }
        currentBitmap = null;
    }

    private void scheduleNext() {
        handler.removeCallbacks(advanceRunnable);
        if (playing && !images.isEmpty()) {
            handler.postDelayed(advanceRunnable, Math.max(2, slideSeconds) * 1000L);
        }
    }

    private void advance(int delta) {
        if (images.isEmpty()) {
            return;
        }
        currentIndex = (currentIndex + delta + images.size()) % images.size();
        renderCurrentSlide();
    }

    private void togglePlay() {
        playing = !playing;
        scheduleNext();
    }

    private String playbackStatusText() {
        return playing ? "Playing" : "Paused";
    }

    private void handleSlideshowTap(float x) {
        if (images.isEmpty()) {
            showChromeTemporarily();
            return;
        }

        if (settingsPanel.getVisibility() == View.VISIBLE) {
            setSettingsPanelVisible(false);
            showChromeTemporarily();
            return;
        }

        float third = root.getWidth() / 3f;
        if (x < third) {
            advance(-1);
        } else if (x > third * 2f) {
            advance(1);
        } else {
            togglePlay();
            showTapFeedback(playbackStatusText(), Gravity.CENTER);
        }

        showChromeTemporarily();
    }

    private void toggleSettingsPanel() {
        boolean shouldShow = settingsPanel.getVisibility() != View.VISIBLE;
        setSettingsPanelVisible(shouldShow);
        if (shouldShow) {
            showChrome();
        } else {
            showChromeTemporarily();
        }
    }

    private void showChrome() {
        handler.removeCallbacks(hideChromeRunnable);
        setChromeVisible(true);
    }

    private void showChromeTemporarily() {
        showChrome();
        handler.postDelayed(hideChromeRunnable, 2600);
    }

    private void setChromeVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (chrome != null) {
            chrome.setVisibility(visibility);
        }
        if (settingsButton != null) {
            boolean panelOpen = settingsPanel != null && settingsPanel.getVisibility() == View.VISIBLE;
            settingsButton.setVisibility(visible && !panelOpen ? View.VISIBLE : View.GONE);
        }
    }

    private void setSettingsPanelVisible(boolean visible) {
        if (settingsPanel != null) {
            settingsPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (settingsButton != null) {
            boolean chromeVisible = chrome != null && chrome.getVisibility() == View.VISIBLE;
            settingsButton.setVisibility(!visible && chromeVisible ? View.VISIBLE : View.GONE);
        }
    }

    private void showTapFeedback(String value, int gravity) {
        handler.removeCallbacks(hideFeedbackRunnable);
        feedbackText.setText(value);
        feedbackText.setTextSize(value.length() == 1 ? 52 : 30);
        FrameLayout.LayoutParams params = feedbackParams(gravity);
        feedbackText.setLayoutParams(params);
        feedbackText.setAlpha(1f);
        feedbackText.setVisibility(View.VISIBLE);
        handler.postDelayed(hideFeedbackRunnable, 1200);
    }

    private boolean isSlideshowTapArea(MotionEvent event) {
        if (settingsPanel != null
            && settingsPanel.getVisibility() == View.VISIBLE
            && isPointInsideView(settingsPanel, event.getRawX(), event.getRawY())) {
            return false;
        }

        return settingsButton == null
            || settingsButton.getVisibility() != View.VISIBLE
            || !isPointInsideView(settingsButton, event.getRawX(), event.getRawY());
    }

    private boolean isPointInsideView(View view, float rawX, float rawY) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return false;
        }

        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return rawX >= location[0]
            && rawX <= location[0] + view.getWidth()
            && rawY >= location[1]
            && rawY <= location[1] + view.getHeight();
    }

    private void updateTimer(int seconds) {
        slideSeconds = Math.max(2, Math.min(120, seconds));
        updateSettingsText();
        postPlaybackSettings();
        scheduleNext();
    }

    private void updateImageMode(String mode) {
        imageMode = normalizeImageMode(mode);
        applyImageMode();
        updateImageModeButtons();
        postPlaybackSettings();
    }

    private void applyImageMode() {
        if (slideImage != null) {
            slideImage.setScaleType("full".equals(imageMode) ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        }
    }

    private void updateImageModeButtons() {
        if (fitButton == null || fullButton == null) {
            return;
        }

        boolean full = "full".equals(imageMode);
        fitButton.setBackgroundColor(full ? Color.argb(120, 0, 0, 0) : ACCENT);
        fullButton.setBackgroundColor(full ? ACCENT : Color.argb(120, 0, 0, 0));
    }

    private void updateSettingsText() {
        if (timerText != null) {
            timerText.setText("Advance every " + slideSeconds + " seconds");
        }
    }

    private int imageTargetWidth() {
        if (slideImage != null && slideImage.getWidth() > 0) {
            return slideImage.getWidth();
        }

        return getResources().getDisplayMetrics().widthPixels;
    }

    private int imageTargetHeight() {
        if (slideImage != null && slideImage.getHeight() > 0) {
            return slideImage.getHeight();
        }

        return getResources().getDisplayMetrics().heightPixels;
    }

    private void postPlaybackSettings() {
        if (connectedServer == null) {
            return;
        }

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("slideSeconds", slideSeconds);
                body.put("imageMode", imageMode);
                postJson(connectedServer.baseUrl() + "/api/playback-settings", body);
            } catch (Exception ignored) {
            }
        });
    }

    private void startViewerHeartbeat() {
        if (!showingSlideshow || connectedServer == null) {
            return;
        }

        handler.removeCallbacks(viewerHeartbeatRunnable);
        viewerHeartbeatRunnable.run();
    }

    private void stopViewerHeartbeat() {
        handler.removeCallbacks(viewerHeartbeatRunnable);
        postViewerHeartbeat(false);
    }

    private void startServerFolderWatch() {
        if (!showingSlideshow || connectedServer == null) {
            return;
        }

        handler.removeCallbacks(serverFolderWatchRunnable);
        handler.post(serverFolderWatchRunnable);
    }

    private void stopServerFolderWatch() {
        handler.removeCallbacks(serverFolderWatchRunnable);
        checkingServerFolder = false;
        pendingServerFolderCheck = false;
    }

    private void scheduleServerFolderWatch() {
        handler.removeCallbacks(serverFolderWatchRunnable);
        if (showingSlideshow && connectedServer != null) {
            handler.postDelayed(serverFolderWatchRunnable, SERVER_FOLDER_WATCH_MS);
        }
    }

    private void checkServerFolderForChanges() {
        if (!showingSlideshow || connectedServer == null) {
            return;
        }

        if (checkingServerFolder || replacementPromptShowing) {
            scheduleServerFolderWatch();
            return;
        }

        if (syncInProgress) {
            pendingServerFolderCheck = true;
            scheduleServerFolderWatch();
            return;
        }

        checkingServerFolder = true;
        ServerInfo info = connectedServer;
        OfflineImageStore.OfflineCatalog catalog = activeCatalog;
        executor.execute(() -> {
            try {
                OfflineSource source = getOfflineSource(info);
                JSONObject state = source.state;
                JSONArray imageList = source.imageList;
                String nextIdentity = OfflineImageStore.folderIdentityFor(info.key(), state);
                if (catalog != null &&
                    nextIdentity.equals(catalog.folderIdentity) &&
                    catalog.usesCurrentImageFormat() &&
                    offlineStore.catalogMatchesSource(catalog, info.key(), imageList)) {
                    if (!offlineStore.catalogMatchesMetadata(catalog, info.key(), state)) {
                        OfflineImageStore.OfflineCatalog updated = offlineStore.updateCatalogMetadata(catalog.slotId, info.key(), info.name, state);
                        if (updated != null) {
                            handler.post(() -> {
                                if (activeCatalog != null && activeCatalog.slotId == updated.slotId) {
                                    activeCatalog = updated;
                                    restoreActiveHeader();
                                }
                            });
                        }
                    }
                    return;
                }

                int syncWorkers = Math.max(2, Math.min(4, state.optInt("syncWorkers", 4)));
                int slotId = offlineStore.chooseSlotForSync(info.key(), state);
                if (slotId == 0 && catalog != null && !activeOffline) {
                    slotId = catalog.slotId;
                }
                int targetSlotId = slotId;
                handler.post(() -> {
                    if (!showingSlideshow || connectedServer == null || !connectedServer.key().equals(info.key())) {
                        return;
                    }

                    if (targetSlotId == 0) {
                        showReplacementPicker(info, state, imageList, syncWorkers, true);
                    } else {
                        syncToSlot(info, state, imageList, syncWorkers, targetSlotId, true);
                    }
                });
            } catch (Exception ignored) {
            } finally {
                checkingServerFolder = false;
                handler.post(this::scheduleServerFolderWatch);
            }
        });
    }

    private void postViewerHeartbeat(boolean active) {
        if (connectedServer == null) {
            return;
        }

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("active", active);
                postJson(connectedServer.baseUrl() + "/api/viewer-heartbeat", body);
            } catch (Exception ignored) {
            }
        });
    }

    private JSONObject getJson(String address) throws Exception {
        return new JSONObject(request("GET", address, null));
    }

    private JSONArray getJsonArray(String address) throws Exception {
        return new JSONArray(request("GET", address, null));
    }

    private void postJson(String address, JSONObject body) throws Exception {
        request("POST", address, body.toString());
    }

    private String request(String method, String address, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(2500);
        connection.setReadTimeout(6000);
        connection.setUseCaches(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }

        try (InputStream stream = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            if (stream == null) {
                return "";
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        } finally {
            connection.disconnect();
        }
    }

    private void recycleCurrentBitmap() {
        if (slideImage != null) {
            slideImage.setImageDrawable(null);
        }

        clearBitmapCache();
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(true);
        if (bold) {
            view.setTypeface(view.getTypeface(), 1);
        }
        return view;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(primary ? Color.WHITE : INK);
        button.setBackgroundColor(primary ? ACCENT : Color.WHITE);
        button.setMinHeight(dp(48));
        return button;
    }

    private Button overlayButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.argb(120, 0, 0, 0));
        button.setMinHeight(dp(48));
        return button;
    }

    private LinearLayout card() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(16), dp(16), dp(16));
        row.setBackgroundColor(Color.WHITE);
        return row;
    }

    private LinearLayout pillRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setBackgroundColor(Color.argb(120, 0, 0, 0));
        return row;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(16), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams fullButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50));
        params.setMargins(0, dp(8), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams controlParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private LinearLayout.LayoutParams settingsButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(180), dp(52));
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private FrameLayout.LayoutParams settingsButtonOverlayParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(180), dp(52));
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.setMargins(0, 0, 0, dp(28));
        return params;
    }

    private FrameLayout.LayoutParams feedbackParams(int gravity) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2);
        params.gravity = gravity;
        params.setMargins(dp(38), 0, dp(38), 0);
        return params;
    }

    private FrameLayout.LayoutParams settingsPanelParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2);
        params.gravity = Gravity.BOTTOM;
        return params;
    }

    private int parseColor(String value) {
        try {
            return Color.parseColor(value);
        } catch (Exception ignored) {
            return Color.rgb(5, 7, 10);
        }
    }

    private String cleanFolderName(String value) {
        if (value == null || value.isEmpty() || "null".equals(value)) {
            return "Slide Show";
        }

        return value;
    }

    private String cleanString(String value, String fallback) {
        return value == null || value.trim().isEmpty() || "null".equals(value) ? fallback : value.trim();
    }

    private String normalizeImageMode(String value) {
        return "full".equalsIgnoreCase(value) ? "full" : "fit";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ServerInfo {
        final String name;
        final String host;
        final int port;

        ServerInfo(String name, String host, int port) {
            this.name = name == null || name.isEmpty() ? "Slide Show" : name;
            this.host = host;
            this.port = port;
        }

        String key() {
            return host + ":" + port;
        }

        String baseUrl() {
            return "http://" + host + ":" + port;
        }
    }

    private static final class OfflineSource {
        final JSONObject state;
        final JSONArray imageList;

        OfflineSource(JSONObject state, JSONArray imageList) {
            this.state = state;
            this.imageList = imageList == null ? new JSONArray() : imageList;
        }
    }

    static final class SlideImage {
        final String name;
        final String path;
        final String encryptedFileName;

        SlideImage(String name, String path, String encryptedFileName) {
            this.name = name;
            this.path = path;
            this.encryptedFileName = encryptedFileName;
        }
    }
}
