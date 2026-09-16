package io.github.maidamai.s7connector.impl;

import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.exception.S7Exception;
import io.github.maidamai.s7connector.impl.nodave.Nodave;
import io.github.maidamai.s7connector.impl.nodave.PDU;
import io.github.maidamai.s7connector.impl.nodave.PLCinterface;
import io.github.maidamai.s7connector.impl.nodave.S7Connection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
        // negotiated PDU 240 -> chunk size 240-18=222; a 300-byte write splits into 222+78
        final ScriptedWriteConnection nodaveConnection = new ScriptedWriteConnection().failWriteNumberWith(2, 0x0A);
        final S7BaseConnection connector = testConnector(nodaveConnection, 240);

        final S7Exception failure = assertThrows(S7Exception.class,
                () -> connector.write(DaveArea.DB, 1, 0, new byte[300]),
                "a second-chunk rejection must fail the public write call");

        final String message = failure.getMessage();
        assertTrue(message.contains("offset=222"), "message should carry the failing chunk offset, but was: " + message);
        assertTrue(message.contains("length=78"), "message should carry the failing chunk length, but was: " + message);
        assertTrue(message.contains("confirmedWrittenBytes=222"),
                "message must state how many bytes the PLC acknowledged before the failure, but was: " + message);
        assertTrue(message.contains("0x000A"),
                "message should carry the raw PLC status code in hex, but was: " + message);
    }

    @Test
    void writeReportsConfirmedBytesWhenThirdChunkFails() {
        // negotiated PDU 240 -> chunk size 222; a 500-byte write splits into 222+222+56
        final ScriptedWriteConnection nodaveConnection = new ScriptedWriteConnection().failWriteNumberWith(3, 0x05);
        final S7BaseConnection connector = testConnector(nodaveConnection, 240);

        final S7Exception failure = assertThrows(S7Exception.class,
                () -> connector.write(DaveArea.DB, 1, 0, new byte[500]),
                "a third-chunk rejection must fail the public write call");

        final String message = failure.getMessage();
        assertTrue(message.contains("offset=444"), "message should carry the failing chunk offset, but was: " + message);
        assertTrue(message.contains("length=56"), "message should carry the failing chunk length, but was: " + message);
        assertTrue(message.contains("confirmedWrittenBytes=444"),
                "confirmed bytes must accumulate across more than two chunks, but was: " + message);
    }

    @Test
    void writeSucceedsWhenAllChunksAreAcknowledged() {
        final ScriptedWriteConnection nodaveConnection = new ScriptedWriteConnection();
        final S7BaseConnection connector = testConnector(nodaveConnection, 240);

        assertDoesNotThrow(() -> connector.write(DaveArea.DB, 1, 0, new byte[300]),
                "fully acknowledged split writes must succeed");
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
            return this;
        }

        @Override
        public int exchange(final PDU p1) throws IOException {
            this.messageNumber++;
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
            return Nodave.RESULT_OK;
        }
    }
}
