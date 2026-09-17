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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the encodable address space of the public read/write API. The S7
 * request item carries the DB/area number in two bytes and the start address
 * in three bytes (as a bit address for byte areas, raw units for
 * TIMER/COUNTER reads). Values outside these fields are rejected before any
 * request is issued — silently truncating them would address a different,
 * valid-looking target on the device.
 *
 * <p>Expected limits: DB/area number &le; 65535; byte offset + length &le;
 * 2097152 (encoded bit address &le; 0xFFFFFF); TIMER/COUNTER read addresses
 * &le; 16777215 raw.</p>
 */
class S7AddressBoundsTest {

    private static final int MAX_BYTE_OFFSET = (int) (PDU.MAX_ITEM_ADDRESS / 8);   // 2097151
    private static final int MAX_RAW_ADDRESS = (int) PDU.MAX_ITEM_ADDRESS;         // 16777215

    private final AddressRecordingConnection nodaveConnection = new AddressRecordingConnection();
    private final S7BaseConnection connector = testConnector(nodaveConnection, 240);

    // ------------------------------------------------------------------
    // DB number: two-byte field
    // ------------------------------------------------------------------

    @Test
    void dbNumberAbove65535IsRejectedBeforeAnyRequest() {
        assertRejectedBeforeAnyRequest(() -> connector.read(DaveArea.DB, 65536, 1, 0));
        assertRejectedBeforeAnyRequest(() -> connector.read(DaveArea.DB, 65537, 1, 0));
        assertRejectedBeforeAnyRequest(() -> connector.write(DaveArea.DB, 65537, 0, new byte[1]));

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> connector.write(DaveArea.DB, 65537, 0, new byte[1]));
        assertTrue(failure.getMessage().contains("65537"), "message should name the DB number: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("65535"),
                "message should name the encodable maximum: " + failure.getMessage());
    }

    @Test
    void dbNumber65535IsAcceptedAndEncodesVerbatim() throws IOException {
        connector.write(DaveArea.DB, 65535, 0, new byte[1]);

        assertEquals(1, nodaveConnection.writeRequests.size(), "the legal DB boundary must produce one request");
        assertEquals(65535, nodaveConnection.writeRequests.get(0).dbNumber,
                "DB 65535 must be encoded verbatim in the two-byte field");
    }

    // ------------------------------------------------------------------
    // byte offsets: three-byte bit address field
    // ------------------------------------------------------------------

    @Test
    void byteOffsetAboveTheBitAddressFieldIsRejectedBeforeAnyRequest() {
        // offset 2097152 * 8 = 0x1000000 -> would truncate to 00 00 00, i.e. offset 0
        assertRejectedBeforeAnyRequest(() -> connector.write(DaveArea.DB, 1, MAX_BYTE_OFFSET + 1, new byte[1]));
        assertRejectedBeforeAnyRequest(() -> connector.read(DaveArea.DB, 1, 1, MAX_BYTE_OFFSET + 1));
    }

    @Test
    void rangeCrossingTheEncodingBoundaryIsRejected() {
        // the last byte of offset 2097151 + 2 bytes is 2097152 -> not encodable
        assertRejectedBeforeAnyRequest(() -> connector.write(DaveArea.DB, 1, MAX_BYTE_OFFSET, new byte[2]));
        assertRejectedBeforeAnyRequest(() -> connector.read(DaveArea.DB, 1, 2, MAX_BYTE_OFFSET));
    }

    @Test
    void byteOffset2097151WithOneByteIsAcceptedAndEncodesVerbatim() throws IOException {
        connector.write(DaveArea.DB, 1, MAX_BYTE_OFFSET, new byte[1]);

        assertEquals(1, nodaveConnection.writeRequests.size(), "the legal offset boundary must produce one request");
        assertEquals(0xFFFFF8, nodaveConnection.writeRequests.get(0).bitAddress,
                "offset 2097151 must encode as bit address 0xFFFFF8, not wrap to 0");
    }

    @Test
    void nonDbAreasFollowTheSameBitAddressRule() {
        assertRejectedBeforeAnyRequest(() -> connector.write(DaveArea.FLAGS, 0, MAX_BYTE_OFFSET + 1, new byte[1]));
        assertRejectedBeforeAnyRequest(() -> connector.read(DaveArea.INPUTS, 0, 1, MAX_BYTE_OFFSET + 1));
        assertRejectedBeforeAnyRequest(() -> connector.read(DaveArea.OUTPUTS, 0, 1, MAX_BYTE_OFFSET + 1));
    }

    @Test
    void nonDbAreaWithinTheLimitIsAccepted() throws IOException {
        connector.write(DaveArea.FLAGS, 0, 100, new byte[1]);
        assertEquals(1, nodaveConnection.writeRequests.size());
        assertEquals(800, nodaveConnection.writeRequests.get(0).bitAddress);
    }

    // ------------------------------------------------------------------
    // TIMER/COUNTER: raw units on read, bit address on write (encoder
    // asymmetry locked as-is; changing it is a protocol behavior change)
    // ------------------------------------------------------------------

    @Test
    void timerReadAddressesRawUnitsUpTo16777215() throws IOException {
        assertRejectedBeforeAnyRequest(() -> connector.read(DaveArea.TIMER, 0, 1, MAX_RAW_ADDRESS + 1));

        connector.read(DaveArea.TIMER, 0, 1, MAX_RAW_ADDRESS);
        assertEquals(1, nodaveConnection.readRequests.size(), "the raw legal boundary must produce one request");
        assertEquals(MAX_RAW_ADDRESS, nodaveConnection.readRequests.get(0).bitAddress,
                "TIMER reads must encode the address in raw units");
    }

    @Test
    void timerWritesAreStillBitAddressed() {
        // offset 2097152 * 8 = 0x1000000 -> beyond the three address bytes,
        // while the same offset stays encodable for TIMER reads (raw units)
        assertRejectedBeforeAnyRequest(() -> connector.write(DaveArea.TIMER, 0, MAX_BYTE_OFFSET + 1, new byte[1]));
        assertRejectedBeforeAnyRequest(() -> connector.write(DaveArea.COUNTER, 0, MAX_BYTE_OFFSET + 1, new byte[1]));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void assertRejectedBeforeAnyRequest(final IoCall call) {
        assertThrows(IllegalArgumentException.class, call::run,
                "unencodable ranges must be rejected as IllegalArgumentException");
        assertEquals(0, nodaveConnection.totalRequests(),
                "rejections must happen before any request is sent, so the PLC cannot be pointed at a truncated address");
    }

    private interface IoCall {
        void run() throws IOException;
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

    private static final class CapturedItem {
        final int dbNumber;
        final int bitAddress;

        CapturedItem(final int dbNumber, final int bitAddress) {
            this.dbNumber = dbNumber;
            this.bitAddress = bitAddress;
        }
    }

    /**
     * Records the DB number and start address actually encoded in each
     * outgoing request item, and acknowledges every request.
     */
    private static final class AddressRecordingConnection extends S7Connection {
        final List<CapturedItem> readRequests = new ArrayList<>();
        final List<CapturedItem> writeRequests = new ArrayList<>();

        private AddressRecordingConnection() {
            super(new PLCinterface(Nodave.PROTOCOL_ISOTCP));
        }

        int totalRequests() {
            return this.readRequests.size() + this.writeRequests.size();
        }

        @Override
        public int exchange(final PDU p1) {
            final int header = this.PDUstartOut;
            final int plen = Nodave.USBEWord(this.msgOut, header + 6);
            final int param = header + 10; // type-1 request header
            final int function = Nodave.USByte(this.msgOut, param);
            final int itemStart = param + 2;
            final int dbNumber = ((this.msgOut[itemStart + 6] & 0xFF) << 8) | (this.msgOut[itemStart + 7] & 0xFF);
            final int bitAddress = readUnsigned24(this.msgOut, itemStart + 9);

            if (function == PDU.FUNC_WRITE) {
                this.writeRequests.add(new CapturedItem(dbNumber, bitAddress));
                plantWriteAck();
                return Nodave.RESULT_OK;
            }
            if (function == PDU.FUNC_READ) {
                final int length = Nodave.USBEWord(this.msgOut, itemStart + 4);
                this.readRequests.add(new CapturedItem(dbNumber, bitAddress));
                plantReadAck(length);
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
            this.answLen = 15;
        }

        private void plantReadAck(final int length) {
            Arrays.fill(this.msgIn, 0, 18 + length + 4, (byte) 0);
            this.msgIn[0] = 0x32;
            this.msgIn[1] = 0x03;
            Nodave.setUSBEWord(this.msgIn, 6, 2);
            Nodave.setUSBEWord(this.msgIn, 8, 4 + length);
            this.msgIn[12] = PDU.FUNC_READ;
            this.msgIn[13] = 1;
            this.msgIn[14] = (byte) 0xFF;
            this.msgIn[15] = 0x04;
            Nodave.setUSBEWord(this.msgIn, 16, length * 8);
            this.answLen = 18 + length;
        }

        private static int readUnsigned24(final byte[] bytes, final int offset) {
            return ((bytes[offset] & 0xFF) << 16)
                    | ((bytes[offset + 1] & 0xFF) << 8)
                    | (bytes[offset + 2] & 0xFF);
        }
    }
}
