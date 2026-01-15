package com.github.rayinfinite;

import lombok.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ManifestXmlDecoder {
    @Setter
    private static BiFunction<String, String, String> attributeValueMapper;

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

    static class Parser {
        protected final ByteBuffer buffer;

        protected Parser(byte[] data) {
            this.buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        }

        protected short readUByte() {
            return (short) (buffer.get() & 0xff);
        }

        protected int readUShort() {
            return buffer.getShort() & 0xffff;
        }

        protected long readUInt() {
            return buffer.getInt() & 0xffffffffL;
        }

        protected String readString(boolean utf8) {
            if (utf8) {
                int strLen = readLength8();
                int byteLen = readLength8();
                byte[] bytes = new byte[byteLen];
                buffer.get(bytes);
                readUByte();
                return new String(bytes, StandardCharsets.UTF_8);
            }
            int strLen = readLength16();
            return readUtf16String(strLen);
        }

        protected int readLength8() {
            int len = readUByte();
            if ((len & 0x80) != 0) {
                len = (len & 0x7f) << 8;
                len += readUByte();
            }
            return len;
        }

        protected int readLength16() {
            int len = readUShort();
            if ((len & 0x8000) != 0) {
                len = (len & 0x7fff) << 16;
                len += readUShort();
            }
            return len;
        }

        protected String readUtf16String(int strLen) {
            StringBuilder sb = new StringBuilder(strLen);
            for (int i = 0; i < strLen; i++) {
                char c = buffer.getChar();
                if (c == 0) {
                    skip((strLen - i - 1) * 2);
                    break;
                }
                sb.append(c);
            }
            return sb.toString();
        }

        protected String readFixedAscii(int len) {
            byte[] bytes = new byte[len];
            buffer.get(bytes);
            int end = 0;
            while (end < bytes.length && bytes[end] != 0) {
                end++;
            }
            return new String(bytes, 0, end, StandardCharsets.US_ASCII);
        }

        protected void skip(int count) {
            position(buffer.position() + count);
        }

        protected void position(long position) {
            buffer.position((int) position);
        }

        protected StringPool readStringPool(StringPoolHeader header) {
            long beginPos = buffer.position();
            int[] offsets = new int[(int) header.getStringCount()];
            for (int i = 0; i < header.getStringCount(); i++) {
                offsets[i] = (int) readUInt();
            }
            boolean utf8 = (header.getFlags() & StringPoolHeader.UTF8_FLAG) != 0;

            long stringsStart = beginPos + header.getStringsStart() - header.getHeaderSize();
            position(stringsStart);

            StringPool pool = new StringPool((int) header.getStringCount());
            long lastOffset = -1;
            String lastValue = null;
            for (int i = 0; i < offsets.length; i++) {
                long offset = stringsStart + (offsets[i] & 0xffffffffL);
                if (offset == lastOffset) {
                    pool.set(i, lastValue);
                    continue;
                }
                position(offset);
                String value = readString(utf8);
                pool.set(i, value);
                lastOffset = offset;
                lastValue = value;
            }

            position(beginPos + header.getBodySize());
            return pool;
        }
    }

    static final class BinaryXmlParser extends Parser {
        private final ResourceResolver resolver;
        private StringPool stringPool;
        private long[] resourceMap;
        private final XmlTranslator translator = new XmlTranslator();

        private BinaryXmlParser(byte[] data, ResourceResolver resolver) {
            super(data);
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
                position(bodyStart + chunkHeader.getBodySize());
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

            int attributeStart = readUShort();
            int attributeSize = readUShort();
            int attributeCount = readUShort();
            int idIndex = readUShort();
            int classIndex = readUShort();
            int styleIndex = readUShort();

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
            readResValue();
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
            ResValue resValue = readResValue();

            String value = rawValue != null ? rawValue : resValue.toStringValue(stringPool, resolver);
            if (attributeValueMapper != null) {
                value = attributeValueMapper.apply(name, value);
            }
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
                ids[i] = readUInt();
            }
            return ids;
        }

        private ChunkHeader readChunkHeader() {
            if (!buffer.hasRemaining()) {
                return null;
            }
            int begin = buffer.position();
            int chunkType = readUShort();
            int headerSize = readUShort();
            long chunkSize = readUInt();

            switch (chunkType) {
                case ChunkType.STRING_POOL:
                    StringPoolHeader header = new StringPoolHeader(headerSize, chunkSize);
                    header.setStringCount(readUInt());
                    header.setStyleCount(readUInt());
                    header.setFlags(readUInt());
                    header.setStringsStart(readUInt());
                    header.setStylesStart(readUInt());
                    position(begin + headerSize);
                    return header;
                case ChunkType.XML_RESOURCE_MAP:
                    position(begin + headerSize);
                    return new ChunkHeader(chunkType, headerSize, chunkSize);
                case ChunkType.XML_START_NAMESPACE:
                case ChunkType.XML_END_NAMESPACE:
                case ChunkType.XML_START_ELEMENT:
                case ChunkType.XML_END_ELEMENT:
                case ChunkType.XML_CDATA:
                    readUInt();
                    readUInt();
                    position(begin + headerSize);
                    return new ChunkHeader(chunkType, headerSize, chunkSize);
                case ChunkType.XML:
                case ChunkType.NULL:
                    position(begin + headerSize);
                    return new ChunkHeader(chunkType, headerSize, chunkSize);
                default:
                    throw new ManifestXmlException("Unexpected chunk type: " + chunkType);
            }
        }

        private ResValue readResValue() {
            readUShort();
            readUByte();
            short dataType = readUByte();
            int data = buffer.getInt();
            return new ResValue(dataType, data);
        }
    }

    @Getter
    public static class ChunkHeader {
        private final int chunkType;
        private final int headerSize;
        private final long chunkSize;

        public ChunkHeader(int chunkType, int headerSize, long chunkSize) {
            this.chunkType = chunkType;
            this.headerSize = headerSize;
            this.chunkSize = chunkSize;
        }

        public int getBodySize() {
            return (int) (chunkSize - headerSize);
        }
    }

    @Setter
    @Getter
    public static final class StringPoolHeader extends ChunkHeader {
        public static final int UTF8_FLAG = 1 << 8;

        private long stringCount;
        private long styleCount;
        private long flags;
        private long stringsStart;
        private long stylesStart;

        public StringPoolHeader(int headerSize, long chunkSize) {
            super(ChunkType.STRING_POOL, headerSize, chunkSize);
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
                case ResType.FLOAT:
                    return Float.toString(Float.intBitsToFloat(data));
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
    public static final class StringPool {
        private final String[] pool;

        public StringPool(int poolSize) {
            this.pool = new String[poolSize];
        }

        public String get(int idx) {
            return pool[idx];
        }

        public void set(int idx, String value) {
            pool[idx] = value;
        }

        public int size() {
            return pool.length;
        }
    }

    public static final class ResType {
        public static final short NULL = 0x00;
        public static final short REFERENCE = 0x01;
        public static final short ATTRIBUTE = 0x02;
        public static final short STRING = 0x03;
        public static final short FLOAT = 0x04;
        public static final short DIMENSION = 0x05;
        public static final short FRACTION = 0x06;
        public static final short INT_DEC = 0x10;
        public static final short INT_HEX = 0x11;
        public static final short INT_BOOLEAN = 0x12;
        public static final short INT_COLOR_ARGB8 = 0x1c;
        public static final short INT_COLOR_RGB8 = 0x1d;
        public static final short INT_COLOR_ARGB4 = 0x1e;
        public static final short INT_COLOR_RGB4 = 0x1f;
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

    public static final class ChunkType {
        public static final int NULL = 0x0000;
        public static final int STRING_POOL = 0x0001;
        public static final int TABLE = 0x0002;
        public static final int XML = 0x0003;

        public static final int XML_FIRST_CHUNK = 0x0100;
        public static final int XML_START_NAMESPACE = 0x0100;
        public static final int XML_END_NAMESPACE = 0x0101;
        public static final int XML_START_ELEMENT = 0x0102;
        public static final int XML_END_ELEMENT = 0x0103;
        public static final int XML_CDATA = 0x0104;
        public static final int XML_LAST_CHUNK = 0x017f;
        public static final int XML_RESOURCE_MAP = 0x0180;

        public static final int TABLE_PACKAGE = 0x0200;
        public static final int TABLE_TYPE = 0x0201;
        public static final int TABLE_TYPE_SPEC = 0x0202;
        public static final int TABLE_LIBRARY = 0x0203;
        public static final int UNKNOWN = 0x0204;
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

    static final class ManifestXmlException extends RuntimeException {
        ManifestXmlException(String message) {
            super(message);
        }

        ManifestXmlException(Throwable ex) {
            super(ex);
        }
    }
}
