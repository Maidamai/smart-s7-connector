package io.github.maidamai.s7connector.impl;

import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.impl.nodave.Nodave;
import io.github.maidamai.s7connector.impl.nodave.PDU;
import io.github.maidamai.s7connector.impl.nodave.PLCinterface;
import io.github.maidamai.s7connector.impl.nodave.S7Connection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chunked reads and writes must be iterative, must never emit an S7 PDU
 * larger than the negotiated length, and must reassemble the payload exactly.
 * Every outgoing frame is captured in {@code exchange} and re-measured from
 * the header fields, so the assertion covers the actually encoded bytes.
 */
class S7ChunkingTest {
    private static final int NEGOTIATED_PDU_LENGTH = 240;
    private static final int READ_WINDOW = NEGOTIATED_PDU_LENGTH - 18;  // 222
    private static final int WRITE_WINDOW = NEGOTIATED_PDU_LENGTH - 28; // 212

    @Test
    void largeReadIsChunkedByTheReadWindowAndReassembled() throws IOException {
        final FrameRecordingConnection nodaveConnection = new FrameRecordingConnection();
        final S7BaseConnection connector = testConnector(nodaveConnection, NEGOTIATED_PDU_LENGTH);
        final int total = 1000;

        final byte[] result = connector.read(DaveArea.DB, 1, total, 0);

        assertEquals(ceilDiv(total, READ_WINDOW), nodaveConnection.readRequests.size(),
                "read chunk count should follow the read window");
        for (final CapturedFrame frame : nodaveConnection.readRequests) {
            assertTrue(frame.pduLength <= NEGOTIATED_PDU_LENGTH,
                    "every read request S7 PDU must fit the negotiated length, got " + frame.pduLength);
        }
        assertArrayEquals(expectedPattern(0, total), result, "chunked read must reassemble the exact payload");
    }

    @Test
    void largeWriteIsChunkedByTheWriteWindowAndReassembled() throws IOException {
        final FrameRecordingConnection nodaveConnection = new FrameRecordingConnection();
        final S7BaseConnection connector = testConnector(nodaveConnection, NEGOTIATED_PDU_LENGTH);
        final byte[] payload = expectedPattern(64, 1000);

        connector.write(DaveArea.DB, 1, 64, payload);

        assertEquals(ceilDiv(payload.length, WRITE_WINDOW), nodaveConnection.writeRequests.size(),
                "write chunk count should follow the write window");
        final byte[] reassembled = new byte[payload.length];
        int position = 0;
        for (final CapturedFrame frame : nodaveConnection.writeRequests) {
            assertEquals(28 + frame.payload.length, frame.pduLength,
                    "write frame length must be header(10)+parameter(14)+data head(4)+payload");
            assertTrue(frame.pduLength <= NEGOTIATED_PDU_LENGTH,
                    "every write request S7 PDU must fit the negotiated length, got " + frame.pduLength);
            System.arraycopy(frame.payload, 0, reassembled, position, frame.payload.length);
            position += frame.payload.length;
        }
        assertArrayEquals(payload, reassembled, "chunked write must carry the exact payload in order");
    }

    @Test
    void offsetAdvancesAcrossWriteChunks() throws IOException {
        final FrameRecordingConnection nodaveConnection = new FrameRecordingConnection();
        final S7BaseConnection connector = testConnector(nodaveConnection, NEGOTIATED_PDU_LENGTH);

        connector.write(DaveArea.DB, 1, 10, new byte[WRITE_WINDOW + 5]);

        assertEquals(2, nodaveConnection.writeRequests.size());
        assertEquals(10, nodaveConnection.writeRequests.get(0).byteOffset);
        assertEquals(10 + WRITE_WINDOW, nodaveConnection.writeRequests.get(1).byteOffset,
                "the second chunk must start after the first chunk's window");
    }

    @Test
    void zeroLengthOperationsDoNotTouchThePlc() throws IOException {
        final FrameRecordingConnection nodaveConnection = new FrameRecordingConnection();
        final S7BaseConnection connector = testConnector(nodaveConnection, NEGOTIATED_PDU_LENGTH);

        assertEquals(0, connector.read(DaveArea.DB, 1, 0, 0).length);
        connector.write(DaveArea.DB, 1, 0, new byte[0]);

        assertEquals(0, nodaveConnection.exchangeCount, "zero-length operations must not produce requests");
    }

    @Test
    void invalidArgumentsAreRejectedBeforeAnyRequest() throws IOException {
        final FrameRecordingConnection nodaveConnection = new FrameRecordingConnection();
        final S7BaseConnection connector = testConnector(nodaveConnection, NEGOTIATED_PDU_LENGTH);

        assertThrows(IllegalArgumentException.class, () -> connector.read(null, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> connector.read(DaveArea.DB, -1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> connector.read(DaveArea.DB, 1, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> connector.read(DaveArea.DB, 1, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> connector.read(DaveArea.DB, 1, 1, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> connector.write(DaveArea.DB, 1, 0, null));
        assertThrows(IllegalArgumentException.class, () -> connector.write(DaveArea.DB, 1, -1, new byte[1]));
        assertThrows(IllegalArgumentException.class,
                () -> connector.write(DaveArea.DB, 1, Integer.MAX_VALUE - 1, new byte[8]));

        assertEquals(0, nodaveConnection.exchangeCount, "invalid arguments must fail before any request is sent");
    }

    private static int ceilDiv(final int value, final int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static byte[] expectedPattern(final int offset, final int length) {
        final byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) ((offset + i) & 0xFF);
        }
        return data;
    }

    private static S7BaseConnection testConnector(final S7Connection nodaveConnection, final int negotiatedPduLength) {
        return new TestS7BaseConnection(nodaveConnection, negotiatedPduLength);
    }

    private static final class TestS7BaseConnection extends S7BaseConnection {
        private TestS7BaseConnection(final S7Connection nodaveConnection, final int negotiatedPduLength) {
            init(nodaveConnection, negotiatedPduLength);
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    private static final class CapturedFrame {
        final int pduLength;
        final int byteOffset;
        final byte[] payload;

        CapturedFrame(final int pduLength, final int byteOffset, final byte[] payload) {
            this.pduLength = pduLength;
            this.byteOffset = byteOffset;
            this.payload = payload;
        }
    }

    /**
     * Records every outgoing S7 request frame (measured from the header
     * fields actually encoded in msgOut) and answers reads with an
     * incrementing pattern derived from the requested offset, so reassembly
     * can be verified byte by byte.
     */
    private static final class FrameRecordingConnection extends S7Connection {
        final List<CapturedFrame> readRequests = new ArrayList<>();
        final List<CapturedFrame> writeRequests = new ArrayList<>();
        int exchangeCount;

        private FrameRecordingConnection() {
            super(new PLCinterface(Nodave.PROTOCOL_ISOTCP));
        }

        @Override
        public int exchange(final PDU p1) {
            this.exchangeCount++;
            final int header = this.PDUstartOut;
            final int hlen = 10; // type-1 request header
            final int plen = Nodave.USBEWord(this.msgOut, header + 6);
            final int dlen = Nodave.USBEWord(this.msgOut, header + 8);
            final int param = header + hlen;
            final int function = Nodave.USByte(this.msgOut, param);
            final int dataStart = param + plen;
            final int pduLength = hlen + plen + dlen;

            if (function == PDU.FUNC_WRITE) {
                final int itemStart = param + 2;
                final int bitAddress = readUnsigned24(this.msgOut, itemStart + 9);
                final byte[] payload = Arrays.copyOfRange(this.msgOut, dataStart + 4, dataStart + dlen);
                this.writeRequests.add(new CapturedFrame(pduLength, bitAddress / 8, payload));
                plantWriteAck();
                return Nodave.RESULT_OK;
            }
            if (function == PDU.FUNC_READ) {
                final int itemStart = param + 2;
                final int length = Nodave.USBEWord(this.msgOut, itemStart + 4);
                final int bitAddress = readUnsigned24(this.msgOut, itemStart + 9);
                this.readRequests.add(new CapturedFrame(pduLength, bitAddress / 8, new byte[0]));
                plantReadAck(bitAddress / 8, length);
                return Nodave.RESULT_OK;
            }
            throw new IllegalStateException("unexpected request function " + function);
        }

        private void plantWriteAck() {
            Arrays.fill(this.msgIn, 0, 32, (byte) 0);
            this.msgIn[0] = 0x32;
            this.msgIn[1] = 0x03;
            Nodave.setUSBEWord(this.msgIn, 6, 2);
            Nodave.setUSBEWord(this.msgIn, 8, 1);
            this.msgIn[12] = PDU.FUNC_WRITE;
            this.msgIn[13] = 1;
            this.msgIn[14] = (byte) 0xFF;
        }

        private void plantReadAck(final int offset, final int length) {
            final byte[] data = expectedPattern(offset, length);
            Arrays.fill(this.msgIn, 0, 18 + data.length + 4, (byte) 0);
            this.msgIn[0] = 0x32;
            this.msgIn[1] = 0x03;
            Nodave.setUSBEWord(this.msgIn, 6, 2);
            Nodave.setUSBEWord(this.msgIn, 8, 4 + data.length);
            this.msgIn[12] = PDU.FUNC_READ;
            this.msgIn[13] = 1;
            this.msgIn[14] = (byte) 0xFF;
            this.msgIn[15] = 0x04; // length in bits
            Nodave.setUSBEWord(this.msgIn, 16, data.length * 8);
            System.arraycopy(data, 0, this.msgIn, 18, data.length);
        }

        private static int readUnsigned24(final byte[] bytes, final int offset) {
            return ((bytes[offset] & 0xFF) << 16)
                    | ((bytes[offset + 1] & 0xFF) << 8)
                    | (bytes[offset + 2] & 0xFF);
        }
    }
}
