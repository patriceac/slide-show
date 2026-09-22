using System.Collections.Concurrent;

namespace SlideShow;

public sealed class ViewerActivityTracker
{
    private static readonly TimeSpan ActiveWindow = TimeSpan.FromSeconds(55);
    private readonly ConcurrentDictionary<string, (DateTimeOffset SeenAt, bool IsLocal)> _viewers = new();

    public void Record(string viewerKey, bool isLocal = false)
    {
        _viewers[viewerKey] = (DateTimeOffset.UtcNow, isLocal);
        Prune();
    }

    public void Clear(string viewerKey) => _viewers.TryRemove(viewerKey, out _);

    public bool HasActiveRemoteViewer()
    {
        Prune();
        return _viewers.Values.Any(viewer => !viewer.IsLocal);
    }

    public bool HasActiveLocalViewer() { Prune(); return _viewers.Values.Any(viewer => viewer.IsLocal); }
    public int RemoteViewerCount { get { Prune(); return _viewers.Values.Count(viewer => !viewer.IsLocal); } }

    private void Prune()
    {
        var cutoff = DateTimeOffset.UtcNow - ActiveWindow;
        foreach (var pair in _viewers)
        {
            if (pair.Value.SeenAt < cutoff)
            {
                _viewers.TryRemove(pair.Key, out _);
            }
        }
    }
}
