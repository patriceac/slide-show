using SlideShow;
using Xunit;

public class WorkflowTests
{
    [Fact]
    public void PlaybackAndStartupChangesDoNotRescanTheLibrary()
    {
        var directory = Path.Combine(Path.GetTempPath(), "slideshow-state-test-" + Guid.NewGuid());
        Directory.CreateDirectory(directory);
        try
        {
            var state = new AppState(new SettingsStore(Path.Combine(directory, "settings.json")), new ViewerActivityTracker());
            state.Rescan();
            var scannedAt = state.GetSnapshot(true).LastScannedAt;
            state.UpdateSettings(new SettingsUpdateDto { SlideSeconds = 42, StartAtLogin = true });
            state.UpdatePlaybackSettings(new PlaybackSettingsUpdateDto { PlaybackOrder = "name" });
            var snapshot = state.GetSnapshot(true);
            Assert.Equal(scannedAt, snapshot.LastScannedAt);
            Assert.Equal(42, snapshot.SlideSeconds);
            Assert.True(snapshot.StartAtLogin);
            Assert.Equal("name", snapshot.PlaybackOrder);
        }
        finally { Directory.Delete(directory, true); }
    }

    [Fact]
    public void CheckingAnUnchangedFolderDoesNotRestartViewers()
    {
        var folder = Path.Combine(Path.GetTempPath(), "slideshow-test-" + Guid.NewGuid());
        Directory.CreateDirectory(folder);
        try
        {
            var path = Path.Combine(folder, "photo.jpg");
            File.WriteAllBytes(path, [1, 2, 3]);
            var catalog = new ImageCatalog();
            var settings = new AppSettings { FolderPath = folder };
            catalog.Scan(settings);
            var version = catalog.Version;
            var key = ImageCatalog.GetCacheKey(catalog.GetById(0)!);
            catalog.Scan(settings);
            Assert.Equal(version, catalog.Version);
            Assert.Equal(key, ImageCatalog.GetCacheKey(catalog.GetById(0)!));
            File.WriteAllBytes(path, [1, 2, 3, 4]);
            catalog.Scan(settings);
            Assert.True(catalog.Version > version);
            Assert.NotEqual(key, ImageCatalog.GetCacheKey(catalog.GetById(0)!));
            File.Delete(path);
            catalog.Scan(settings);
            Assert.Equal(0, catalog.Count);
            Assert.Contains("No supported photos", catalog.ScanMessage);
        }
        finally { Directory.Delete(folder, true); }
    }

    [Theory]
    [InlineData("name", "name")]
    [InlineData("date", "date")]
    [InlineData("invalid", "shuffle")]
    public void PlaybackOrderSurvivesCopyAndNormalizes(string input, string expected)
    {
        var settings = new AppSettings { PlaybackOrder = input };
        settings.Normalize();
        Assert.Equal(expected, settings.Copy().PlaybackOrder);
    }

    [Fact]
    public void LocalPlaybackHoldsTheDisplayWithoutCountingAsARemoteDevice()
    {
        var viewers = new ViewerActivityTracker();
        viewers.Record("local", true);
        Assert.True(viewers.HasActiveLocalViewer());
        Assert.False(viewers.HasActiveRemoteViewer());
        viewers.Record("phone");
        viewers.Record("phone");
        Assert.Equal(1, viewers.RemoteViewerCount);
        viewers.Clear("phone");
        Assert.False(viewers.HasActiveRemoteViewer());
    }
}
