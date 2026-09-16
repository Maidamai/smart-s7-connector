package io.github.maidamai.s7connector.impl;

import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Connector;
import io.github.maidamai.s7connector.api.S7Serializer;
import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.api.SiemensPLCS;
import io.github.maidamai.s7connector.api.factory.S7ConnectorFactory;
import io.github.maidamai.s7connector.api.factory.S7SerializerFactory;
import io.github.maidamai.s7connector.bean.PlcS7PointVariable;
import io.github.maidamai.s7connector.impl.support.LivePlcTestGuard;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live tests against a real or simulated S7-1500. Not selected by plain
 * {@code mvn test}; run via the {@code plc-live-it} profile (see docs/testing.md).
 * The write test refuses to send any write request unless
 * {@code plc.allowWrites=true} and {@code plc.allow.ranges} cover DB1 0-7.
 */
class S1500SimulatedPlcIT {
    private static final int DB_NUMBER = 1;
    private static final int DB_START_OFFSET = 0;
    private static final int DB_TEST_BYTES = 8;
    private static final boolean BOOL_VALUE = true;
    private static final short INT_VALUE = 1234;
    private static final long DINT_VALUE = 123456789L;

    @Test
    void readsConfiguredDbPointsFromSimulatedS1500() throws IOException {
        final LivePlcTestGuard.LiveTarget target = LivePlcTestGuard.fromSystemProperties().requireLiveTarget();
        final S7Connector connector = buildConnector(target);
        try {
            final S7Serializer serializer = S7SerializerFactory.buildSerializer(connector);

            final List<?> values = (List<?>) serializer.dispense(Arrays.asList(boolPoint(), intPoint(), dintPoint()));
            final byte[] rawBytes = connector.read(DaveArea.DB, DB_NUMBER, DB_TEST_BYTES, DB_START_OFFSET);

            assertEquals(Boolean.valueOf((rawBytes[0] & 0x01) != 0), values.get(0),
                    "DB1.0.0 BOOL should match the raw DB byte");
            assertEquals(Short.valueOf(readInt(rawBytes, 2)), values.get(1),
                    "DB1.DBW2 INT should match the raw bytes");
            assertEquals(Long.valueOf(readDint(rawBytes, 4)), values.get(2),
                    "DB1.DBD4 DINT should match the raw bytes");
        } finally {
            connector.close();
        }
    }

    @Test
    void writesAndReadsBackConfiguredDbPointsOnSimulatedS1500() throws IOException {
        final LivePlcTestGuard guard = LivePlcTestGuard.fromSystemProperties();
        guard.requireWriteRange(DaveArea.DB, DB_NUMBER, DB_START_OFFSET, DB_TEST_BYTES);
        final S7Connector connector = buildConnector(guard.requireLiveTarget());
        byte[] originalBytes = null;
        try {
            final S7Serializer serializer = S7SerializerFactory.buildSerializer(connector);
            originalBytes = connector.read(DaveArea.DB, DB_NUMBER, DB_TEST_BYTES, DB_START_OFFSET);

            serializer.store(Boolean.valueOf(BOOL_VALUE), boolPoint());
            serializer.store(Short.valueOf(INT_VALUE), intPoint());
            serializer.store(Long.valueOf(DINT_VALUE), dintPoint());

            final List<?> values = (List<?>) serializer.dispense(Arrays.asList(boolPoint(), intPoint(), dintPoint()));
            assertEquals(Boolean.valueOf(BOOL_VALUE), values.get(0), "DB1.0.0 BOOL should match the written value");
            assertEquals(Short.valueOf(INT_VALUE), values.get(1), "DB1.DBW2 INT should match the written value");
            assertEquals(Long.valueOf(DINT_VALUE), values.get(2), "DB1.DBD4 DINT should match the written value");

            final byte[] rawBytes = connector.read(DaveArea.DB, DB_NUMBER, DB_TEST_BYTES, DB_START_OFFSET);
            assertTrue((rawBytes[0] & 0x01) != 0, "DB1.0.0 should be set in the raw DB byte");
            assertEquals(INT_VALUE, readInt(rawBytes, 2), "raw bytes at DB1.DBW2 should contain the written INT");
            assertEquals(DINT_VALUE, readDint(rawBytes, 4), "raw bytes at DB1.DBD4 should contain the written DINT");
        } finally {
            try {
                if (originalBytes != null) {
                    connector.write(DaveArea.DB, DB_NUMBER, DB_START_OFFSET, originalBytes);
                }
            } finally {
                connector.close();
            }
        }
    }

    private static S7Connector buildConnector(final LivePlcTestGuard.LiveTarget target) {
        return S7ConnectorFactory.buildTCPConnector(SiemensPLCS.S1500)
                .withHost(target.getHost())
                .withPort(target.getPort())
                .withRack(target.getRack())
                .withSlot(target.getSlot())
                .withTimeout(target.getTimeoutMillis())
                .build();
    }

    private static PlcS7PointVariable boolPoint() {
        return new PlcS7PointVariable(DB_NUMBER, 0, 0, 1, DaveArea.DB, S7Type.BOOL, Boolean.class);
    }

    private static PlcS7PointVariable intPoint() {
        return new PlcS7PointVariable(DB_NUMBER, 2, 0, 2, DaveArea.DB, S7Type.INT, Short.class);
    }

    private static PlcS7PointVariable dintPoint() {
        return new PlcS7PointVariable(DB_NUMBER, 4, 0, 4, DaveArea.DB, S7Type.DINT, Long.class);
    }

    private static short readInt(final byte[] bytes, final int offset) {
        return (short) (((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF));
    }

    private static long readDint(final byte[] bytes, final int offset) {
        return ((long) (bytes[offset] & 0xFF) << 24)
                | ((long) (bytes[offset + 1] & 0xFF) << 16)
                | ((long) (bytes[offset + 2] & 0xFF) << 8)
                | (long) (bytes[offset + 3] & 0xFF);
    }
}
