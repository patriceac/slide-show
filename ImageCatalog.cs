using System.Security.Cryptography;
using System.Text;

namespace SlideShow;

public sealed record ImageItem(int Id, string Path, string Name);

public sealed class ImageCatalog
{
    private static readonly HashSet<string> SupportedExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".avif",
        ".bmp",
        ".gif",
        ".jpeg",
        ".jpg",
        ".png",
        ".svg",
        ".webp"
    };

    private readonly object _gate = new();
    private List<ImageItem> _images = [];
    private long _version;

    public DateTimeOffset? LastScannedAt { get; private set; }
    public string? ScanMessage { get; private set; }

    public int Count
    {
        get
        {
            lock (_gate)
            {
                return _images.Count;
            }
        }
    }

    public long Version
    {
        get
        {
            lock (_gate)
            {
                return _version;
            }
        }
    }

    public ImageItem? GetById(int id)
    {
        lock (_gate)
        {
            return id >= 0 && id < _images.Count ? _images[id] : null;
        }
    }

    public IReadOnlyList<ImageItem> GetImages(bool shuffle)
    {
        lock (_gate)
        {
            var copy = _images.ToList();
            if (shuffle)
            {
                Shuffle(copy);
            }

            return copy;
        }
    }

    public void Scan(AppSettings settings)
    {
        var next = new List<ImageItem>();
        var message = settings.FolderPath is null ? "Choose a slideshow folder." : null;

        try
        {
            if (!string.IsNullOrWhiteSpace(settings.FolderPath) && Directory.Exists(settings.FolderPath))
            {
                var option = settings.IncludeSubfolders ? SearchOption.AllDirectories : SearchOption.TopDirectoryOnly;
                foreach (var file in Directory.EnumerateFiles(settings.FolderPath, "*", option))
                {
                    if (SupportedExtensions.Contains(System.IO.Path.GetExtension(file)))
                    {
                        next.Add(new ImageItem(next.Count, file, System.IO.Path.GetFileName(file)));
                    }
                }
            }
            else if (!string.IsNullOrWhiteSpace(settings.FolderPath))
            {
                message = "The selected folder could not be found.";
            }
        }
        catch (UnauthorizedAccessException)
        {
            message = "Some files could not be reached from this folder.";
        }
        catch (IOException)
        {
            message = "This folder could not be scanned right now.";
        }

        lock (_gate)
        {
            _images = next;
            _version++;
            LastScannedAt = DateTimeOffset.Now;
            ScanMessage = message;
        }
    }

    public static string GetContentType(string filePath)
    {
        return System.IO.Path.GetExtension(filePath).ToLowerInvariant() switch
        {
            ".avif" => "image/avif",
            ".bmp" => "image/bmp",
            ".gif" => "image/gif",
            ".jpeg" => "image/jpeg",
            ".jpg" => "image/jpeg",
            ".png" => "image/png",
            ".svg" => "image/svg+xml",
            ".webp" => "image/webp",
            _ => "application/octet-stream"
        };
    }

    public static string GetCacheKey(ImageItem image)
    {
        var info = new FileInfo(image.Path);
        var material = string.Join('\n',
            System.IO.Path.GetFullPath(image.Path).ToUpperInvariant(),
            info.Exists ? info.Length : 0,
            info.Exists ? info.LastWriteTimeUtc.Ticks : 0);
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(material))).ToLowerInvariant();
    }

    private static void Shuffle<T>(IList<T> items)
    {
        for (var i = items.Count - 1; i > 0; i--)
        {
            var j = RandomNumberGenerator.GetInt32(i + 1);
            (items[i], items[j]) = (items[j], items[i]);
        }
    }
}
