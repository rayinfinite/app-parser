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
        List<String> list = new ArrayList<String>();
        if ((value & 0x00001000) != 0) {
            list.add("density");
        }
        if ((value & 0x40000000) != 0) {
            list.add("fontScale");
        }
        if ((value & 0x00000010) != 0) {
            list.add("keyboard");
        }
        if ((value & 0x00000020) != 0) {
            list.add("keyboardHidden");
        }
        if ((value & 0x00002000) != 0) {
            list.add("direction");
        }
        if ((value & 0x00000004) != 0) {
            list.add("locale");
        }
        if ((value & 0x00000001) != 0) {
            list.add("mcc");
        }
        if ((value & 0x00000002) != 0) {
            list.add("mnc");
        }
        if ((value & 0x00000040) != 0) {
            list.add("navigation");
        }
        if ((value & 0x00000080) != 0) {
            list.add("orientation");
        }
        if ((value & 0x00000100) != 0) {
            list.add("screenLayout");
        }
        if ((value & 0x00000400) != 0) {
            list.add("screenSize");
        }
        if ((value & 0x00000800) != 0) {
            list.add("smallestScreenSize");
        }
        if ((value & 0x00000008) != 0) {
            list.add("touchscreen");
        }
        if ((value & 0x00000200) != 0) {
            list.add("uiMode");
        }
        return join(list, "|");
    }

    private static String getWindowSoftInputMode(int value) {
        int adjust = value & 0x000000f0;
        int state = value & 0x0000000f;
        List<String> list = new ArrayList<String>(2);
        switch (adjust) {
            case 0x00000030:
                list.add("adjustNothing");
                break;
            case 0x00000020:
                list.add("adjustPan");
                break;
            case 0x00000010:
                list.add("adjustResize");
                break;
            case 0x00000000:
                break;
            default:
                list.add("WindowInputModeAdjust:" + Integer.toHexString(adjust));
        }
        switch (state) {
            case 0x00000003:
                list.add("stateAlwaysHidden");
                break;
            case 0x00000005:
                list.add("stateAlwaysVisible");
                break;
            case 0x00000002:
                list.add("stateHidden");
                break;
            case 0x00000001:
                list.add("stateUnchanged");
                break;
            case 0x00000004:
                list.add("stateVisible");
                break;
            case 0x00000000:
                break;
            default:
                list.add("WindowInputModeState:" + Integer.toHexString(state));
        }
        return join(list, "|");
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
        return join(levels, "|");
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

    private static String join(List<String> items, String separator) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0 && separator != null) {
                sb.append(separator);
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
