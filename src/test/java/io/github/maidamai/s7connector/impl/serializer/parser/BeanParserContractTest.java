package io.github.maidamai.s7connector.impl.serializer.parser;

import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.api.annotation.S7Variable;
import io.github.maidamai.s7connector.exception.S7Exception;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link BeanParser}: public-field mapping contract,
 * fast-failure on unmapped beans, primitive wrapper resolution and
 * declaration-order independent block size.
 */
class BeanParserContractTest {

    // ------------------------------------------------------------------
    // fixture beans
    // ------------------------------------------------------------------

    static class PlainUnmappedBean {
        public Integer plainField;
    }

    static class PrivateOnlyMappedBean {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0)
        private Boolean running;
    }

    static class MixedVisibilityBean {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0)
        public Boolean running;

        @S7Variable(type = S7Type.INT, byteOffset = 2)
        private Short hidden;
    }

    static class PublicMappedBean {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0)
        public Boolean running;

        @S7Variable(type = S7Type.INT, byteOffset = 2)
        public Short speed;
    }

    static class AllPrimitivesBean {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0)
        public boolean boolField;

        @S7Variable(type = S7Type.BYTE, byteOffset = 1)
        public byte byteField;

        @S7Variable(type = S7Type.WORD, byteOffset = 2)
        public char charField;

        @S7Variable(type = S7Type.INT, byteOffset = 4)
        public short shortField;

        @S7Variable(type = S7Type.WORD, byteOffset = 6)
        public int intField;

        @S7Variable(type = S7Type.DINT, byteOffset = 8)
        public long longField;

        @S7Variable(type = S7Type.REAL, byteOffset = 12)
        public float floatField;

        @S7Variable(type = S7Type.REAL, byteOffset = 16)
        public double doubleField;
    }

    static class LowOffsetFirst {
        @S7Variable(type = S7Type.INT, byteOffset = 0)
        public Short low;

        @S7Variable(type = S7Type.BYTE, byteOffset = 10)
        public Byte high;
    }

    static class HighOffsetFirst {
        @S7Variable(type = S7Type.BYTE, byteOffset = 10)
        public Byte high;

        @S7Variable(type = S7Type.INT, byteOffset = 0)
        public Short low;
    }

    static class InnerStructBean {
        @S7Variable(type = S7Type.INT, byteOffset = 0)
        public Short x;
    }

    static class StructLowFirst {
        @S7Variable(type = S7Type.BYTE, byteOffset = 0)
        public Byte prefix;

        @S7Variable(type = S7Type.STRUCT, byteOffset = 4)
        public InnerStructBean inner;
    }

    static class StructHighFirst {
        @S7Variable(type = S7Type.STRUCT, byteOffset = 4)
        public InnerStructBean inner;

        @S7Variable(type = S7Type.BYTE, byteOffset = 0)
        public Byte prefix;
    }

    static class CoverageKindsBean {
        @S7Variable(type = S7Type.STRING, byteOffset = 0, size = 10)
        public String text;

        @S7Variable(type = S7Type.BOOL, byteOffset = 12, bitOffset = 7, arraySize = 3)
        public Boolean[] flags;

        @S7Variable(type = S7Type.INT, byteOffset = 20, arraySize = 4)
        public Short[] words;
    }

    static class StringArrayCoverageBean {
        @S7Variable(type = S7Type.STRING, byteOffset = 0, size = 10, arraySize = 2)
        public String[] tags;
    }

    static class NestedItemBean {
        @S7Variable(type = S7Type.INT, byteOffset = 0)
        public Short x;

        @S7Variable(type = S7Type.BYTE, byteOffset = 2)
        public Byte y;
    }

    static class StructArrayCoverageBean {
        @S7Variable(type = S7Type.STRUCT, byteOffset = 1, arraySize = 3)
        public NestedItemBean[] items;
    }

    static class OversizedStringBean {
        @S7Variable(type = S7Type.STRING, byteOffset = 0, size = 255)
        public String text;
    }

    static class NegativeArraySizeBean {
        @S7Variable(type = S7Type.INT, byteOffset = 0, arraySize = -1)
        public Short[] words;
    }

    static class NegativeOffsetBean {
        @S7Variable(type = S7Type.INT, byteOffset = -1)
        public Short x;
    }

    static class NegativeSizeBean {
        @S7Variable(type = S7Type.STRING, byteOffset = 0, size = -1)
        public String text;
    }

    static class SelfReferencingBean {
        @S7Variable(type = S7Type.INT, byteOffset = 0)
        public Short x;

        @S7Variable(type = S7Type.STRUCT, byteOffset = 2)
        public SelfReferencingBean inner;
    }

    static class SelfReferencingArrayBean {
        @S7Variable(type = S7Type.STRUCT, byteOffset = 0, arraySize = 2)
        public SelfReferencingArrayBean[] children;
    }

    static class OversizedCoverageBean {
        // 254 + 2 header bytes per element * 10,000,000 elements > Integer.MAX_VALUE
        @S7Variable(type = S7Type.STRING, byteOffset = 0, size = 254, arraySize = 10_000_000)
        public String[] texts;
    }

    // ------------------------------------------------------------------
    // tests
    // ------------------------------------------------------------------

    @Test
    void failsFastOnBeanWithoutMappedFields() {
        final S7Exception ex = assertThrows(S7Exception.class,
                () -> BeanParser.parse(PlainUnmappedBean.class));
        assertTrue(ex.getMessage().contains(PlainUnmappedBean.class.getName()),
                "message should carry the offending class name: " + ex.getMessage());
    }

    @Test
    void failsFastOnPrivateOnlyMappedBean() {
        final S7Exception ex = assertThrows(S7Exception.class,
                () -> BeanParser.parse(PrivateOnlyMappedBean.class));
        assertTrue(ex.getMessage().contains(PrivateOnlyMappedBean.class.getName()),
                "message should carry the offending class name: " + ex.getMessage());
    }

    @Test
    void mapsPublicFieldsAndIgnoresPrivateOnes() throws Exception {
        final BeanParseResult mixed = BeanParser.parse(MixedVisibilityBean.class);
        assertEquals(1, mixed.entries.size(), "private mapped fields must not be mapped");
        assertEquals("running", mixed.entries.get(0).field.getName());

        final BeanParseResult all = BeanParser.parse(PublicMappedBean.class);
        assertEquals(2, all.entries.size());
        assertEquals(4, all.blockSize, "BOOL at byte 0 and INT at byte 2 must cover 4 bytes");
    }

    @Test
    void resolvesWrapperTypeForEveryPrimitive() throws Exception {
        final BeanParseResult result = BeanParser.parse(AllPrimitivesBean.class);

        final Map<String, Class<?>> wrappersByField = new HashMap<>();
        for (final BeanEntry entry : result.entries) {
            wrappersByField.put(entry.field.getName(), entry.type);
        }

        assertEquals(Boolean.class, wrappersByField.get("boolField"));
        assertEquals(Byte.class, wrappersByField.get("byteField"));
        assertEquals(Character.class, wrappersByField.get("charField"));
        assertEquals(Short.class, wrappersByField.get("shortField"));
        assertEquals(Integer.class, wrappersByField.get("intField"));
        assertEquals(Long.class, wrappersByField.get("longField"));
        assertEquals(Float.class, wrappersByField.get("floatField"));
        assertEquals(Double.class, wrappersByField.get("doubleField"));
    }

    @Test
    void blockSizeIsIndependentOfFieldDeclarationOrder() throws Exception {
        assertEquals(BeanParser.parse(LowOffsetFirst.class).blockSize,
                BeanParser.parse(HighOffsetFirst.class).blockSize,
                "plain fields: declaration order must not change blockSize");
        assertEquals(11, BeanParser.parse(LowOffsetFirst.class).blockSize,
                "INT@0 + BYTE@10 must cover 11 bytes");

        assertEquals(BeanParser.parse(StructLowFirst.class).blockSize,
                BeanParser.parse(StructHighFirst.class).blockSize,
                "struct fields: declaration order must not change blockSize");
        assertEquals(6, BeanParser.parse(StructLowFirst.class).blockSize,
                "BYTE@0 + STRUCT{INT}@4 must cover 6 bytes");
    }

    @Test
    void blockSizeCoversStringBoolArrayAndTypedArray() throws Exception {
        final BeanParseResult result = BeanParser.parse(CoverageKindsBean.class);
        // STRING size 10 -> 12 bytes header included
        // BOOL array of 3 starting at bit 7 -> 2 bytes (bits 7..9)
        // INT array of 4 at offset 20 -> 8 bytes
        assertEquals(28, result.blockSize, "blockSize must be max(offset + coverage) over all entries");
        assertEquals(3, result.entries.size());
        for (final BeanEntry entry : result.entries) {
            assertNotNull(entry.serializer, "every entry needs a serializer instance");
        }
    }

    @Test
    void stringArrayCoverageAndElementOffsetsShareTheCapacityStride() throws Exception {
        final BeanParseResult result = BeanParser.parse(StringArrayCoverageBean.class);
        assertEquals(24, result.blockSize, "2 elements of capacity 10 + 2 header bytes each");
        final BeanEntry entry = result.entries.get(0);
        assertEquals(12, entry.getElementByteOffset(1),
                "the second STRING element must sit after the first element's full capacity, not after 2 header bytes");
        assertEquals(0, entry.getElementByteOffset(0));
    }

    @Test
    void structArrayCoverageCountsEveryNestedBlock() throws Exception {
        final BeanParseResult result = BeanParser.parse(StructArrayCoverageBean.class);
        // 3 nested blocks of 3 bytes each, starting at byte 1
        assertEquals(10, result.blockSize, "STRUCT array coverage must count every element's nested block size");
        final BeanEntry entry = result.entries.get(0);
        assertEquals(1, entry.getElementByteOffset(0));
        assertEquals(4, entry.getElementByteOffset(1), "second struct element must advance by the nested block size");
        assertEquals(7, entry.getElementByteOffset(2));
    }

    @Test
    void rejectsStringCapacityAboveTheEncodableHeaderByte() {
        final S7Exception ex = assertThrows(S7Exception.class, () -> BeanParser.parse(OversizedStringBean.class));
        assertTrue(ex.getMessage().contains("255"), "message should name the offending size: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("254"), "message should name the encodable maximum: " + ex.getMessage());
    }

    @Test
    void rejectsNegativeAnnotationValuesAtParseTime() {
        assertThrows(S7Exception.class, () -> BeanParser.parse(NegativeArraySizeBean.class),
                "negative arraySize must be rejected, not silently laid out");
        assertThrows(S7Exception.class, () -> BeanParser.parse(NegativeOffsetBean.class),
                "negative byteOffset must be rejected");
        assertThrows(S7Exception.class, () -> BeanParser.parse(NegativeSizeBean.class),
                "negative size must be rejected");
    }

    @Test
    void rejectsRecursiveStructNestingInsteadOfStackOverflow() {
        final S7Exception direct = assertThrows(S7Exception.class,
                () -> BeanParser.parse(SelfReferencingBean.class),
                "a self-referencing STRUCT must fail with a clear error, not a StackOverflowError");
        assertTrue(direct.getMessage().contains("Recursive STRUCT mapping rejected"),
                "message should name the cycle: " + direct.getMessage());

        final S7Exception array = assertThrows(S7Exception.class,
                () -> BeanParser.parse(SelfReferencingArrayBean.class),
                "a self-referencing STRUCT array must fail with a clear error too");
        assertTrue(array.getMessage().contains(SelfReferencingArrayBean.class.getName()));
    }

    @Test
    void rejectsCoverageThatOverflowsTheBlockSize() {
        final S7Exception ex = assertThrows(S7Exception.class, () -> BeanParser.parse(OversizedCoverageBean.class),
                "an entry covering more than Integer.MAX_VALUE bytes must be rejected at parse time");
        assertTrue(ex.getMessage().contains("exceeds the representable block size"),
                "message should explain the overflow: " + ex.getMessage());
    }

    @Test
    void parsesBeanInstanceLikeBeanClass() throws Exception {
        final LowOffsetFirst instance = new LowOffsetFirst();
        final BeanParseResult fromInstance = BeanParser.parse((Object) instance);
        final BeanParseResult fromClass = BeanParser.parse(LowOffsetFirst.class);
        assertEquals(fromClass.blockSize, fromInstance.blockSize);
        assertEquals(fromClass.entries.size(), fromInstance.entries.size());
        for (int i = 0; i < fromClass.entries.size(); i++) {
            final Field expected = fromClass.entries.get(i).field;
            assertEquals(expected, fromInstance.entries.get(i).field);
        }
    }
}
