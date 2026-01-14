package com.github.rayinfinite;

import lombok.Getter;
import lombok.Setter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class AndroidParser {
    public static final String MANIFEST_PATH = "AndroidManifest.xml";
    public static final String RESOURCE_FILE = "resources.arsc";
    @Getter
    @Setter
    private static volatile boolean attributeValueMappingEnabled = true;

    public static String decode(File apkFile) {
        return decode(apkFile, 1);
    }

    /**
     * decode type
     * 0: without resources
     * 1: resolve to param
     * 2: resolve to Locale.getDefault()
     */
    public static String decode(File apkFile, int type) {
        if (apkFile == null) {
            throw new IllegalArgumentException("apkFile is null");
        }
        switch (type) {
            case 0:
                return decode(apkFile, null);
            case 1:
                return decodeWithResources(apkFile, false);
            case 2:
                return decodeWithResources(apkFile, true);
            default:
                throw new IllegalArgumentException("wrong parse type");
        }
    }

    public static String decodeWithResources(File apkFile, boolean resolveToValue) {
        Map<String, byte[]> files = readFiles(apkFile, MANIFEST_PATH, RESOURCE_FILE);
        byte[] manifestBytes = files.get(MANIFEST_PATH);
        if (manifestBytes == null) {
            throw new ManifestXmlDecoder.ManifestXmlException("Manifest file not found: " + MANIFEST_PATH);
        }
        byte[] resourcesBytes = files.get(RESOURCE_FILE);
        return decodeFromManifest(manifestBytes, ResourceTableParser.fromResources(resourcesBytes, resolveToValue));
    }

    public static String decode(File apkFile, ManifestXmlDecoder.ResourceResolver resolver) {
        Map<String, byte[]> files = readFiles(apkFile, MANIFEST_PATH);
        byte[] manifestBytes = files.get(MANIFEST_PATH);
        if (manifestBytes == null) {
            throw new ManifestXmlDecoder.ManifestXmlException("Manifest file not found: " + MANIFEST_PATH);
        }
        return decodeFromManifest(manifestBytes, resolver);
    }

    public static Map<String, byte[]> readFiles(File apkFile, String... paths) {
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
        } catch (IOException e) {
            throw new ManifestXmlDecoder.ManifestXmlException(e);
        }
        return result;
    }

    public static String decodeFromManifest(byte[] manifestBytes) {
        return decodeFromManifest(manifestBytes, null);
    }

    public static String decodeFromManifest(byte[] manifestBytes, ManifestXmlDecoder.ResourceResolver resolver) {
        if (attributeValueMappingEnabled) {
            ManifestXmlDecoder.setAttributeValueMapper(AttributeValueMapper::mapIfNeeded);
        }
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
