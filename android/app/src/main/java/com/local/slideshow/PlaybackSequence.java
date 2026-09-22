package com.local.slideshow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class PlaybackSequence {
    interface Photo<T> { String key(T photo); String name(T photo); long date(T photo); }

    static <T> List<T> arrange(List<T> photos, String order, List<String> savedKeys, Photo<T> reader) {
        List<T> result = new ArrayList<>(photos);
        if ("name".equals(order)) result.sort((a,b) -> compareNames(reader.name(a), reader.name(b)));
        else if ("date".equals(order)) result.sort((a,b) -> {
            int date = Long.compare(reader.date(a), reader.date(b));
            return date == 0 ? compareNames(reader.name(a), reader.name(b)) : date;
        });
        else {
            Map<String,T> byKey = new HashMap<>();
            for (T photo : photos) byKey.put(reader.key(photo), photo);
            result.clear();
            for (String key : savedKeys) { T photo = byKey.remove(key); if (photo != null) result.add(photo); }
            List<T> added = new ArrayList<>(byKey.values());
            Collections.shuffle(added); result.addAll(added);
        }
        return result;
    }

    static int compareNames(String a, String b) {
        String[] left = a.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        String[] right = b.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        for (int i=0; i<Math.min(left.length,right.length); i++) {
            int difference = left[i].matches("\\d+") && right[i].matches("\\d+")
                ? new java.math.BigInteger(left[i]).compareTo(new java.math.BigInteger(right[i])) : left[i].compareToIgnoreCase(right[i]);
            if (difference != 0) return difference;
        }
        return Integer.compare(left.length,right.length);
    }
}
