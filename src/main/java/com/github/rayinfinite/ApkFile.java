package com.github.rayinfinite;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Simplified ApkFile for reading basic metadata and manifest XML.
 * Combines manifest decoding and APK file access in a single entry point.
 */
public final class ApkFile {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    public static final String MANIFEST_PATH = "AndroidManifest.xml";
    public static final String RESOURCE_FILE = "resources.arsc";
    private static volatile boolean attributeValueMappingEnabled = true;

    private final File apkFile;
    private final boolean resolveToValue;
    private String manifestXml;
    private ApkMeta apkMeta;

    public ApkFile(File apkFile) {
        this(apkFile, true);
    }

    public ApkFile(File apkFile, boolean resolveToValue) {
        if (apkFile == null) {
            throw new IllegalArgumentException("apkFile is null");
        }
        this.apkFile = apkFile;
        this.resolveToValue = resolveToValue;
    }

    public String getManifestXml() {
        if (manifestXml == null) {
            manifestXml = decodeWithResources(apkFile, resolveToValue);
        }
        return manifestXml;
    }

    public ApkMeta getApkMeta() {
        if (apkMeta == null) {
            apkMeta = parseApkMeta(getManifestXml());
        }
        return apkMeta;
    }

    public static boolean isAttributeValueMappingEnabled() {
        return attributeValueMappingEnabled;
    }

    public static void setAttributeValueMappingEnabled(boolean enabled) {
        attributeValueMappingEnabled = enabled;
    }

    public static String decode(File apkFile) {
        return decode(apkFile, 1);
    }

    /**
     * Decode type:
     * 0: without resources
     * 1: resolve to resource id or value depending on resolver
     * 2: resolve using resources.arsc and Locale.getDefault()
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
            throw new IllegalArgumentException("Manifest file not found: " + MANIFEST_PATH);
        }
        byte[] resourcesBytes = files.get(RESOURCE_FILE);
        return decodeFromManifest(manifestBytes, ResourceTableParser.fromResources(resourcesBytes, resolveToValue));
    }

    /**
     * Decode manifest bytes with a custom resource resolver.
     */
    public static String decode(File apkFile, ManifestXmlDecoder.ResourceResolver resolver) {
        Map<String, byte[]> files = readFiles(apkFile, MANIFEST_PATH);
        byte[] manifestBytes = files.get(MANIFEST_PATH);
        if (manifestBytes == null) {
            throw new IllegalArgumentException("Manifest file not found: " + MANIFEST_PATH);
        }
        return decodeFromManifest(manifestBytes, resolver);
    }

    /**
     * Read specific entries from an APK zip into memory.
     */
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
            throw new IllegalArgumentException(e);
        }
        return result;
    }

    public static String decodeFromManifest(byte[] manifestBytes) {
        return decodeFromManifest(manifestBytes, null);
    }

    /**
     * Decode a binary AndroidManifest.xml payload.
     */
    public static String decodeFromManifest(byte[] manifestBytes, ManifestXmlDecoder.ResourceResolver resolver) {
        if (attributeValueMappingEnabled) {
            ManifestXmlDecoder.setAttributeValueMapper(AttributeValueMapper::mapIfNeeded);
        }
        return ManifestXmlDecoder.decodeFromManifest(manifestBytes, resolver);
    }

    private ApkMeta parseApkMeta(String xml) {
        Document doc = parseXml(xml);
        Element manifest = doc.getDocumentElement();

        ApkMeta meta = new ApkMeta();
        meta.setPackageName(manifest.getAttribute("package"));
        meta.setVersionName(getAndroidAttr(manifest, "versionName"));
        meta.setVersionCode(parseLong(getAndroidAttr(manifest, "versionCode")));

        Element usesSdk = firstChild(manifest, "uses-sdk");
        if (usesSdk != null) {
            meta.setMinSdkVersion(getAndroidAttr(usesSdk, "minSdkVersion"));
            meta.setTargetSdkVersion(getAndroidAttr(usesSdk, "targetSdkVersion"));
        }

        Element application = firstChild(manifest, "application");
        if (application != null) {
            meta.setLabel(getAndroidAttr(application, "label"));
            meta.setApplicationName(getAndroidAttr(application, "name"));
            meta.setIcon(getAndroidAttr(application, "icon"));
        }

        NodeList permissions = manifest.getElementsByTagName("uses-permission");
        for (int i = 0; i < permissions.getLength(); i++) {
            Element element = (Element) permissions.item(i);
            meta.addUsesPermission(getAndroidAttr(element, "name"));
        }

        return meta;
    }

    private static Element firstChild(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return (Element) nodes.item(0);
    }

    private static String getAndroidAttr(Element element, String localName) {
        String value = element.getAttributeNS(ANDROID_NS, localName);
        if (value == null || value.isEmpty()) {
            value = element.getAttribute("android:" + localName);
        }
        return value;
    }

    private static Long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    private static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (ParserConfigurationException ignore) {
                // ignore if not supported
            }
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse manifest xml", e);
        }
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
