package io.github.maidamai.s7connector.impl.serializer;

import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Connector;
import io.github.maidamai.s7connector.api.S7Serializer;
import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.bean.PlcS7PointVariable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class S7SerializerDispensePointsTest {
    @Test
    void dispensePointsReturnsValuesInInputOrder() {
        final S7Serializer serializer = new S7SerializerImpl(new ZeroConnector());

        final List<?> values = serializer.dispensePoints(java.util.Arrays.asList(
                point(2, S7Type.BYTE, Byte.class),
                point(0, S7Type.BYTE, Byte.class),
                point(1, S7Type.BYTE, Byte.class)));

        assertEquals(3, values.size(), "one value per input point");
        assertEquals(Byte.valueOf((byte) 2), values.get(0), "index 0 must match the point at index 0");
        assertEquals(Byte.valueOf((byte) 0), values.get(1), "index 1 must match the point at index 1");
        assertEquals(Byte.valueOf((byte) 1), values.get(2), "index 2 must match the point at index 2");
    }

    @Test
    void dispensePointsIsEquivalentToLegacyDispense() {
        final S7Serializer serializer = new S7SerializerImpl(new ZeroConnector());
        final List<PlcS7PointVariable> points = java.util.Arrays.asList(
                point(0, S7Type.BOOL, Boolean.class),
                point(2, S7Type.INT, Short.class));

        final List<?> viaDefault = serializer.dispensePoints(points);
        final List<?> viaLegacy = (List<?>) serializer.dispense(points);

        assertEquals(viaLegacy, viaDefault, "default method must be equivalent to the legacy dispense(List)");
    }

    @Test
    void dispensePointsOnEmptyListReturnsEmptyList() {
        final S7Serializer serializer = new S7SerializerImpl(new ZeroConnector());

        assertEquals(0, serializer.dispensePoints(java.util.Collections.<PlcS7PointVariable>emptyList()).size(),
                "empty input should return an empty list");
    }

    private static PlcS7PointVariable point(final int byteOffset, final S7Type type, final Class<?> fieldType) {
        return new PlcS7PointVariable(1, byteOffset, 0, type == S7Type.BOOL ? 1 : 2, DaveArea.DB, type, fieldType);
    }

    /** In-memory connector that returns its offset value for every byte. */
    private static final class ZeroConnector implements S7Connector {
        @Override
        public byte[] read(final DaveArea area, final int areaNumber, final int bytes, final int offset) {
            final byte[] result = new byte[bytes];
            for (int i = 0; i < bytes; i++) {
                result[i] = (byte) (offset + i);
            }
            return result;
        }

        @Override
        public void write(final DaveArea area, final int areaNumber, final int offset, final byte[] buffer) {
            throw new UnsupportedOperationException("write is not needed in this test");
        }

        @Override
        public void close() {
            // no resource held
        }
    }
}
