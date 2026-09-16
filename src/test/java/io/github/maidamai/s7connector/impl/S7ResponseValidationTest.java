package io.github.maidamai.s7connector.impl;

import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Connector;
import io.github.maidamai.s7connector.api.SiemensPLCS;
import io.github.maidamai.s7connector.api.factory.S7ConnectorFactory;
import io.github.maidamai.s7connector.exception.S7Exception;
import io.github.maidamai.s7connector.impl.support.LocalS1500Server;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Response validation over a real connector/Netty/nodave stack against a
 * local loopback server with error injection. Truncated frames, lying
 * length fields, wrong PDU references, unexpected functions, and short read
 * data must fail the call instead of returning stale or zero-filled
 * "success" data; stream-poisoning violations close the transport.
 */
class S7ResponseValidationTest {
    private static final int NEGOTIATED_PDU_LENGTH = 240;
    private static final int DB_NUMBER = 1;

    @Test
    void writeRoundTripsThroughLoopbackAndRespectsPduLimit() throws IOException, InterruptedException {
        try (LocalLoopback loopback = LocalLoopback.open()) {
            final byte[] payload = pattern(0x40, 400);

            loopback.connector.write(DaveArea.DB, DB_NUMBER, 0, payload);

            assertEqualsWriteRequest(loopback.server.takeWriteRequest(), 0, 212);
            assertEqualsWriteRequest(loopback.server.takeWriteRequest(), 212, 188);
            for (final int s7PduLength : loopback.server.getRequestS7PduLengths()) {
                assertTrue(s7PduLength <= NEGOTIATED_PDU_LENGTH,
                        "write request S7 PDU must respect the negotiated length, got " + s7PduLength);
            }
            assertArrayEquals(payload, loopback.connector.read(DaveArea.DB, DB_NUMBER, 400, 0),
                    "loopback memory should round-trip the written payload");
        }
    }

    @Test
    void plcWriteRejectionFailsTheCallAndLeavesMemoryUntouched() throws IOException, InterruptedException {
        try (LocalLoopback loopback = LocalLoopback.open()) {
            final byte[] original = loopback.connector.read(DaveArea.DB, DB_NUMBER, 8, 0);
            loopback.server.queueWriteItemStatus(0x0A);

            final S7Exception failure = assertThrows(S7Exception.class,
                    () -> loopback.connector.write(DaveArea.DB, DB_NUMBER, 0, pattern(0x40, 8)),
                    "a rejected item status must fail the write");

            assertTrue(failure.getMessage().contains("status=10"),
                    "failure should carry the raw PLC status, but was: " + failure.getMessage());
            assertArrayEquals(original, loopback.connector.read(DaveArea.DB, DB_NUMBER, 8, 0),
                    "rejected write must not modify the memory");
            assertFalse(((S7TCPConnection) loopback.connector).isTransportClosed(),
                    "a clean PLC rejection must not close the connection");
        }
    }

    @Test
    void truncatedWriteResponseFailsAndClosesTheConnection() throws IOException {
        try (LocalLoopback loopback = LocalLoopback.open()) {
            loopback.server.queueRawResponse(shortenedFrame(12), true);

            assertThrows(S7Exception.class,
                    () -> loopback.connector.write(DaveArea.DB, DB_NUMBER, 0, pattern(0x40, 8)),
                    "a truncated response must fail the write");
            assertTransportClosedAndUnusable(loopback);
        }
    }

    @Test
    void partialHeaderOnlyIsRejected() throws IOException {
        try (LocalLoopback loopback = LocalLoopback.open()) {
            loopback.server.queueRawResponse(shortenedFrame(9), true);

            assertThrows(S7Exception.class,
                    () -> loopback.connector.write(DaveArea.DB, DB_NUMBER, 0, pattern(0x40, 8)),
                    "a frame shorter than the S7 header must fail the write");
            assertTransportClosedAndUnusable(loopback);
        }
    }

    @Test
    void announcedLengthBeyondTheFrameIsRejected() throws IOException {
        try (LocalLoopback loopback = LocalLoopback.open()) {
            final byte[] lying = writeAckFrame();
            lying[15] = 0x01; // dlen hi: 0x0100 = 256 announced bytes
            lying[16] = 0x00;
            loopback.server.queueRawResponse(lying, true);

            assertThrows(S7Exception.class,
                    () -> loopback.connector.write(DaveArea.DB, DB_NUMBER, 0, pattern(0x40, 8)),
                    "a dlen larger than the received frame must fail the write");
            assertTransportClosedAndUnusable(loopback);
        }
    }

    @Test
    void wrongPduReferenceIsRejectedAndClosesTheConnection() throws IOException {
        try (LocalLoopback loopback = LocalLoopback.open()) {
            final byte[] mismatched = writeAckFrame();
            mismatched[11] = 0x7E; mismatched[12] = (byte) 0xFF; // number 0x7EFF, not patched
            loopback.server.queueRawResponse(mismatched, false);

            final S7Exception failure = assertThrows(S7Exception.class,
                    () -> loopback.connector.write(DaveArea.DB, DB_NUMBER, 0, pattern(0x40, 8)),
                    "a response for a different request must fail the write");
            assertTrue(failure.getMessage().contains("PDU number"),
                    "failure should state the reference mismatch, but was: " + failure.getMessage());
            assertTransportClosedAndUnusable(loopback);
        }
    }

    @Test
    void unexpectedFunctionInWriteResponseIsRejected() throws IOException {
        try (LocalLoopback loopback = LocalLoopback.open()) {
            final byte[] wrongFunction = writeAckFrame();
            wrongFunction[19] = 0x04; // FUNC_READ inside a write response
            loopback.server.queueRawResponse(wrongFunction, true);

            assertThrows(S7Exception.class,
                    () -> loopback.connector.write(DaveArea.DB, DB_NUMBER, 0, pattern(0x40, 8)),
                    "a write request answered with a read function must fail");
        }
    }

    @Test
    void shortReadDataFailsInsteadOfReturningStaleBytes() throws IOException {
        try (LocalLoopback loopback = LocalLoopback.open()) {
            // valid frame for the requested 16 bytes, but it carries only 8
            loopback.server.queueRawResponse(readDataFrame(pattern(0x11, 8)), true);

            assertThrows(Exception.class,
                    () -> loopback.connector.read(DaveArea.DB, DB_NUMBER, 16, 0),
                    "a short read answer must fail instead of returning half-stale data");
            final byte[] retry = loopback.connector.read(DaveArea.DB, DB_NUMBER, 16, 0);
            assertArrayEquals(pattern(0, 16), retry,
                    "a subsequent correct read must return the real memory content");
        }
    }

    private static void assertEqualsWriteRequest(final LocalS1500Server.WriteRequest request, final int offset, final int length) {
        assertTrue(request.getOffset() == offset && request.getLength() == length,
                "expected write chunk offset=" + offset + " length=" + length + " but was offset="
                        + request.getOffset() + " length=" + request.getLength());
    }

    private static void assertTransportClosedAndUnusable(final LocalLoopback loopback) throws IOException {
        assertTrue(((S7TCPConnection) loopback.connector).isTransportClosed(),
                "a stream-poisoning violation must close the transport");
        assertThrows(IOException.class,
                () -> loopback.connector.read(DaveArea.DB, DB_NUMBER, 4, 0),
                "the closed connection must fail subsequent calls");
    }

    /**
     * A write acknowledgement frame physically cut to {@code frameLength}
     * bytes, with the TPKT length field matching, so the client's framer
     * actually delivers the short frame instead of waiting for more bytes.
     */
    private static byte[] shortenedFrame(final int frameLength) {
        final byte[] frame = Arrays.copyOf(writeAckFrame(), frameLength);
        frame[2] = (byte) (frameLength / 0x100);
        frame[3] = (byte) (frameLength % 0x100);
        return frame;
    }

    private static byte[] pattern(final int start, final int length) {
        final byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (start + i);
        }
        return data;
    }

    /**
     * A full TPKT frame carrying a single-item write acknowledgement. Layout
     * (PDU starts at frame offset 7, type-3 header is 12 bytes): number at
     * frame[11..12], plen at [13..14], dlen at [15..16], function at [19],
     * item count at [20], item status at [21]. The number stays zero; the
     * server patches it when the scripted response asks for it.
     */
    private static byte[] writeAckFrame() {
        final byte[] frame = new byte[4 + 3 + 12 + 2 + 1];
        frame[0] = 0x03;
        frame[1] = 0x00;
        frame[2] = (byte) (frame.length / 0x100);
        frame[3] = (byte) (frame.length % 0x100);
        frame[4] = 0x02;
        frame[5] = (byte) 0xF0;
        frame[6] = (byte) 0x80;
        frame[7] = 0x32;
        frame[8] = 0x03;
        frame[14] = 0x02; // plen
        frame[16] = 0x01; // dlen: one status byte
        frame[19] = 0x05; // FUNC_WRITE
        frame[20] = 0x01; // item count
        frame[21] = (byte) 0xFF; // item status
        return frame;
    }

    /**
     * A full TPKT frame carrying a single-item read answer with the given
     * data bytes; the data head announces exactly the carried bytes.
     */
    private static byte[] readDataFrame(final byte[] data) {
        final byte[] frame = new byte[4 + 3 + 12 + 2 + 4 + data.length];
        frame[0] = 0x03;
        frame[1] = 0x00;
        frame[2] = (byte) (frame.length / 0x100);
        frame[3] = (byte) (frame.length % 0x100);
        frame[4] = 0x02;
        frame[5] = (byte) 0xF0;
        frame[6] = (byte) 0x80;
        frame[7] = 0x32;
        frame[8] = 0x03;
        frame[14] = 0x02; // plen
        frame[19] = 0x04; // FUNC_READ
        frame[20] = 0x01; // item count
        final int dataHead = 21;
        frame[dataHead] = (byte) 0xFF;
        frame[dataHead + 1] = 0x04; // length in bits
        frame[dataHead + 2] = (byte) ((data.length * 8) / 0x100);
        frame[dataHead + 3] = (byte) ((data.length * 8) % 0x100);
        System.arraycopy(data, 0, frame, dataHead + 4, data.length);
        return frame;
    }

    private static final class LocalLoopback implements AutoCloseable {
        private final LocalS1500Server server;
        private final S7Connector connector;

        private LocalLoopback(final LocalS1500Server server, final S7Connector connector) {
            this.server = server;
            this.connector = connector;
        }

        static LocalLoopback open() throws IOException {
            final LocalS1500Server server = new LocalS1500Server(NEGOTIATED_PDU_LENGTH,
                    LocalS1500Server.incrementingDbMemory(512));
            try {
                final S7Connector connector = S7ConnectorFactory.buildTCPConnector(SiemensPLCS.S1500)
                        .withHost("127.0.0.1")
                        .withPort(server.getPort())
                        .withTimeout(1500)
                        .build();
                return new LocalLoopback(server, connector);
            } catch (final RuntimeException buildFailure) {
                server.close();
                throw buildFailure;
            }
        }

        @Override
        public void close() {
            try {
                this.connector.close();
            } catch (final IOException closeFailure) {
                throw new IllegalStateException("failed to close connector", closeFailure);
            } finally {
                this.server.close();
            }
        }
    }
}
