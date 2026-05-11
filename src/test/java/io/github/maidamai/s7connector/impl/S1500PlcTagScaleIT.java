package io.github.maidamai.s7connector.impl;

import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Connector;
import io.github.maidamai.s7connector.api.S7Serializer;
import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.api.SiemensPLCS;
import io.github.maidamai.s7connector.api.factory.S7ConnectorFactory;
import io.github.maidamai.s7connector.api.factory.S7SerializerFactory;
import io.github.maidamai.s7connector.bean.PlcS7PointVariable;
import io.github.maidamai.s7connector.exception.S7Exception;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

class S1500PlcTagScaleIT {
    private static final String TAG_FILE_PROPERTY = "plc.tags.file";
    private static final String HOST_PROPERTY = "plc.host";
    private static final int DEFAULT_PORT = 102;
    private static final int DEFAULT_RACK = 0;
    private static final int DEFAULT_SLOT = 2;
    private static final int DEFAULT_TIMEOUT_MILLIS = 3000;
    private static final int DEFAULT_CYCLES = 3;
    private static final int SCALE_STEP_SIZE = 100;

    @TestFactory
    List<DynamicTest> readsContinuouslyAndWritesAllTagsFromTagWorkbook() throws IOException {
        Assumptions.assumeTrue(isConfigured(TAG_FILE_PROPERTY) && isConfigured(HOST_PROPERTY),
                "set -D" + TAG_FILE_PROPERTY + " and -D" + HOST_PROPERTY + " to run live PLC scale tests");
        final List<PlcTag> tags = PlcTagWorkbook.load(tagFile()).getSupportedTags();
        assertTrue(!tags.isEmpty(), "tag workbook should contain supported PLC tags");

        final List<DynamicTest> tests = new ArrayList<>();
        for (final Integer pointCount : pointCounts(tags.size())) {
            tests.add(dynamicTest("read/write scale " + pointCount + " tags", () -> runScaleTest(tags, pointCount.intValue())));
        }
        return tests;
    }

    private void runScaleTest(final List<PlcTag> allTags, final int pointCount) throws IOException {
        final List<PlcTag> selectedTags = new ArrayList<>(allTags.subList(0, pointCount));

        final S7Connector connector = S7ConnectorFactory.buildTCPConnector(SiemensPLCS.S1500)
                .withHost(host())
                .withPort(port())
                .withRack(rack())
                .withSlot(slot())
                .withTimeout(timeoutMillis())
                .build();
        final S7Serializer serializer = S7SerializerFactory.buildSerializer(connector);
        final List<MemorySnapshot> originalMemory = readMemorySnapshots(connector, selectedTags);
        readValues(serializer, selectedTags, "initial read", pointCount);
        try {
            for (int cycle = 0; cycle < cycles(); cycle++) {
                assertEquals(pointCount, readValues(serializer, selectedTags, "pre-write cycle " + cycle, pointCount).size(),
                        "pre-write read should return one value per selected tag");

                for (int i = 0; i < selectedTags.size(); i++) {
                    final PlcTag tag = selectedTags.get(i);
                    final Object writeValue = tag.valueForCycle(cycle, i);
                    writeTag(serializer, tag, writeValue, "write cycle " + cycle);
                    final Object actualValue = readValue(serializer, tag, "read back cycle " + cycle);
                    assertValueEquals(new WriteExpectation(tag, writeValue), actualValue);
                }

                readValues(serializer, selectedTags, "post-write cycle " + cycle, pointCount);
            }
        } finally {
            restoreMemorySnapshots(connector, originalMemory);
            connector.close();
        }
    }

    private static List<?> readValues(
            final S7Serializer serializer,
            final List<PlcTag> tags,
            final String stage,
            final int expectedSize) {
        final List<PlcS7PointVariable> points = new ArrayList<>();
        for (final PlcTag tag : tags) {
            points.add(tag.toPointVariable());
        }
        try {
            final List<?> values = (List<?>) serializer.dispense(points);
            assertEquals(expectedSize, values.size(), stage + " should return one value per selected tag");
            return values;
        } catch (final S7Exception cause) {
            throw new AssertionError(stage + " failed for " + expectedSize + " tags", cause);
        }
    }

    private static Object readValue(final S7Serializer serializer, final PlcTag tag, final String stage) {
        try {
            return serializer.dispense(tag.toPointVariable());
        } catch (final S7Exception cause) {
            throw new AssertionError(stage + " failed for " + tag.describe(), cause);
        }
    }

    private static void writeTag(
            final S7Serializer serializer,
            final PlcTag tag,
            final Object value,
            final String stage) {
        try {
            serializer.store(value, tag.toPointVariable());
        } catch (final S7Exception cause) {
            throw new AssertionError(stage + " failed for " + tag.describe(), cause);
        }
    }

    private static void assertValueEquals(final WriteExpectation expectation, final Object actualValue) {
        final Object expectedValue = expectation.getValue();
        if (expectedValue instanceof Float && actualValue instanceof Float) {
            assertEquals(((Float) expectedValue).floatValue(), ((Float) actualValue).floatValue(), 0.001f,
                    "read back value should match written value for " + expectation.getTag().describe());
            return;
        }
        assertEquals(expectedValue, actualValue,
                "read back value should match written value for " + expectation.getTag().describe());
    }

    private static List<MemorySnapshot> readMemorySnapshots(final S7Connector connector, final List<PlcTag> tags)
            throws IOException {
        final List<MemorySnapshot> snapshots = new ArrayList<>();
        for (final MemoryRange range : mergedMemoryRanges(tags)) {
            snapshots.add(new MemorySnapshot(range, connector.read(
                    range.getArea(),
                    range.getDbNum(),
                    range.getLength(),
                    range.getStartOffset())));
        }
        return snapshots;
    }

    private static void restoreMemorySnapshots(final S7Connector connector, final List<MemorySnapshot> snapshots)
            throws IOException {
        for (final MemorySnapshot snapshot : snapshots) {
            final MemoryRange range = snapshot.getRange();
            connector.write(range.getArea(), range.getDbNum(), range.getStartOffset(), snapshot.getBuffer());
        }
    }

    private static List<MemoryRange> mergedMemoryRanges(final List<PlcTag> tags) {
        final List<MemoryRange> ranges = new ArrayList<>();
        for (final PlcTag tag : tags) {
            ranges.add(new MemoryRange(tag.getArea(), tag.getDbNum(), tag.getByteOffset(), tag.getByteOffset() + tag.getSize()));
        }
        ranges.sort(Comparator
                .comparing(MemoryRange::getArea)
                .thenComparing(MemoryRange::getDbNum)
                .thenComparing(MemoryRange::getStartOffset));

        final List<MemoryRange> mergedRanges = new ArrayList<>();
        for (final MemoryRange range : ranges) {
            if (mergedRanges.isEmpty()) {
                mergedRanges.add(range);
                continue;
            }
            final MemoryRange lastRange = mergedRanges.get(mergedRanges.size() - 1);
            if (lastRange.canMerge(range)) {
                mergedRanges.set(mergedRanges.size() - 1, lastRange.merge(range));
            } else {
                mergedRanges.add(range);
            }
        }
        return mergedRanges;
    }

    private static List<Integer> pointCounts(final int availablePointCount) {
        final List<Integer> pointCounts = new ArrayList<>();
        for (int pointCount = SCALE_STEP_SIZE; pointCount < availablePointCount; pointCount += SCALE_STEP_SIZE) {
            pointCounts.add(Integer.valueOf(pointCount));
        }
        if (pointCounts.isEmpty() || pointCounts.get(pointCounts.size() - 1).intValue() != availablePointCount) {
            pointCounts.add(Integer.valueOf(availablePointCount));
        }
        return pointCounts;
    }

    private static Path tagFile() {
        return Paths.get(requiredProperty(TAG_FILE_PROPERTY));
    }

    private static String host() {
        return requiredProperty(HOST_PROPERTY);
    }

    private static int port() {
        return Integer.parseInt(System.getProperty("plc.port", String.valueOf(DEFAULT_PORT)));
    }

    private static int rack() {
        return Integer.parseInt(System.getProperty("plc.rack", String.valueOf(DEFAULT_RACK)));
    }

    private static int slot() {
        return Integer.parseInt(System.getProperty("plc.slot", String.valueOf(DEFAULT_SLOT)));
    }

    private static int timeoutMillis() {
        return Integer.parseInt(System.getProperty("plc.timeoutMillis", String.valueOf(DEFAULT_TIMEOUT_MILLIS)));
    }

    private static int cycles() {
        return Integer.parseInt(System.getProperty("plc.scale.cycles", String.valueOf(DEFAULT_CYCLES)));
    }

    private static boolean isConfigured(final String propertyName) {
        final String value = System.getProperty(propertyName);
        return value != null && !value.trim().isEmpty();
    }

    private static String requiredProperty(final String propertyName) {
        final String value = System.getProperty(propertyName);
        Assumptions.assumeTrue(value != null && !value.trim().isEmpty(),
                "set -D" + propertyName + " to run live PLC scale tests");
        return value;
    }

    private static final class PlcTagWorkbook {
        private static final String SHARED_STRINGS_ENTRY = "xl/sharedStrings.xml";
        private static final String WORKBOOK_ENTRY = "xl/workbook.xml";
        private static final String WORKBOOK_RELS_ENTRY = "xl/_rels/workbook.xml.rels";
        private static final String RELATIONSHIP_NAMESPACE =
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        private static final String WORKSHEET_RELATIONSHIP_TYPE =
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet";

        private final List<PlcTag> supportedTags;

        private PlcTagWorkbook(final List<PlcTag> supportedTags) {
            this.supportedTags = supportedTags;
        }

        private static PlcTagWorkbook load(final Path path) throws IOException {
            final byte[] fileHeader = readFileHeader(path);
            if (!isZipPackage(fileHeader)) {
                final List<List<String>> csvRows = readCsvRows(path, fileHeader);
                if (!csvRows.isEmpty()) {
                    return new PlcTagWorkbook(toSupportedTags(csvRows));
                }
                throw new IOException("tag file is not a valid .xlsx zip package or CSV text file: " + path
                        + "; firstBytes=" + toHex(fileHeader));
            }
            try (ZipFile zipFile = new ZipFile(path.toFile())) {
                final List<String> sharedStrings = readSharedStrings(zipFile);
                final List<List<String>> rows = readRows(zipFile, sharedStrings);
                if (rows.isEmpty()) {
                    throw new IOException("tag workbook is empty: " + path);
                }
                return new PlcTagWorkbook(toSupportedTags(rows));
            } catch (final ZipException cause) {
                throw new IOException("tag file has .xlsx extension but is not a complete zip package: " + path
                        + "; firstBytes=" + toHex(fileHeader), cause);
            } catch (final ParserConfigurationException | SAXException cause) {
                throw new IOException("failed to parse tag workbook: " + path, cause);
            }
        }

        private List<PlcTag> getSupportedTags() {
            return this.supportedTags;
        }

        private static byte[] readFileHeader(final Path path) throws IOException {
            if (!Files.exists(path)) {
                throw new IOException("tag file does not exist: " + path);
            }
            try (InputStream inputStream = Files.newInputStream(path)) {
                final byte[] header = new byte[16];
                final int readLength = inputStream.read(header);
                if (readLength < 0) {
                    return new byte[0];
                }
                final byte[] exactHeader = new byte[readLength];
                System.arraycopy(header, 0, exactHeader, 0, readLength);
                return exactHeader;
            }
        }

        private static boolean isZipPackage(final byte[] fileHeader) {
            return fileHeader.length >= 4
                    && fileHeader[0] == 'P'
                    && fileHeader[1] == 'K'
                    && fileHeader[2] == 0x03
                    && fileHeader[3] == 0x04;
        }

        private static List<List<String>> readCsvRows(final Path path, final byte[] fileHeader) throws IOException {
            if (!looksLikeText(fileHeader)) {
                return new ArrayList<>();
            }
            final List<List<String>> rows = new ArrayList<>();
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        rows.add(parseCsvLine(line));
                    }
                }
            }
            return rows;
        }

        private static boolean looksLikeText(final byte[] fileHeader) {
            if (fileHeader.length == 0) {
                return false;
            }
            for (final byte value : fileHeader) {
                final int unsignedValue = value & 0xFF;
                if (unsignedValue == 0) {
                    return false;
                }
                if (unsignedValue < 0x09) {
                    return false;
                }
            }
            return true;
        }

        private static List<String> parseCsvLine(final String line) {
            final List<String> values = new ArrayList<>();
            final StringBuilder currentValue = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                final char currentChar = line.charAt(i);
                if (currentChar == '"') {
                    if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        currentValue.append('"');
                        i++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (currentChar == ',' && !quoted) {
                    values.add(currentValue.toString().trim());
                    currentValue.setLength(0);
                } else {
                    currentValue.append(currentChar);
                }
            }
            values.add(currentValue.toString().trim());
            return values;
        }

        private static String toHex(final byte[] bytes) {
            final StringBuilder builder = new StringBuilder();
            for (final byte value : bytes) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                final String hex = Integer.toHexString(value & 0xFF).toUpperCase(Locale.ROOT);
                if (hex.length() == 1) {
                    builder.append('0');
                }
                builder.append(hex);
            }
            return builder.toString();
        }

        private static List<String> readSharedStrings(final ZipFile zipFile)
                throws IOException, ParserConfigurationException, SAXException {
            final ZipEntry entry = zipFile.getEntry(SHARED_STRINGS_ENTRY);
            final List<String> sharedStrings = new ArrayList<>();
            if (entry == null) {
                return sharedStrings;
            }
            final Document document = parseXml(zipFile, entry);
            final NodeList textNodes = elementsByLocalName(document, "t");
            for (int i = 0; i < textNodes.getLength(); i++) {
                sharedStrings.add(textNodes.item(i).getTextContent());
            }
            return sharedStrings;
        }

        private static List<List<String>> readRows(final ZipFile zipFile, final List<String> sharedStrings)
                throws IOException, ParserConfigurationException, SAXException {
            final String firstWorksheetEntry = firstWorksheetEntry(zipFile);
            final ZipEntry entry = zipFile.getEntry(firstWorksheetEntry);
            if (entry == null) {
                throw new IOException("workbook first sheet target not found: " + firstWorksheetEntry);
            }
            final Document document = parseXml(zipFile, entry);
            final NodeList rowNodes = elementsByLocalName(document, "row");
            final List<List<String>> rows = new ArrayList<>();
            for (int rowIndex = 0; rowIndex < rowNodes.getLength(); rowIndex++) {
                final Element row = (Element) rowNodes.item(rowIndex);
                final NodeList cellNodes = elementsByLocalName(row, "c");
                final Map<Integer, String> cells = new LinkedHashMap<>();
                int maxColumnIndex = -1;
                for (int cellIndex = 0; cellIndex < cellNodes.getLength(); cellIndex++) {
                    final Element cell = (Element) cellNodes.item(cellIndex);
                    final int columnIndex = columnIndex(cell.getAttribute("r"));
                    maxColumnIndex = Math.max(maxColumnIndex, columnIndex);
                    cells.put(Integer.valueOf(columnIndex), cellValue(cell, sharedStrings));
                }
                final List<String> values = new ArrayList<>();
                for (int columnIndex = 0; columnIndex <= maxColumnIndex; columnIndex++) {
                    final String value = cells.get(Integer.valueOf(columnIndex));
                    values.add(value == null ? "" : value);
                }
                rows.add(values);
            }
            return rows;
        }

        private static String firstWorksheetEntry(final ZipFile zipFile)
                throws IOException, ParserConfigurationException, SAXException {
            final ZipEntry workbookEntry = zipFile.getEntry(WORKBOOK_ENTRY);
            if (workbookEntry == null) {
                throw new IOException("workbook metadata not found: " + WORKBOOK_ENTRY);
            }
            final Document workbookDocument = parseXml(zipFile, workbookEntry);
            final NodeList sheetNodes = elementsByLocalName(workbookDocument, "sheet");
            if (sheetNodes.getLength() == 0) {
                throw new IOException("workbook contains no sheets");
            }
            final Element firstSheet = (Element) sheetNodes.item(0);
            final String relationshipId = sheetRelationshipId(firstSheet);
            final String target = worksheetTarget(zipFile, relationshipId);
            return normalizeWorkbookTarget(target);
        }

        private static String sheetRelationshipId(final Element sheet) throws IOException {
            final String prefixedId = sheet.getAttribute("r:id");
            if (!prefixedId.isEmpty()) {
                return prefixedId;
            }
            final String namespacedId = sheet.getAttributeNS(RELATIONSHIP_NAMESPACE, "id");
            if (!namespacedId.isEmpty()) {
                return namespacedId;
            }
            throw new IOException("workbook first sheet missing relationship id");
        }

        private static String worksheetTarget(final ZipFile zipFile, final String relationshipId)
                throws IOException, ParserConfigurationException, SAXException {
            final ZipEntry relsEntry = zipFile.getEntry(WORKBOOK_RELS_ENTRY);
            if (relsEntry == null) {
                throw new IOException("workbook relationships not found: " + WORKBOOK_RELS_ENTRY);
            }
            final Document relsDocument = parseXml(zipFile, relsEntry);
            final NodeList relationshipNodes = elementsByLocalName(relsDocument, "Relationship");
            for (int i = 0; i < relationshipNodes.getLength(); i++) {
                final Element relationship = (Element) relationshipNodes.item(i);
                if (!relationshipId.equals(relationship.getAttribute("Id"))) {
                    continue;
                }
                final String type = relationship.getAttribute("Type");
                if (!WORKSHEET_RELATIONSHIP_TYPE.equals(type)) {
                    throw new IOException("workbook first sheet relationship is not a worksheet: id="
                            + relationshipId + ", type=" + type);
                }
                final String target = relationship.getAttribute("Target");
                if (target.isEmpty()) {
                    throw new IOException("workbook first sheet relationship has empty target: " + relationshipId);
                }
                return target;
            }
            throw new IOException("workbook first sheet relationship not found: " + relationshipId);
        }

        private static String normalizeWorkbookTarget(final String target) {
            String normalizedTarget = target.replace('\\', '/');
            while (normalizedTarget.startsWith("/")) {
                normalizedTarget = normalizedTarget.substring(1);
            }
            if (normalizedTarget.startsWith("xl/")) {
                return normalizedTarget;
            }
            return "xl/" + normalizedTarget;
        }

        private static Document parseXml(final ZipFile zipFile, final ZipEntry entry)
                throws IOException, ParserConfigurationException, SAXException {
            try (InputStream inputStream = zipFile.getInputStream(entry)) {
                final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
                documentBuilderFactory.setNamespaceAware(true);
                return documentBuilderFactory.newDocumentBuilder().parse(inputStream);
            }
        }

        private static NodeList elementsByLocalName(final Document document, final String localName) {
            return document.getElementsByTagNameNS("*", localName);
        }

        private static NodeList elementsByLocalName(final Element element, final String localName) {
            return element.getElementsByTagNameNS("*", localName);
        }

        private static String cellValue(final Element cell, final List<String> sharedStrings) {
            final String cellType = cell.getAttribute("t");
            if ("inlineStr".equals(cellType)) {
                final NodeList textNodes = elementsByLocalName(cell, "t");
                return textNodes.getLength() == 0 ? "" : textNodes.item(0).getTextContent();
            }
            final NodeList valueNodes = elementsByLocalName(cell, "v");
            final String value = valueNodes.getLength() == 0 ? "" : valueNodes.item(0).getTextContent();
            if ("s".equals(cellType) && !value.isEmpty()) {
                return sharedStrings.get(Integer.parseInt(value));
            }
            return value;
        }

        private static int columnIndex(final String cellReference) {
            int index = 0;
            int position = 0;
            while (position < cellReference.length() && Character.isLetter(cellReference.charAt(position))) {
                index = (index * 26) + (Character.toUpperCase(cellReference.charAt(position)) - 'A' + 1);
                position++;
            }
            return index - 1;
        }

        private static List<PlcTag> toSupportedTags(final List<List<String>> rows) throws IOException {
            final List<String> header = rows.get(0);
            final int nameIndex = headerIndex(header, "Name");
            final int dataTypeIndex = headerIndex(header, "Data Type");
            final int addressIndex = headerIndex(header, "Logical Address");
            final List<PlcTag> tags = new ArrayList<>();
            for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
                final List<String> row = rows.get(rowIndex);
                final String address = valueAt(row, addressIndex);
                if (address.isEmpty()) {
                    continue;
                }
                final PlcTag parsedTag = PlcTag.parse(
                        tags.size(),
                        valueAt(row, nameIndex),
                        valueAt(row, dataTypeIndex),
                        address);
                tags.add(parsedTag);
            }
            return tags;
        }

        private static int headerIndex(final List<String> header, final String name) throws IOException {
            for (int i = 0; i < header.size(); i++) {
                if (name.equals(header.get(i))) {
                    return i;
                }
            }
            throw new IOException("tag workbook missing header: " + name);
        }

        private static String valueAt(final List<String> row, final int index) {
            if (index >= row.size()) {
                return "";
            }
            return row.get(index).trim();
        }
    }

    private static final class PlcTag {
        private final int index;
        private final String name;
        private final String logicalAddress;
        private final DaveArea area;
        private final int byteOffset;
        private final int bitOffset;
        private final int size;
        private final S7Type type;
        private final Class<?> fieldType;

        private PlcTag(
                final int index,
                final String name,
                final String logicalAddress,
                final DaveArea area,
                final int byteOffset,
                final int bitOffset,
                final int size,
                final S7Type type,
                final Class<?> fieldType) {
            this.index = index;
            this.name = name;
            this.logicalAddress = logicalAddress;
            this.area = area;
            this.byteOffset = byteOffset;
            this.bitOffset = bitOffset;
            this.size = size;
            this.type = type;
            this.fieldType = fieldType;
        }

        private static PlcTag parse(
                final int index,
                final String name,
                final String dataType,
                final String logicalAddress) throws IOException {
            if (!logicalAddress.startsWith("%") || logicalAddress.length() < 3) {
                throw new IOException("unsupported logical address: " + logicalAddress);
            }
            final DaveArea area = area(logicalAddress.charAt(1), logicalAddress);
            final TagType tagType = TagType.fromDataType(dataType);
            final AddressOffset offset = parseOffset(logicalAddress, tagType);
            return new PlcTag(index, name, logicalAddress, area, offset.getByteOffset(), offset.getBitOffset(),
                    tagType.getSize(), tagType.getType(), tagType.getFieldType());
        }

        private int getIndex() {
            return this.index;
        }

        private DaveArea getArea() {
            return this.area;
        }

        private int getDbNum() {
            return 0;
        }

        private int getByteOffset() {
            return this.byteOffset;
        }

        private int getSize() {
            return this.size;
        }

        private PlcS7PointVariable toPointVariable() {
            return new PlcS7PointVariable(0, this.byteOffset, this.bitOffset, this.size, this.area, this.type, this.fieldType);
        }

        private Object valueForCycle(final int cycle, final int ordinal) {
            final int seed = ((cycle + 1) * 1000) + ordinal;
            switch (this.type) {
                case BOOL:
                    return Boolean.valueOf(seed % 2 == 0);
                case INT:
                    return Short.valueOf((short) (seed % 30000));
                case WORD:
                    return Integer.valueOf(seed & 0xFFFF);
                case DINT:
                case DWORD:
                    return Long.valueOf(100000L + seed);
                case REAL:
                    return Float.valueOf(seed + 0.25f);
                default:
                    throw new IllegalArgumentException("unsupported writable type: " + this.type);
            }
        }

        private String describe() {
            return this.name + " " + this.logicalAddress + " " + this.type;
        }

        private static DaveArea area(final char areaCode, final String logicalAddress) throws IOException {
            switch (areaCode) {
                case 'I':
                    return DaveArea.INPUTS;
                case 'Q':
                    return DaveArea.OUTPUTS;
                case 'M':
                    return DaveArea.FLAGS;
                default:
                    throw new IOException("unsupported area in logical address: " + logicalAddress);
            }
        }

        private static AddressOffset parseOffset(final String logicalAddress, final TagType tagType) throws IOException {
            if (tagType.getType() == S7Type.BOOL) {
                final int dotIndex = logicalAddress.indexOf('.');
                if (dotIndex < 0) {
                    throw new IOException("BOOL logical address must contain bit offset: " + logicalAddress);
                }
                return new AddressOffset(
                        Integer.parseInt(logicalAddress.substring(2, dotIndex)),
                        Integer.parseInt(logicalAddress.substring(dotIndex + 1)));
            }
            final int offsetStart = firstDigitIndex(logicalAddress);
            return new AddressOffset(Integer.parseInt(logicalAddress.substring(offsetStart)), 0);
        }

        private static int firstDigitIndex(final String logicalAddress) throws IOException {
            for (int i = 2; i < logicalAddress.length(); i++) {
                if (Character.isDigit(logicalAddress.charAt(i))) {
                    return i;
                }
            }
            throw new IOException("logical address missing byte offset: " + logicalAddress);
        }
    }

    private enum TagType {
        BOOL(S7Type.BOOL, Boolean.class, 1),
        INT(S7Type.INT, Short.class, 2),
        WORD(S7Type.WORD, Integer.class, 2),
        DINT(S7Type.DINT, Long.class, 4),
        DWORD(S7Type.DWORD, Long.class, 4),
        REAL(S7Type.REAL, Float.class, 4);

        private final S7Type type;
        private final Class<?> fieldType;
        private final int size;

        TagType(final S7Type type, final Class<?> fieldType, final int size) {
            this.type = type;
            this.fieldType = fieldType;
            this.size = size;
        }

        private static TagType fromDataType(final String dataType) throws IOException {
            final String normalizedType = dataType.trim().toUpperCase(Locale.ROOT);
            if ("BOOL".equals(normalizedType)) {
                return BOOL;
            }
            if ("INT".equals(normalizedType)) {
                return INT;
            }
            if ("WORD".equals(normalizedType)) {
                return WORD;
            }
            if ("DINT".equals(normalizedType)) {
                return DINT;
            }
            if ("DWORD".equals(normalizedType)) {
                return DWORD;
            }
            if ("REAL".equals(normalizedType)) {
                return REAL;
            }
            throw new IOException("unsupported tag data type: " + dataType);
        }

        private S7Type getType() {
            return this.type;
        }

        private Class<?> getFieldType() {
            return this.fieldType;
        }

        private int getSize() {
            return this.size;
        }
    }

    private static final class AddressOffset {
        private final int byteOffset;
        private final int bitOffset;

        private AddressOffset(final int byteOffset, final int bitOffset) {
            this.byteOffset = byteOffset;
            this.bitOffset = bitOffset;
        }

        private int getByteOffset() {
            return this.byteOffset;
        }

        private int getBitOffset() {
            return this.bitOffset;
        }
    }

    private static final class MemoryRange {
        private final DaveArea area;
        private final int dbNum;
        private final int startOffset;
        private final int endOffset;

        private MemoryRange(final DaveArea area, final int dbNum, final int startOffset, final int endOffset) {
            this.area = area;
            this.dbNum = dbNum;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        private DaveArea getArea() {
            return this.area;
        }

        private int getDbNum() {
            return this.dbNum;
        }

        private int getStartOffset() {
            return this.startOffset;
        }

        private int getLength() {
            return this.endOffset - this.startOffset;
        }

        private boolean canMerge(final MemoryRange other) {
            return this.area == other.area
                    && this.dbNum == other.dbNum
                    && other.startOffset <= this.endOffset;
        }

        private MemoryRange merge(final MemoryRange other) {
            return new MemoryRange(this.area, this.dbNum, this.startOffset, Math.max(this.endOffset, other.endOffset));
        }
    }

    private static final class MemorySnapshot {
        private final MemoryRange range;
        private final byte[] buffer;

        private MemorySnapshot(final MemoryRange range, final byte[] buffer) {
            this.range = range;
            this.buffer = buffer;
        }

        private MemoryRange getRange() {
            return this.range;
        }

        private byte[] getBuffer() {
            return this.buffer;
        }
    }

    private static final class WriteExpectation {
        private final PlcTag tag;
        private final Object value;

        private WriteExpectation(final PlcTag tag, final Object value) {
            this.tag = tag;
            this.value = value;
        }

        private PlcTag getTag() {
            return this.tag;
        }

        private Object getValue() {
            return this.value;
        }
    }
}
