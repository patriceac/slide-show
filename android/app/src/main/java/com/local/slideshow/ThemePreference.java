package com.local.slideshow;

final class ThemePreference {
    static String normalize(String preference) {
        return "light".equals(preference) || "dark".equals(preference) ? preference : "system";
    }

    static boolean isDark(String preference, boolean systemDark) {
        return "dark".equals(preference) || (!"light".equals(preference) && systemDark);
    }
}
