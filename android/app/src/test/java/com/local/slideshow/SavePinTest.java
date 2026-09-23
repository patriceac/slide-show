package com.local.slideshow;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class SavePinTest {
    private OfflineImageStore.OfflineCatalog catalog(String identity, boolean protectedCopy) {
        return new OfflineImageStore.OfflineCatalog(1, "pc:5177", "PC", identity, "Photos", "Photos",
            "#000000", "fit", 7, 1, 0, 0, "", protectedCopy ? "salt" : "", protectedCopy ? "hash" : "", Collections.emptyList());
    }

    @Test public void newSavesAndUnprotectedUpdatesOfferAPin() {
        assertFalse(OfflineImageStore.hasMatchingPin(null, "pc:5177\nC:\\Photos", "pc:5177\nPhotos"));
        assertFalse(OfflineImageStore.hasMatchingPin(catalog("pc:5177\nC:\\Photos", false), "pc:5177\nC:\\Photos", "pc:5177\nPhotos"));
    }

    @Test public void protectedUpdatesKeepTheirPinIncludingLegacyCopies() {
        assertTrue(OfflineImageStore.hasMatchingPin(catalog("pc:5177\nC:\\Photos", true), "pc:5177\nC:\\Photos", "pc:5177\nPhotos"));
        assertTrue(OfflineImageStore.hasMatchingPin(catalog("pc:5177\nPhotos", true), "pc:5177\nC:\\Photos", "pc:5177\nPhotos"));
    }

    @Test public void replacementsOfferANewPinInsteadOfInheritingTheOldOne() {
        assertFalse(OfflineImageStore.hasMatchingPin(catalog("pc:5177\nC:\\Other", true), "pc:5177\nC:\\Photos", "pc:5177\nPhotos"));
        assertFalse(OfflineImageStore.hasMatchingPin(catalog("other-pc:5177\nPhotos", true), "pc:5177\nC:\\Photos", "pc:5177\nPhotos"));
    }
}
