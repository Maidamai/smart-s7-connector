package io.github.maidamai.s7connector.impl;

import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Connector;
import io.github.maidamai.s7connector.api.S7Serializer;
import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.api.SiemensPLCS;
import io.github.maidamai.s7connector.api.factory.S7ConnectorFactory;
import io.github.maidamai.s7connector.api.factory.S7SerializerFactory;
import io.github.maidamai.s7connector.bean.PlcS7PointVariable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S1500SimulatedPlcIT {
    private static final String HOST_PROPERTY = "plc.host";
    private static final int RACK = 0;
    private static final int SLOT = 2;
    private static final int PORT = 102;
    private static final int TIMEOUT_MILLIS = 3000;
    private static final int DB_NUMBER = 1;
    private static final int DB_START_OFFSET = 0;
    private static final int DB_TEST_BYTES = 8;
    private static final boolean BOOL_VALUE = true;
    private static final short INT_VALUE = 1234;
    private static final long DINT_VALUE = 123456789L;

    @Test
    void readsAndWritesConfiguredDbPointsOnSimulatedS1500() throws IOException {
        final S7Connector connector = S7ConnectorFactory.buildTCPConnector(SiemensPLCS.S1500)
                .withHost(host())
                .withPort(PORT)
                .withRack(RACK)
                .withSlot(SLOT)
                .withTimeout(TIMEOUT_MILLIS)
                .build();
        final S7Serializer serializer = S7SerializerFactory.buildSerializer(connector);
        final byte[] originalBytes = connector.read(DaveArea.DB, DB_NUMBER, DB_TEST_BYTES, DB_START_OFFSET);
        try {
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
                connector.write(DaveArea.DB, DB_NUMBER, DB_START_OFFSET, originalBytes);
            } finally {
                connector.close();
            }
        }
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

    private static String host() {
        final String host = System.getProperty(HOST_PROPERTY);
        Assumptions.assumeTrue(host != null && !host.trim().isEmpty(),
                "set -D" + HOST_PROPERTY + " to run live PLC integration tests");
        return host;
    }
}
