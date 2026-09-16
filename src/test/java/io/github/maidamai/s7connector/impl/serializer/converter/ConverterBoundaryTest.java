package io.github.maidamai.s7connector.impl.serializer.converter;

import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.api.annotation.S7Variable;
import io.github.maidamai.s7connector.impl.serializer.S7SerializerImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Boundary tests for the primitive converters: BOOL array element
 * positioning (byte-crossing), the ASCII S7 STRING contract, the signed
 * DWORD/DINT interpretation and the Short boundaries.
 */
class ConverterBoundaryTest {

    public static class BoolArraySize7 {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0, arraySize = 7)
        public Boolean[] bits;
    }

    public static class BoolArraySize8 {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0, arraySize = 8)
        public Boolean[] bits;
    }

    public static class BoolArraySize9 {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0, arraySize = 9)
        public Boolean[] bits;
    }

    public static class BoolArraySize16 {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0, arraySize = 16)
        public Boolean[] bits;
    }

    public static class BoolArraySize9StartBit7 {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 7, arraySize = 9)
        public Boolean[] bits;
    }

    // ------------------------------------------------------------------
    // BOOL arrays (defect: bit addressing beyond 0-7)
    // ------------------------------------------------------------------

    @Test
    void boolArraysAtByteBoundarySizesRoundTripThroughTheBeanPath() throws Exception {
        assertBoolArrayRoundTrip(BoolArraySize7.class, 7, 0);
        assertBoolArrayRoundTrip(BoolArraySize8.class, 8, 0);
        assertBoolArrayRoundTrip(BoolArraySize9.class, 9, 0);
        assertBoolArrayRoundTrip(BoolArraySize16.class, 16, 0);
    }

    @Test
    void boolArrayStartingAtBitOffset7AddressesElementsAcrossBytes() throws Exception {
        assertBoolArrayRoundTrip(BoolArraySize9StartBit7.class, 9, 7);
    }

    private static void assertBoolArrayRoundTrip(final Class<?> beanClass, final int size, final int startBit)
            throws Exception {
        final Object bean = beanClass.newInstance();
        final Boolean[] values = new Boolean[size];
        for (int i = 0; i < size; i++) {
            values[i] = i % 2 == 0;
        }
        final Field bitsField = beanClass.getField("bits");
        bitsField.set(bean, values);

        final byte[] buffer = new byte[(startBit + size + 7) / 8];
        S7SerializerImpl.insertBytes(bean, buffer, 0);

        final byte[] expected = new byte[buffer.length];
        for (int i = 0; i < size; i++) {
            if (values[i]) {
                final int absoluteBit = startBit + i;
                expected[absoluteBit / 8] |= (byte) (0x01 << (absoluteBit % 8));
            }
        }
        assertArrayEquals(expected, buffer,
                beanClass.getSimpleName() + ": every element must hit its own bit");

        final Object readBack = S7SerializerImpl.extractBytes(beanClass, buffer, 0);
        assertArrayEquals(values, (Boolean[]) bitsField.get(readBack),
                beanClass.getSimpleName() + ": extract must return every element");
    }

    // ------------------------------------------------------------------
    // STRING (defect: platform charset and char-count based length)
    // ------------------------------------------------------------------

    @Test
    void stringExtractHandlesEmptyString() {
        final byte[] buffer = new byte[]{0x00, 0x00, 'x', 'y'};
        assertEquals("", new StringConverter().extract(String.class, buffer, 0, 0));
    }

    @Test
    void stringExtractHandlesFullCapacityPayload() {
        final byte[] buffer = new byte[]{0x06, 0x06, 'a', 'b', 'c', 'd', 'e', 'f'};
        assertEquals("abcdef", new StringConverter().extract(String.class, buffer, 0, 0));
    }

    @Test
    void stringExtractRejectsCurrentLengthAboveMaxLength() {
        final byte[] buffer = new byte[]{0x04, 0x05, 'a', 'b', 'c', 'd'};
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new StringConverter().extract(String.class, buffer, 0, 0));
        assertEquals(true, ex.getMessage().contains("currentLength=5")
                && ex.getMessage().contains("maxLength=4"), ex.getMessage());
    }

    @Test
    void stringExtractRejectsCurrentLengthBeyondRemainingBuffer() {
        final byte[] buffer = new byte[]{0x0A, 0x04, 'x'};
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new StringConverter().extract(String.class, buffer, 0, 0));
        assertEquals(true, ex.getMessage().contains("currentLength=4")
                && ex.getMessage().contains("remaining buffer"), ex.getMessage());
    }

    @Test
    void stringInsertRejectsNonAsciiCharacters() {
        final StringConverter converter = new StringConverter();
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> converter.insert("h\u00E9llo", new byte[7], 0, 0, 5));
        assertEquals(true, ex.getMessage().contains("ASCII"), ex.getMessage());
    }

    @Test
    void stringInsertRejectsValueLargerThanCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> new StringConverter().insert("abc", new byte[5], 0, 0, 2));
    }

    @Test
    void stringInsertIntoTooSmallBufferThrows() {
        // header fits into bytes 0..2, but the payload of two bytes does not
        assertThrows(IndexOutOfBoundsException.class,
                () -> new StringConverter().insert("AB", new byte[3], 0, 0, 2));
    }

    @Test
    void stringInsertWritesAsciiHeaderAndPayload() {
        final byte[] buffer = new byte[6];
        new StringConverter().insert("AB", buffer, 0, 0, 4);

        assertEquals(4, buffer[0] & 0xFF, "max length header");
        assertEquals(2, buffer[1] & 0xFF, "current length must be the encoded byte count");
        assertEquals((byte) 'A', buffer[2]);
        assertEquals((byte) 'B', buffer[3]);
        assertEquals(0, buffer[4]);
        assertEquals(0, buffer[5]);
    }

    @Test
    void stringAsciiRoundTrip() {
        final StringConverter converter = new StringConverter();
        final byte[] buffer = new byte[2 + 8];
        converter.insert("S7-1500!", buffer, 0, 0, 8);
        assertEquals("S7-1500!", converter.extract(String.class, buffer, 0, 0));
    }

    // ------------------------------------------------------------------
    // DWORD / DINT (locked signed interpretation)
    // ------------------------------------------------------------------

    @Test
    void dwordAllBitsSetReadsAsMinusOne() {
        final Long value = new LongConverter().extract(Long.class,
                new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, 0, 0);
        assertEquals(Long.valueOf(-1L), value, "DWORD 0xFFFFFFFF is read as a signed -1L by contract");
    }

    @Test
    void dintPositiveAndNegativeBoundariesRoundTrip() {
        final LongConverter converter = new LongConverter();
        assertDintRoundTrip(converter, 2147483647L, new byte[]{0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        assertDintRoundTrip(converter, -2147483648L, new byte[]{(byte) 0x80, 0x00, 0x00, 0x00});
        assertDintRoundTrip(converter, -1L, new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
    }

    private static void assertDintRoundTrip(final LongConverter converter, final long value, final byte[] expected) {
        final byte[] buffer = new byte[4];
        converter.insert(value, buffer, 0, 0, 4);
        assertArrayEquals(expected, buffer, "insert must produce the big endian raw 32 bits");
        assertEquals(Long.valueOf(value), converter.extract(Long.class, buffer, 0, 0));
    }

    // ------------------------------------------------------------------
    // INT (short)
    // ------------------------------------------------------------------

    @Test
    void shortConverterHandlesSignBoundaries() {
        final ShortConverter converter = new ShortConverter();
        assertEquals(Short.valueOf((short) -32768), converter.extract(Short.class,
                new byte[]{(byte) 0x80, 0x00}, 0, 0), "0x8000 must read as -32768");
        assertEquals(Short.valueOf((short) 32767), converter.extract(Short.class,
                new byte[]{0x7F, (byte) 0xFF}, 0, 0), "0x7FFF must read as 32767");
        assertEquals(Short.valueOf((short) 0), converter.extract(Short.class,
                new byte[]{0x00, 0x00}, 0, 0));
        assertEquals(Short.valueOf((short) -1), converter.extract(Short.class,
                new byte[]{(byte) 0xFF, (byte) 0xFF}, 0, 0));
    }

    @Test
    void shortConverterRoundTripsNegativeAndPositiveValues() {
        final ShortConverter converter = new ShortConverter();
        final short[] values = {(short) -32768, (short) -2, (short) 0, (short) 1, (short) 32767};
        for (final short value : values) {
            final byte[] buffer = new byte[2];
            converter.insert(value, buffer, 0, 0, 2);
            assertEquals(Short.valueOf(value), converter.extract(Short.class, buffer, 0, 0),
                    "round trip of " + value);
        }
    }
}
