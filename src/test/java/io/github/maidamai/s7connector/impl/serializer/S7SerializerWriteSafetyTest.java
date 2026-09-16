package io.github.maidamai.s7connector.impl.serializer;

import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Connector;
import io.github.maidamai.s7connector.api.S7Serializer;
import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.api.annotation.S7Variable;
import io.github.maidamai.s7connector.api.factory.S7SerializerFactory;
import io.github.maidamai.s7connector.bean.PlcS7PointVariable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Write semantics of the serializer: bean store is a documented full-block
 * overwrite (unmapped bytes become zero), point store merges into the current
 * memory, and the point read-modify-write is one critical section per
 * connector so two serializers sharing a connector cannot lose updates.
 * Uses a gated in-memory connector; no PLC is involved.
 */
class S7SerializerWriteSafetyTest {

    @Test
    void beanStoreOverwritesTheWholeBlockWithZerosInUnmappedBytes() throws IOException {
        final RecordingConnector connector = new RecordingConnector();
        final S7Serializer serializer = S7SerializerFactory.buildSerializer(connector);

        final PartialBean bean = new PartialBean();
        bean.intValue = 0x1234;
        serializer.store(bean, DaveArea.DB, 1, 0);

        assertEquals(1, connector.writes.size(), "bean store should issue exactly one write");
        final byte[] written = connector.writes.get(0);
        assertEquals(4, written.length, "the whole mapped block is written");
        assertEquals(0x12, written[2] & 0xFF, "mapped INT high byte at offset 2");
        assertEquals(0x34, written[3] & 0xFF, "mapped INT low byte at offset 3");
        assertEquals(0, written[0], "unmapped byte 0 is overwritten with zero (documented semantics)");
        assertEquals(0, written[1], "unmapped byte 1 is overwritten with zero (documented semantics)");
    }

    @Test
    void beanStoreSkipsNullFieldsAndLeavesTheirBytesZero() throws IOException {
        final RecordingConnector connector = new RecordingConnector();
        final S7Serializer serializer = S7SerializerFactory.buildSerializer(connector);

        serializer.store(new PartialBean(), DaveArea.DB, 1, 0); // intValue stays null

        final byte[] written = connector.writes.get(0);
        assertEquals(0, written[2], "a null field leaves its bytes zero (documented semantics)");
    }

    @Test
    void pointStoreMergesIntoCurrentMemoryAndPreservesSiblingBits() throws IOException {
        final RecordingConnector connector = new RecordingConnector();
        connector.memory[0] = (byte) 0xAA;
        final S7Serializer serializer = S7SerializerFactory.buildSerializer(connector);

        serializer.store(Boolean.TRUE, boolPoint(0));

        assertEquals((byte) 0xAB, connector.memory[0],
                "point store must merge into the read bytes, not clear sibling bits");
    }

    @Test
    void concurrentPointStoresOnASharedConnectorDoNotLoseUpdates() throws Exception {
        final GatedConnector connector = new GatedConnector();
        // two SEPARATE serializer instances sharing one connector
        final S7Serializer serializerA = S7SerializerFactory.buildSerializer(connector);
        final S7Serializer serializerB = S7SerializerFactory.buildSerializer(connector);
        final CountDownLatch started = new CountDownLatch(2);
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final Thread threadA = new Thread(() -> runPointStore(serializerA, boolPoint(0), started, failures), "writer-A");
        final Thread threadB = new Thread(() -> runPointStore(serializerB, boolPoint(1), started, failures), "writer-B");

        threadA.start();
        threadB.start();
        assertTrue(started.await(5, TimeUnit.SECONDS), "both writer threads should start");
        threadA.join(10_000);
        threadB.join(10_000);
        assertTrue(failures.isEmpty(), "point stores should not fail: " + failures);

        assertEquals(0x03, connector.memory[0] & 0xFF,
                "both bits must survive: read-modify-write has to be atomic per connector");

        // the second read may only start after the first write finished
        final List<Integer> readStarts = eventIndices(connector.events, "read-start");
        final List<Integer> writes = eventIndices(connector.events, "write");
        assertTrue(readStarts.size() == 2 && writes.size() == 2,
                "expected two reads and two writes, events were: " + connector.events);
        assertTrue(readStarts.get(1) > writes.get(0),
                "the second read-modify-write must not begin before the first write completed, events were: "
                        + connector.events);
    }

    private static void runPointStore(final S7Serializer serializer, final PlcS7PointVariable point,
                                      final CountDownLatch started, final List<Throwable> failures) {
        started.countDown();
        try {
            serializer.store(Boolean.TRUE, point);
        } catch (final Throwable failure) {
            failures.add(failure);
        }
    }

    private static List<Integer> eventIndices(final List<String> events, final String prefix) {
        final List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).startsWith(prefix)) {
                indices.add(Integer.valueOf(i));
            }
        }
        return indices;
    }

    private static PlcS7PointVariable boolPoint(final int bitOffset) {
        return new PlcS7PointVariable(1, 0, bitOffset, 1, DaveArea.DB, S7Type.BOOL, Boolean.class);
    }

    /** One INT at offset 2 inside a 4-byte block; offsets 0 and 1 unmapped. */
    public static final class PartialBean {
        @S7Variable(type = S7Type.INT, byteOffset = 2)
        public Short intValue;
    }

    /** Plain in-memory connector recording writes. */
    private static final class RecordingConnector implements S7Connector {
        final byte[] memory = new byte[64];
        final List<byte[]> writes = new CopyOnWriteArrayList<>();

        @Override
        public byte[] read(final DaveArea area, final int areaNumber, final int bytes, final int offset) {
            return Arrays.copyOfRange(this.memory, offset, offset + bytes);
        }

        @Override
        public void write(final DaveArea area, final int areaNumber, final int offset, final byte[] buffer) {
            this.writes.add(buffer.clone());
            System.arraycopy(buffer, 0, this.memory, offset, buffer.length);
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    /**
     * In-memory connector whose read() announces itself and then waits
     * (bounded) for a second reader: without the connector-wide critical
     * section both read-modify-writes read zeroes concurrently and lose an
     * update; with it, the second read cannot even start before the first
     * write completed.
     */
    private static final class GatedConnector implements S7Connector {
        final byte[] memory = new byte[64];
        final List<String> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch readersArrived = new CountDownLatch(2);

        @Override
        public byte[] read(final DaveArea area, final int areaNumber, final int bytes, final int offset) {
            this.events.add("read-start@" + offset);
            this.readersArrived.countDown();
            try {
                // bounded: under the fix the second reader never arrives here
                // while the first holds the connector monitor
                this.readersArrived.await(400, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            this.events.add("read-return@" + offset);
            return Arrays.copyOfRange(this.memory, offset, offset + bytes);
        }

        @Override
        public void write(final DaveArea area, final int areaNumber, final int offset, final byte[] buffer) {
            System.arraycopy(buffer, 0, this.memory, offset, buffer.length);
            this.events.add("write@" + offset);
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}
