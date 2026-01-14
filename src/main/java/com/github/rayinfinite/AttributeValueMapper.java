package com.github.rayinfinite;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

final class AttributeValueMapper {
    private static final Map<String, Function<Integer, String>> MAPPERS = new ConcurrentHashMap<>();
    private static final String[] SCREEN_ORIENTATION = {"landscape", "portrait", "user", "behind", "sensor",
            "nosensor", "sensorLandscape", "sensorPortrait", "reverseLandscape", "reversePortrait", "fullSensor",
            "userLandscape", "userPortrait", "fullUser", "locked"};
    private static final String[] LAUNCH_MODE = {"standard", "singleTop", "singleTask", "singleInstance"};
    private static final String[] DOCUMENT_LAUNCH_MODE = {"none", "intoExisting", "always", "never"};
    private static final String[] INSTALL_LOCATION = {"auto", "internalOnly", "preferExternal"};
    private static final String[] WINDOW_INPUT_STATE = {"", "stateUnchanged", "stateHidden",
            "stateAlwaysHidden", "stateVisible", "stateAlwaysVisible", "stateUnspecified"};
    private static final String[] WINDOW_INPUT_ADJUST = {"", "adjustResize", "adjustPan", "adjustNothing"};
    // 按 CONFIG_* 位索引升序排列的名字列表（索引 i 对应 bit i，即 mask = 1 << i）
    private static final String[] CONFIG_CHANGES = {"mcc", "mnc", "locale", "touchscreen", "keyboard",
            "keyboardHidden", "navigation", "orientation", "screenLayout", "uiMode", "screenSize",
            "smallestScreenSize", "density", "direction"};

    static {
        register("screenOrientation", AttributeValueMapper::getScreenOrientation);
        register("configChanges", AttributeValueMapper::getConfigChanges);
        register("windowSoftInputMode", AttributeValueMapper::getWindowSoftInputMode);
        register("launchMode", value -> mapByIndex(LAUNCH_MODE, value, "LaunchMode:"));
        register("documentLaunchMode", value -> mapByIndex(DOCUMENT_LAUNCH_MODE, value, "DocumentLaunchMode:"));
        register("installLocation", value -> mapByIndex(INSTALL_LOCATION, value, "installLocation:"));
        register("protectionLevel", AttributeValueMapper::getProtectionLevel);
    }

    private AttributeValueMapper() {
    }

    static String mapIfNeeded(String attributeName, String value) {
        if (attributeName == null || value == null || attributeName.isEmpty()) {
            return value;
        }
        try {
            int intValue;
            if (isNumeric(value)) {
                intValue = Integer.parseInt(value);
            } else if (isHex(value)) {
                intValue = Integer.parseInt(value.substring(2), 16);
            } else {
                return value;
            }
            Function<Integer, String> mapper = MAPPERS.get(attributeName);
            if (mapper == null) {
                return value;
            }
            return mapper.apply(intValue);
        } catch (RuntimeException ignore) {
            return value;
        }
    }

    static void register(String attributeName, Function<Integer, String> mapper) {
        if (attributeName == null || mapper == null) {
            return;
        }
        MAPPERS.put(attributeName, mapper);
    }

    private static String getScreenOrientation(int value) {
        if (value == 0xffffffff) {
            return "unspecified";
        }
        return mapByIndex(SCREEN_ORIENTATION, value, "ScreenOrientation:");
    }

    private static String mapByIndex(String[] values, int value, String fallbackPrefix) {
        if (value >= 0 && value < values.length) {
            return values[value];
        }
        return fallbackPrefix + Integer.toHexString(value);
    }

    private static String getConfigChanges(int value) {
        List<String> parts = new ArrayList<>();

        // 遍历所有已定义的低比特位（0 ～ 13）
        for (int i = 0; i < CONFIG_CHANGES.length; i++) {
            if ((value & (1 << i)) != 0) {
                parts.add(CONFIG_CHANGES[i]);
            }
        }

        // 单独处理 fontScale：它位于 bit 30
        if ((value & 0x40000000) != 0) {
            parts.add("fontScale");
        }

        return join(parts);
    }

    private static String getWindowSoftInputMode(int value) {
        final int adjust = (value >> 4) & 0x0F; // bits 4–7 → adjust flags
        final int state = value & 0x0F;         // bits 0–3 → state flags

        List<String> list = new ArrayList<>(2);

        // State flags
        addFlagByIndex(list, WINDOW_INPUT_STATE, state, "WindowInputModeState:");

        // Adjust flags
        addFlagByIndex(list, WINDOW_INPUT_ADJUST, adjust, "WindowInputModeAdjust:");

        return join(list);
    }

    private static String getProtectionLevel(int value) {
        List<String> levels = new ArrayList<String>(3);
        if ((value & 0x10) != 0) {
            value = value ^ 0x10;
            levels.add("system");
        }
        if ((value & 0x20) != 0) {
            value = value ^ 0x20;
            levels.add("development");
        }
        switch (value) {
            case 0:
                levels.add("normal");
                break;
            case 1:
                levels.add("dangerous");
                break;
            case 2:
                levels.add("signature");
                break;
            case 3:
                levels.add("signatureOrSystem");
                break;
            default:
                levels.add("ProtectionLevel:" + Integer.toHexString(value));
        }
        return join(levels);
    }

    private static boolean isNumeric(String value) {
        return value.chars().allMatch(Character::isDigit);
    }

    public static boolean isHex(String value) {
        // 去除可选前缀
        if (value.startsWith("0x") || value.startsWith("0X")) {
            value = value.substring(2);
        }
        if (value.isEmpty()) {
            return false;
        }
        // 检查每个字符是否为 [0-9a-fA-F]
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f') && (c < 'A' || c > 'F')) {
                return false;
            }
        }
        return true;
    }

    private static String join(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        return String.join("|", list);
    }

    private static void addFlagByIndex(List<String> list, String[] values, int value, String fallbackPrefix) {
        if (value == 0) {
            return;
        }
        if (value > 0 && value < values.length && !values[value].isEmpty()) {
            list.add(values[value]);
            return;
        }
        list.add(fallbackPrefix + Integer.toHexString(value));
    }
}
