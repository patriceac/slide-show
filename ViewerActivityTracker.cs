using System.Collections.Concurrent;

namespace SlideShow;

public sealed class ViewerActivityTracker
{
    private static readonly TimeSpan ActiveWindow = TimeSpan.FromSeconds(55);
    private readonly ConcurrentDictionary<string, DateTimeOffset> _viewers = new();

    public void Record(string viewerKey)
    {
        _viewers[viewerKey] = DateTimeOffset.UtcNow;
        Prune();
    }

    public void Clear(string viewerKey) => _viewers.TryRemove(viewerKey, out _);

    public bool HasActiveRemoteViewer()
    {
        Prune();
        return !_viewers.IsEmpty;
    }

    private void Prune()
    {
        var cutoff = DateTimeOffset.UtcNow - ActiveWindow;
        foreach (var pair in _viewers)
        {
            if (pair.Value < cutoff)
            {
                _viewers.TryRemove(pair.Key, out _);
            }
        }
    }
}
