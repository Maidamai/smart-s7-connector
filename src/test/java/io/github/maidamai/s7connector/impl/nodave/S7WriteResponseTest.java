package io.github.maidamai.s7connector.impl.nodave;

import io.github.maidamai.s7connector.api.DaveArea;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Regression tests for write-response evaluation. A PLC that rejects a write
 * (non-0xFF item status), answers with a PDU header error, an unexpected
 * function code, a mismatching item count, or without item status bytes must
 * never be reported as a successful write. All cases run against planted
 * response frames; no PLC is involved.
 */
class S7WriteResponseTest {
    private static final int PDU_START = 0;
    private static final int HEADER_LEN = 12;
    private static final int PARAM_START = PDU_START + HEADER_LEN;

    @Test
    void plcItemRejectionIsNotReportedAsSuccess() throws IOException {
        final WriteResponseConnection connection = WriteResponseConnection.itemStatus(0x0A);

        final int result = connection.writeBytes(DaveArea.DB, 1, 0, 1, new byte[]{1});

        assertEquals(Nodave.RESULT_ITEM_NOT_AVAILABLE, result,
                "PLC item status 0x0A must be returned as a failure code, not 0");
    }

    @Test
    void addressOutOfRangeStatusPropagates() throws IOException {
        final WriteResponseConnection connection = WriteResponseConnection.itemStatus(0x05);

        final int result = connection.writeBytes(DaveArea.DB, 1, 0, 1, new byte[]{1});

        assertEquals(Nodave.RESULT_ADDRESS_OUT_OF_RANGE, result,
                "PLC item status 0x05 must be returned as a failure code, not 0");
    }

    @Test
    void pduHeaderErrorIsNotIgnored() throws IOException {
        final WriteResponseConnection connection = WriteResponseConnection.builder()
                .withHeaderError(0x8500)
                .withItemStatus((byte) 0xFF)
                .build();

        final int result = connection.writeBytes(DaveArea.DB, 1, 0, 1, new byte[]{1});

        assertNotEquals(Nodave.RESULT_OK, result,
                "a non-zero PDU header error must fail the write even if the item bytes look successful");
        assertEquals(0x8500, result, "the original PLC header error code must be preserved");
    }

    @Test
    void unexpectedFunctionCodeStillFails() throws IOException {
        final WriteResponseConnection connection = WriteResponseConnection.builder()
                .withFunction(PDU.FUNC_READ)
                .withItemStatus((byte) 0xFF)
                .build();

        final int result = connection.writeBytes(DaveArea.DB, 1, 0, 1, new byte[]{1});

        assertNotEquals(Nodave.RESULT_OK, result,
                "a response with an unexpected function code must fail the write");
    }

    @Test
    void unexpectedItemCountIsRejected() throws IOException {
        final WriteResponseConnection connection = WriteResponseConnection.builder()
                .withItemCount(2)
                .withItemStatus((byte) 0xFF)
                .build();

        final int result = connection.writeBytes(DaveArea.DB, 1, 0, 1, new byte[]{1});

        assertNotEquals(Nodave.RESULT_OK, result,
                "a response that acknowledges a different item count than the single item sent must fail");
    }

    @Test
    void missingItemStatusBytesAreRejected() throws IOException {
        final WriteResponseConnection connection = WriteResponseConnection.builder()
                .withDataLength(0)
                .withItemStatus((byte) 0xFF)
                .build();

        final int result = connection.writeBytes(DaveArea.DB, 1, 0, 1, new byte[]{1});

        assertNotEquals(Nodave.RESULT_OK, result,
                "a response without item status bytes must fail, not trust stale buffer content");
    }

    @Test
    void successfulWriteReturnsOk() throws IOException {
        final WriteResponseConnection connection = WriteResponseConnection.itemStatus(0xFF);

        final int result = connection.writeBytes(DaveArea.DB, 1, 0, 1, new byte[]{1});

        assertEquals(Nodave.RESULT_OK, result,
                "a 0xFF acknowledgement must still be treated as success");
    }

    @Test
    void failingWriteResponsesReleaseTheSemaphoreOnce() throws IOException {
        final WriteResponseConnection[] failingConnections = {
                WriteResponseConnection.itemStatus(0x0A),
                WriteResponseConnection.itemStatus(0x05),
                WriteResponseConnection.builder().withHeaderError(0x8500).withItemStatus((byte) 0xFF).build(),
                WriteResponseConnection.builder().withFunction(PDU.FUNC_READ).withItemStatus((byte) 0xFF).build(),
        };
        for (final WriteResponseConnection connection : failingConnections) {
            final int result = connection.writeBytes(DaveArea.DB, 1, 0, 1, new byte[]{1});
            assertNotEquals(Nodave.RESULT_OK, result, "setup: this connection must simulate a failed write");
            assertEquals(1, connection.semaphore.availablePermits(),
                    "a failed write must leave exactly one semaphore permit");
        }
    }

    @Test
    void pduTestWriteResultTreatsSuccessUnsigned() {
        final PDU response = receivedWriteResponse(0xFF);

        assertEquals(Nodave.RESULT_OK, response.testWriteResult(),
                "0xFF in a signed byte is -1; the helper must compare unsigned so success is recognized");
    }

    @Test
    void pduTestWriteResultReturnsUnsignedItemStatus() {
        final PDU response = receivedWriteResponse(0x0A);

        assertEquals(Nodave.RESULT_ITEM_NOT_AVAILABLE, response.testWriteResult(),
                "the helper must return the PLC item status as an unsigned value");
    }

    private static PDU receivedWriteResponse(final int itemStatus) {
        final byte[] frame = new byte[32];
        plantWriteResponse(frame, 0, PDU.FUNC_WRITE, 1, 1, (byte) itemStatus);
        final PDU response = new PDU(frame, PDU_START);
        response.setupReceivedPDU();
        return response;
    }

    private static void plantWriteResponse(
            final byte[] frame,
            final int headerError,
            final byte function,
            final int itemCount,
            final int dataLength,
            final byte itemStatus) {
        Arrays.fill(frame, (byte) 0);
        frame[0] = 0x32;
        frame[1] = 0x03; // ack-data header: 12-byte header, error field at +10
        Nodave.setUSBEWord(frame, 6, 2); // parameter length: function + item count
        Nodave.setUSBEWord(frame, 8, dataLength);
        if (headerError != 0) {
            Nodave.setUSBEWord(frame, 10, headerError);
        }
        frame[PARAM_START] = function;
        frame[PARAM_START + 1] = (byte) itemCount;
        frame[PARAM_START + 2] = itemStatus;
    }

    private static final class WriteResponseConnection extends S7Connection {
        private final int headerError;
        private final byte function;
        private final int itemCount;
        private final int dataLength;
        private final byte itemStatus;

        private WriteResponseConnection(
                final int headerError,
                final byte function,
                final int itemCount,
                final int dataLength,
                final byte itemStatus) {
            super(new PLCinterface(Nodave.PROTOCOL_ISOTCP));
            this.headerError = headerError;
            this.function = function;
            this.itemCount = itemCount;
            this.dataLength = dataLength;
            this.itemStatus = itemStatus;
        }

        static WriteResponseConnection itemStatus(final int status) {
            return builder().withItemStatus((byte) status).build();
        }

        static Builder builder() {
            return new Builder();
        }

        @Override
        public int exchange(final PDU p1) {
            plantWriteResponse(this.msgIn, this.headerError, this.function, this.itemCount, this.dataLength,
                    this.itemStatus);
            return Nodave.RESULT_OK;
        }

        private static final class Builder {
            private int headerError;
            private byte function = PDU.FUNC_WRITE;
            private int itemCount = 1;
            private int dataLength = 1;
            private byte itemStatus;

            Builder withHeaderError(final int headerError) {
                this.headerError = headerError;
                return this;
            }

            Builder withFunction(final byte function) {
                this.function = function;
                return this;
            }

            Builder withItemCount(final int itemCount) {
                this.itemCount = itemCount;
                return this;
            }

            Builder withDataLength(final int dataLength) {
                this.dataLength = dataLength;
                return this;
            }

            Builder withItemStatus(final byte itemStatus) {
                this.itemStatus = itemStatus;
                return this;
            }

            WriteResponseConnection build() {
                return new WriteResponseConnection(this.headerError, this.function, this.itemCount, this.dataLength,
                        this.itemStatus);
            }
        }
    }
}
