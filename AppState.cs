using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace SlideShow;

public sealed class AppState
{
    private readonly object _gate = new();
    private readonly SettingsStore _settingsStore;
    private readonly ImageCatalog _catalog = new();
    private readonly Dictionary<string, ImageCatalog> _collectionCatalogs = new();
    private readonly ViewerActivityTracker _viewerActivity;
    private AppSettings _settings;

    public AppState(SettingsStore settingsStore, ViewerActivityTracker viewerActivity)
    {
        _settingsStore = settingsStore;
        _viewerActivity = viewerActivity;
        _settings = settingsStore.Load();
        _settings.Normalize();
        if (RememberCurrentSlideshow(_settings) || _settings.Collections.Count > 0)
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

    public AppSettings GetSettings(string? collectionId = null)
    {
        lock (_gate)
        {
            var settings = _settings.Copy();
            var collection = FindCollection(collectionId);
            if (collectionId is not null && collection is null) throw new KeyNotFoundException("Collection not found.");
            collection?.ApplyTo(settings);
            return settings;
        }
    }

    public StateDto GetSnapshot(bool canConfigure, string? collectionId = null)
    {
        AppSettings settings;
        PhotoCollection? collection;
        ImageCatalog catalog;
        lock (_gate)
        {
            settings = GetSettings(collectionId);
            collection = FindCollection(collectionId)?.Copy();
            catalog = CatalogFor(collectionId);
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
        var showPath = collection is null ? "/show" : $"/show?collection={Uri.EscapeDataString(collection.Id)}";

        return new StateDto(
            settings.FolderPath,
            collection?.Name ?? (settings.FolderPath is null ? null : RecentSlideshow.DisplayNameFor(settings.FolderPath)),
            settings.IncludeSubfolders,
            settings.SlideSeconds,
            settings.BackgroundColor,
            settings.ImageMode,
            settings.SyncWorkers,
            settings.Port,
            settings.HttpsPort,
            settings.StartAtLogin,
            catalog.Count,
            catalog.Version,
            catalog.LastScannedAt,
            catalog.ScanMessage,
            canConfigure,
            HttpsEnabled,
            localUrl,
            $"{localUrl}{showPath}",
            displayUrl,
            $"{displayUrl}{showPath}",
            lanUrls.Select(url => $"{url}{showPath}").ToArray(),
            localHttpsUrl,
            $"{localHttpsUrl}{showPath}",
            httpsDisplayUrl,
            $"{httpsDisplayUrl}{showPath}",
            httpsLanUrls.Select(url => $"{url}{showPath}").ToArray(),
            $"{displayUrl}/certificate.cer",
            $"{httpsDisplayUrl}/certificate.cer",
            canConfigure ? settings.RecentSlideshows.Select(ToDto).ToArray() : [],
            settings.PlaybackOrder,
            Environment.MachineName,
            _viewerActivity.RemoteViewerCount,
            collection?.Id,
            collection?.Shared ?? false,
            GetCollections(canConfigure));
    }

    public IReadOnlyList<ImageDto> GetImages(bool shuffle, string? collectionId = null)
    {
        lock (_gate)
        {
            var catalog = CatalogFor(collectionId);
            var id = FindCollection(collectionId)?.Id;
            return catalog.GetImages(shuffle)
                .Select(image => new ImageDto(image.Id, image.Name, $"/image/{image.Id}?v={catalog.Version}&collection={id}", ImageCatalog.GetCacheKey(image), image.SizeBytes, image.ModifiedAt.ToUnixTimeMilliseconds()))
                .ToArray();
        }
    }

    public ImageItem? GetImage(int id, string? collectionId = null)
    {
        lock (_gate) { return CatalogFor(collectionId).GetById(id); }
    }

    public void RecordViewer(string viewerKey, bool isLocal) => _viewerActivity.Record(viewerKey, isLocal);

    public void ClearRemoteViewer(string viewerKey) => _viewerActivity.Clear(viewerKey);

    public void Rescan(string? collectionId = null)
    {
        lock (_gate) { CatalogFor(collectionId).Scan(GetSettings(collectionId)); }
    }

    public void RefreshCatalog(string? collectionId = null)
    {
        lock (_gate)
        {
            var catalog = CatalogFor(collectionId);
            if (catalog.LastScannedAt is null || DateTimeOffset.Now - catalog.LastScannedAt >= TimeSpan.FromSeconds(5))
                catalog.Scan(GetSettings(collectionId));
        }
    }

    private PhotoCollection? FindCollection(string? id) => id is not null
        ? _settings.Collections.FirstOrDefault(item => item.Id == id)
        : _settings.Collections.FirstOrDefault(item => string.Equals(item.FolderPath, _settings.FolderPath, StringComparison.OrdinalIgnoreCase));

    private ImageCatalog CatalogFor(string? id)
    {
        var collection = FindCollection(id);
        if (collection is null)
        {
            if (id is not null) throw new KeyNotFoundException("Collection not found.");
            return _catalog;
        }
        if (!_collectionCatalogs.TryGetValue(collection.Id, out var catalog))
            _collectionCatalogs[collection.Id] = catalog = new ImageCatalog();
        return catalog;
    }

    public bool CanAccessCollection(string? id, bool local)
    {
        lock (_gate)
        {
            var collection = FindCollection(id);
            return collection is null ? local && id is null : local || collection.Shared;
        }
    }

    public CollectionDto[] GetCollections(bool local)
    {
        lock (_gate)
        {
            return _settings.Collections.Where(item => local || item.Shared).Select(item =>
            {
                RefreshCatalog(item.Id);
                var catalog = CatalogFor(item.Id);
                return new CollectionDto(item.Id, item.Name, catalog.Count, item.Shared,
                    catalog.Count > 0 ? $"/image/0?collection={item.Id}&v={catalog.Version}" : null,
                    local ? item.FolderPath : null, Directory.Exists(item.FolderPath));
            }).Where(item => item.Available).ToArray();
        }
    }

    public bool UpdateCollection(string id, CollectionUpdateDto update)
    {
        lock (_gate)
        {
            var collection = FindCollection(id);
            if (collection is null) return false;
            if (!string.IsNullOrWhiteSpace(update.Name)) collection.Name = update.Name.Trim();
            if (update.Shared.HasValue) collection.Shared = update.Shared.Value;
            _settingsStore.Save(_settings);
            return true;
        }
    }

    public bool RemoveCollection(string id)
    {
        lock (_gate)
        {
            var collection = FindCollection(id);
            if (collection is null) return false;
            _settings.Collections.Remove(collection);
            _settings.RecentSlideshows.RemoveAll(item => string.Equals(item.FolderPath, collection.FolderPath, StringComparison.OrdinalIgnoreCase));
            _collectionCatalogs.Remove(id);
            if (string.Equals(_settings.FolderPath, collection.FolderPath, StringComparison.OrdinalIgnoreCase))
                _settings.FolderPath = _settings.Collections.FirstOrDefault()?.FolderPath;
            _settingsStore.Save(_settings);
            return true;
        }
    }

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

    public void UpdateSettings(SettingsUpdateDto update, string? collectionId = null)
    {
        lock (_gate)
        {
            var rescan = false;
            if (update.FolderPath is not null)
            {
                _settings.FolderPath = string.IsNullOrWhiteSpace(update.FolderPath) ? null : update.FolderPath.Trim();
                if (_settings.FolderPath is not null && FindCollection(null) is null)
                {
                    var added = new PhotoCollection { FolderPath = _settings.FolderPath,
                        Name = RecentSlideshow.DisplayNameFor(_settings.FolderPath) };
                    added.ReadPlayback(_settings);
                    _settings.Collections.Add(added);
                }
                collectionId = FindCollection(null)?.Id;
                RememberCurrentSlideshow(_settings);
                rescan = true;
            }
            var effective = GetSettings(collectionId);
            var collection = FindCollection(collectionId);
            rescan |= update.IncludeSubfolders.HasValue && update.IncludeSubfolders != effective.IncludeSubfolders;
            if (update.IncludeSubfolders.HasValue) effective.IncludeSubfolders = update.IncludeSubfolders.Value;
            if (update.SlideSeconds.HasValue) effective.SlideSeconds = update.SlideSeconds.Value;
            if (update.BackgroundColor is not null) effective.BackgroundColor = update.BackgroundColor;
            if (update.ImageMode is not null) effective.ImageMode = update.ImageMode;
            if (update.PlaybackOrder is not null) effective.PlaybackOrder = update.PlaybackOrder;
            effective.Normalize();
            collection?.ReadPlayback(effective);
            if (collection is null || string.Equals(collection.FolderPath, _settings.FolderPath, StringComparison.OrdinalIgnoreCase))
            {
                _settings.IncludeSubfolders = effective.IncludeSubfolders;
                _settings.SlideSeconds = effective.SlideSeconds;
                _settings.BackgroundColor = effective.BackgroundColor;
                _settings.ImageMode = effective.ImageMode;
                _settings.PlaybackOrder = effective.PlaybackOrder;
            }
            if (update.SyncWorkers.HasValue) _settings.SyncWorkers = update.SyncWorkers.Value;
            if (update.StartAtLogin.HasValue) _settings.StartAtLogin = update.StartAtLogin.Value;
            _settingsStore.Save(_settings);
            if (rescan) CatalogFor(collectionId).Scan(effective);
        }
    }

    public void UpdatePlaybackSettings(PlaybackSettingsUpdateDto update, string? collectionId = null) =>
        UpdateSettings(new SettingsUpdateDto { SlideSeconds = update.SlideSeconds,
            BackgroundColor = update.BackgroundColor, ImageMode = update.ImageMode, PlaybackOrder = update.PlaybackOrder }, collectionId);

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
            .OrderByDescending(adapter => adapter.GetIPProperties().GatewayAddresses.Any(gateway => !gateway.Address.Equals(IPAddress.Any)))
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
    RecentSlideshowDto[] RecentSlideshows,
    string PlaybackOrder,
    string ComputerName,
    int ConnectedViewers,
    string? CollectionId,
    bool Shared,
    CollectionDto[] Collections);

public sealed record CollectionDto(string Id, string Name, int ImageCount, bool Shared, string? PreviewUrl, string? FolderPath, bool Available);

public sealed class CollectionUpdateDto
{
    public string? Name { get; set; }
    public bool? Shared { get; set; }
}

public sealed record RecentSlideshowDto(string FolderPath, string FolderName, DateTimeOffset LastUsedAt);

public sealed record ImageDto(int Id, string Name, string Url, string CacheKey, long SizeBytes, long ModifiedAt);

public sealed record OfflineSourceDto(StateDto State, IReadOnlyList<ImageDto> Images);

public sealed class SettingsUpdateDto
{
    public string? FolderPath { get; set; }
    public bool? IncludeSubfolders { get; set; }
    public int? SlideSeconds { get; set; }
    public string? BackgroundColor { get; set; }
    public string? ImageMode { get; set; }
    public string? PlaybackOrder { get; set; }
    public int? SyncWorkers { get; set; }
    public bool? StartAtLogin { get; set; }
}

public sealed class PlaybackSettingsUpdateDto
{
    public int? SlideSeconds { get; set; }
    public string? BackgroundColor { get; set; }
    public string? ImageMode { get; set; }
    public string? PlaybackOrder { get; set; }
}

public sealed record ViewerHeartbeatDto(bool Active, string? ViewerId = null);
