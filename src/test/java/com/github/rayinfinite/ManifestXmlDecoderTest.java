package com.github.rayinfinite;

import net.dongliu.apk.parser.ApkFile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public class ManifestXmlDecoderTest {
    private static final List<String> APK_NAMES = Arrays.asList(
            "app-debug.apk",
            "app-release.apk",
            "NetworkStack_210000000.apk"
    );

    @Test
    public void manifestXmlMatchesOriginalParser() throws Exception {
        for (String apkName : APK_NAMES) {
            File apkFile = resourceFile(apkName);
            String expected;
            try (ApkFile parser = new ApkFile(apkFile)) {
                expected = parser.getManifestXml();
            }
            String actual = ManifestXmlDecoder.decodeFromApk(apkFile);
            Assertions.assertEquals(expected, actual, "Manifest mismatch: " + apkName);
        }
    }

    @Test
    public void manifestXmlTwitterParser() throws Exception {
        String apkName = "Twitter_v7.93.2.apk";
        File apkFile = resourceFile(apkName);
        String expected;
        try (ApkFile parser = new ApkFile(apkFile)) {
            expected = parser.getManifestXml();
        }
        String expectedTwitter = resourceFileString("OldTwitterAndroidManifest.xml");
        Assertions.assertEquals(expectedTwitter, expected, "Manifest mismatch: " + apkName);

        String actual = ManifestXmlDecoder.decodeFromApk(apkFile);
        String actualTwitter = resourceFileString("AndroidManifest.xml");
        Assertions.assertEquals(actualTwitter, actual, "Manifest mismatch: " + apkName);
    }

    @Test
    public void manifestXmlTwitterParserWithResources() throws Exception {
        String apkName = "Twitter_v7.93.2.apk";
        File apkFile = resourceFile(apkName);

        String actual = ManifestXmlDecoder.decodeFromApkWithResources(apkFile);
        String actualTwitter = resourceFileString("OldTwitterAndroidManifest.xml");
        Assertions.assertEquals(actualTwitter, actual, "Manifest mismatch: " + apkName);
    }

    private File resourceFile(String name) throws Exception {
        URL url = getClass().getClassLoader().getResource(name);
        Assertions.assertNotNull(url, "Missing resource: " + name);
        return new File(url.toURI());
    }

    private String resourceFileString(String name) throws Exception {
        return new String(Files.readAllBytes(resourceFile(name).toPath()), StandardCharsets.UTF_8).replace("    ", "\t");
    }
}
