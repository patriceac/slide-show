namespace SlideShow;

public sealed class AppSettings
{
    public string? FolderPath { get; set; }
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
    }
}
