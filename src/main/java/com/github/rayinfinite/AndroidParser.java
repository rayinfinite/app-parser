package com.github.rayinfinite;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class AndroidParser {
    public static final String MANIFEST_PATH = "AndroidManifest.xml";
    public static final String RESOURCE_FILE = "resources.arsc";

    public static String decodeFromApk(File apkFile) throws IOException {
        return decodeFromApk(apkFile, null);
    }

    public static String decodeFromApkWithResources(File apkFile) throws IOException {
        return decodeFromApkWithResources(apkFile, true);
    }

    public static String decodeFromApkWithResources(File apkFile, boolean resolveToValue) throws IOException {
        Map<String, byte[]> files = readFiles(apkFile, MANIFEST_PATH, RESOURCE_FILE);
        byte[] manifestBytes = files.get(MANIFEST_PATH);
        if (manifestBytes == null) {
            throw new ManifestXmlDecoder.ManifestXmlException("Manifest file not found: " + MANIFEST_PATH);
        }
        byte[] resourcesBytes = files.get(RESOURCE_FILE);
        return decodeFromManifest(manifestBytes, ResourceTableParser.fromResources(resourcesBytes, resolveToValue));
    }

    public static String decodeFromApk(File apkFile, ManifestXmlDecoder.ResourceResolver resolver) throws IOException {
        if (apkFile == null) {
            throw new IllegalArgumentException("apkFile is null");
        }
        Map<String, byte[]> files = readFiles(apkFile, MANIFEST_PATH);
        byte[] manifestBytes = files.get(MANIFEST_PATH);
        if (manifestBytes == null) {
            throw new ManifestXmlDecoder.ManifestXmlException("Manifest file not found: " + MANIFEST_PATH);
        }
        return decodeFromManifest(manifestBytes, resolver);
    }

    public static Map<String, byte[]> readFiles(File apkFile, String... paths) throws IOException {
        if (apkFile == null) {
            throw new IllegalArgumentException("apkFile is null");
        }
        Map<String, byte[]> result = new HashMap<>();
        if (paths == null || paths.length == 0) {
            return result;
        }
        try (ZipFile zipFile = new ZipFile(apkFile)) {
            for (String path : paths) {
                if (path == null) {
                    continue;
                }
                ZipEntry entry = zipFile.getEntry(path);
                if (entry == null) {
                    result.put(path, null);
                    continue;
                }
                try (InputStream inputStream = zipFile.getInputStream(entry)) {
                    result.put(path, readAllBytes(inputStream));
                }
            }
        }
        return result;
    }

    public static String decodeFromManifest(byte[] manifestBytes) {
        return decodeFromManifest(manifestBytes, null);
    }

    public static String decodeFromManifest(byte[] manifestBytes, ManifestXmlDecoder.ResourceResolver resolver) {
        return ManifestXmlDecoder.decodeFromManifest(manifestBytes, resolver);
    }

    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }
}
