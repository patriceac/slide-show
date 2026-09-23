using SlideShow;
using Xunit;

public sealed class CollectionTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "slideshow-collections-" + Guid.NewGuid());
    private AppState CreateState() => new(new SettingsStore(Path.Combine(_root, "settings.json")), new ViewerActivityTracker());
    private string Folder(string name)
    {
        var path = Path.Combine(_root, name);
        Directory.CreateDirectory(path);
        File.WriteAllBytes(Path.Combine(path, name + ".jpg"), [1, 2, 3]);
        return path;
    }

    [Fact]
    public void DifferentViewersKeepTheirCollectionAndPlaybackSettings()
    {
        var state = CreateState();
        state.UpdateSettings(new SettingsUpdateDto { FolderPath = Folder("Alpine") });
        var alpine = state.GetSnapshot(true).CollectionId!;
        state.UpdateSettings(new SettingsUpdateDto { FolderPath = Folder("City") });
        var city = state.GetSnapshot(true).CollectionId!;
        state.UpdatePlaybackSettings(new PlaybackSettingsUpdateDto { SlideSeconds = 24 }, alpine);
        Assert.Equal("Alpine", state.GetSnapshot(false, alpine).FolderName);
        Assert.Equal(24, state.GetSnapshot(false, alpine).SlideSeconds);
        Assert.Equal(7, state.GetSnapshot(false, city).SlideSeconds);
        Assert.Contains("collection=" + alpine, Assert.Single(state.GetImages(false, alpine)).Url);
        Assert.EndsWith("Alpine.jpg", state.GetImage(0, alpine)!.Path);
        Assert.EndsWith("City.jpg", state.GetImage(0, city)!.Path);
        Assert.Contains("collection=" + alpine, state.GetSnapshot(true, alpine).DisplaySlideshowUrl);
    }

    [Fact]
    public void UnsharedOrMissingCollectionNeverFallsBackToAnotherCollection()
    {
        var state = CreateState();
        state.UpdateSettings(new SettingsUpdateDto { FolderPath = Folder("Alpine") });
        var id = state.GetSnapshot(true).CollectionId!;
        Assert.False(state.GetSnapshot(true, id).Shared);
        state.UpdateCollection(id, new CollectionUpdateDto { Shared = true });
        Assert.Single(state.GetCollections(false));
        state.UpdateCollection(id, new CollectionUpdateDto { Shared = false });
        Assert.Empty(state.GetCollections(false));
        Assert.True(state.CanAccessCollection(id, true));
        Assert.False(state.CanAccessCollection(id, false));
        Assert.False(state.CanAccessCollection(null, false));
        Assert.False(state.CanAccessCollection("missing", true));
        Assert.Throws<KeyNotFoundException>(() => state.GetImages(false, "missing"));
        state.RemoveCollection(id);
        Assert.False(state.CanAccessCollection(id, true));
    }

    [Fact]
    public void MigrationSharesOnlyCurrentFolderAndPersistsCollectionIdentity()
    {
        var current = Folder("Alpine");
        var previous = Folder("City");
        var store = new SettingsStore(Path.Combine(_root, "settings.json"));
        store.Save(new AppSettings { FolderPath = current, SlideSeconds = 19,
            RecentSlideshows = [new RecentSlideshow { FolderPath = previous }] });
        var state = CreateState();
        var shared = Assert.Single(state.GetCollections(false));
        Assert.Equal("Alpine", shared.Name);
        Assert.Equal(2, state.GetCollections(true).Length);
        Assert.Equal(shared.Id, Assert.Single(CreateState().GetCollections(false)).Id);
        state.UpdateCollection(shared.Id, new CollectionUpdateDto { Name = "Mountain walks" });
        Assert.Equal(current, state.GetSnapshot(false, shared.Id).FolderPath);
        Assert.Equal(19, state.GetSnapshot(false, shared.Id).SlideSeconds);
        Assert.Equal("Mountain walks", state.GetSnapshot(false, shared.Id).FolderName);
    }

    public void Dispose() { if (Directory.Exists(_root)) Directory.Delete(_root, true); }
}
