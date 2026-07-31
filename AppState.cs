using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace SlideShow;

public sealed class AppState
{
    private readonly object _gate = new();
    private readonly SettingsStore _settingsStore;
    private readonly ImageCatalog _catalog = new();
    private readonly ViewerActivityTracker _viewerActivity;
    private AppSettings _settings;

    public AppState(SettingsStore settingsStore, ViewerActivityTracker viewerActivity)
    {
        _settingsStore = settingsStore;
        _viewerActivity = viewerActivity;
        _settings = settingsStore.Load();
        if (RememberCurrentSlideshow(_settings))
        {
            _settingsStore.Save(_settings);
        }
    }

    public int Port
    {
        get
        {
            lock (_gate)
            {
                return _settings.Port;
            }
        }
    }

    public int HttpsPort
    {
        get
        {
            lock (_gate)
            {
                return _settings.HttpsPort;
            }
        }
    }

    public bool HttpsEnabled { get; private set; }

    public AppSettings GetSettings()
    {
        lock (_gate)
        {
            return _settings.Copy();
        }
    }

    public StateDto GetSnapshot(bool canConfigure)
    {
        AppSettings settings;
        lock (_gate)
        {
            settings = _settings.Copy();
        }

        var localUrl = $"http://localhost:{settings.Port}";
        var localHttpsUrl = $"https://localhost:{settings.HttpsPort}";
        var lanUrls = GetLanAddresses()
            .Select(ip => $"http://{ip}:{settings.Port}")
            .ToArray();
        var httpsLanUrls = GetLanAddresses()
            .Select(ip => $"https://{ip}:{settings.HttpsPort}")
            .ToArray();
        var displayUrl = lanUrls.FirstOrDefault() ?? localUrl;
        var httpsDisplayUrl = httpsLanUrls.FirstOrDefault() ?? localHttpsUrl;

        return new StateDto(
            settings.FolderPath,
            settings.FolderPath is null ? null : Path.GetFileName(settings.FolderPath.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)),
            settings.IncludeSubfolders,
            settings.SlideSeconds,
            settings.BackgroundColor,
            settings.ImageMode,
            settings.SyncWorkers,
            settings.Port,
            settings.HttpsPort,
            settings.StartAtLogin,
            _catalog.Count,
            _catalog.Version,
            _catalog.LastScannedAt,
            _catalog.ScanMessage,
            canConfigure,
            HttpsEnabled,
            localUrl,
            $"{localUrl}/show",
            displayUrl,
            $"{displayUrl}/show",
            lanUrls.Select(url => $"{url}/show").ToArray(),
            localHttpsUrl,
            $"{localHttpsUrl}/show",
            httpsDisplayUrl,
            $"{httpsDisplayUrl}/show",
            httpsLanUrls.Select(url => $"{url}/show").ToArray(),
            $"{displayUrl}/certificate.cer",
            $"{httpsDisplayUrl}/certificate.cer",
            settings.RecentSlideshows.Select(ToDto).ToArray());
    }

    public IReadOnlyList<ImageDto> GetImages(bool shuffle)
    {
        return _catalog.GetImages(shuffle)
            .Select(image => new ImageDto(image.Id, image.Name, $"/image/{image.Id}?v={_catalog.Version}", ImageCatalog.GetCacheKey(image)))
            .ToArray();
    }

    public ImageItem? GetImage(int id) => _catalog.GetById(id);

    public void RecordRemoteViewer(string viewerKey) => _viewerActivity.Record(viewerKey);

    public void ClearRemoteViewer(string viewerKey) => _viewerActivity.Clear(viewerKey);

    public void Rescan() => _catalog.Scan(GetSettings());

    public void UpdatePorts(int port, int? httpsPort)
    {
        lock (_gate)
        {
            _settings.Port = port;
            if (httpsPort.HasValue)
            {
                _settings.HttpsPort = httpsPort.Value;
            }
            HttpsEnabled = httpsPort.HasValue;
            _settings.Normalize();
            _settingsStore.Save(_settings);
        }
    }

    public void UpdateSettings(SettingsUpdateDto update)
    {
        lock (_gate)
        {
            if (update.FolderPath is not null)
            {
                _settings.FolderPath = string.IsNullOrWhiteSpace(update.FolderPath) ? null : update.FolderPath.Trim();
            }

            if (update.IncludeSubfolders.HasValue)
            {
                _settings.IncludeSubfolders = update.IncludeSubfolders.Value;
            }

            if (update.SlideSeconds.HasValue)
            {
                _settings.SlideSeconds = update.SlideSeconds.Value;
            }

            if (update.BackgroundColor is not null)
            {
                _settings.BackgroundColor = update.BackgroundColor;
            }

            if (update.ImageMode is not null)
            {
                _settings.ImageMode = update.ImageMode;
            }

            if (update.SyncWorkers.HasValue)
            {
                _settings.SyncWorkers = update.SyncWorkers.Value;
            }

            if (update.StartAtLogin.HasValue)
            {
                _settings.StartAtLogin = update.StartAtLogin.Value;
            }

            _settings.Normalize();
            if (update.FolderPath is not null)
            {
                RememberCurrentSlideshow(_settings);
            }
            _settingsStore.Save(_settings);
        }

        Rescan();
    }

    public void UpdatePlaybackSettings(PlaybackSettingsUpdateDto update)
    {
        lock (_gate)
        {
            if (update.SlideSeconds.HasValue)
            {
                _settings.SlideSeconds = update.SlideSeconds.Value;
            }

            if (update.BackgroundColor is not null)
            {
                _settings.BackgroundColor = update.BackgroundColor;
            }

            if (update.ImageMode is not null)
            {
                _settings.ImageMode = update.ImageMode;
            }

            _settings.Normalize();
            _settingsStore.Save(_settings);
        }
    }

    private static bool RememberCurrentSlideshow(AppSettings settings)
    {
        if (string.IsNullOrWhiteSpace(settings.FolderPath))
        {
            return false;
        }

        settings.Normalize();
        var folderPath = settings.FolderPath;
        var existing = settings.RecentSlideshows
            .FirstOrDefault(slideshow => string.Equals(slideshow.FolderPath, folderPath, StringComparison.OrdinalIgnoreCase));
        var now = DateTimeOffset.UtcNow;

        if (existing is not null)
        {
            existing.FolderName = RecentSlideshow.DisplayNameFor(folderPath);
            existing.LastUsedAt = now;
        }
        else
        {
            settings.RecentSlideshows.Insert(0, new RecentSlideshow
            {
                FolderPath = folderPath,
                FolderName = RecentSlideshow.DisplayNameFor(folderPath),
                LastUsedAt = now
            });
        }

        settings.Normalize();
        return true;
    }

    private static RecentSlideshowDto ToDto(RecentSlideshow slideshow) => new(
        slideshow.FolderPath,
        slideshow.FolderName ?? RecentSlideshow.DisplayNameFor(slideshow.FolderPath),
        slideshow.LastUsedAt);

    private static IEnumerable<string> GetLanAddresses()
    {
        return NetworkInterface.GetAllNetworkInterfaces()
            .Where(adapter => adapter.OperationalStatus == OperationalStatus.Up)
            .SelectMany(adapter => adapter.GetIPProperties().UnicastAddresses)
            .Where(address => address.Address.AddressFamily == AddressFamily.InterNetwork)
            .Select(address => address.Address)
            .Where(address => !IPAddress.IsLoopback(address))
            .Where(address => !address.ToString().StartsWith("169.254.", StringComparison.Ordinal))
            .Select(address => address.ToString())
            .Distinct();
    }
}

public sealed record StateDto(
    string? FolderPath,
    string? FolderName,
    bool IncludeSubfolders,
    int SlideSeconds,
    string BackgroundColor,
    string ImageMode,
    int SyncWorkers,
    int Port,
    int HttpsPort,
    bool StartAtLogin,
    int ImageCount,
    long Version,
    DateTimeOffset? LastScannedAt,
    string? ScanMessage,
    bool CanConfigure,
    bool HttpsEnabled,
    string LocalUrl,
    string LocalSlideshowUrl,
    string DisplayUrl,
    string DisplaySlideshowUrl,
    string[] LanSlideshowUrls,
    string LocalHttpsUrl,
    string LocalHttpsSlideshowUrl,
    string HttpsDisplayUrl,
    string HttpsDisplaySlideshowUrl,
    string[] HttpsLanSlideshowUrls,
    string CertificateUrl,
    string HttpsCertificateUrl,
    RecentSlideshowDto[] RecentSlideshows);

public sealed record RecentSlideshowDto(string FolderPath, string FolderName, DateTimeOffset LastUsedAt);

public sealed record ImageDto(int Id, string Name, string Url, string CacheKey);

public sealed record OfflineSourceDto(StateDto State, IReadOnlyList<ImageDto> Images);

public sealed class SettingsUpdateDto
{
    public string? FolderPath { get; set; }
    public bool? IncludeSubfolders { get; set; }
    public int? SlideSeconds { get; set; }
    public string? BackgroundColor { get; set; }
    public string? ImageMode { get; set; }
    public int? SyncWorkers { get; set; }
    public bool? StartAtLogin { get; set; }
}

public sealed class PlaybackSettingsUpdateDto
{
    public int? SlideSeconds { get; set; }
    public string? BackgroundColor { get; set; }
    public string? ImageMode { get; set; }
}

public sealed record ViewerHeartbeatDto(bool Active);
