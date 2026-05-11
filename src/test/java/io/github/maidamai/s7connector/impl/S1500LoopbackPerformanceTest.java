package io.github.maidamai.s7connector.impl;

import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Connector;
import io.github.maidamai.s7connector.api.S7Serializer;
import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.api.SiemensPLCS;
import io.github.maidamai.s7connector.api.factory.S7ConnectorFactory;
import io.github.maidamai.s7connector.api.factory.S7SerializerFactory;
import io.github.maidamai.s7connector.bean.PlcS7PointVariable;
import io.github.maidamai.s7connector.impl.support.LocalS1500Server;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S1500LoopbackPerformanceTest {
    private static final int DB_NUMBER = 1;
    private static final int PDU_LENGTH = 480;
    private static final int MAX_READ_BYTES = 462;
    private static final int TIMEOUT_MILLIS = 1000;
    private static final int POINT_COUNT = 1000;
    private static final int PERFORMANCE_CYCLES = 12;

    @Test
    void negotiatedPduWindowSplitsLargeReadAgainstLoopbackS1500() throws IOException, InterruptedException {
        final byte[] memory = LocalS1500Server.incrementingDbMemory(1024);
        try (LocalS1500Server server = new LocalS1500Server(PDU_LENGTH, memory);
             S7Connector connector = buildConnector(server)) {

            final byte[] actual = connector.read(DaveArea.DB, DB_NUMBER, 500, 0);

            assertArrayEquals(Arrays.copyOf(memory, 500), actual,
                    "loopback S7-1500 should return the requested DB bytes");
            final LocalS1500Server.ReadRequest firstRequest = server.takeReadRequest();
            final LocalS1500Server.ReadRequest secondRequest = server.takeReadRequest();
            assertEquals(0, firstRequest.getOffset(), "first read should start at the requested offset");
            assertEquals(MAX_READ_BYTES, firstRequest.getLength(),
                    "first read should use the negotiated PDU read window");
            assertEquals(MAX_READ_BYTES, secondRequest.getOffset(),
                    "second read should continue after the negotiated PDU read window");
            assertEquals(38, secondRequest.getLength(), "second read should contain the remaining bytes");
            assertEquals(2, server.getReadRequestCount(), "500 bytes should be split into two PLC reads");
        }
    }

    @Test
    void loopbackS1500BatchReadReportsPerformanceEnvelope() throws IOException {
        final byte[] memory = LocalS1500Server.incrementingDbMemory(POINT_COUNT + 16);
        try (LocalS1500Server server = new LocalS1500Server(PDU_LENGTH, memory);
             S7Connector connector = buildConnector(server)) {
            final S7Serializer serializer = S7SerializerFactory.buildSerializer(connector);
            final List<PlcS7PointVariable> points = bytePoints(POINT_COUNT);
            final long[] elapsedNanos = new long[PERFORMANCE_CYCLES];

            for (int cycle = 0; cycle < PERFORMANCE_CYCLES; cycle++) {
                final long startedAtNanos = System.nanoTime();
                final List<?> values = (List<?>) serializer.dispense(points);
                elapsedNanos[cycle] = System.nanoTime() - startedAtNanos;
                assertEquals(POINT_COUNT, values.size(), "batch read should return one value per point");
                assertEquals(Byte.valueOf(memory[0]), values.get(0), "first byte point should match DB memory");
                assertEquals(Byte.valueOf(memory[POINT_COUNT - 1]), values.get(POINT_COUNT - 1),
                        "last byte point should match DB memory");
            }

            final PerformanceEnvelope envelope = PerformanceEnvelope.from(elapsedNanos, POINT_COUNT);
            assertEquals(PERFORMANCE_CYCLES * 3, server.getReadRequestCount(),
                    "1000 byte points should be batched into three PLC reads per cycle with 462-byte window");
            assertTrue(envelope.getPointsPerSecond() > 0.0d,
                    "loopback performance benchmark should report positive points per second");
            assertTrue(envelope.getP50Millis() <= envelope.getP95Millis(),
                    "p50 should not exceed p95 in the recorded benchmark envelope");
            assertTrue(envelope.getP95Millis() <= envelope.getP99Millis(),
                    "p95 should not exceed p99 in the recorded benchmark envelope");
        }
    }

    private static S7Connector buildConnector(final LocalS1500Server server) {
        return S7ConnectorFactory.buildTCPConnector(SiemensPLCS.S1500)
                .withHost("127.0.0.1")
                .withPort(server.getPort())
                .withRack(0)
                .withSlot(2)
                .withTimeout(TIMEOUT_MILLIS)
                .build();
    }

    private static List<PlcS7PointVariable> bytePoints(final int pointCount) {
        final List<PlcS7PointVariable> points = new ArrayList<>();
        for (int i = 0; i < pointCount; i++) {
            points.add(new PlcS7PointVariable(DB_NUMBER, i, 0, 1, DaveArea.DB, S7Type.BYTE, Byte.class));
        }
        return points;
    }

    private static final class PerformanceEnvelope {
        private final double p50Millis;
        private final double p95Millis;
        private final double p99Millis;
        private final double pointsPerSecond;

        private PerformanceEnvelope(
                final double p50Millis,
                final double p95Millis,
                final double p99Millis,
                final double pointsPerSecond) {
            this.p50Millis = p50Millis;
            this.p95Millis = p95Millis;
            this.p99Millis = p99Millis;
            this.pointsPerSecond = pointsPerSecond;
        }

        private static PerformanceEnvelope from(final long[] elapsedNanos, final int pointsPerCycle) {
            final long[] sortedNanos = elapsedNanos.clone();
            Arrays.sort(sortedNanos);
            final long totalNanos = sum(sortedNanos);
            final double totalSeconds = totalNanos / 1_000_000_000.0d;
            final double pointsPerSecond = (pointsPerCycle * sortedNanos.length) / totalSeconds;
            return new PerformanceEnvelope(
                    nanosToMillis(percentile(sortedNanos, 50)),
                    nanosToMillis(percentile(sortedNanos, 95)),
                    nanosToMillis(percentile(sortedNanos, 99)),
                    pointsPerSecond);
        }

        private static long percentile(final long[] sortedNanos, final int percentile) {
            final int index = (int) Math.ceil((percentile / 100.0d) * sortedNanos.length) - 1;
            return sortedNanos[Math.max(0, Math.min(index, sortedNanos.length - 1))];
        }

        private static long sum(final long[] values) {
            long sum = 0L;
            for (final long value : values) {
                sum += value;
            }
            return sum;
        }

        private static double nanosToMillis(final long nanos) {
            return nanos / 1_000_000.0d;
        }

        private double getP50Millis() {
            return this.p50Millis;
        }

        private double getP95Millis() {
            return this.p95Millis;
        }

        private double getP99Millis() {
            return this.p99Millis;
        }

        private double getPointsPerSecond() {
            return this.pointsPerSecond;
        }
    }
}
