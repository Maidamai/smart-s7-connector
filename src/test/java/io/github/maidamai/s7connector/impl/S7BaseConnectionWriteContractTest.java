package io.github.maidamai.s7connector.impl;

import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.exception.S7Exception;
import io.github.maidamai.s7connector.exception.S7PartialWriteException;
import io.github.maidamai.s7connector.impl.nodave.Nodave;
import io.github.maidamai.s7connector.impl.nodave.PDU;
import io.github.maidamai.s7connector.impl.nodave.PLCinterface;
import io.github.maidamai.s7connector.impl.nodave.S7Connection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the public {@code S7Connector.write} path: a rejected
 * write must fail the call with the target coordinates (area, DB, offset,
 * length), the original PLC status code, and — for split writes — the number
 * of bytes the PLC has already acknowledged. Runs against scripted responses;
 * no PLC is involved.
 */
class S7BaseConnectionWriteContractTest {

    @Test
    void writeThrowsWithTargetContextWhenPlcRejects() {
        final ScriptedWriteConnection nodaveConnection = new ScriptedWriteConnection().failNextWriteWith(0x0A);
        final S7BaseConnection connector = testConnector(nodaveConnection, 240);

        final S7Exception failure = assertThrows(S7Exception.class,
                () -> connector.write(DaveArea.DB, 1, 0, new byte[8]),
                "a PLC rejection must fail the public write call");

        final String message = failure.getMessage();
        assertTrue(message.contains("status=10"), "message should carry the raw PLC status, but was: " + message);
        assertTrue(message.contains("area=DB"), "message should carry the area, but was: " + message);
        assertTrue(message.contains("db=1"), "message should carry the DB number, but was: " + message);
        assertTrue(message.contains("offset=0"), "message should carry the offset, but was: " + message);
        assertTrue(message.contains("length=8"), "message should carry the length, but was: " + message);
        assertTrue(message.contains("confirmedWrittenBytes=0"),
                "message should carry the confirmed byte count, but was: " + message);
    }

    @Test
    void writeReportsConfirmedBytesWhenSecondChunkFails() {
        // negotiated PDU 240 -> write window 240-28=212; a 300-byte write splits into 212+88
        final ScriptedWriteConnection nodaveConnection = new ScriptedWriteConnection().failWriteNumberWith(2, 0x0A);
        final S7BaseConnection connector = testConnector(nodaveConnection, 240);

        final S7Exception failure = assertThrows(S7Exception.class,
                () -> connector.write(DaveArea.DB, 1, 0, new byte[300]),
                "a second-chunk rejection must fail the public write call");

        final String message = failure.getMessage();
        assertTrue(message.contains("offset=212"), "message should carry the failing chunk offset, but was: " + message);
        assertTrue(message.contains("length=88"), "message should carry the failing chunk length, but was: " + message);
        assertTrue(message.contains("confirmedWrittenBytes=212"),
                "message must state how many bytes the PLC acknowledged before the failure, but was: " + message);
        assertTrue(message.contains("0x000A"),
                "message should carry the raw PLC status code in hex, but was: " + message);
    }

    @Test
    void writeReportsConfirmedBytesWhenThirdChunkFails() {
        // negotiated PDU 240 -> write window 212; a 500-byte write splits into 212+212+76
        final ScriptedWriteConnection nodaveConnection = new ScriptedWriteConnection().failWriteNumberWith(3, 0x05);
        final S7BaseConnection connector = testConnector(nodaveConnection, 240);

        final S7Exception failure = assertThrows(S7Exception.class,
                () -> connector.write(DaveArea.DB, 1, 0, new byte[500]),
                "a third-chunk rejection must fail the public write call");

        final String message = failure.getMessage();
        assertTrue(message.contains("offset=424"), "message should carry the failing chunk offset, but was: " + message);
        assertTrue(message.contains("length=76"), "message should carry the failing chunk length, but was: " + message);
        assertTrue(message.contains("confirmedWrittenBytes=424"),
                "confirmed bytes must accumulate across more than two chunks, but was: " + message);
    }

    @Test
    void writeSucceedsWhenAllChunksAreAcknowledged() {
        final ScriptedWriteConnection nodaveConnection = new ScriptedWriteConnection();
        final S7BaseConnection connector = testConnector(nodaveConnection, 240);

        assertDoesNotThrow(() -> connector.write(DaveArea.DB, 1, 0, new byte[300]),
                "fully acknowledged split writes must succeed");
    }

    @Test
    void transportFailureOnSecondChunkKeepsConfirmedProgress() {
        // negotiated PDU 240 -> write window 212; a 500-byte write splits into 212+212+76
        final IOException transportFailure = new IOException("simulated read timeout");
        final ScriptedWriteConnection nodaveConnection =
                new ScriptedWriteConnection().failWriteNumberWithIOException(2, transportFailure);
        final S7BaseConnection connector = testConnector(nodaveConnection, 240);

        final S7PartialWriteException failure = assertThrows(S7PartialWriteException.class,
                () -> connector.write(DaveArea.DB, 1, 0, new byte[500]),
                "a transport failure mid-write must surface the confirmed progress");

        assertEquals(212, failure.getConfirmedWrittenBytes(),
                "the first chunk was acknowledged before the transport failed");
        assertEquals(212, failure.getFailingChunkOffset(), "the failing chunk is the second one");
        assertEquals(212, failure.getFailingChunkLength());
        assertEquals(2, nodaveConnection.messageNumber, "the third chunk must never be executed");
        assertSame(transportFailure, failure.getCause(), "the original transport failure must be preserved");

        final String message = failure.getMessage();
        assertTrue(message.contains("confirmedWrittenBytes=212"),
                "message should carry the confirmed byte count, but was: " + message);
        assertTrue(message.contains("offset=212"), "message should carry the failing chunk offset, but was: " + message);
        assertTrue(message.contains("unknown"),
                "message must state that the failing chunk's outcome is unknown, but was: " + message);
    }

    @Test
    void transportFailureOnFirstChunkReportsZeroConfirmedAndUnknownOutcome() {
        final IOException transportFailure = new IOException("connect reset");
        final ScriptedWriteConnection nodaveConnection =
                new ScriptedWriteConnection().failWriteNumberWithIOException(1, transportFailure);
        final S7BaseConnection connector = testConnector(nodaveConnection, 240);

        final S7PartialWriteException failure = assertThrows(S7PartialWriteException.class,
                () -> connector.write(DaveArea.DB, 1, 0, new byte[300]));

        assertEquals(0, failure.getConfirmedWrittenBytes(), "no chunk was confirmed yet");
        assertEquals(0, failure.getFailingChunkOffset());
        assertEquals(212, failure.getFailingChunkLength());
        assertEquals(1, nodaveConnection.messageNumber, "only the first chunk was attempted");
        assertTrue(failure.getMessage().contains("confirmedWrittenBytes=0"));
        assertTrue(failure.getMessage().contains("may or may not have been written"),
                "a timeout must not be reported as 'nothing was written': " + failure.getMessage());
        assertSame(transportFailure, failure.getCause());
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
            // nothing to release in this in-memory connector
        }
    }

    /**
     * Answers every write exchange with 0xFF by default; individual exchanges
     * can be scripted to return a PLC item status instead.
     */
    private static final class ScriptedWriteConnection extends S7Connection {
        private int failingExchangeNumber;
        private int failingStatus;
        private IOException transportFailure;

        private ScriptedWriteConnection() {
            super(new PLCinterface(Nodave.PROTOCOL_ISOTCP));
            this.failingExchangeNumber = Integer.MAX_VALUE;
            this.failingStatus = 0;
        }

        ScriptedWriteConnection failNextWriteWith(final int status) {
            return failWriteNumberWith(1, status);
        }

        ScriptedWriteConnection failWriteNumberWith(final int exchangeNumber, final int status) {
            if (exchangeNumber < 1 || status < 0 || status > 0xFF) {
                throw new IllegalArgumentException("invalid script: exchange=" + exchangeNumber + ", status=" + status);
            }
            this.failingExchangeNumber = exchangeNumber;
            this.failingStatus = status;
            this.transportFailure = null;
            return this;
        }

        ScriptedWriteConnection failWriteNumberWithIOException(final int exchangeNumber, final IOException failure) {
            if (exchangeNumber < 1 || failure == null) {
                throw new IllegalArgumentException("invalid script: exchange=" + exchangeNumber);
            }
            this.failingExchangeNumber = exchangeNumber;
            this.transportFailure = failure;
            return this;
        }

        @Override
        public int exchange(final PDU p1) throws IOException {
            this.messageNumber++;
            if (this.messageNumber == this.failingExchangeNumber && this.transportFailure != null) {
                throw this.transportFailure;
            }
            final byte itemStatus = this.messageNumber == this.failingExchangeNumber
                    ? (byte) this.failingStatus
                    : (byte) 0xFF;
            final byte[] frame = this.msgIn;
            Arrays.fill(frame, 0, 32, (byte) 0);
            frame[0] = 0x32;
            frame[1] = 0x03;
            Nodave.setUSBEWord(frame, 6, 2);
            Nodave.setUSBEWord(frame, 8, 1);
            frame[12] = PDU.FUNC_WRITE;
            frame[13] = 1;
            frame[14] = itemStatus;
            this.answLen = 15;
            return Nodave.RESULT_OK;
        }
    }
}
