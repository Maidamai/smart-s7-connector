package io.github.maidamai.smoke;

import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Serializer;
import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.api.annotation.S7Variable;
import io.github.maidamai.s7connector.api.factory.S7SerializerFactory;
import io.github.maidamai.s7connector.bean.PlcS7PointVariable;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consumer smoke tests: everything here goes through the public API only
 * ({@link S7SerializerFactory}, {@link S7Serializer}, {@link PlcS7PointVariable})
 * against an in-memory {@link InMemoryS7Connector}, so it exercises the code
 * path a real consumer takes with an actually published artifact — minus the
 * PLC transport.
 *
 * <p>Expected outcomes per library version: all tests pass from the release
 * that fixes the round-3 audit finding C1 (primitive array mapping) onwards.
 * On v1.0.0-rc.1, {@link #primitiveArraysRoundTrip} legitimately fails: the
 * published rc.1 maps primitive component arrays through wrapper arrays and
 * the failure is exactly what this harness exists to catch.</p>
 */
class ConsumerSmokeTest {

    // ------------------------------------------------------------------
    // Bean round trip through the connector: wrapper arrays and scalars
    // (known-good mapping since rc.1)
    // ------------------------------------------------------------------

    public static class WrapperBean {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0, arraySize = 8)
        public Boolean[] flags;

        @S7Variable(type = S7Type.INT, byteOffset = 2)
        public Short speed;

        @S7Variable(type = S7Type.BYTE, byteOffset = 4, arraySize = 3)
        public Byte[] counters;

        @S7Variable(type = S7Type.STRING, byteOffset = 8, size = 8)
        public String tag;
    }

    @Test
    void wrapperBeanRoundTripsThroughConnector() {
        final InMemoryS7Connector connector = new InMemoryS7Connector();
        final S7Serializer serializer = S7SerializerFactory.buildSerializer(connector);

        final WrapperBean out = new WrapperBean();
        out.flags = new Boolean[]{true, false, true, false, true, false, true, false};
        out.speed = (short) -1234;
        out.counters = new Byte[]{0x01, 0x7F, (byte) 0x80};
        out.tag = "SMOKE";

        serializer.store(out, 1, 0);
        final WrapperBean in = serializer.dispense(WrapperBean.class, 1, 0);

        assertArrayEquals(out.flags, in.flags, "BOOL wrapper array");
        assertEquals(out.speed, in.speed, "INT scalar");
        assertArrayEquals(out.counters, in.counters, "BYTE wrapper array");
        assertEquals(out.tag, in.tag, "STRING scalar");
    }

    // ------------------------------------------------------------------
    // Bean round trip with primitive component arrays
    // ------------------------------------------------------------------

    public static class PrimitiveArraysBean {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0, arraySize = 9)
        public boolean[] bits;

        @S7Variable(type = S7Type.INT, byteOffset = 2, arraySize = 2)
        public short[] regs;

        @S7Variable(type = S7Type.WORD, byteOffset = 6, arraySize = 2)
        public int[] words;

        @S7Variable(type = S7Type.DINT, byteOffset = 10, arraySize = 3)
        public long[] dints;

        @S7Variable(type = S7Type.BYTE, byteOffset = 22, arraySize = 4)
        public byte[] raw;
    }

    @Test
    void primitiveArraysRoundTrip() {
        final InMemoryS7Connector connector = new InMemoryS7Connector();
        final S7Serializer serializer = S7SerializerFactory.buildSerializer(connector);

        final PrimitiveArraysBean out = new PrimitiveArraysBean();
        // 9 BOOLs cross the byte boundary (bits 0..8)
        out.bits = new boolean[9];
        for (int i = 0; i < out.bits.length; i++) {
            out.bits[i] = i % 2 == 0;
        }
        out.regs = new short[]{-2, 1234};
        // WORD maps to Integer as unsigned 16-bit, so stay within 0..0xFFFF
        out.words = new int[]{0x1234, 0xFEDC};
        // DINT is signed 32-bit, so stay within int range
        out.dints = new long[]{-7L, 65536L, 2147483647L};
        out.raw = new byte[]{0x00, 0x7F, (byte) 0x80, (byte) 0xFF};

        serializer.store(out, 7, 0);
        final PrimitiveArraysBean in = serializer.dispense(PrimitiveArraysBean.class, 7, 0);

        assertArrayEquals(out.bits, in.bits, "boolean[] (BOOL, 9 elements across the byte boundary)");
        assertArrayEquals(out.regs, in.regs, "short[] (INT)");
        assertArrayEquals(out.words, in.words, "int[] (WORD)");
        assertArrayEquals(out.dints, in.dints, "long[] (DINT)");
        assertArrayEquals(out.raw, in.raw, "byte[] (BYTE)");
    }

    // ------------------------------------------------------------------
    // Multi-point batch read: values follow the input order
    // ------------------------------------------------------------------

    @Test
    void multiPointBatchReadFollowsInputOrder() {
        final InMemoryS7Connector connector = new InMemoryS7Connector();
        final S7Serializer serializer = S7SerializerFactory.buildSerializer(connector);

        // Deterministic DB 1 content: bit patterns, INT 0x1234, WORD 0xABCD,
        // REAL 3.5f (big endian IEEE 754)
        connector.write(DaveArea.DB, 1, 0, new byte[]{
                (byte) 0xA5, 0x00, 0x12, 0x34, (byte) 0xAB, (byte) 0xCD, 0x00, 0x00,
                0x40, 0x60, 0x00, 0x00});

        final List<PlcS7PointVariable> points = Arrays.asList(
                new PlcS7PointVariable(1, 0, 0, 1, DaveArea.DB, S7Type.BOOL, Boolean.class),
                new PlcS7PointVariable(1, 0, 3, 1, DaveArea.DB, S7Type.BOOL, Boolean.class),
                new PlcS7PointVariable(1, 2, 0, 2, DaveArea.DB, S7Type.INT, Short.class),
                new PlcS7PointVariable(1, 4, 0, 2, DaveArea.DB, S7Type.WORD, Integer.class),
                new PlcS7PointVariable(1, 8, 0, 4, DaveArea.DB, S7Type.REAL, Float.class));

        final List<?> values = serializer.dispensePoints(points);

        assertEquals(5, values.size(), "one value per point");
        // 0xA5 = 1010 0101: bit 0 set, bit 3 clear
        assertEquals(Boolean.TRUE, values.get(0), "BOOL bit 0 of 0xA5");
        assertEquals(Boolean.FALSE, values.get(1), "BOOL bit 3 of 0xA5");
        assertEquals(Short.valueOf((short) 0x1234), values.get(2), "INT at byte 2");
        assertEquals(Integer.valueOf(0xABCD), values.get(3), "WORD at byte 4");
        assertEquals(Float.valueOf(3.5f), values.get(4), "REAL at byte 8");
    }

    // ------------------------------------------------------------------
    // Single-point store is a read-modify-write of just that byte range
    // ------------------------------------------------------------------

    @Test
    void singlePointStoreMergesOnlyItsByte() {
        final InMemoryS7Connector connector = new InMemoryS7Connector();
        final S7Serializer serializer = S7SerializerFactory.buildSerializer(connector);

        final byte[] seed = new byte[8];
        Arrays.fill(seed, (byte) 0x55);
        connector.write(DaveArea.DB, 2, 0, seed);

        serializer.store(Boolean.TRUE, new PlcS7PointVariable(2, 3, 3, 1, DaveArea.DB, S7Type.BOOL, Boolean.class));

        final byte[] after = connector.read(DaveArea.DB, 2, 8, 0);
        // BOOL insert merges the bit into the byte: 0x55 | 0x08 = 0x5D;
        // every other byte keeps the seeded 0x55
        assertEquals((byte) 0x5D, after[3], "only the addressed bit changed inside the byte");
        for (int i = 0; i < 8; i++) {
            if (i != 3) {
                assertEquals((byte) 0x55, after[i], "byte " + i + " must be untouched");
            }
        }
        assertTrue(true);
    }
}
