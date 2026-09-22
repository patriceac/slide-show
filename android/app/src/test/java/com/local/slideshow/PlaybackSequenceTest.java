package com.local.slideshow;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PlaybackSequenceTest {
    private final PlaybackSequence.Photo<String> reader = new PlaybackSequence.Photo<String>() {
        public String key(String value) { return value; }
        public String name(String value) { return value; }
        public long date(String value) { return Long.parseLong(value.replace("Photo ", "")); }
    };
    @Test public void aRefreshPreservesShuffleWhileAppendingNewPhotos() {
        assertEquals(Arrays.asList("Photo 2","Photo 1","Photo 3"), PlaybackSequence.arrange(Arrays.asList("Photo 1","Photo 2","Photo 3"), "shuffle", Arrays.asList("Photo 2","removed","Photo 1"), reader));
    }
    @Test public void nameAndDateOrdersArePredictable() {
        for (String order : Arrays.asList("name","date")) assertEquals(Arrays.asList("Photo 2","Photo 10"), PlaybackSequence.arrange(Arrays.asList("Photo 10","Photo 2"),order,Collections.emptyList(),reader));
    }
}
