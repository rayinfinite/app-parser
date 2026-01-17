package com.github.rayinfinite;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads a minimal set of Android framework resources for style resolution.
 * Data source: {@code /r_styles.ini}.
 */
final class FrameworkResource {
    private static final int SYS_STYLE_ID_START = 0x01030000;
    private static final int SYS_STYLE_ID_END = 0x01031000;
    private static final Map<Integer, String> SYS_STYLE = loadSystemStyles();

    private FrameworkResource() {
    }

    /**
     * Resolve framework style ids to "@android:style/..." references.
     */
    static String resolveAndroidStyle(long resId) {
        if (resId <= SYS_STYLE_ID_START || resId >= SYS_STYLE_ID_END) {
            return null;
        }
        String name = SYS_STYLE.get((int) resId);
        if (name == null) {
            name = "0x" + Long.toHexString(resId);
        }
        return "@android:style/" + name;
    }

    private static Map<Integer, String> loadSystemStyles() {
        InputStream input = FrameworkResource.class.getResourceAsStream("/r_styles.ini");
        if (input == null) {
            return Collections.emptyMap();
        }
        Map<Integer, String> map = new HashMap<Integer, String>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] items = line.split("=");
                if (items.length != 2) {
                    continue;
                }
                String name = items[0].trim();
                String idText = items[1].trim();
                try {
                    Integer id = Integer.valueOf(idText);
                    map.put(id, name);
                } catch (NumberFormatException ignore) {
                    // skip invalid line
                }
            }
        } catch (IOException ignore) {
            return Collections.emptyMap();
        }
        return map;
    }
}
