package com.github.rayinfinite;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ResourceTableParser {
    public static final String RESOURCE_FILE = "resources.arsc";

    public static ManifestXmlDecoder.ResourceResolver fromResources(byte[] resourcesBytes) {
        if (resourcesBytes == null) {
            return new EmptyResolver();
        }
        ResourceTable table = new Parser(resourcesBytes).parse();
        return new TableResolver(table);
    }

    private static final class TableResolver implements ManifestXmlDecoder.ResourceResolver {
        private final ResourceTable table;
        private final Locale locale = Locale.getDefault();

        private TableResolver(ResourceTable table) {
            this.table = table;
        }

        @Override
        public String resolveReference(long resId) {
            String frameworkStyle = FrameworkResource.resolveAndroidStyle(resId);
            if (frameworkStyle != null) {
                return frameworkStyle;
            }
            ResourceEntry entry = table.selectEntry(resId, locale);
            if (entry == null) {
                return null;
            }
            String value = resolveString(entry, new HashSet<Long>());
            if (value != null) {
                return value;
            }
            return "@" + entry.typeName + "/" + entry.key;
        }

        @Override
        public String resolveAttributeName(long resId) {
            return table.getAttributeName(resId);
        }

        private String resolveString(ResourceEntry entry, Set<Long> seen) {
            if (entry.value == null) {
                return null;
            }
            if (entry.value.dataType == ResType.STRING) {
                return table.getString(entry.value.data);
            }
            if (entry.value.isReference()) {
                long refId = entry.value.referenceId();
                if (!seen.add(refId)) {
                    return null;
                }
                ResourceEntry refEntry = table.selectEntry(refId, locale);
                if (refEntry == null) {
                    return null;
                }
                return resolveString(refEntry, seen);
            }
            return null;
        }
    }

    private static final class EmptyResolver implements ManifestXmlDecoder.ResourceResolver {
    }

    private static final class Parser {
        private final ByteBuffer buffer;
        private StringPool globalStringPool;

        private Parser(byte[] data) {
            this.buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        }

        private ResourceTable parse() {
            ResourceTableHeader tableHeader = (ResourceTableHeader) readChunkHeader();
            if (tableHeader == null) {
                return new ResourceTable(new StringPool(0));
            }
            StringPoolHeader stringPoolHeader = (StringPoolHeader) readChunkHeader();
            if (stringPoolHeader == null) {
                return new ResourceTable(new StringPool(0));
            }
            globalStringPool = readStringPool(stringPoolHeader);
            ResourceTable table = new ResourceTable(globalStringPool);

            if (tableHeader.packageCount > 0) {
                ChunkHeader header = readChunkHeader();
                if (!(header instanceof PackageHeader)) {
                    return table;
                }
                PackageHeader packageHeader = (PackageHeader) header;
                for (int i = 0; i < tableHeader.packageCount; i++) {
                    packageHeader = parsePackage(packageHeader, table);
                    if (packageHeader == null && i + 1 < tableHeader.packageCount) {
                        break;
                    }
                }
            }
            return table;
        }

        private PackageHeader parsePackage(PackageHeader packageHeader, ResourceTable table) {
            StringPool typeStringPool = null;
            StringPool keyStringPool = null;
            long beginPos = buffer.position();

            if (packageHeader.typeStrings > 0) {
                position(buffer, beginPos + packageHeader.typeStrings - packageHeader.getHeaderSize());
                typeStringPool = readStringPool((StringPoolHeader) readChunkHeader());
            }
            if (packageHeader.keyStrings > 0) {
                position(buffer, beginPos + packageHeader.keyStrings - packageHeader.getHeaderSize());
                keyStringPool = readStringPool((StringPoolHeader) readChunkHeader());
            }

            while (buffer.hasRemaining()) {
                ChunkHeader chunkHeader = readChunkHeader();
                if (chunkHeader == null) {
                    return null;
                }
                long chunkBegin = buffer.position();
                switch (chunkHeader.chunkType) {
                    case ChunkType.TABLE_TYPE_SPEC:
                        TypeSpecHeader typeSpecHeader = (TypeSpecHeader) chunkHeader;
                        for (int i = 0; i < typeSpecHeader.entryCount; i++) {
                            readUInt(buffer);
                        }
                        position(buffer, chunkBegin + typeSpecHeader.getBodySize());
                        break;
                    case ChunkType.TABLE_TYPE:
                        TypeHeader typeHeader = (TypeHeader) chunkHeader;
                        long[] offsets = new long[typeHeader.entryCount];
                        for (int i = 0; i < typeHeader.entryCount; i++) {
                            offsets[i] = readUInt(buffer);
                        }
                        String typeName = typeStringPool != null && typeHeader.id > 0
                                ? typeStringPool.get(typeHeader.id - 1)
                                : "type" + typeHeader.id;
                        long entriesStart = chunkBegin + typeHeader.entriesStart - typeHeader.getHeaderSize();
                        String locale = typeHeader.config.locale();
                        for (int entryIndex = 0; entryIndex < offsets.length; entryIndex++) {
                            if (offsets[entryIndex] == TypeHeader.NO_ENTRY) {
                                continue;
                            }
                            position(buffer, entriesStart + offsets[entryIndex]);
                            ResourceEntry entry = readResourceEntry(packageHeader.id, typeHeader.id, entryIndex,
                                    typeName, keyStringPool, locale);
                            if (entry != null) {
                                table.addEntry(entry);
                            }
                        }
                        position(buffer, chunkBegin + typeHeader.getBodySize());
                        break;
                    case ChunkType.TABLE_PACKAGE:
                        return (PackageHeader) chunkHeader;
                    case ChunkType.TABLE_LIBRARY:
                    case ChunkType.NULL:
                        position(buffer, chunkBegin + chunkHeader.getBodySize());
                        break;
                    default:
                        position(buffer, chunkBegin + chunkHeader.getBodySize());
                        break;
                }
            }
            return null;
        }

        private ResourceEntry readResourceEntry(int packageId, int typeId, int entryIndex, String typeName,
                                                StringPool keyStringPool, String locale) {
            long beginPos = buffer.position();
            int size = readUShort(buffer);
            int flags = readUShort(buffer);
            long keyRef = readUInt(buffer);
            String key = keyStringPool != null ? keyStringPool.get((int) keyRef) : "key" + keyRef;

            if ((flags & ResourceEntry.FLAG_COMPLEX) != 0) {
                readUInt(buffer);
                long count = readUInt(buffer);
                position(buffer, beginPos + size);
                for (int i = 0; i < count; i++) {
                    readUInt(buffer);
                    readResValue(buffer);
                }
                int resId = (packageId << 24) | (typeId << 16) | entryIndex;
                return new ResourceEntry(resId, typeName, key, null, locale);
            }

            position(buffer, beginPos + size);
            ResourceValue value = readResValue(buffer);
            int resId = (packageId << 24) | (typeId << 16) | entryIndex;
            return new ResourceEntry(resId, typeName, key, value, locale);
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
                case ChunkType.TABLE:
                    ResourceTableHeader tableHeader = new ResourceTableHeader(headerSize, chunkSize);
                    tableHeader.setPackageCount(readUInt(buffer));
                    position(buffer, begin + headerSize);
                    return tableHeader;
                case ChunkType.STRING_POOL:
                    StringPoolHeader stringPoolHeader = new StringPoolHeader(headerSize, chunkSize);
                    stringPoolHeader.setStringCount(readUInt(buffer));
                    stringPoolHeader.setStyleCount(readUInt(buffer));
                    stringPoolHeader.setFlags(readUInt(buffer));
                    stringPoolHeader.setStringsStart(readUInt(buffer));
                    stringPoolHeader.setStylesStart(readUInt(buffer));
                    position(buffer, begin + headerSize);
                    return stringPoolHeader;
                case ChunkType.TABLE_PACKAGE:
                    PackageHeader packageHeader = new PackageHeader(headerSize, chunkSize);
                    packageHeader.setId(readUInt(buffer));
                    packageHeader.setName(readFixedUtf16String(buffer, 128));
                    packageHeader.setTypeStrings(readUInt(buffer));
                    packageHeader.setLastPublicType(readUInt(buffer));
                    packageHeader.setKeyStrings(readUInt(buffer));
                    packageHeader.setLastPublicKey(readUInt(buffer));
                    position(buffer, begin + headerSize);
                    return packageHeader;
                case ChunkType.TABLE_TYPE_SPEC:
                    TypeSpecHeader typeSpecHeader = new TypeSpecHeader(headerSize, chunkSize);
                    typeSpecHeader.setId(readUByte(buffer));
                    typeSpecHeader.setRes0(readUByte(buffer));
                    typeSpecHeader.setRes1(readUShort(buffer));
                    typeSpecHeader.setEntryCount(readUInt(buffer));
                    position(buffer, begin + headerSize);
                    return typeSpecHeader;
                case ChunkType.TABLE_TYPE:
                    TypeHeader typeHeader = new TypeHeader(headerSize, chunkSize);
                    typeHeader.setId(readUByte(buffer));
                    typeHeader.setRes0(readUByte(buffer));
                    typeHeader.setRes1(readUShort(buffer));
                    typeHeader.setEntryCount(readUInt(buffer));
                    typeHeader.setEntriesStart(readUInt(buffer));
                    typeHeader.setConfig(readResTableConfig());
                    position(buffer, begin + headerSize);
                    return typeHeader;
                case ChunkType.TABLE_LIBRARY:
                case ChunkType.UNKNOWN:
                case ChunkType.NULL:
                    position(buffer, begin + headerSize);
                    return new ChunkHeader(chunkType, headerSize, chunkSize);
                default:
                    throw new IllegalStateException("Unexpected chunk type: 0x" + Integer.toHexString(chunkType));
            }
        }

        private ResTableConfig readResTableConfig() {
            long beginPos = buffer.position();
            long size = readUInt(buffer);
            ResTableConfig config = new ResTableConfig();
            config.setSize(size);
            config.setMcc(buffer.getShort());
            config.setMnc(buffer.getShort());
            config.setLanguage(readFixedAscii(buffer, 2));
            config.setCountry(readFixedAscii(buffer, 2));
            config.setOrientation(readUByte(buffer));
            config.setTouchscreen(readUByte(buffer));
            config.setDensity(readUShort(buffer));
            long endPos = buffer.position();
            skip(buffer, (int) (size - (endPos - beginPos)));
            return config;
        }

        private StringPool readStringPool(StringPoolHeader header) {
            long beginPos = buffer.position();
            int[] offsets = new int[header.stringCount];
            for (int i = 0; i < header.stringCount; i++) {
                offsets[i] = (int) readUInt(buffer);
            }
            boolean utf8 = (header.flags & StringPoolHeader.UTF8_FLAG) != 0;

            long stringsStart = beginPos + header.stringsStart - header.getHeaderSize();
            position(buffer, stringsStart);

            StringPool pool = new StringPool(header.stringCount);
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
    private static final class ResourceTableHeader extends ChunkHeader {
        private int packageCount;

        private ResourceTableHeader(int headerSize, long chunkSize) {
            super(ChunkType.TABLE, headerSize, chunkSize);
        }

        private void setPackageCount(long packageCount) {
            this.packageCount = ensureUInt(packageCount);
        }
    }

    @Getter
    private static final class StringPoolHeader extends ChunkHeader {
        private static final int UTF8_FLAG = 1 << 8;

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

    @Getter
    private static final class PackageHeader extends ChunkHeader {
        private int id;
        private String name;
        private long typeStrings;
        private long lastPublicType;
        private long keyStrings;
        private long lastPublicKey;

        private PackageHeader(int headerSize, long chunkSize) {
            super(ChunkType.TABLE_PACKAGE, headerSize, chunkSize);
        }

        private void setId(long id) {
            this.id = ensureUInt(id);
        }

        private void setName(String name) {
            this.name = name;
        }

        private void setTypeStrings(long typeStrings) {
            this.typeStrings = typeStrings;
        }

        private void setLastPublicType(long lastPublicType) {
            this.lastPublicType = lastPublicType;
        }

        private void setKeyStrings(long keyStrings) {
            this.keyStrings = keyStrings;
        }

        private void setLastPublicKey(long lastPublicKey) {
            this.lastPublicKey = lastPublicKey;
        }
    }

    @Getter
    private static final class TypeSpecHeader extends ChunkHeader {
        private int id;
        private int res0;
        private int res1;
        private long entryCount;

        private TypeSpecHeader(int headerSize, long chunkSize) {
            super(ChunkType.TABLE_TYPE_SPEC, headerSize, chunkSize);
        }

        private void setId(short id) {
            this.id = id & 0xff;
        }

        private void setRes0(short res0) {
            this.res0 = res0 & 0xff;
        }

        private void setRes1(int res1) {
            this.res1 = res1 & 0xffff;
        }

        private void setEntryCount(long entryCount) {
            this.entryCount = entryCount;
        }
    }

    @Getter
    private static final class TypeHeader extends ChunkHeader {
        private static final long NO_ENTRY = 0xffffffffL;

        private int id;
        private int res0;
        private int res1;
        private int entryCount;
        private long entriesStart;
        private ResTableConfig config;

        private TypeHeader(int headerSize, long chunkSize) {
            super(ChunkType.TABLE_TYPE, headerSize, chunkSize);
        }

        private void setId(short id) {
            this.id = id & 0xff;
        }

        private void setRes0(short res0) {
            this.res0 = res0 & 0xff;
        }

        private void setRes1(int res1) {
            this.res1 = res1 & 0xffff;
        }

        private void setEntryCount(long entryCount) {
            this.entryCount = ensureUInt(entryCount);
        }

        private void setEntriesStart(long entriesStart) {
            this.entriesStart = entriesStart;
        }

        private void setConfig(ResTableConfig config) {
            this.config = config;
        }
    }

    @Getter
    private static final class ResTableConfig {
        private int size;
        private short mcc;
        private short mnc;
        private String language;
        private String country;
        private short orientation;
        private short touchscreen;
        private int density;

        private void setSize(long size) {
            this.size = ensureUInt(size);
        }

        private void setMcc(short mcc) {
            this.mcc = mcc;
        }

        private void setMnc(short mnc) {
            this.mnc = mnc;
        }

        private void setLanguage(String language) {
            this.language = language;
        }

        private void setCountry(String country) {
            this.country = country;
        }

        private void setOrientation(short orientation) {
            this.orientation = orientation;
        }

        private void setTouchscreen(short touchscreen) {
            this.touchscreen = touchscreen;
        }

        private void setDensity(int density) {
            this.density = density;
        }

        private String locale() {
            if (language == null || language.isEmpty()) {
                return "";
            }
            if (country == null || country.isEmpty()) {
                return language;
            }
            return language + "-" + country;
        }
    }

    @RequiredArgsConstructor
    private static final class ResourceEntry {
        private static final int FLAG_COMPLEX = 0x0001;

        private final int resId;
        private final String typeName;
        private final String key;
        private final ResourceValue value;
        private final String locale;
    }

    @RequiredArgsConstructor
    private static final class ResourceValue {
        private final short dataType;
        private final int data;

        private boolean isReference() {
            return dataType == ResType.REFERENCE || dataType == ResType.ATTRIBUTE;
        }

        private long referenceId() {
            return data & 0xffffffffL;
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

    private static final class ResourceTable {
        private final StringPool stringPool;
        private final Map<Integer, List<ResourceEntry>> entries = new HashMap<>();
        private final Map<Integer, String> attrNames = new HashMap<>();

        private ResourceTable(StringPool stringPool) {
            this.stringPool = stringPool;
        }

        private void addEntry(ResourceEntry entry) {
            entries.computeIfAbsent(entry.resId, key -> new ArrayList<>()).add(entry);
            if ("attr".equals(entry.typeName)) {
                attrNames.put(entry.resId, entry.key);
            }
        }

        private String getString(int idx) {
            if (stringPool == null || idx < 0 || idx >= stringPool.pool.length) {
                return null;
            }
            return stringPool.get(idx);
        }

        private String getAttributeName(long resId) {
            return attrNames.get((int) resId);
        }

        private ResourceEntry selectEntry(long resId, Locale locale) {
            List<ResourceEntry> candidates = entries.get((int) resId);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            String lang = locale != null ? locale.getLanguage() : "";
            String country = locale != null ? locale.getCountry() : "";
            String langCountry = lang.isEmpty() ? "" : (country.isEmpty() ? lang : lang + "-" + country);

            for (ResourceEntry entry : candidates) {
                if (langCountry.equals(entry.locale)) {
                    return entry;
                }
            }
            if (!lang.isEmpty()) {
                for (ResourceEntry entry : candidates) {
                    if (lang.equals(entry.locale)) {
                        return entry;
                    }
                }
            }
            for (ResourceEntry entry : candidates) {
                if (entry.locale == null || entry.locale.isEmpty()) {
                    return entry;
                }
            }
            return candidates.get(0);
        }
    }

    private static final class ResType {
        private static final short NULL = 0x00;
        private static final short REFERENCE = 0x01;
        private static final short ATTRIBUTE = 0x02;
        private static final short STRING = 0x03;
    }

    private static final class ChunkType {
        private static final int NULL = 0x0000;
        private static final int STRING_POOL = 0x0001;
        private static final int TABLE = 0x0002;
        private static final int TABLE_PACKAGE = 0x0200;
        private static final int TABLE_TYPE = 0x0201;
        private static final int TABLE_TYPE_SPEC = 0x0202;
        private static final int TABLE_LIBRARY = 0x0203;
        private static final int UNKNOWN = 0x0204;
    }

    private static ResourceValue readResValue(ByteBuffer buffer) {
        readUShort(buffer);
        readUByte(buffer);
        short dataType = readUByte(buffer);
        int data = buffer.getInt();
        return new ResourceValue(dataType, data);
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

    private static String readFixedUtf16String(ByteBuffer buffer, int fixedLen) {
        StringBuilder sb = new StringBuilder(fixedLen);
        for (int i = 0; i < fixedLen; i++) {
            char c = buffer.getChar();
            if (c == 0) {
                skip(buffer, (fixedLen - i - 1) * 2);
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String readFixedAscii(ByteBuffer buffer, int len) {
        byte[] bytes = new byte[len];
        buffer.get(bytes);
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, 0, end, StandardCharsets.US_ASCII);
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
            throw new IllegalStateException("unsigned int overflow");
        }
        return (int) value;
    }

}
