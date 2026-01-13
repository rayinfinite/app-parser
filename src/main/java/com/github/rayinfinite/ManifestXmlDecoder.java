package com.github.rayinfinite;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ManifestXmlDecoder {
    public static final String MANIFEST_PATH = "AndroidManifest.xml";

    public static String decodeFromApk(File apkFile) throws IOException {
        return decodeFromApk(apkFile, null);
    }

    public static String decodeFromApkWithResources(File apkFile) throws IOException {
        return decodeFromApk(apkFile, ResourceTableParser.fromApk(apkFile));
    }

    public static String decodeFromApk(File apkFile, ResourceResolver resolver) throws IOException {
        if (apkFile == null) {
            throw new IllegalArgumentException("apkFile is null");
        }
        try (ZipFile zipFile = new ZipFile(apkFile)) {
            ZipEntry entry = zipFile.getEntry(MANIFEST_PATH);
            if (entry == null) {
                throw new ManifestXmlException("Manifest file not found: " + MANIFEST_PATH);
            }
            try (InputStream inputStream = zipFile.getInputStream(entry)) {
                byte[] manifestBytes = readAllBytes(inputStream);
                return decodeFromManifest(manifestBytes, resolver);
            }
        }
    }

    public static String decodeFromManifest(byte[] manifestBytes) {
        return decodeFromManifest(manifestBytes, null);
    }

    public static String decodeFromManifest(byte[] manifestBytes, ResourceResolver resolver) {
        if (manifestBytes == null) {
            throw new IllegalArgumentException("manifestBytes is null");
        }
        BinaryXmlParser parser = new BinaryXmlParser(manifestBytes, resolver);
        return parser.parse();
    }

    public interface ResourceResolver {
        default String resolveReference(long resId) {
            return null;
        }

        default String resolveAttributeName(long resId) {
            return null;
        }
    }

    private static final class BinaryXmlParser {
        private final ByteBuffer buffer;
        private final ResourceResolver resolver;
        private StringPool stringPool;
        private long[] resourceMap;
        private final XmlTranslator translator = new XmlTranslator();

        private BinaryXmlParser(byte[] data, ResourceResolver resolver) {
            this.buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            this.resolver = resolver;
        }

        private String parse() {
            ChunkHeader firstHeader = readChunkHeader();
            if (firstHeader == null) {
                return translator.getXml();
            }

            if (firstHeader.getChunkType() != ChunkType.XML && firstHeader.getChunkType() != ChunkType.NULL) {
                throw new ManifestXmlException("Unexpected first chunk: " + firstHeader.getChunkType());
            }

            ChunkHeader stringPoolHeader = readChunkHeader();
            if (!(stringPoolHeader instanceof StringPoolHeader)) {
                throw new ManifestXmlException("String pool chunk not found");
            }
            this.stringPool = readStringPool((StringPoolHeader) stringPoolHeader);

            ChunkHeader chunkHeader = readChunkHeader();
            if (chunkHeader == null) {
                return translator.getXml();
            }

            if (chunkHeader.getChunkType() == ChunkType.XML_RESOURCE_MAP) {
                resourceMap = readResourceMap(chunkHeader);
                chunkHeader = readChunkHeader();
            }

            while (chunkHeader != null) {
                int bodyStart = buffer.position();
                switch (chunkHeader.getChunkType()) {
                    case ChunkType.XML_START_NAMESPACE:
                        readNamespaceStart();
                        break;
                    case ChunkType.XML_END_NAMESPACE:
                        readNamespaceEnd();
                        break;
                    case ChunkType.XML_START_ELEMENT:
                        readStartTag();
                        break;
                    case ChunkType.XML_END_ELEMENT:
                        readEndTag();
                        break;
                    case ChunkType.XML_CDATA:
                        readCData();
                        break;
                    default:
                        if (chunkHeader.getChunkType() < ChunkType.XML_FIRST_CHUNK
                                || chunkHeader.getChunkType() > ChunkType.XML_LAST_CHUNK) {
                            throw new ManifestXmlException("Unexpected chunk: " + chunkHeader.getChunkType());
                        }
                        break;
                }
                position(buffer, bodyStart + chunkHeader.getBodySize());
                chunkHeader = readChunkHeader();
            }

            return translator.getXml();
        }

        private void readNamespaceStart() {
            int prefixRef = buffer.getInt();
            int uriRef = buffer.getInt();
            String prefix = getString(prefixRef);
            String uri = getString(uriRef);
            translator.onNamespaceStart(prefix, uri);
        }

        private void readNamespaceEnd() {
            int prefixRef = buffer.getInt();
            int uriRef = buffer.getInt();
            String prefix = getString(prefixRef);
            String uri = getString(uriRef);
            translator.onNamespaceEnd(prefix, uri);
        }

        private void readStartTag() {
            int nsRef = buffer.getInt();
            int nameRef = buffer.getInt();
            String namespace = getString(nsRef);
            String name = getString(nameRef);

            int attributeStart = readUShort(buffer);
            int attributeSize = readUShort(buffer);
            int attributeCount = readUShort(buffer);
            int idIndex = readUShort(buffer);
            int classIndex = readUShort(buffer);
            int styleIndex = readUShort(buffer);

            List<XmlAttribute> attributes = new ArrayList<>(attributeCount);
            for (int i = 0; i < attributeCount; i++) {
                attributes.add(readAttribute());
            }
            translator.onStartTag(namespace, name, attributes);
        }

        private void readEndTag() {
            int nsRef = buffer.getInt();
            int nameRef = buffer.getInt();
            String namespace = getString(nsRef);
            String name = getString(nameRef);
            translator.onEndTag(namespace, name);
        }

        private void readCData() {
            int dataRef = buffer.getInt();
            String data = getString(dataRef);
            readResValue(buffer);
            if (data != null) {
                translator.onCData(data);
            }
        }

        private XmlAttribute readAttribute() {
            int nsRef = buffer.getInt();
            int nameRef = buffer.getInt();
            int rawValueRef = buffer.getInt();

            String namespace = getString(nsRef);
            String name = getAttributeName(nameRef);
            String rawValue = rawValueRef >= 0 ? getString(rawValueRef) : null;
            ResValue resValue = readResValue(buffer);

            String value = rawValue != null ? rawValue : resValue.toStringValue(stringPool, resolver);
            return new XmlAttribute(namespace, name, value);
        }

        private String getAttributeName(int nameRef) {
            String name = getString(nameRef);
            if (name != null && !name.isEmpty()) {
                return name;
            }
            if (resourceMap != null && nameRef >= 0 && nameRef < resourceMap.length) {
                long resId = resourceMap[nameRef];
                if (resolver != null) {
                    String resolved = resolver.resolveAttributeName(resId);
                    if (resolved != null) {
                        return resolved;
                    }
                }
                return "AttrId:0x" + Long.toHexString(resId);
            }
            return name;
        }

        private String getString(int ref) {
            if (ref < 0 || stringPool == null) {
                return null;
            }
            return stringPool.get(ref);
        }

        private long[] readResourceMap(ChunkHeader header) {
            int count = header.getBodySize() / 4;
            long[] ids = new long[count];
            for (int i = 0; i < count; i++) {
                ids[i] = readUInt(buffer);
            }
            return ids;
        }

        private StringPool readStringPool(StringPoolHeader header) {
            long beginPos = buffer.position();
            int[] offsets = new int[header.getStringCount()];
            for (int i = 0; i < header.getStringCount(); i++) {
                offsets[i] = (int) readUInt(buffer);
            }
            boolean utf8 = (header.getFlags() & StringPoolHeader.UTF8_FLAG) != 0;

            long stringsStart = beginPos + header.getStringsStart() - header.getHeaderSize();
            position(buffer, stringsStart);

            StringPool pool = new StringPool(header.getStringCount());
            long lastOffset = -1;
            String lastValue = null;
            for (int i = 0; i < offsets.length; i++) {
                long offset = stringsStart + (offsets[i] & 0xffffffffL);
                if (offset == lastOffset) {
                    pool.set(i, lastValue);
                    continue;
                }
                position(buffer, offset);
                String value = readString(buffer, utf8);
                pool.set(i, value);
                lastOffset = offset;
                lastValue = value;
            }

            position(buffer, beginPos + header.getBodySize());
            return pool;
        }

        private ChunkHeader readChunkHeader() {
            if (!buffer.hasRemaining()) {
                return null;
            }
            int begin = buffer.position();
            int chunkType = readUShort(buffer);
            int headerSize = readUShort(buffer);
            long chunkSize = readUInt(buffer);

            switch (chunkType) {
                case ChunkType.STRING_POOL:
                    StringPoolHeader header = new StringPoolHeader(headerSize, chunkSize);
                    header.setStringCount(readUInt(buffer));
                    header.setStyleCount(readUInt(buffer));
                    header.setFlags(readUInt(buffer));
                    header.setStringsStart(readUInt(buffer));
                    header.setStylesStart(readUInt(buffer));
                    position(buffer, begin + headerSize);
                    return header;
                case ChunkType.XML_RESOURCE_MAP:
                    position(buffer, begin + headerSize);
                    return new ChunkHeader(chunkType, headerSize, chunkSize);
                case ChunkType.XML_START_NAMESPACE:
                case ChunkType.XML_END_NAMESPACE:
                case ChunkType.XML_START_ELEMENT:
                case ChunkType.XML_END_ELEMENT:
                case ChunkType.XML_CDATA:
                    readUInt(buffer);
                    readUInt(buffer);
                    position(buffer, begin + headerSize);
                    return new ChunkHeader(chunkType, headerSize, chunkSize);
                case ChunkType.XML:
                case ChunkType.NULL:
                    position(buffer, begin + headerSize);
                    return new ChunkHeader(chunkType, headerSize, chunkSize);
                default:
                    throw new ManifestXmlException("Unexpected chunk type: " + chunkType);
            }
        }
    }

    @Getter
    private static class ChunkHeader {
        private final int chunkType;
        private final int headerSize;
        private final int chunkSize;

        private ChunkHeader(int chunkType, int headerSize, long chunkSize) {
            this.chunkType = chunkType;
            this.headerSize = headerSize;
            this.chunkSize = ensureUInt(chunkSize);
        }

        public int getBodySize() {
            return chunkSize - headerSize;
        }
    }

    @Getter
    private static final class StringPoolHeader extends ChunkHeader {
        public static final int UTF8_FLAG = 1 << 8;

        private int stringCount;
        private int styleCount;
        private long flags;
        private long stringsStart;
        private long stylesStart;

        private StringPoolHeader(int headerSize, long chunkSize) {
            super(ChunkType.STRING_POOL, headerSize, chunkSize);
        }

        private void setStringCount(long stringCount) {
            this.stringCount = ensureUInt(stringCount);
        }

        private void setStyleCount(long styleCount) {
            this.styleCount = ensureUInt(styleCount);
        }

        private void setFlags(long flags) {
            this.flags = flags;
        }

        private void setStringsStart(long stringsStart) {
            this.stringsStart = stringsStart;
        }

        private void setStylesStart(long stylesStart) {
            this.stylesStart = stylesStart;
        }
    }

    @RequiredArgsConstructor
    private static final class XmlAttribute {
        private final String namespace;
        private final String name;
        private final String value;
    }

    @RequiredArgsConstructor
    private static final class ResValue {
        private final short dataType;
        private final int data;

        private String toStringValue(StringPool stringPool, ResourceResolver resolver) {
            switch (dataType) {
                case ResType.INT_DEC:
                    return Integer.toString(data);
                case ResType.INT_HEX:
                    return "0x" + Integer.toHexString(data);
                case ResType.INT_BOOLEAN:
                    return data != 0 ? "true" : "false";
                case ResType.STRING:
                    return data >= 0 && stringPool != null ? stringPool.get(data) : "";
                case ResType.REFERENCE:
                case ResType.ATTRIBUTE:
                    long resId = data & 0xffffffffL;
                    if (resolver != null) {
                        String resolved = resolver.resolveReference(resId);
                        if (resolved != null) {
                            return resolved;
                        }
                    }
                    return "@0x" + Long.toHexString(resId);
                case ResType.NULL:
                    return "";
                case ResType.INT_COLOR_ARGB8:
                    return String.format("#%08x", data);
                case ResType.INT_COLOR_RGB8:
                    return String.format("#%06x", data & 0x00ffffff);
                case ResType.INT_COLOR_ARGB4:
                    return String.format("#%04x", data & 0xffff);
                case ResType.INT_COLOR_RGB4:
                    return String.format("#%03x", data & 0x0fff);
                case ResType.DIMENSION:
                    return complexToFloat(data) + dimensionUnit(data);
                case ResType.FRACTION:
                    return complexToFloat(data) + fractionUnit(data);
                default:
                    return "{" + dataType + ":" + (data & 0xffffffffL) + "}";
            }
        }

        private float complexToFloat(int complex) {
            return (complex & 0xffffff00) / 256.0f;
        }

        private String dimensionUnit(int complex) {
            switch (complex & 0xf) {
                case ResValueUnits.UNIT_PX:
                    return "px";
                case ResValueUnits.UNIT_DIP:
                    return "dp";
                case ResValueUnits.UNIT_SP:
                    return "sp";
                case ResValueUnits.UNIT_PT:
                    return "pt";
                case ResValueUnits.UNIT_IN:
                    return "in";
                case ResValueUnits.UNIT_MM:
                    return "mm";
                default:
                    return "unknown";
            }
        }

        private String fractionUnit(int complex) {
            switch (complex & 0xf) {
                case ResValueUnits.UNIT_FRACTION:
                    return "%";
                case ResValueUnits.UNIT_FRACTION_PARENT:
                    return "%p";
                default:
                    return "unknown";
            }
        }
    }

    private static final class XmlTranslator {
        private final StringBuilder sb = new StringBuilder();
        private final NamespaceStack namespaces = new NamespaceStack();
        private boolean isLastStartTag;
        private int indent;

        private XmlTranslator() {
            sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        }

        private void onStartTag(String namespace, String name, List<XmlAttribute> attributes) {
            if (isLastStartTag) {
                sb.append(">\n");
            }
            appendIndent();
            sb.append('<');
            String prefix = namespaces.getPrefixByUri(namespace);
            if (prefix != null && !prefix.isEmpty()) {
                sb.append(prefix).append(':');
            }
            sb.append(name);

            List<Namespace> pending = namespaces.consumePending();
            for (Namespace ns : pending) {
                sb.append(" xmlns:").append(ns.prefix).append("=\"").append(ns.uri).append("\"");
            }

            for (XmlAttribute attribute : attributes) {
                sb.append(' ');
                String attrPrefix = namespaces.getPrefixByUri(attribute.namespace);
                if (attrPrefix != null && !attrPrefix.isEmpty()) {
                    sb.append(attrPrefix).append(':');
                } else if (attribute.namespace != null && !attribute.namespace.isEmpty()) {
                    sb.append(attribute.namespace).append(':');
                }
                sb.append(attribute.name).append("=\"")
                        .append(escapeXml(attribute.value)).append('"');
            }

            isLastStartTag = true;
            indent++;
        }

        private void onEndTag(String namespace, String name) {
            indent--;
            if (isLastStartTag) {
                sb.append(" />\n");
            } else {
                appendIndent();
                sb.append("</");
                String prefix = namespaces.getPrefixByUri(namespace);
                if (prefix != null && !prefix.isEmpty()) {
                    sb.append(prefix).append(':');
                }
                sb.append(name).append(">\n");
            }
            isLastStartTag = false;
        }

        private void onCData(String data) {
            if (isLastStartTag) {
                sb.append(">\n");
                isLastStartTag = false;
            }
            appendIndent();
            sb.append(escapeXml(data)).append('\n');
        }

        private void onNamespaceStart(String prefix, String uri) {
            namespaces.push(prefix, uri);
        }

        private void onNamespaceEnd(String prefix, String uri) {
            namespaces.pop(prefix, uri);
        }

        private void appendIndent() {
            for (int i = 0; i < indent; i++) {
                sb.append('\t');
            }
        }

        private String getXml() {
            return sb.toString();
        }
    }

    @RequiredArgsConstructor
    private static final class Namespace {
        private final String prefix;
        private final String uri;
    }

    private static final class NamespaceStack {
        private final List<Namespace> stack = new ArrayList<>();
        private final List<Namespace> pending = new ArrayList<>();

        private void push(String prefix, String uri) {
            if (prefix == null || uri == null) {
                return;
            }
            Namespace ns = new Namespace(prefix, uri);
            stack.add(ns);
            pending.add(ns);
        }

        private void pop(String prefix, String uri) {
            if (prefix == null || uri == null) {
                return;
            }
            for (int i = stack.size() - 1; i >= 0; i--) {
                Namespace ns = stack.get(i);
                if (prefix.equals(ns.prefix) && uri.equals(ns.uri)) {
                    stack.remove(i);
                    return;
                }
            }
        }

        private String getPrefixByUri(String uri) {
            if (uri == null) {
                return null;
            }
            for (int i = stack.size() - 1; i >= 0; i--) {
                Namespace ns = stack.get(i);
                if (uri.equals(ns.uri)) {
                    return ns.prefix;
                }
            }
            return null;
        }

        private List<Namespace> consumePending() {
            List<Namespace> out = new ArrayList<>(pending);
            pending.clear();
            return out;
        }
    }

    @Getter
    private static final class StringPool {
        private final String[] pool;

        private StringPool(int poolSize) {
            this.pool = new String[poolSize];
        }

        private String get(int idx) {
            return pool[idx];
        }

        private void set(int idx, String value) {
            pool[idx] = value;
        }
    }

    private static final class ResType {
        private static final short NULL = 0x00;
        private static final short REFERENCE = 0x01;
        private static final short ATTRIBUTE = 0x02;
        private static final short STRING = 0x03;
        private static final short FLOAT = 0x04;
        private static final short DIMENSION = 0x05;
        private static final short FRACTION = 0x06;
        private static final short INT_DEC = 0x10;
        private static final short INT_HEX = 0x11;
        private static final short INT_BOOLEAN = 0x12;
        private static final short INT_COLOR_ARGB8 = 0x1c;
        private static final short INT_COLOR_RGB8 = 0x1d;
        private static final short INT_COLOR_ARGB4 = 0x1e;
        private static final short INT_COLOR_RGB4 = 0x1f;
    }

    private static final class ResValueUnits {
        private static final short UNIT_PX = 0;
        private static final short UNIT_DIP = 1;
        private static final short UNIT_SP = 2;
        private static final short UNIT_PT = 3;
        private static final short UNIT_IN = 4;
        private static final short UNIT_MM = 5;
        private static final short UNIT_FRACTION = 0;
        private static final short UNIT_FRACTION_PARENT = 1;
    }

    private static final class ChunkType {
        private static final int NULL = 0x0000;
        private static final int STRING_POOL = 0x0001;
        private static final int XML = 0x0003;
        private static final int XML_FIRST_CHUNK = 0x0100;
        private static final int XML_START_NAMESPACE = 0x0100;
        private static final int XML_END_NAMESPACE = 0x0101;
        private static final int XML_START_ELEMENT = 0x0102;
        private static final int XML_END_ELEMENT = 0x0103;
        private static final int XML_CDATA = 0x0104;
        private static final int XML_LAST_CHUNK = 0x017f;
        private static final int XML_RESOURCE_MAP = 0x0180;
    }

    private static ResValue readResValue(ByteBuffer buffer) {
        readUShort(buffer);
        readUByte(buffer);
        short dataType = readUByte(buffer);
        int data = buffer.getInt();
        return new ResValue(dataType, data);
    }

    private static String readString(ByteBuffer buffer, boolean utf8) {
        if (utf8) {
            int strLen = readLength8(buffer);
            int byteLen = readLength8(buffer);
            byte[] bytes = new byte[byteLen];
            buffer.get(bytes);
            readUByte(buffer);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        int strLen = readLength16(buffer);
        return readUtf16String(buffer, strLen);
    }

    private static int readLength8(ByteBuffer buffer) {
        int len = readUByte(buffer);
        if ((len & 0x80) != 0) {
            len = (len & 0x7f) << 8;
            len += readUByte(buffer);
        }
        return len;
    }

    private static int readLength16(ByteBuffer buffer) {
        int len = readUShort(buffer);
        if ((len & 0x8000) != 0) {
            len = (len & 0x7fff) << 16;
            len += readUShort(buffer);
        }
        return len;
    }

    private static String readUtf16String(ByteBuffer buffer, int strLen) {
        StringBuilder sb = new StringBuilder(strLen);
        for (int i = 0; i < strLen; i++) {
            char c = buffer.getChar();
            if (c == 0) {
                skip(buffer, (strLen - i - 1) * 2);
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static short readUByte(ByteBuffer buffer) {
        return (short) (buffer.get() & 0xff);
    }

    private static int readUShort(ByteBuffer buffer) {
        return buffer.getShort() & 0xffff;
    }

    private static long readUInt(ByteBuffer buffer) {
        return buffer.getInt() & 0xffffffffL;
    }

    private static void skip(ByteBuffer buffer, int count) {
        position(buffer, buffer.position() + count);
    }

    private static void position(ByteBuffer buffer, long position) {
        buffer.position(ensureUInt(position));
    }

    private static int ensureUInt(long value) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new ManifestXmlException("unsigned int overflow");
        }
        return (int) value;
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '\"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                default:
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                        break;
                    }
                    sb.append(c);
            }
        }
        return sb.toString();
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

    private static final class ManifestXmlException extends RuntimeException {
        private ManifestXmlException(String message) {
            super(message);
        }
    }
}
