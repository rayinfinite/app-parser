package com.github.rayinfinite;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * Basic APK metadata parsed from AndroidManifest.xml.
 */
@Getter
@Setter
@ToString
public class ApkMeta {
    private String packageName;
    private String label;
    private String applicationName;
    private String icon;
    private String versionName;
    private Long versionCode;
    private String minSdkVersion;
    private String targetSdkVersion;
    private final List<String> usesPermissions = new ArrayList<>();

    public void addUsesPermission(String permission) {
        if (permission == null || permission.isEmpty()) {
            return;
        }
        usesPermissions.add(permission);
    }
}
