package io.github.maidamai.s7connector.impl.serializer;

import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Connector;
import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.bean.PlcS7PointVariable;
import io.github.maidamai.s7connector.impl.S7ReadWindowProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S7SerializerBatchReadIntegrationTest {
    @Test
    void batchReadKeepsInputOrderAndReducesRequestCount() {
        final CountingConnector connector = new CountingConnector(96);
        connector.overrideByte(20, (byte) 0x08);
        final S7SerializerImpl serializer = new S7SerializerImpl(connector);
        final List<PlcS7PointVariable> points = Arrays.asList(
                point(DaveArea.DB, 1, 2, 0, 1, S7Type.BYTE, Byte.class),
                point(DaveArea.DB, 1, 0, 0, 1, S7Type.BYTE, Byte.class),
                point(DaveArea.DB, 1, 1, 0, 1, S7Type.BYTE, Byte.class),
                point(DaveArea.DB, 1, 20, 3, 1, S7Type.BOOL, Boolean.class));

        final List<?> results = (List<?>) serializer.dispense(points);

        assertEquals(2, connector.getReadCount(), "three continuous byte points and one distant bool should use two reads");
        assertEquals(Byte.valueOf((byte) 2), results.get(0), "result order must follow input order for offset 2");
        assertEquals(Byte.valueOf((byte) 0), results.get(1), "result order must follow input order for offset 0");
        assertEquals(Byte.valueOf((byte) 1), results.get(2), "result order must follow input order for offset 1");
        assertEquals(Boolean.TRUE, results.get(3), "BOOL value should be extracted from the batched byte");
    }

    @Test
    void thousandContinuousPointsDoNotTriggerThousandReads() {
        final CountingConnector connector = new CountingConnector(96);
        final S7SerializerImpl serializer = new S7SerializerImpl(connector);
        final List<PlcS7PointVariable> points = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            points.add(point(DaveArea.DB, 1, i, 0, 1, S7Type.BYTE, Byte.class));
        }

        final List<?> results = (List<?>) serializer.dispense(points);

        assertEquals(1000, results.size(), "serializer should return one value per input point");
        assertTrue(connector.getReadCount() < 1000, "old point-by-point implementation would issue 1000 reads");
        assertEquals(11, connector.getReadCount(), "96-byte dynamic window should batch 1000 continuous byte points into 11 reads");
    }

    private static PlcS7PointVariable point(
            final DaveArea area,
            final int dbNum,
            final int byteOffset,
            final int bitOffset,
            final int size,
            final S7Type type,
            final Class<?> fieldType) {
        return new PlcS7PointVariable(dbNum, byteOffset, bitOffset, size, area, type, fieldType);
    }

    private static final class CountingConnector implements S7Connector, S7ReadWindowProvider {
        private final int maxReadBytes;
        private int readCount;
        private final byte[] overrides;

        private CountingConnector(final int maxReadBytes) {
            this.maxReadBytes = maxReadBytes;
            this.readCount = 0;
            this.overrides = new byte[4096];
        }

        @Override
        public byte[] read(final DaveArea area, final int areaNumber, final int bytes, final int offset) {
            this.readCount++;
            final byte[] buffer = new byte[bytes];
            for (int i = 0; i < bytes; i++) {
                final int absoluteOffset = offset + i;
                buffer[i] = this.overrides[absoluteOffset] == 0 ? (byte) absoluteOffset : this.overrides[absoluteOffset];
            }
            return buffer;
        }

        @Override
        public void write(final DaveArea area, final int areaNumber, final int offset, final byte[] buffer) throws IOException {
            throw new IOException("write is not used by batch read tests");
        }

        @Override
        public int getMaxReadBytes() {
            return this.maxReadBytes;
        }

        @Override
        public void close() {
        }

        private int getReadCount() {
            return this.readCount;
        }

        private void overrideByte(final int offset, final byte value) {
            this.overrides[offset] = value;
        }
    }

}
