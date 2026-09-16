package io.github.maidamai.s7connector.impl;

import io.github.maidamai.s7connector.exception.S7Exception;
import io.github.maidamai.s7connector.impl.nodave.Nodave;
import io.github.maidamai.s7connector.impl.nodave.PDU;
import io.github.maidamai.s7connector.impl.nodave.PLCinterface;
import io.github.maidamai.s7connector.impl.nodave.S7Connection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Read and write payload windows are derived independently from the
 * negotiated PDU length: a read is bounded by the response frame
 * (12-byte header + 2-byte parameter + 4-byte data head), a single-item byte
 * write by the request frame (10-byte header + 14-byte parameter + 4-byte
 * data head). Nonsensical negotiation values must fail explicitly instead of
 * falling back to a possibly-wrong default.
 */
class S7PduWindowTest {

    @Test
    void windowsFollowTheNegotiatedPduLength() {
        assertWindows(240, 222, 212);
        assertWindows(480, 462, 452);
        assertWindows(960, 942, 932);
    }

    @Test
    void smallestUsablePduCarriesSingleByteWindows() {
        // read overhead 18, write overhead 28
        assertWindows(29, 11, 1);
        assertWindows(28, 10, -1); // 28 cannot carry a write payload -> init must fail
    }

    @Test
    void pduThatCannotCarryUserDataIsRejected() {
        assertInitThrows(18); // read window 0
        assertInitThrows(28); // write window 0
    }

    @Test
    void nonsensicalNegotiationValuesFailExplicitly() {
        assertInitThrows(0);
        assertInitThrows(-1);
        assertInitThrows(-480);
    }

    @Test
    void negotiationBeyondTransportBufferCapacityIsRejected() {
        // transport frame = TPKT(4) + COTP(3) + S7 PDU within Nodave.MAX_RAW_LEN
        assertInitThrows(Nodave.MAX_RAW_LEN - 7 + 1);
        assertInitThrows(Nodave.MAX_RAW_LEN);
        assertWindows(Nodave.MAX_RAW_LEN - 7, Nodave.MAX_RAW_LEN - 7 - 18, Nodave.MAX_RAW_LEN - 7 - 28);
    }

    @Test
    void defaultWindowsAreUsedWithoutNegotiation() {
        final S7BaseConnection connector = new TestS7BaseConnection(new DummyConnection());

        assertEquals(S7BaseConnection.DEFAULT_MAX_READ_BYTES, connector.getMaxReadBytes());
        assertEquals(S7BaseConnection.DEFAULT_MAX_WRITE_BYTES, connector.getMaxWriteBytes());
    }

    /**
     * A negative expected write window means this PDU length cannot carry a
     * write payload and init must reject it.
     */
    private static void assertWindows(final int negotiatedPduLength, final int expectedReadWindow, final int expectedWriteWindow) {
        if (expectedWriteWindow < 0) {
            assertInitThrows(negotiatedPduLength);
            return;
        }
        final S7BaseConnection connector = new TestS7BaseConnection(new DummyConnection(), negotiatedPduLength);
        assertEquals(expectedReadWindow, connector.getMaxReadBytes(),
                "read window for negotiated PDU " + negotiatedPduLength);
        assertEquals(expectedWriteWindow, connector.getMaxWriteBytes(),
                "write window for negotiated PDU " + negotiatedPduLength);
    }

    private static void assertInitThrows(final int negotiatedPduLength) {
        assertThrows(S7Exception.class,
                () -> new TestS7BaseConnection(new DummyConnection(), negotiatedPduLength),
                "init must reject negotiated PDU length " + negotiatedPduLength);
    }

    private static final class DummyConnection extends S7Connection {
        private DummyConnection() {
            super(new PLCinterface(Nodave.PROTOCOL_ISOTCP));
        }

        @Override
        public int exchange(final PDU p1) {
            throw new UnsupportedOperationException("not expected in window tests");
        }
    }

    private static final class TestS7BaseConnection extends S7BaseConnection {
        private TestS7BaseConnection(final S7Connection nodaveConnection) {
            init(nodaveConnection);
        }

        private TestS7BaseConnection(final S7Connection nodaveConnection, final int negotiatedPduLength) {
            init(nodaveConnection, negotiatedPduLength);
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}
