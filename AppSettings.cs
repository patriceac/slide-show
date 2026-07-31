namespace SlideShow;

public sealed class AppSettings
{
    public string? FolderPath { get; set; }
    public List<RecentSlideshow> RecentSlideshows { get; set; } = [];
    public bool IncludeSubfolders { get; set; } = true;
    public int SlideSeconds { get; set; } = 7;
    public string BackgroundColor { get; set; } = "#05070a";
    public string ImageMode { get; set; } = "fit";
    public int SyncWorkers { get; set; } = 4;
    public int Port { get; set; } = 5177;
    public int HttpsPort { get; set; } = 5178;
    public bool StartAtLogin { get; set; } = true;

    public AppSettings Copy() => new()
    {
        FolderPath = FolderPath,
        RecentSlideshows = RecentSlideshows
            .Select(slideshow => slideshow.Copy())
            .ToList(),
        IncludeSubfolders = IncludeSubfolders,
        SlideSeconds = SlideSeconds,
        BackgroundColor = BackgroundColor,
        ImageMode = ImageMode,
        SyncWorkers = SyncWorkers,
        Port = Port,
        HttpsPort = HttpsPort,
        StartAtLogin = StartAtLogin
    };

    public void Normalize()
    {
        SlideSeconds = Math.Clamp(SlideSeconds, 2, 120);
        SyncWorkers = Math.Clamp(SyncWorkers, 2, 4);
        Port = Math.Clamp(Port, 1024, 65535);
        HttpsPort = Math.Clamp(HttpsPort, 1024, 65535);
        if (HttpsPort == Port)
        {
            HttpsPort = Math.Clamp(Port + 1, 1024, 65535);
        }

        if (string.IsNullOrWhiteSpace(BackgroundColor) || !BackgroundColor.StartsWith('#') || BackgroundColor.Length is not (4 or 7))
        {
            BackgroundColor = "#05070a";
        }

        ImageMode = string.Equals(ImageMode, "full", StringComparison.OrdinalIgnoreCase) ? "full" : "fit";

        if (string.IsNullOrWhiteSpace(FolderPath))
        {
            FolderPath = null;
        }
        else
        {
            FolderPath = FolderPath.Trim();
        }

        RecentSlideshows = RecentSlideshows
            .Where(slideshow => !string.IsNullOrWhiteSpace(slideshow.FolderPath))
            .Select(slideshow =>
            {
                var copy = slideshow.Copy();
                copy.Normalize();
                return copy;
            })
            .GroupBy(slideshow => slideshow.FolderPath, StringComparer.OrdinalIgnoreCase)
            .Select(group => group.OrderByDescending(slideshow => slideshow.LastUsedAt).First())
            .OrderByDescending(slideshow => slideshow.LastUsedAt)
            .Take(8)
            .ToList();
    }
}

public sealed class RecentSlideshow
{
    public string FolderPath { get; set; } = "";
    public string? FolderName { get; set; }
    public DateTimeOffset LastUsedAt { get; set; } = DateTimeOffset.UtcNow;

    public RecentSlideshow Copy() => new()
    {
        FolderPath = FolderPath,
        FolderName = FolderName,
        LastUsedAt = LastUsedAt
    };

    public void Normalize()
    {
        FolderPath = FolderPath.Trim();
        FolderName = string.IsNullOrWhiteSpace(FolderName)
            ? DisplayNameFor(FolderPath)
            : FolderName.Trim();
    }

    public static string DisplayNameFor(string folderPath)
    {
        var trimmed = folderPath.Trim().TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        var name = Path.GetFileName(trimmed);
        return string.IsNullOrWhiteSpace(name) ? trimmed : name;
    }
}
