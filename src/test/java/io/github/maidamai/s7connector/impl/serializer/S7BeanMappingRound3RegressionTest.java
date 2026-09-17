package io.github.maidamai.s7connector.impl.serializer;

import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.api.annotation.S7Variable;
import io.github.maidamai.s7connector.exception.S7Exception;
import io.github.maidamai.s7connector.impl.serializer.parser.BeanParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round 3 audit regressions for the bean mapping:
 *
 * <ul>
 * <li>primitive array fields must be extracted into arrays of the declared
 * primitive component type (not wrapper arrays), element by element;</li>
 * <li>wrapper array fields keep their existing behavior;</li>
 * <li>layouts whose overall end offset overflows int must be rejected at
 * parse time instead of silently producing a negative block size;</li>
 * <li>array fields whose declared component type is not one of the component
 * types the S7 type maps to must be rejected at parse time.</li>
 * </ul>
 */
class S7BeanMappingRound3RegressionTest {

    static class PrimitiveBoolArray9 {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0, arraySize = 9)
        public boolean[] bits;
    }

    static class PrimitiveShortArray {
        @S7Variable(type = S7Type.INT, byteOffset = 0, arraySize = 3)
        public short[] values;
    }

    static class PrimitiveByteArray {
        @S7Variable(type = S7Type.BYTE, byteOffset = 0, arraySize = 5)
        public byte[] values;
    }

    static class PrimitiveIntArrayWord {
        @S7Variable(type = S7Type.WORD, byteOffset = 0, arraySize = 4)
        public int[] values;
    }

    static class PrimitiveLongArrayDint {
        @S7Variable(type = S7Type.DINT, byteOffset = 0, arraySize = 4)
        public long[] values;
    }

    static class WrapperBoolArray9 {
        @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0, arraySize = 9)
        public Boolean[] bits;
    }

    static class WrapperByteArray {
        @S7Variable(type = S7Type.BYTE, byteOffset = 0, arraySize = 5)
        public Byte[] values;
    }

    static class WrapperShortArray {
        @S7Variable(type = S7Type.INT, byteOffset = 0, arraySize = 3)
        public Short[] values;
    }

    static class OverflowAtIntMaxBean {
        // byteOffset + coverage = Integer.MAX_VALUE + 1, which must not be
        // cast to a negative int block size
        @S7Variable(type = S7Type.BYTE, byteOffset = Integer.MAX_VALUE, arraySize = 1)
        public Byte value;
    }

    static class CharArrayWithIntTypeBean {
        @S7Variable(type = S7Type.INT, byteOffset = 0, arraySize = 2)
        public char[] letters;
    }

    static class IntArrayWithDintTypeBean {
        // DINT maps to long/Long, not int/Integer
        @S7Variable(type = S7Type.DINT, byteOffset = 0, arraySize = 2)
        public int[] values;
    }

    // ------------------------------------------------------------------
    // primitive array round trips
    // ------------------------------------------------------------------

    @Test
    void primitiveBoolArrayRoundTripCrossesByteBoundary() {
        final PrimitiveBoolArray9 bean = new PrimitiveBoolArray9();
        final boolean[] values = new boolean[9];
        for (int i = 0; i < 9; i++) {
            values[i] = i % 3 != 0; // false, true, true, false, ...
        }
        bean.bits = values;

        final byte[] buffer = new byte[3];
        S7SerializerImpl.insertBytes(bean, buffer, 0);

        final byte[] expected = new byte[3];
        for (int i = 0; i < 9; i++) {
            if (values[i]) {
                expected[i / 8] |= (byte) (0x01 << (i % 8));
            }
        }
        assertArrayEquals(expected, buffer, "primitive boolean[] insert must place every bit");

        final PrimitiveBoolArray9 readBack = S7SerializerImpl.extractBytes(PrimitiveBoolArray9.class, buffer, 0);
        assertArrayEquals(values, readBack.bits, "primitive boolean[] extract must return a boolean[]");
    }

    @Test
    void primitiveShortArrayRoundTripsIncludingNegativeValues() {
        final PrimitiveShortArray bean = new PrimitiveShortArray();
        final short[] values = {(short) 123, (short) -2, Short.MIN_VALUE};
        bean.values = values;

        final byte[] buffer = new byte[6];
        S7SerializerImpl.insertBytes(bean, buffer, 0);

        final PrimitiveShortArray readBack = S7SerializerImpl.extractBytes(PrimitiveShortArray.class, buffer, 0);
        assertArrayEquals(values, readBack.values, "primitive short[] must survive the round trip");
    }

    @Test
    void primitiveByteArrayRoundTrips() {
        // guards the byte[] special case: the extracted array must be a
        // byte[] of the declared length with the same element values
        final PrimitiveByteArray bean = new PrimitiveByteArray();
        final byte[] values = {0x00, 0x7F, (byte) 0x80, (byte) 0xFF, 0x01};
        bean.values = values;

        final byte[] buffer = new byte[5];
        S7SerializerImpl.insertBytes(bean, buffer, 0);
        assertArrayEquals(values, buffer, "primitive byte[] insert must write one byte per element");

        final PrimitiveByteArray readBack = S7SerializerImpl.extractBytes(PrimitiveByteArray.class, buffer, 0);
        assertTrue(readBack.values instanceof byte[], "extract must produce a byte[], not Byte[]");
        assertArrayEquals(values, readBack.values, "primitive byte[] must survive the round trip");
    }

    @Test
    void primitiveIntArrayWordRoundTrips() {
        final PrimitiveIntArrayWord bean = new PrimitiveIntArrayWord();
        final int[] values = {0, 1, 32767, 65535}; // WORD extracts as an unsigned 16-bit Integer
        bean.values = values;

        final byte[] buffer = new byte[8];
        S7SerializerImpl.insertBytes(bean, buffer, 0);

        final PrimitiveIntArrayWord readBack = S7SerializerImpl.extractBytes(PrimitiveIntArrayWord.class, buffer, 0);
        assertArrayEquals(values, readBack.values, "primitive int[] with WORD must survive the round trip");
    }

    @Test
    void primitiveLongArrayDintRoundTrips() {
        final PrimitiveLongArrayDint bean = new PrimitiveLongArrayDint();
        final long[] values = {1L, -1L, 2147483647L, -2147483648L}; // DINT is 32 bits, sign-extended
        bean.values = values;

        final byte[] buffer = new byte[16];
        S7SerializerImpl.insertBytes(bean, buffer, 0);

        final PrimitiveLongArrayDint readBack = S7SerializerImpl.extractBytes(PrimitiveLongArrayDint.class, buffer, 0);
        assertArrayEquals(values, readBack.values, "primitive long[] with DINT must survive the round trip");
    }

    static class PrimitiveFloatArrayReal {
        @S7Variable(type = S7Type.REAL, byteOffset = 0, arraySize = 3)
        public float[] values;
    }

    static class PrimitiveDoubleArrayReal {
        @S7Variable(type = S7Type.REAL, byteOffset = 0, arraySize = 3)
        public double[] values;
    }

    @Test
    void realArraysAcceptFloatAndDoubleComponents() {
        // REAL is the one type with two accepted component classes; both must
        // round trip and encode the same bytes for equal values
        final PrimitiveFloatArrayReal floatBean = new PrimitiveFloatArrayReal();
        floatBean.values = new float[]{1.5f, -0.25f, 0.0f};
        final byte[] floatBuffer = new byte[12];
        S7SerializerImpl.insertBytes(floatBean, floatBuffer, 0);
        final PrimitiveFloatArrayReal floatReadBack =
                S7SerializerImpl.extractBytes(PrimitiveFloatArrayReal.class, floatBuffer, 0);
        assertArrayEquals(floatBean.values, floatReadBack.values, "float[] with REAL must survive the round trip");

        final PrimitiveDoubleArrayReal doubleBean = new PrimitiveDoubleArrayReal();
        doubleBean.values = new double[]{1.5, -0.25, 0.0};
        final byte[] doubleBuffer = new byte[12];
        S7SerializerImpl.insertBytes(doubleBean, doubleBuffer, 0);
        final PrimitiveDoubleArrayReal doubleReadBack =
                S7SerializerImpl.extractBytes(PrimitiveDoubleArrayReal.class, doubleBuffer, 0);
        assertArrayEquals(doubleBean.values, doubleReadBack.values, "double[] with REAL must survive the round trip");
        assertArrayEquals(floatBuffer, doubleBuffer,
                "REAL must encode the same bytes for equal float and double values");
    }

    // ------------------------------------------------------------------
    // wrapper array fields keep their existing behavior
    // ------------------------------------------------------------------

    @Test
    void wrapperArraysKeepExistingBehavior() {
        final WrapperBoolArray9 boolBean = new WrapperBoolArray9();
        boolBean.bits = new Boolean[9];
        for (int i = 0; i < 9; i++) {
            boolBean.bits[i] = i % 2 == 0;
        }
        final byte[] boolBuffer = new byte[3];
        S7SerializerImpl.insertBytes(boolBean, boolBuffer, 0);
        final WrapperBoolArray9 boolReadBack =
                S7SerializerImpl.extractBytes(WrapperBoolArray9.class, boolBuffer, 0);
        assertArrayEquals(boolBean.bits, boolReadBack.bits, "Boolean[] must keep working");

        final WrapperByteArray byteBean = new WrapperByteArray();
        byteBean.values = new Byte[]{0x00, 0x7F, (byte) 0x80, (byte) 0xFF, 0x01};
        final byte[] byteBuffer = new byte[5];
        S7SerializerImpl.insertBytes(byteBean, byteBuffer, 0);
        final WrapperByteArray byteReadBack =
                S7SerializerImpl.extractBytes(WrapperByteArray.class, byteBuffer, 0);
        assertArrayEquals(byteBean.values, byteReadBack.values, "Byte[] must keep working");

        final WrapperShortArray shortBean = new WrapperShortArray();
        shortBean.values = new Short[]{(short) 123, (short) -2, Short.MIN_VALUE};
        final byte[] shortBuffer = new byte[6];
        S7SerializerImpl.insertBytes(shortBean, shortBuffer, 0);
        final WrapperShortArray shortReadBack =
                S7SerializerImpl.extractBytes(WrapperShortArray.class, shortBuffer, 0);
        assertArrayEquals(shortBean.values, shortReadBack.values, "Short[] must keep working");
    }

    // ------------------------------------------------------------------
    // parse-time rejections
    // ------------------------------------------------------------------

    @Test
    void parseRejectsEndOffsetBeyondIntegerMaxValue() {
        final S7Exception ex = assertThrows(S7Exception.class,
                () -> BeanParser.parse(OverflowAtIntMaxBean.class));
        assertTrue(ex.getMessage().contains("exceeds the representable block size"),
                "message must state the overflow: " + ex.getMessage());
    }

    @Test
    void parseRejectsIncompatibleArrayComponentTypes() {
        final S7Exception charEx = assertThrows(S7Exception.class,
                () -> BeanParser.parse(CharArrayWithIntTypeBean.class));
        assertTrue(charEx.getMessage().contains("letters"),
                "message must name the offending field: " + charEx.getMessage());

        // DINT maps to long/Long; an int[] component must be rejected too
        final S7Exception intDintEx = assertThrows(S7Exception.class,
                () -> BeanParser.parse(IntArrayWithDintTypeBean.class));
        assertTrue(intDintEx.getMessage().contains("values"),
                "message must name the offending field: " + intDintEx.getMessage());
    }
}
