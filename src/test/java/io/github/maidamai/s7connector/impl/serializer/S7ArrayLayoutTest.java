package io.github.maidamai.s7connector.impl.serializer;

import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.api.annotation.S7Variable;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole-bean extract/insert round trips locking the array element layout:
 * BOOL arrays must advance bit by bit and cross byte boundaries, mixed-type
 * beans must place every element at its declared offset.
 */
class S7ArrayLayoutTest {

    static class BoolArray7 {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0, arraySize = 7)
        public Boolean[] bits;
    }

    static class BoolArray8 {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0, arraySize = 8)
        public Boolean[] bits;
    }

    static class BoolArray9 {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0, arraySize = 9)
        public Boolean[] bits;
    }

    static class BoolArray16 {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0, arraySize = 16)
        public Boolean[] bits;
    }

    static class BoolArray9StartingAtBit7 {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 7, arraySize = 9)
        public Boolean[] bits;
    }

    static class MixedLayoutBean {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0, arraySize = 16)
        public Boolean[] flags;

        @S7Variable(type = S7Type.INT, byteOffset = 2)
        public Short speed;

        @S7Variable(type = S7Type.BYTE, byteOffset = 4, arraySize = 3)
        public Byte[] counters;

        @S7Variable(type = S7Type.STRING, byteOffset = 8, size = 4)
        public String tag;
    }

    // ------------------------------------------------------------------
    // BOOL arrays
    // ------------------------------------------------------------------

    @Test
    void boolArrayRoundTripForSizes7And8() {
        roundTripBoolArray(BoolArray7.class, 7, 0);
        roundTripBoolArray(BoolArray8.class, 8, 0);
    }

    @Test
    void boolArrayRoundTripCrossesByteBoundaryForSizes9And16() {
        roundTripBoolArray(BoolArray9.class, 9, 0);
        roundTripBoolArray(BoolArray16.class, 16, 0);
    }

    @Test
    void boolArrayWithHighStartBitOffsetPlacesEveryElement() {
        final BoolArray9StartingAtBit7 bean = new BoolArray9StartingAtBit7();
        bean.bits = new Boolean[9];
        for (int i = 0; i < 9; i++) {
            bean.bits[i] = i % 3 != 0; // false, true, true, false, true, true, ...
        }

        final byte[] buffer = new byte[4];
        S7SerializerImpl.insertBytes(bean, buffer, 0);

        // element i lives at absolute bit 7 + i: byte 0 bit 7, then byte 1 bits 0..7
        final byte[] expected = new byte[4];
        for (int i = 0; i < 9; i++) {
            if (bean.bits[i]) {
                final int absoluteBit = 7 + i;
                expected[absoluteBit / 8] |= (byte) (0x01 << (absoluteBit % 8));
            }
        }
        assertArrayEquals(expected, buffer, "each BOOL element must land on its absolute bit");

        final BoolArray9StartingAtBit7 readBack = S7SerializerImpl.extractBytes(
                BoolArray9StartingAtBit7.class, buffer, 0);
        assertArrayEquals(bean.bits, readBack.bits, "extract must read back the same elements");
    }

    private static void roundTripBoolArray(final Class<?> beanClass, final int size, final int startBit) {
        final Object bean;
        try {
            bean = beanClass.newInstance();
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        final Boolean[] values = new Boolean[size];
        for (int i = 0; i < size; i++) {
            values[i] = i % 2 == 0;
        }
        try {
            final java.lang.reflect.Field field = beanClass.getField("bits");
            field.set(bean, values);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }

        final byte[] buffer = new byte[(startBit + size + 7) / 8 + 1];
        S7SerializerImpl.insertBytes(bean, buffer, 0);

        final byte[] expected = new byte[buffer.length];
        for (int i = 0; i < size; i++) {
            if (values[i]) {
                final int absoluteBit = startBit + i;
                expected[absoluteBit / 8] |= (byte) (0x01 << (absoluteBit % 8));
            }
        }
        assertArrayEquals(expected, buffer, "insert must place element " + size + " array bits correctly");

        final Object readBack = S7SerializerImpl.extractBytes(beanClass, buffer, 0);
        try {
            final Boolean[] readValues = (Boolean[]) beanClass.getField("bits").get(readBack);
            assertArrayEquals(values, readValues, "extract must return every element");
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // mixed layout
    // ------------------------------------------------------------------

    @Test
    void mixedTypeBeanRoundTripPlacesEveryElement() {
        final MixedLayoutBean bean = new MixedLayoutBean();
        bean.flags = new Boolean[16];
        for (int i = 0; i < 16; i++) {
            bean.flags[i] = i % 2 == 0; // 0x55 pattern in both bytes
        }
        bean.speed = (short) -2; // 0xFFFE big endian at byte 2
        bean.counters = new Byte[]{0x11, 0x22, 0x33};
        bean.tag = "AB";

        final byte[] buffer = new byte[14];
        S7SerializerImpl.insertBytes(bean, buffer, 0);

        final byte[] expected = new byte[14];
        expected[0] = 0x55; // flags 0..7, even indexes set
        expected[1] = 0x55; // flags 8..15
        expected[2] = (byte) 0xFF; // INT high byte
        expected[3] = (byte) 0xFE; // INT low byte
        expected[4] = 0x11;
        expected[5] = 0x22;
        expected[6] = 0x33;
        expected[7] = 0x00; // untouched gap byte
        expected[8] = 0x04; // STRING max length
        expected[9] = 0x02; // STRING current length
        expected[10] = 'A';
        expected[11] = 'B';
        expected[12] = 0x00; // unused STRING capacity
        expected[13] = 0x00;
        assertArrayEquals(expected, buffer, "every element must land at its declared offset");

        final MixedLayoutBean readBack = S7SerializerImpl.extractBytes(MixedLayoutBean.class, buffer, 0);
        assertArrayEquals(bean.flags, readBack.flags, "BOOL array elements must survive the round trip");
        assertEquals(bean.speed, readBack.speed);
        assertArrayEquals(bean.counters, readBack.counters);
        assertEquals("AB", readBack.tag);
    }

    @Test
    void nonBoolArraysKeepElementStrideInBytes() {
        final MixedLayoutBean bean = new MixedLayoutBean();
        bean.flags = new Boolean[16];
        bean.speed = (short) 0x0102;
        bean.counters = new Byte[]{0x01, 0x00, 0x03};
        bean.tag = "";

        final byte[] buffer = new byte[14];
        S7SerializerImpl.insertBytes(bean, buffer, 0);

        assertEquals((byte) 0x01, buffer[4], "first BYTE array element");
        assertEquals((byte) 0x00, buffer[5], "second BYTE array element");
        assertEquals((byte) 0x03, buffer[6], "third BYTE array element");

        final MixedLayoutBean readBack = S7SerializerImpl.extractBytes(MixedLayoutBean.class, buffer, 0);
        assertTrue(Arrays.equals(bean.counters, readBack.counters), "BYTE array stride must be one byte per element");
        assertEquals(bean.speed, readBack.speed);
    }

    // ------------------------------------------------------------------
    // STRING arrays: element stride must be capacity + 2 header bytes
    // ------------------------------------------------------------------

    static class StringArrayBean {
        @S7Variable(type = S7Type.STRING, byteOffset = 0, size = 10, arraySize = 2)
        public String[] tags;
    }

    static class StringArray3WithOffsetAndSentinel {
        @S7Variable(type = S7Type.STRING, byteOffset = 4, size = 6, arraySize = 3)
        public String[] tags;

        @S7Variable(type = S7Type.BYTE, byteOffset = 28)
        public Byte sentinel;
    }

    @Test
    void stringArrayPlacesHeadersAtCapacityStride() {
        final StringArrayBean bean = new StringArrayBean();
        bean.tags = new String[]{"ONE", "TWOTWO"};

        final byte[] buffer = new byte[24]; // 2 * (10 + 2)
        S7SerializerImpl.insertBytes(bean, buffer, 0);

        // element headers must sit at 0 and 12 — not at 0 and 2
        assertEquals(10, buffer[0] & 0xFF, "first max-length header");
        assertEquals(3, buffer[1] & 0xFF, "first current-length header");
        assertEquals(10, buffer[12] & 0xFF, "second max-length header must sit after the full first element");
        assertEquals(6, buffer[13] & 0xFF, "second current-length header");

        // contents must not overlap: first content in 2..11, second in 14..23
        assertEquals('O', buffer[2]);
        assertEquals('E', buffer[4]);
        assertEquals(0, buffer[5], "unused capacity of the first element");
        assertEquals('T', buffer[14], "second content must start after the first element's capacity");
        assertEquals('O', buffer[19]);
        assertEquals(0, buffer[20], "unused capacity of the second element");

        final StringArrayBean readBack = S7SerializerImpl.extractBytes(StringArrayBean.class, buffer, 0);
        assertArrayEquals(bean.tags, readBack.tags, "both strings must survive the round trip");
    }

    @Test
    void stringArrayOfThreeWithOffsetCapacityEdgeAndSentinelRoundTrips() {
        final StringArray3WithOffsetAndSentinel bean = new StringArray3WithOffsetAndSentinel();
        // capacity edge: element 0 exactly full; element 1 mixed; element 2 empty
        bean.tags = new String[]{"ABCDEF", "xy", ""};
        bean.sentinel = 0x5A;

        final byte[] buffer = new byte[29]; // array 4..27, sentinel at 28
        java.util.Arrays.fill(buffer, (byte) 0x11);
        S7SerializerImpl.insertBytes(bean, buffer, 0);

        // three element headers at 4, 12, 20 (stride 6 + 2)
        assertEquals(6, buffer[4] & 0xFF);
        assertEquals(6, buffer[5] & 0xFF, "full-capacity element: current == max");
        assertEquals(6, buffer[12] & 0xFF);
        assertEquals(2, buffer[13] & 0xFF);
        assertEquals(6, buffer[20] & 0xFF);
        assertEquals(0, buffer[21] & 0xFF, "empty element: current == 0");

        assertEquals('A', buffer[6]);
        assertEquals('F', buffer[11], "last content byte of the full element");
        assertEquals('x', buffer[14]);
        assertEquals('y', buffer[15]);
        assertEquals(0x11, buffer[16], "unused capacity keeps the pre-existing byte");
        assertEquals((byte) 0x5A, buffer[28], "the trailing sentinel byte must not be touched by the array");

        final StringArray3WithOffsetAndSentinel readBack =
                S7SerializerImpl.extractBytes(StringArray3WithOffsetAndSentinel.class, buffer, 0);
        assertArrayEquals(bean.tags, readBack.tags, "all three strings must round trip at the non-zero offset");
        assertEquals(bean.sentinel, readBack.sentinel);
    }

    // ------------------------------------------------------------------
    // STRUCT arrays: element stride must be the nested block size
    // ------------------------------------------------------------------

    static class StructItemBean {
        @S7Variable(type = S7Type.INT, byteOffset = 0)
        public Short x;

        @S7Variable(type = S7Type.BYTE, byteOffset = 2)
        public Byte y;
    }

    static class StructArrayBean {
        @S7Variable(type = S7Type.STRUCT, byteOffset = 0, arraySize = 3)
        public StructItemBean[] items;
    }

    @Test
    void structArrayAdvancesByTheNestedBlockSize() {
        final StructArrayBean bean = new StructArrayBean();
        bean.items = new StructItemBean[3];
        for (int i = 0; i < 3; i++) {
            bean.items[i] = new StructItemBean();
            bean.items[i].x = (short) (0x0100 + i);
            bean.items[i].y = (byte) (0x40 + i);
        }

        final byte[] buffer = new byte[9]; // 3 * nested block size 3
        S7SerializerImpl.insertBytes(bean, buffer, 0);

        // each element occupies its own 3-byte slot; the second header must
        // not fall into the first element's payload
        for (int i = 0; i < 3; i++) {
            final int base = i * 3;
            assertEquals(0x01, buffer[base] & 0xFF, "element " + i + " x high byte");
            assertEquals(i, buffer[base + 1] & 0xFF, "element " + i + " x low byte");
            assertEquals(0x40 + i, buffer[base + 2] & 0xFF, "element " + i + " y byte");
        }

        final StructArrayBean readBack = S7SerializerImpl.extractBytes(StructArrayBean.class, buffer, 0);
        for (int i = 0; i < 3; i++) {
            assertEquals(bean.items[i].x, readBack.items[i].x, "element " + i + " must round trip");
            assertEquals(bean.items[i].y, readBack.items[i].y);
        }
    }
}
