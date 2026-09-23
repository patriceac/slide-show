package com.local.slideshow;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
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
    private static final int DISCOVERY_PORT = 5179;
    private static final String DISCOVERY_PROBE = "SLIDE_SHOW_DISCOVER_V1";
    private static final String LOG_TAG = "SlideShowAndroid";
    private static final long SERVER_FOLDER_WATCH_MS = 5000;
    private int INK, MUTED, ACCENT, SURFACE, LINE, OVERLAY, CARD, PRIMARY_TEXT;
    private String themePreference = "system";
    private boolean darkTheme;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService preloadExecutor = Executors.newFixedThreadPool(2);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, ServerInfo> discovered = new LinkedHashMap<>();
    private final Map<String, ServerInfo> availableCollections = new LinkedHashMap<>();
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
    private Button searchAgainButton;
    private boolean discoveryInProgress;
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
    private ScrollView settingsScroll;
    private LinearLayout playbackControls;
    private Button libraryButton;
    private Button pauseButton;
    private TextView connectionNotice;
    private String playbackOrder = "shuffle";
    private int connectionGeneration;
    private final String viewerId = java.util.UUID.randomUUID().toString();
    private final PlaybackSequence.Photo<SlideImage> photoReader = new PlaybackSequence.Photo<SlideImage>() {
        public String key(SlideImage photo) { return photo.encryptedFileName; }
        public String name(SlideImage photo) { return photo.name; }
        public long date(SlideImage photo) { return photo.modifiedAt; }
    };

    private ServerInfo connectedServer;
    private String folderName = "Slide Show";
    private String imageMode = "fit";
    private int slideSeconds = 7;
    private int currentIndex = 0;
    private boolean playing = true;
    private boolean showingSlideshow = false;
    private boolean activityVisible;
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
            if (playing && (settingsPanel == null || settingsPanel.getVisibility() != View.VISIBLE)) {
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
        themePreference = ThemePreference.normalize(getSharedPreferences("appearance", MODE_PRIVATE).getString("theme", "system"));
        applyThemePalette();
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
        activityVisible = false;
        savePlayback();
        handler.removeCallbacks(advanceRunnable);
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
        activityVisible = true;
        if (showingSlideshow && !protectedSlideshowPaused) scheduleNext();
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

    private boolean useDarkTheme() {
        return ThemePreference.isDark(themePreference,
            (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES);
    }

    private void applyThemePalette() {
        darkTheme = useDarkTheme();
        setTheme(darkTheme ? R.style.AppThemeDark : R.style.AppTheme);
        INK = Color.parseColor(darkTheme ? "#e5eaf1" : "#242b35");
        MUTED = Color.parseColor(darkTheme ? "#a4afbf" : "#697586");
        ACCENT = Color.parseColor(darkTheme ? "#bfd2eb" : "#36516a");
        SURFACE = Color.parseColor(darkTheme ? "#171c24" : "#f8f8f7");
        LINE = Color.parseColor(darkTheme ? "#465268" : "#d7dbe0");
        CARD = Color.parseColor(darkTheme ? "#252e3c" : "#ffffff");
        PRIMARY_TEXT = darkTheme ? Color.rgb(25, 38, 53) : Color.WHITE;
        OVERLAY = darkTheme ? Color.argb(235, 28, 34, 44) : Color.argb(235, 250, 250, 249);
    }

    private Button themeButton(boolean overlay) {
        String label = "Theme: " + ("dark".equals(themePreference) ? "Dark" : "light".equals(themePreference) ? "Light" : "System");
        Button button = overlay ? overlayButton(label) : button(label, false);
        button.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Theme on this device")
            .setSingleChoiceItems(new String[]{"System", "Light", "Dark"},
                "light".equals(themePreference) ? 1 : "dark".equals(themePreference) ? 2 : 0,
                (dialog, which) -> {
                    themePreference = new String[]{"system", "light", "dark"}[which];
                    getSharedPreferences("appearance", MODE_PRIVATE).edit().putString("theme", themePreference).apply();
                    dialog.dismiss();
                    refreshTheme();
                }).setNegativeButton("Cancel", null).show());
        return button;
    }

    private void refreshTheme() {
        applyThemePalette();
        if (root == null) return;
        if (!showingSlideshow) {
            String title = discoveryTitle.getText().toString(), message = discoveryMessage.getText().toString();
            int progressVisibility = discoveryProgress.getVisibility();
            showDiscoveryScreen(title, message);
            if (!discovered.isEmpty()) renderDiscoveredServers();
            discoveryTitle.setText(title); discoveryMessage.setText(message);
            discoveryProgress.setVisibility(progressVisibility);
            return;
        }
        // Repaint controls in place so playback and protected photos are undisturbed.
        folderTitle.setTextColor(INK); positionText.setTextColor(MUTED);
        ((View) folderTitle.getParent()).setBackground(rounded(OVERLAY, 8, false));
        ((View) positionText.getParent()).setBackground(rounded(OVERLAY, 8, false));
        libraryButton.setTextColor(INK); libraryButton.setBackground(buttonBackground(OVERLAY, true));
        playbackControls.setBackground(rounded(OVERLAY, 14, false));
        for (int i = 0; i < playbackControls.getChildCount(); i++) ((Button) playbackControls.getChildAt(i)).setTextColor(INK);
        pauseButton.setBackground(buttonBackground(ACCENT, false)); pauseButton.setTextColor(PRIMARY_TEXT);
        settingsPanel.setBackgroundColor(SURFACE);
        settingsScroll.setBackground(rounded(SURFACE, 16, false));
        buildSettingsPanel();
        feedbackText.setTextColor(INK); feedbackText.setBackground(rounded(OVERLAY, 18, false));
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
        shell.setPadding(dp(24), dp(32), dp(24), dp(28));
        scroll.setFillViewport(true);
        scroll.addView(shell);

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView brandIcon = new ImageView(this);
        brandIcon.setImageResource(R.drawable.ic_photo);
        brandIcon.setImageTintList(ColorStateList.valueOf(MUTED));
        LinearLayout.LayoutParams brandIconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        brandIconParams.rightMargin = dp(10);
        brand.addView(brandIcon, brandIconParams);
        brand.addView(text("Slide Show", 18, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button appearance = themeButton(false);
        appearance.setText("⋮");
        appearance.setContentDescription("Theme on this device");
        appearance.setBackground(buttonBackground(Color.TRANSPARENT, false));
        appearance.setMinWidth(0); appearance.setMinimumWidth(0); appearance.setPadding(0,0,0,0);
        brand.addView(appearance, new LinearLayout.LayoutParams(dp(36), dp(36)));
        shell.addView(brand);
        TextView libraryTitle = text("Collections", 34, INK, true);
        libraryTitle.setPadding(0, dp(24), 0, dp(8));
        shell.addView(libraryTitle);

        discoveryTitle = text(title, 15, MUTED, false);
        discoveryTitle.setPadding(0, 0, 0, dp(5));
        shell.addView(discoveryTitle);

        discoveryMessage = text(message, 14, MUTED, false);
        discoveryMessage.setLineSpacing(0, 1.15f);
        shell.addView(discoveryMessage);

        discoveryProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        discoveryProgress.setIndeterminate(true);
        discoveryProgress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        discoveryProgress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        discoveryProgress.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
        discoveryProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(3));
        progressParams.setMargins(0, dp(16), 0, 0);
        shell.addView(discoveryProgress, progressParams);

        discoveryList = new LinearLayout(this);
        discoveryList.setOrientation(LinearLayout.VERTICAL);
        shell.addView(discoveryList);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dp(20), 0, 0);
        shell.addView(actions);

        searchAgainButton = button("Search again", false);
        setDiscoveryInProgress(discoveryInProgress);
        searchAgainButton.setOnClickListener(v -> discoverServers());
        actions.addView(searchAgainButton, fullButtonParams());

        Button manual = button("Enter PC address manually", false);
        manual.setBackground(buttonBackground(Color.TRANSPARENT, false));
        manual.setOnClickListener(v -> showManualAddressDialog());
        actions.addView(manual, fullButtonParams());

        root.addView(scroll);
        setContentView(root);
        renderOfflineOptions(offlineStore.loadCatalogs());
    }

    private void setDiscoveryInProgress(boolean inProgress) {
        discoveryInProgress = inProgress;
        if (searchAgainButton != null) {
            searchAgainButton.setEnabled(!inProgress);
            searchAgainButton.setAlpha(inProgress ? 0.45f : 1f);
        }
    }

    private void discoverServers() {
        if (discoveryInProgress) return;
        setDiscoveryInProgress(true);
        discovered.clear();
        availableCollections.clear();
        if (discoveryList != null) {
            renderOfflineOptions(offlineStore.loadCatalogs());
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
                Set<String> seen = new HashSet<>();
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
                        if (seen.add(info.key())) loadCollectionsFromServer(info);
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
                handler.post(() -> setDiscoveryInProgress(false));
            }

            handler.post(() -> {
                hideSyncProgress();
                if (showingSlideshow) return;
                renderDiscoveredServers();

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
            return "PIN protected";
        }

        if (catalog.images.isEmpty()) return "No photos saved";
        String fileCount = catalog.images.size() == 1 ? "1 photo" : catalog.images.size() + " photos";
        String details = fileCount + " · " + sizeText(catalog.sizeBytes);
        return catalog.hasPin() ? details + " · PIN protected" : details;
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

    private void loadCollectionsFromServer(ServerInfo pc) throws Exception {
        JSONObject listing = getJson(pc.baseUrl() + "/api/collections");
        JSONArray entries = listing.getJSONArray("collections");
        List<ServerInfo> collections = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            ServerInfo source = new ServerInfo(listing.optString("computerName", pc.name), pc.host, pc.port, pc.scheme);
            source.collectionId = entry.getString("id");
            source.collectionName = entry.optString("name", "Collection");
            source.imageCount = entry.optInt("imageCount");
            source.previewUrl = entry.optString("previewUrl", "");
            collections.add(source);
        }
        handler.post(() -> {
            discovered.put(pc.key(), pc);
            availableCollections.entrySet().removeIf(entry -> entry.getValue().key().equals(pc.key()));
            for (ServerInfo source : collections) availableCollections.put(source.key() + "/" + source.collectionId, source);
            renderDiscoveredServers();
        });
    }

    private void renderDiscoveredServers() {
        if (showingSlideshow || discoveryList == null) return;
        discoveryList.removeAllViews();
        List<OfflineImageStore.OfflineCatalog> saved = offlineStore.loadCatalogs();
        discoveryTitle.setText(availableCollections.size() + " available · " + saved.size() + " saved");
        discoveryMessage.setVisibility(availableCollections.isEmpty() ? View.VISIBLE : View.GONE);
        discoveryMessage.setText(discoveryInProgress ? "Searching this Wi-Fi…" : discovered.isEmpty()
            ? "No shared collections found. Make sure Slide Show is running on your PC."
            : "This PC has no shared collections. Turn on sharing for a collection on the PC.");
        TextView section = text("Available on this Wi-Fi", 18, INK, true);
        section.setPadding(0, dp(16), 0, 0);
        discoveryList.addView(section);
        for (ServerInfo info : availableCollections.values()) {
            LinearLayout row = card();
            LinearLayout heading = new LinearLayout(this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            ImageView thumbnail = new ImageView(this);
            thumbnail.setImageResource(R.drawable.ic_photo);
            thumbnail.setImageTintList(ColorStateList.valueOf(MUTED));
            thumbnail.setScaleType(ImageView.ScaleType.CENTER);
            thumbnail.setBackground(rounded(SURFACE, 8, false));
            thumbnail.setClipToOutline(true);
            thumbnail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(80), dp(76));
            previewParams.rightMargin = dp(16);
            heading.addView(thumbnail, previewParams);
            if (!info.previewUrl.isEmpty()) preloadExecutor.execute(() -> {
                try {
                    Bitmap preview = offlineStore.decodeOnlineBitmap(info.baseUrl() + info.previewUrl, dp(160), dp(152));
                    handler.post(() -> {
                        if (!isDestroyed() && thumbnail.isAttachedToWindow()) {
                            thumbnail.setImageTintList(null); thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP); thumbnail.setImageBitmap(preview);
                        } else preview.recycle();
                    });
                } catch (Exception ignored) { }
            });
            LinearLayout identity = new LinearLayout(this);
            identity.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(info.collectionName, 18, INK, true);
            name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
            identity.addView(name);
            identity.addView(text(info.imageCount + " photos", 14, MUTED, false));
            identity.addView(text(info.name, 14, MUTED, false));
            heading.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(heading);
            LinearLayout actions = new LinearLayout(this);
            Button play = button("Play", true);
            play.setEnabled(info.imageCount > 0);
            play.setOnClickListener(v -> connectTo(info));
            actions.addView(play, new LinearLayout.LayoutParams(0, dp(40), 1));
            Button save = button("Save offline", false);
            save.setEnabled(info.imageCount > 0);
            save.setOnClickListener(v -> saveCollectionOffline(info));
            LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(40), 1);
            saveParams.leftMargin = dp(14);
            actions.addView(save, saveParams);
            row.addView(actions, fullButtonParams());
            discoveryList.addView(row, cardParams());
        }
        if (!saved.isEmpty()) addSavedHeading();
        for (OfflineImageStore.OfflineCatalog catalog : saved) discoveryList.addView(offlineCatalogCard(catalog, true), cardParams());
    }

    private void saveCollectionOffline(ServerInfo info) {
        if (syncInProgress) return;
        executor.execute(() -> {
            try {
                OfflineSource source = getOfflineSource(info);
                handler.post(() -> {
                    int slot = offlineStore.chooseSlotForSync(info.key(), source.state);
                    int workers = source.state.optInt("syncWorkers", 4);
                    if (slot == 0) showReplacementPicker(info, source.state, source.imageList, workers, true);
                    else {
                        OfflineImageStore.OfflineCatalog existing = offlineStore.loadCatalog(slot);
                        Runnable save = () -> syncToSlot(info, source.state, source.imageList, workers, slot, true);
                        if (existing != null) ensureCatalogUnlocked(existing, save); else save.run();
                    }
                });
            } catch (Exception error) {
                handler.post(() -> new AlertDialog.Builder(this).setTitle("Collection unavailable")
                    .setMessage("Make sure this collection is still shared from " + info.name + ". Your saved photos are unchanged.")
                    .setPositiveButton("OK", null).show());
            }
        });
    }

    private void showOfflineCatalogs(List<OfflineImageStore.OfflineCatalog> catalogs) {
        renderDiscoveredServers();
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
        showDiscoveryScreen("Collections", "");
        renderDiscoveredServers();
    }

    private void renderOfflineOptions(List<OfflineImageStore.OfflineCatalog> catalogs) {
        if (discoveryList == null) {
            return;
        }

        discoveryList.removeAllViews();
        if (!catalogs.isEmpty()) addSavedHeading();
        for (OfflineImageStore.OfflineCatalog catalog : catalogs) {
            discoveryList.addView(offlineCatalogCard(catalog, true), cardParams());
        }
    }

    private void addSavedHeading() {
        TextView heading = text("Saved on this phone", 18, INK, true);
        heading.setPadding(0, dp(20), 0, 0);
        discoveryList.addView(heading);
    }

    private LinearLayout offlineCatalogCard(OfflineImageStore.OfflineCatalog catalog, boolean allowDelete) {
        LinearLayout row = card();
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        ImageView thumbnail = new ImageView(this);
        thumbnail.setImageResource(catalog.hasPin() ? R.drawable.ic_lock : R.drawable.ic_photo);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER);
        thumbnail.setBackground(rounded(SURFACE, 8, false));
        thumbnail.setImageTintList(ColorStateList.valueOf(MUTED));
        thumbnail.setClipToOutline(true);
        thumbnail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams thumbnailParams = new LinearLayout.LayoutParams(dp(76), dp(82));
        thumbnailParams.rightMargin = dp(16);
        heading.addView(thumbnail, thumbnailParams);
        // Protected collections never reveal a preview in the library, even after unlocking.
        if (!catalog.hasPin() && !catalog.images.isEmpty()) {
            preloadExecutor.execute(() -> {
                try {
                    Bitmap preview = offlineStore.decodeBitmap(catalog, catalog.images.get(0), dp(152), dp(164));
                    handler.post(() -> {
                        if (!isDestroyed() && thumbnail.isAttachedToWindow()) {
                            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            thumbnail.setImageTintList(null);
                            thumbnail.setImageBitmap(preview);
                        } else preview.recycle();
                    });
                } catch (Exception error) {
                    Log.d(LOG_TAG, "Saved collection preview unavailable", error);
                }
            });
        }
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleRow = new LinearLayout(this);
        TextView name = text(cleanFolderName(catalog.displayName), 18, INK, true);
        name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        if (allowDelete) {
            Button more = button("⋮", false);
            more.setMinWidth(0); more.setMinimumWidth(0); more.setMinHeight(0); more.setMinimumHeight(0); more.setPadding(0,0,0,0);
            more.setBackground(buttonBackground(Color.TRANSPARENT, false));
            more.setContentDescription("Options for " + catalog.displayName);
            more.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(catalog.displayName)
                .setItems(new String[]{"Rename saved copy", "Delete saved copy"}, (dialog, which) -> ensureCatalogUnlocked(catalog, () -> {
                    if (which == 0) showRenameCatalogDialog(reloadCatalog(catalog));
                    else confirmDeleteCatalog(reloadCatalog(catalog), false);
                })).show());
            titleRow.addView(more, new LinearLayout.LayoutParams(dp(28), dp(28)));
        }
        identity.addView(titleRow);
        identity.addView(text(catalog.images.size() + " photos · " + (catalog.hasPin() ? "PIN protected" : "Saved copy"), 12, MUTED, false));
        if (canTryOpenOffline(catalog)) {
            Button open = button("Play offline", true);
            open.setOnClickListener(v -> openOfflineCatalog(catalog));
            LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(-1, dp(40));
            openParams.topMargin = dp(8);
            identity.addView(open, openParams);
        }
        heading.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(heading);
        return row;
    }

    private void showManualAddressDialog() {
        EditText input = new EditText(this);
        input.setHint("192.168.1.142:5177");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setPadding(dp(16), dp(10), dp(16), dp(10));

        new AlertDialog.Builder(this)
            .setTitle("Enter PC address")
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
                    int port = url.getPort() > 0 ? url.getPort() : ("https".equals(url.getProtocol()) ? 443 : 5177);
                    if (url.getHost().isEmpty()) throw new IllegalArgumentException("Missing host");
                    ServerInfo info = new ServerInfo("Slide Show", url.getHost(), port, url.getProtocol());
                    setDiscoveryInProgress(true);
                    executor.execute(() -> {
                        try { loadCollectionsFromServer(info); }
                        catch (Exception error) { handler.post(() -> { discoveryMessage.setText("Could not reach this PC. Check the address and try again."); discoveryMessage.setVisibility(View.VISIBLE); }); }
                        finally { handler.post(() -> setDiscoveryInProgress(false)); }
                    });
                } catch (Exception ignored) {
                    showDiscoveryScreen("Couldn't connect to Slide Show", "Check the address and try again.");
                }
            })
            .show();
    }

    private void connectTo(ServerInfo info) {
        final int generation = ++connectionGeneration;
        connectedServer = info;
        showDiscoveryScreen("Opening " + info.collectionName, "From " + info.name);
        executor.execute(() -> {
            try {
                OfflineSource source = getOfflineSource(info);
                OfflineImageStore.OfflineCatalog live = liveCatalog(info, source);
                handler.post(() -> {
                    if (generation != connectionGeneration || showingSlideshow) return;
                    if (source.imageList.length() == 0) showServerEmptyState(source.state);
                    else showSlideshow(info, live, false, this::showServerLaunchScreen);
                });
            } catch (Exception error) {
                handler.post(() -> { if (generation == connectionGeneration && !showingSlideshow) showDiscoveryScreen("Could not connect to " + info.name, "Check the address, Wi-Fi and Windows Firewall, then retry. Saved photos remain available below."); });
            }
        });
    }

    private OfflineImageStore.OfflineCatalog liveCatalog(ServerInfo info, OfflineSource source) throws Exception {
        JSONObject state = source.state;
        String name = state.optString("folderName", "Slide Show");
        return new OfflineImageStore.OfflineCatalog(0, info.key(), info.name, OfflineImageStore.folderIdentityFor(info.key(), state), name, name,
            state.optString("backgroundColor", "#05070a"), state.optString("imageMode", "fit"), state.optInt("slideSeconds",7),
            state.optLong("version",0), 0, 0, "", "", "", offlineStore.imagesForSource(info.key(), source.imageList)).withOrder(state.optString("playbackOrder","shuffle")).withCollectionId(state.optString("collectionId", ""));
    }

    private void saveOfflineFromCurrent() {
        if (syncInProgress || activeCatalog == null) return;
        ServerInfo info = activeOffline ? serverInfoForCatalog(activeCatalog) : connectedServer;
        if (info == null) { showTapFeedback("Connect to the source PC first", Gravity.CENTER); return; }
        final OfflineImageStore.OfflineCatalog selected = activeCatalog;
        executor.execute(() -> {
            try {
                OfflineSource source = getOfflineSource(info);
                handler.post(() -> {
                    if (activeCatalog != selected) return;
                    if (!OfflineImageStore.folderIdentityFor(info.key(), source.state).equals(selected.folderIdentity)
                        && !(info.key() + "\n" + source.state.optString("folderName")).equals(selected.folderIdentity)) {
                        new AlertDialog.Builder(this).setTitle("Collection unavailable").setMessage("The source no longer matches this saved copy.").setPositiveButton("OK",null).show(); return;
                    }
                    int slot = offlineStore.chooseSlotForSync(info.key(), source.state);
                    int workers = source.state.optInt("syncWorkers",4);
                    if (slot == 0) showReplacementPicker(info,source.state,source.imageList,workers,true);
                    else {
                        OfflineImageStore.OfflineCatalog existing = offlineStore.loadCatalog(slot);
                        if (existing != null) ensureCatalogUnlocked(existing, () -> syncToSlot(info,source.state,source.imageList,workers,slot,true));
                        else syncToSlot(info,source.state,source.imageList,workers,slot,true);
                    }
                });
            } catch (Exception error) { handler.post(() -> new AlertDialog.Builder(this).setTitle("PC unavailable").setMessage("Reconnect to " + info.name + " and try again. Your saved photos are unchanged.").setPositiveButton("OK",null).show()); }
        });
    }

    private void syncToSlot(ServerInfo info, JSONObject state, JSONArray imageList, int syncWorkers, int slotId) {
        syncToSlot(info, state, imageList, syncWorkers, slotId, false);
    }

    private void syncToSlot(ServerInfo info, JSONObject state, JSONArray imageList, int syncWorkers, int slotId, boolean keepCurrentOnFailure) {
        if (syncInProgress) return;
        if (imageList.length() == 0) { showTapFeedback("No photos to save", Gravity.CENTER); return; }
        syncInProgress = true;
        java.util.concurrent.atomic.AtomicBoolean canceled = new java.util.concurrent.atomic.AtomicBoolean();
        long startedAt = SystemClock.elapsedRealtime();
        long totalBytes = 0;
        for (int i=0; i<imageList.length(); i++) totalBytes += imageList.optJSONObject(i).optLong("sizeBytes",0);
        final String expectedSize = totalBytes > 0 ? " · about " + sizeText(totalBytes) : "";
        AlertDialog progress = new AlertDialog.Builder(this).setTitle("Saving offline")
            .setMessage("0 / " + imageList.length() + " photos" + expectedSize).setCancelable(false)
            .setNegativeButton("Cancel download", null).create();
        progress.setOnShowListener(dialog -> progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            canceled.set(true); progress.setMessage("Canceling download… Your previous copy will be kept."); progress.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        }));
        progress.show();
        final OfflineImageStore.OfflineCatalog selected = activeCatalog;
        executor.execute(() -> {
            try {
                OfflineImageStore.OfflineCatalog saved = offlineStore.sync(slotId, info.key(), info.name, info.baseUrl(), state, imageList, syncWorkers,
                    (completed,total,name) -> handler.post(() -> { if (!canceled.get()) progress.setMessage(completed + " / " + total + " photos" + expectedSize + etaText(completed,total,startedAt)); }), canceled);
                handler.post(() -> {
                    progress.dismiss();
                    if (showingSlideshow && activeCatalog == selected) {
                        if (activeOffline) openOfflineCatalog(saved);
                        else { restoreActiveHeader(); showTapFeedback("Saved on this device", Gravity.CENTER); buildSettingsPanel(); }
                    }
                    else if (!showingSlideshow) renderDiscoveredServers();
                });
            } catch (Exception error) {
                handler.post(() -> { progress.dismiss(); new AlertDialog.Builder(this).setTitle(canceled.get()?"Download canceled":"Could not save photos")
                    .setMessage(canceled.get()?"Your previous saved copy is unchanged.":cleanString(error.getMessage(),"Check storage and the connection, then retry. Your previous saved copy is unchanged."))
                    .setPositiveButton("OK",null).show(); });
            } finally { syncInProgress=false; }
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
        return matching;
    }

    private OfflineSource getOfflineSource(ServerInfo info) throws Exception {
        if (info.collectionId.isEmpty()) {
            JSONObject listing = getJson(info.baseUrl() + "/api/collections");
            JSONArray collections = listing.optJSONArray("collections");
            if (collections != null) for (int i = 0; i < collections.length(); i++) {
                String id = collections.getJSONObject(i).getString("id");
                JSONObject candidate = getJson(info.baseUrl() + "/api/state?collection=" + id);
                if (OfflineImageStore.folderIdentityFor(info.key(), candidate).equals(info.expectedFolderIdentity)
                    || (info.key() + "\n" + candidate.optString("folderName")).equals(info.expectedFolderIdentity)) { info.collectionId = id; break; }
            }
            if (info.collectionId.isEmpty()) throw new java.io.IOException("This collection is no longer shared from its PC.");
        }
        try {
            JSONObject source = getJson(info.sourceUrl("/api/offline-source"));
            JSONObject state = source.optJSONObject("state");
            JSONArray imageList = source.optJSONArray("images");
            if (state != null && imageList != null) {
                return new OfflineSource(state, imageList);
            }
        } catch (Exception ignored) {
        }

        return new OfflineSource(
            getJson(info.sourceUrl("/api/state")),
            getJsonArray(info.sourceUrl("/api/images?shuffle=false")));
    }

    private ServerInfo serverInfoForCatalog(OfflineImageStore.OfflineCatalog catalog) {
        if (catalog == null || catalog.serverKey == null || catalog.serverKey.isEmpty()) return null;
        try {
            URL url = new URL(catalog.serverKey.contains("://") ? catalog.serverKey : "http://" + catalog.serverKey);
            ServerInfo info = new ServerInfo(catalog.serverName,url.getHost(),url.getPort()>0?url.getPort():url.getDefaultPort(),url.getProtocol());
            info.collectionId = catalog.collectionId;
            info.collectionName = catalog.folderName;
            info.expectedFolderIdentity = catalog.folderIdentity;
            return info;
        } catch (Exception error) { return null; }
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

            openUnlockedOfflineCatalog(current, beforeOpen);
        }, onCanceled);
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
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
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
            input.setOnEditorActionListener((view, actionId, event) -> {
                boolean doneAction = actionId == EditorInfo.IME_ACTION_DONE;
                boolean enterRelease = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP;
                if (doneAction || enterRelease) {
                    unlock.performClick();
                    return true;
                }
                return false;
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
        playbackOrder = catalog.playbackOrder;

        folderName = cleanFolderName(catalog.displayName);
        slideSeconds = catalog.slideSeconds;
        imageMode = normalizeImageMode(catalog.imageMode);
        currentIndex = 0;
        restorePlayback();
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

        libraryButton = overlayButton("Library");
        libraryButton.setOnClickListener(v -> returnToSlideshowLauncher());
        top.addView(libraryButton, new LinearLayout.LayoutParams(-2,-2));
        LinearLayout folderPill = pillRow();
        folderTitle = text(offline ? folderName + " - Offline" : folderName, 16, INK, true);
        folderTitle.setSingleLine(true);
        folderTitle.setEllipsize(TextUtils.TruncateAt.END);
        folderPill.addView(folderTitle);

        LinearLayout counterPill = pillRow();
        positionText = text("0 / 0", 15, MUTED, false);
        counterPill.addView(positionText);

        LinearLayout.LayoutParams folderParams = new LinearLayout.LayoutParams(0, -2, 1);
        folderParams.setMargins(dp(8), 0, dp(8), 0);
        top.addView(folderPill, folderParams);
        top.addView(counterPill, new LinearLayout.LayoutParams(-2, -2));
        chrome.addView(top, new LinearLayout.LayoutParams(-1, -2));

        View spacer = new View(this);
        chrome.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

        settingsButton = overlayButton("Settings");
        settingsButton.setOnClickListener(v -> toggleSettingsPanel());
        playbackControls = new LinearLayout(this);
        playbackControls.setGravity(Gravity.CENTER);
        playbackControls.setPadding(dp(4), dp(6), dp(4), dp(6));
        playbackControls.setBackground(rounded(OVERLAY, 14, false));
        Button previous = overlayButton("Previous"); previous.setOnClickListener(v -> advance(-1));
        pauseButton = overlayButton("Pause"); pauseButton.setOnClickListener(v -> togglePlay());
        Button next = overlayButton("Next"); next.setOnClickListener(v -> advance(1));
        previous.setBackground(buttonBackground(Color.TRANSPARENT, false));
        next.setBackground(buttonBackground(Color.TRANSPARENT, false));
        settingsButton.setBackground(buttonBackground(Color.TRANSPARENT, false));
        pauseButton.setBackground(buttonBackground(ACCENT, false));
        pauseButton.setTextColor(PRIMARY_TEXT);
        playbackControls.addView(previous,controlParams()); playbackControls.addView(pauseButton,controlParams());
        playbackControls.addView(next,controlParams()); playbackControls.addView(settingsButton,controlParams());
        chrome.addView(playbackControls,new LinearLayout.LayoutParams(-1,-2));
        connectionNotice = text("",14,Color.WHITE,false); connectionNotice.setBackgroundColor(Color.argb(220,70,45,10));
        connectionNotice.setPadding(dp(14),dp(8),dp(14),dp(8)); connectionNotice.setVisibility(View.GONE);
        connectionNotice.setOnClickListener(v -> { checkServerFolderForChanges(); renderCurrentSlide(); });
        FrameLayout.LayoutParams noticeParams = new FrameLayout.LayoutParams(-1,-2,Gravity.TOP); noticeParams.topMargin=dp(90);
        root.addView(connectionNotice,noticeParams);

        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setPadding(dp(20), dp(12), dp(20), dp(24));
        settingsPanel.setBackgroundColor(SURFACE);
        settingsPanel.setClickable(true);
        settingsPanel.setVisibility(View.GONE);
        settingsScroll = new ScrollView(this);
        settingsScroll.setBackground(rounded(SURFACE, 16, false));
        settingsScroll.setClipToOutline(true);
        settingsScroll.addView(settingsPanel);
        settingsScroll.setVisibility(View.GONE);
        root.addView(settingsScroll, settingsPanelParams());
        buildSettingsPanel();

        feedbackText = text("", 44, INK, true);
        feedbackText.setGravity(Gravity.CENTER);
        feedbackText.setMinWidth(dp(118));
        feedbackText.setMinHeight(dp(86));
        feedbackText.setPadding(dp(22), dp(12), dp(22), dp(14));
        feedbackText.setBackground(rounded(OVERLAY, 18, false));
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
        savePlayback(); connectionGeneration++;
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
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text("Playback", 24, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button closeSettings = overlayButton("Close");
        closeSettings.setContentDescription("Close settings");
        closeSettings.setBackground(buttonBackground(Color.TRANSPARENT, false));
        closeSettings.setOnClickListener(v -> setSettingsPanelVisible(false));
        heading.addView(closeSettings, new LinearLayout.LayoutParams(-2, -2));
        settingsPanel.addView(heading);
        settingsPanel.addView(themeButton(true), fullButtonParams());
        TextView scope = text(activeOffline ? "Only this saved copy" : "Applies to viewers connected to this PC",13,MUTED,false);
        scope.setPadding(0, 0, 0, dp(16));
        settingsPanel.addView(scope);
        Button order = overlayButton("Order: " + ("name".equals(playbackOrder)?"File name":"date".equals(playbackOrder)?"Date modified":"Shuffle"));
        order.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Photo order").setItems(new String[]{"Shuffle","File name (A–Z)","Date modified (oldest first)"},(dialog,which)-> {
            savePlayback(); playbackOrder = new String[]{"shuffle","name","date"}[which];
            arrangeImages(Collections.emptyList()); postPlaybackSettings(); buildSettingsPanel(); renderCurrentSlide();
        }).show()); settingsPanel.addView(order,fullButtonParams());
        Button restart = overlayButton("Start from beginning"); restart.setOnClickListener(v -> {currentIndex=0;renderCurrentSlide();}); settingsPanel.addView(restart,fullButtonParams());

        timerText = text("", 16, INK, false);
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

        TextView imageSize = text("Image size", 16, INK, false);
        imageSize.setPadding(0, dp(18), 0, dp(8));
        settingsPanel.addView(imageSize);

        LinearLayout modeControls = new LinearLayout(this);
        modeControls.setOrientation(LinearLayout.HORIZONTAL);
        settingsPanel.addView(modeControls);

        fitButton = overlayButton("Entire photo");
        fitButton.setOnClickListener(v -> updateImageMode("fit"));
        modeControls.addView(fitButton, controlParams());

        fullButton = overlayButton("Fill (crops)");
        fullButton.setOnClickListener(v -> updateImageMode("full"));
        modeControls.addView(fullButton, controlParams());

        TextView offlineTitle = text("Offline slideshows", 16, INK, false);
        offlineTitle.setPadding(0, dp(18), 0, dp(8));
        settingsPanel.addView(offlineTitle);

        TextView offlineDetails = text(activeCatalog == null || activeCatalog.slotId == 0 ? "Playing from the PC. Save a copy to watch without a connection." : cleanFolderName(activeCatalog.displayName) + " - " + savedCatalogSummary(activeCatalog), 14, MUTED, false);
        offlineDetails.setPadding(0, 0, 0, dp(8));
        settingsPanel.addView(offlineDetails);

        Button save = overlayButton(activeOffline ? "Update saved copy" : "Save offline");
        save.setOnClickListener(v -> saveOfflineFromCurrent()); settingsPanel.addView(save,fullButtonParams());
        List<OfflineImageStore.OfflineCatalog> catalogs = offlineStore.loadCatalogs();
        if (!catalogs.isEmpty()) {
            Button switchOffline = overlayButton("Switch offline slideshow");
            switchOffline.setOnClickListener(v -> showOfflineSwitchDialog());
            settingsPanel.addView(switchOffline, fullButtonParams());
        }

        if (activeCatalog != null && activeCatalog.slotId > 0) {
            TextView protectionTitle = text("Protection", 16, INK, false);
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
                    if (!showingSlideshow) renderDiscoveredServers();
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
            showServerLaunchScreen();
            return;
        }

        if (fromSettings && settingsPanel != null) {
            buildSettingsPanel();
        }

        if (!showingSlideshow) renderDiscoveredServers();
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

    private String playbackKey() { return (activeOffline ? "saved:" : "live:") + activeCatalog.folderIdentity; }

    private void savePlayback() {
        if (activeCatalog == null || images.isEmpty() || currentIndex >= images.size()) return;
        try {
            JSONObject data = new JSONObject(); JSONArray keys = new JSONArray();
            for (SlideImage image : images) keys.put(image.encryptedFileName);
            data.put("keys",keys); data.put("current",images.get(currentIndex).encryptedFileName); data.put("order",playbackOrder);
            data.put("seconds",slideSeconds); data.put("mode",imageMode);
            getSharedPreferences("playback",MODE_PRIVATE).edit().putString(playbackKey(),data.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void restorePlayback() {
        List<String> keys = new ArrayList<>(); String current = "";
        try {
            JSONObject data = new JSONObject(getSharedPreferences("playback",MODE_PRIVATE).getString(playbackKey(),"{}"));
            if (activeOffline) { playbackOrder=data.optString("order",playbackOrder); slideSeconds=data.optInt("seconds",slideSeconds); imageMode=data.optString("mode",imageMode); }
            JSONArray saved = data.optJSONArray("keys");
            if (playbackOrder.equals(data.optString("order")) && saved != null) for (int i=0;i<saved.length();i++) keys.add(saved.getString(i));
            current=data.optString("current","");
        } catch (Exception ignored) {}
        images.clear(); images.addAll(PlaybackSequence.arrange(activeCatalog.images,playbackOrder,keys,photoReader)); currentIndex=0;
        for (int i=0;i<images.size();i++) if (images.get(i).encryptedFileName.equals(current)) {currentIndex=i;break;}
    }

    private void arrangeImages(List<String> keys) {
        String current = images.isEmpty()?"":images.get(currentIndex).encryptedFileName;
        List<SlideImage> arranged=PlaybackSequence.arrange(images,playbackOrder,keys,photoReader);
        images.clear();images.addAll(arranged);currentIndex=0;
        for (int i=0;i<images.size();i++) if(images.get(i).encryptedFileName.equals(current)){currentIndex=i;break;}
    }

    private Bitmap decodePhoto(OfflineImageStore.OfflineCatalog catalog, SlideImage item, int width, int height) throws Exception {
        if (catalog.slotId > 0) return offlineStore.decodeBitmap(catalog,item,width,height);
        ServerInfo info=serverInfoForCatalog(catalog);
        if (info == null) throw new IllegalStateException("PC unavailable");
        return offlineStore.decodeOnlineBitmap(info.baseUrl()+item.path,width,height);
    }

    private void renderCurrentSlide() {
        savePlayback();
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
                Bitmap bitmap = cacheBitmap(item, decodePhoto(catalog, item, targetWidth, targetHeight));
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
                        connectionNotice.setText("Could not load this photo. Tap to retry, use Next, or open Library."); connectionNotice.setVisibility(View.VISIBLE);
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
        connectionNotice.setVisibility(View.GONE);
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

        for (int offset = 1; offset <= (activeOffline ? 3 : 1); offset++) {
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
                    cacheBitmap(item, decodePhoto(catalog, item, targetWidth, targetHeight));
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
        if (activityVisible && playing && !images.isEmpty()) {
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
        if (pauseButton != null) pauseButton.setText(playing ? "Pause" : "Play");
        showChromeTemporarily(); scheduleNext();
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

        if (chrome.getVisibility() != View.VISIBLE) { showChromeTemporarily(); return; }
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
            if (settingsScroll != null) settingsScroll.setVisibility(visible ? View.VISIBLE : View.GONE);
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
        if ((chrome != null && chrome.getVisibility() == View.VISIBLE && (isPointInsideView(playbackControls,event.getRawX(),event.getRawY()) || isPointInsideView(libraryButton,event.getRawX(),event.getRawY()))) || isPointInsideView(connectionNotice,event.getRawX(),event.getRawY())) return false;
        if (settingsScroll != null
            && settingsScroll.getVisibility() == View.VISIBLE
            && isPointInsideView(settingsScroll, event.getRawX(), event.getRawY())) {
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
        fitButton.setBackground(buttonBackground(full ? OVERLAY : ACCENT, true));
        fullButton.setBackground(buttonBackground(full ? ACCENT : OVERLAY, true));
        fitButton.setTextColor(full ? INK : PRIMARY_TEXT);
        fullButton.setTextColor(full ? PRIMARY_TEXT : INK);
        fitButton.setSelected(!full);
        fullButton.setSelected(full);
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
        savePlayback();
        if (activeOffline || connectedServer == null) return;
        final ServerInfo target = connectedServer;
        final int seconds = slideSeconds;
        final String size = imageMode, order = playbackOrder;
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject(); body.put("slideSeconds",seconds); body.put("imageMode",size); body.put("playbackOrder",order);
                postJson(target.sourceUrl("/api/playback-settings"),body);
            } catch (Exception error) { handler.post(() -> { if (showingSlideshow && connectionNotice != null) {connectionNotice.setText("Settings could not be saved to the PC. Reconnect and try again.");connectionNotice.setVisibility(View.VISIBLE);} }); }
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
        if (!showingSlideshow || activeOffline || connectedServer == null || checkingServerFolder) return;
        checkingServerFolder = true;
        final ServerInfo info = connectedServer;
        executor.execute(() -> {
            try {
                OfflineSource source = getOfflineSource(info);
                OfflineImageStore.OfflineCatalog next = liveCatalog(info,source);
                handler.post(() -> {
                    if (!showingSlideshow || activeOffline || connectedServer != info) return;
                    connectionNotice.setVisibility(View.GONE);
                    if (activeCatalog == null || !next.folderIdentity.equals(activeCatalog.folderIdentity)) { showSlideshow(info,next,false,this::showServerLaunchScreen); return; }
                    boolean contentChanged = next.serverVersion != activeCatalog.serverVersion;
                    boolean orderChanged = !playbackOrder.equals(next.playbackOrder);
                    boolean durationChanged = slideSeconds != next.slideSeconds;
                    savePlayback(); activeCatalog=next; slideSeconds=next.slideSeconds; imageMode=next.imageMode; playbackOrder=next.playbackOrder;
                    restoreActiveHeader();
                    root.setBackgroundColor(parseColor(next.backgroundColor)); applyImageMode(); updateSettingsText(); updateImageModeButtons();
                    if (contentChanged) { restorePlayback(); renderCurrentSlide(); }
                    else if (orderChanged) { arrangeImages(Collections.emptyList()); renderCurrentSlide(); }
                    else if (durationChanged) scheduleNext();
                });
            } catch (Exception error) { handler.post(() -> { if (showingSlideshow && connectedServer == info && connectionNotice != null) {connectionNotice.setText("PC disconnected. Tap to retry or open a saved copy in Library.");connectionNotice.setVisibility(View.VISIBLE);} }); }
            finally { checkingServerFolder=false; handler.post(this::scheduleServerFolderWatch); }
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
                body.put("viewerId", viewerId);
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
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return view;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(primary ? PRIMARY_TEXT : INK);
        button.setBackground(buttonBackground(primary ? ACCENT : CARD, !primary));
        button.setPadding(dp(16), dp(10), dp(16), dp(10));
        button.setMinHeight(dp(48));
        button.setMinimumWidth(0);
        button.setStateListAnimator(null);
        return button;
    }

    private Button overlayButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(INK);
        button.setBackground(buttonBackground(OVERLAY, true));
        button.setPadding(dp(8), dp(10), dp(8), dp(10));
        button.setMinHeight(dp(48));
        button.setMinimumWidth(0);
        button.setStateListAnimator(null);
        return button;
    }

    private GradientDrawable rounded(int color, int radius, boolean border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (border) drawable.setStroke(dp(1), LINE);
        return drawable;
    }

    private RippleDrawable buttonBackground(int color, boolean border) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(35, 133, 153, 179)), rounded(color, 8, border), rounded(Color.WHITE, 8, false));
    }

    private LinearLayout card() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackground(rounded(CARD, 12, true));
        return row;
    }

    private LinearLayout pillRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setBackground(rounded(OVERLAY, 8, false));
        row.setMinimumHeight(dp(48));
        return row;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(12), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams fullButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(8), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams controlParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
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
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, Math.round(getResources().getDisplayMetrics().heightPixels * .8f));
        params.gravity = Gravity.BOTTOM;
        params.setMargins(dp(10), 0, dp(10), dp(10));
        return params;
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if (darkTheme != useDarkTheme()) refreshTheme();
        if (settingsScroll != null) settingsScroll.setLayoutParams(settingsPanelParams());
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

    static final class ServerInfo {
        final String name;
        final String host;
        final int port;
        final String scheme;
        String collectionId = "", collectionName = "", previewUrl = "", expectedFolderIdentity = "";
        int imageCount;

        ServerInfo(String name, String host, int port) { this(name,host,port,"http"); }
        ServerInfo(String name, String host, int port, String scheme) {
            this.scheme = "https".equals(scheme) ? "https" : "http";
            this.name = name == null || name.isEmpty() ? "Slide Show" : name;
            this.host = host;
            this.port = port;
        }

        String key() {
            return ("https".equals(scheme) ? "https://" : "") + host + ":" + port;
        }

        String baseUrl() {
            return scheme + "://" + host + ":" + port;
        }
        String sourceUrl(String path) {
            return baseUrl() + path + (path.contains("?") ? "&" : "?") + "collection=" + collectionId;
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
        final long modifiedAt;

        SlideImage(String name, String path, String encryptedFileName) { this(name,path,encryptedFileName,0); }
        SlideImage(String name, String path, String encryptedFileName, long modifiedAt) {
            this.modifiedAt = modifiedAt;
            this.name = name;
            this.path = path;
            this.encryptedFileName = encryptedFileName;
        }
    }
}
