package com.local.slideshow;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class CollectionSourceTest {
    @Test public void collectionsKeepSeparateRoutesAndTheSameOfflineServerIdentity() {
        MainActivity.ServerInfo first = new MainActivity.ServerInfo("PC", "192.168.1.2", 5177);
        MainActivity.ServerInfo second = new MainActivity.ServerInfo("PC", "192.168.1.2", 5177);
        first.collectionId = "alpine";
        second.collectionId = "city";
        assertEquals(first.key(), second.key());
        assertEquals("192.168.1.2:5177", first.key());
        assertEquals("http://192.168.1.2:5177/api/images?shuffle=false&collection=alpine", first.sourceUrl("/api/images?shuffle=false"));
        assertEquals("http://192.168.1.2:5177/api/playback-settings?collection=city", second.sourceUrl("/api/playback-settings"));
    }

    @Test public void savedCopyEditsPreserveCollectionIdentityAndProtection() {
        OfflineImageStore.OfflineCatalog original = new OfflineImageStore.OfflineCatalog(
            1, "pc:5177", "PC", "pc:5177\nphotos", "Photos", "Photos", "#000000", "fit", 7,
            3, 100, 20, "v2", "salt", "hash", Collections.emptyList()).withOrder("date").withCollectionId("alpine");
        OfflineImageStore.OfflineCatalog changed = original.withDisplayName("Holiday").withSize(40);
        assertEquals("alpine", changed.collectionId);
        assertEquals(original.folderIdentity, changed.folderIdentity);
        assertEquals("date", changed.playbackOrder);
        assertEquals("salt", changed.pinSalt);
        assertEquals("hash", changed.pinHash);
        assertEquals("alpine", changed.withPin("new-salt", "new-hash").collectionId);
    }
}
