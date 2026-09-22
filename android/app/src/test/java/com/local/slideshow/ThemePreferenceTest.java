package com.local.slideshow;

import static org.junit.Assert.*;
import org.junit.Test;

public class ThemePreferenceTest {
    @Test public void followsSystemByDefaultAndHonorsExplicitOverrides() {
        for (boolean systemDark : new boolean[]{false, true}) {
            for (String preference : new String[]{null, "system", "invalid"}) {
                assertEquals("system", ThemePreference.normalize(preference));
                assertEquals(systemDark, ThemePreference.isDark(preference, systemDark));
            }
            assertTrue(ThemePreference.isDark("dark", systemDark));
            assertFalse(ThemePreference.isDark("light", systemDark));
        }
    }
}
