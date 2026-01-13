package com.github.rayinfinite;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

final class AttributeValueMapper {
    private static final Map<String, Function<Integer, String>> MAPPERS = new ConcurrentHashMap<>();

    static {
        register("screenOrientation", AttributeValueMapper::getScreenOrientation);
        register("configChanges", AttributeValueMapper::getConfigChanges);
        register("windowSoftInputMode", AttributeValueMapper::getWindowSoftInputMode);
        register("launchMode", AttributeValueMapper::getLaunchMode);
        register("installLocation", AttributeValueMapper::getInstallLocation);
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

    private static final String[] SCREEN_ORIENTATION = new String[]{
            "landscape",
            "portrait",
            "user",
            "behind",
            "sensor",
            "nosensor",
            "sensorLandscape",
            "sensorPortrait",
            "reverseLandscape",
            "reversePortrait",
            "fullSensor",
            "userLandscape",
            "userPortrait",
            "fullUser",
            "locked"
    };

    private static String getScreenOrientation(int value) {
        if (value == 0xffffffff) {
            return "unspecified";
        }
        if (value >= 0 && value < SCREEN_ORIENTATION.length) {
            return SCREEN_ORIENTATION[value];
        }
        return "ScreenOrientation:" + Integer.toHexString(value);
    }

    private static final String[] LAUNCH_MODE = new String[]{
            "standard",
            "singleTop",
            "singleTask",
            "singleInstance"
    };

    private static final String[] INSTALL_LOCATION = new String[]{
            "auto",
            "internalOnly",
            "preferExternal"
    };

    private static String mapByIndex(String[] values, int value, String fallbackPrefix) {
        if (value >= 0 && value < values.length) {
            return values[value];
        }
        return fallbackPrefix + Integer.toHexString(value);
    }

    private static String getLaunchMode(int value) {
        return mapByIndex(LAUNCH_MODE, value, "LaunchMode:");
    }

    private static String getConfigChanges(int value) {
        // 按 CONFIG_* 位索引升序排列的名字列表（索引 i 对应 bit i，即 mask = 1 << i）
        final String[] NAMES = {
                "mcc", "mnc", "locale", "touchscreen",
                "keyboard", "keyboardHidden", "navigation", "orientation",
                "screenLayout", "uiMode", "screenSize", "smallestScreenSize",
                "density", "direction"
        };

        List<String> parts = new ArrayList<>();

        // 遍历所有已定义的低比特位（0 ～ 13）
        for (int i = 0; i < NAMES.length; i++) {
            if ((value & (1 << i)) != 0) {
                parts.add(NAMES[i]);
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
        switch (state) {
            case 0x0:
                break;
            case 0x1:
                list.add("stateUnchanged");
                break;
            case 0x2:
                list.add("stateHidden");
                break;
            case 0x3:
                list.add("stateAlwaysHidden");
                break;
            case 0x4:
                list.add("stateVisible");
                break;
            case 0x5:
                list.add("stateAlwaysVisible");
                break;
            case 0x6:
                list.add("stateUnspecified");
                break;
            default:
                list.add("WindowInputModeState:" + Integer.toHexString(state));
        }

        // Adjust flags
        switch (adjust) {
            case 0x0:
                break;
            case 0x1:
                list.add("adjustResize");
                break;
            case 0x2:
                list.add("adjustPan");
                break;
            case 0x3:
                list.add("adjustNothing");
                break;
            default:
                list.add("WindowInputModeAdjust:" + Integer.toHexString(adjust));
        }

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

    private static String getInstallLocation(int value) {
        return mapByIndex(INSTALL_LOCATION, value, "installLocation:");
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
}
