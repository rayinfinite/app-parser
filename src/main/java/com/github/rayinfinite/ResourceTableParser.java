package com.github.rayinfinite;

import lombok.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.github.rayinfinite.ManifestXmlDecoder.ChunkHeader;
import com.github.rayinfinite.ManifestXmlDecoder.ChunkType;
import com.github.rayinfinite.ManifestXmlDecoder.ResType;
import com.github.rayinfinite.ManifestXmlDecoder.StringPool;
import com.github.rayinfinite.ManifestXmlDecoder.StringPoolHeader;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ResourceTableParser {

    public static ManifestXmlDecoder.ResourceResolver fromResources(byte[] resourcesBytes) {
        return fromResources(resourcesBytes, true);
    }

    public static ManifestXmlDecoder.ResourceResolver fromResources(byte[] resourcesBytes, boolean resolveToValue) {
        if (resourcesBytes == null) {
            return new EmptyResolver();
        }
        ResourceTable table = new Parser(resourcesBytes).parse();
        return new TableResolver(table, resolveToValue);
    }

    private static final class TableResolver implements ManifestXmlDecoder.ResourceResolver {
        private final ResourceTable table;
        private final Locale locale = Locale.getDefault();
        private final boolean resolveToValue;

        private TableResolver(ResourceTable table, boolean resolveToValue) {
            this.table = table;
            this.resolveToValue = resolveToValue;
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
            if (!resolveToValue) {
                return "@" + entry.typeName + "/" + entry.key;
            }
            String value = resolveString(entry, new HashSet<>());
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

    private static final class Parser extends ManifestXmlDecoder.Parser {
        private Parser(byte[] data) {
            super(data);
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
            StringPool globalStringPool = readStringPool(stringPoolHeader);
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
                position(beginPos + packageHeader.typeStrings - packageHeader.getHeaderSize());
                typeStringPool = readStringPool((StringPoolHeader) readChunkHeader());
            }
            if (packageHeader.keyStrings > 0) {
                position(beginPos + packageHeader.keyStrings - packageHeader.getHeaderSize());
                keyStringPool = readStringPool((StringPoolHeader) readChunkHeader());
            }

            while (buffer.hasRemaining()) {
                ChunkHeader chunkHeader = readChunkHeader();
                if (chunkHeader == null) {
                    return null;
                }
                long chunkBegin = buffer.position();
                switch (chunkHeader.getChunkType()) {
                    case ChunkType.TABLE_TYPE_SPEC:
                        TypeSpecHeader typeSpecHeader = (TypeSpecHeader) chunkHeader;
                        for (int i = 0; i < typeSpecHeader.entryCount; i++) {
                            readUInt();
                        }
                        position(chunkBegin + typeSpecHeader.getBodySize());
                        break;
                    case ChunkType.TABLE_TYPE:
                        TypeHeader typeHeader = (TypeHeader) chunkHeader;
                        long[] offsets = new long[typeHeader.entryCount];
                        for (int i = 0; i < typeHeader.entryCount; i++) {
                            offsets[i] = readUInt();
                        }
                        String typeName = typeStringPool != null && typeHeader.id > 0
                                ? typeStringPool.get(typeHeader.id - 1)
                                : "type" + typeHeader.id;
                        long entriesStart = chunkBegin + typeHeader.entriesStart - typeHeader.getHeaderSize();
                        String locale = typeHeader.config.locale();
                        for (int entryIndex = 0; entryIndex < offsets.length; entryIndex++) {
                            if (offsets[entryIndex] == 0xffffffffL) {
                                continue;
                            }
                            position(entriesStart + offsets[entryIndex]);
                            ResourceEntry entry = readResourceEntry(packageHeader.id, typeHeader.id, entryIndex,
                                    typeName, keyStringPool, locale);
                            table.addEntry(entry);
                        }
                        position(chunkBegin + typeHeader.getBodySize());
                        break;
                    case ChunkType.TABLE_PACKAGE:
                        return (PackageHeader) chunkHeader;
                    case ChunkType.TABLE_LIBRARY:
                    case ChunkType.NULL:
                    default:
                        position(chunkBegin + chunkHeader.getBodySize());
                        break;
                }
            }
            return null;
        }

        private ResourceEntry readResourceEntry(int packageId, int typeId, int entryIndex, String typeName,
                                                StringPool keyStringPool, String locale) {
            long beginPos = buffer.position();
            int size = readUShort();
            int flags = readUShort();
            long keyRef = readUInt();
            String key = keyStringPool != null ? keyStringPool.get((int) keyRef) : "key" + keyRef;

            if ((flags & ResourceEntry.FLAG_COMPLEX) != 0) {
                readUInt();
                long count = readUInt();
                position(beginPos + size);
                for (int i = 0; i < count; i++) {
                    readUInt();
                    readResValue();
                }
                int resId = (packageId << 24) | (typeId << 16) | entryIndex;
                return new ResourceEntry(resId, typeName, key, null, locale);
            }

            position(beginPos + size);
            ResourceValue value = readResValue();
            int resId = (packageId << 24) | (typeId << 16) | entryIndex;
            return new ResourceEntry(resId, typeName, key, value, locale);
        }

        private ResourceValue readResValue() {
            readUShort();
            readUByte();
            short dataType = readUByte();
            int data = buffer.getInt();
            return new ResourceValue(dataType, data);
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
                case ChunkType.TABLE:
                    ResourceTableHeader tableHeader = new ResourceTableHeader(headerSize, chunkSize);
                    tableHeader.setPackageCount(readUInt());
                    position(begin + headerSize);
                    return tableHeader;
                case ChunkType.STRING_POOL:
                    StringPoolHeader stringPoolHeader = new StringPoolHeader(headerSize, chunkSize);
                    stringPoolHeader.setStringCount(readUInt());
                    stringPoolHeader.setStyleCount(readUInt());
                    stringPoolHeader.setFlags(readUInt());
                    stringPoolHeader.setStringsStart(readUInt());
                    stringPoolHeader.setStylesStart(readUInt());
                    position(begin + headerSize);
                    return stringPoolHeader;
                case ChunkType.TABLE_PACKAGE:
                    PackageHeader packageHeader = new PackageHeader(headerSize, chunkSize);
                    packageHeader.setId((int) readUInt());
                    packageHeader.setName(readUtf16String(128));
                    packageHeader.setTypeStrings(readUInt());
                    packageHeader.setLastPublicType(readUInt());
                    packageHeader.setKeyStrings(readUInt());
                    packageHeader.setLastPublicKey(readUInt());
                    position(begin + headerSize);
                    return packageHeader;
                case ChunkType.TABLE_TYPE_SPEC:
                    TypeSpecHeader typeSpecHeader = new TypeSpecHeader(headerSize, chunkSize);
                    typeSpecHeader.setId(readUByte());
                    typeSpecHeader.setRes0(readUByte());
                    typeSpecHeader.setRes1(readUShort());
                    typeSpecHeader.setEntryCount(readUInt());
                    position(begin + headerSize);
                    return typeSpecHeader;
                case ChunkType.TABLE_TYPE:
                    TypeHeader typeHeader = new TypeHeader(headerSize, chunkSize);
                    typeHeader.setId(readUByte());
                    typeHeader.setRes0(readUByte());
                    typeHeader.setRes1(readUShort());
                    typeHeader.setEntryCount(readUInt());
                    typeHeader.setEntriesStart(readUInt());
                    typeHeader.setConfig(readResTableConfig());
                    position(begin + headerSize);
                    return typeHeader;
                case ChunkType.TABLE_LIBRARY:
                case ChunkType.UNKNOWN:
                case ChunkType.NULL:
                    position(begin + headerSize);
                    return new ChunkHeader(chunkType, headerSize, chunkSize);
                default:
                    throw new IllegalStateException("Unexpected chunk type: 0x" + Integer.toHexString(chunkType));
            }
        }

        private ResTableConfig readResTableConfig() {
            long beginPos = buffer.position();
            long size = readUInt();
            ResTableConfig config = new ResTableConfig();
            config.setSize(size);
            config.setMcc(buffer.getShort());
            config.setMnc(buffer.getShort());
            config.setLanguage(readFixedAscii(2));
            config.setCountry(readFixedAscii(2));
            config.setOrientation(readUByte());
            config.setTouchscreen(readUByte());
            config.setDensity(readUShort());
            long endPos = buffer.position();
            skip((int) (size - (endPos - beginPos)));
            return config;
        }
    }

    @Getter
    @Setter
    private static final class ResourceTableHeader extends ChunkHeader {
        private long packageCount;

        private ResourceTableHeader(int headerSize, long chunkSize) {
            super(ChunkType.TABLE, headerSize, chunkSize);
        }
    }

    @Getter
    @Setter
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
            if (stringPool == null || idx < 0 || idx >= stringPool.size()) {
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

    private static int ensureUInt(long value) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalStateException("unsigned int overflow");
        }
        return (int) value;
    }

}
