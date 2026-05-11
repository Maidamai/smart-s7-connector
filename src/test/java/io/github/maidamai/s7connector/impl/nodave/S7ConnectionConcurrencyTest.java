package io.github.maidamai.s7connector.impl.nodave;

import io.github.maidamai.s7connector.api.DaveArea;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S7ConnectionConcurrencyTest {
    @Test
    void readBytesReleasesSemaphoreWhenExchangeThrows() throws IOException, InterruptedException, ExecutionException, TimeoutException {
        final ThrowingReadConnection connection = new ThrowingReadConnection();
        assertThrows(IOException.class, () -> connection.readBytes(DaveArea.DB, 1, 0, 1, new byte[1]),
                "transport exception should propagate from readBytes");

        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            final Future<Integer> result = executorService.submit(() -> connection.readBytes(DaveArea.DB, 1, 0, 1, new byte[1]));

            assertEquals(Nodave.RESULT_CPU_RETURNED_NO_DATA, result.get(1, TimeUnit.SECONDS),
                    "second read should not block on a leaked semaphore permit");
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void writeBytesKeepsSingleSemaphorePermitWhenExchangeThrows() {
        final ThrowingWriteConnection connection = new ThrowingWriteConnection();

        assertThrows(IOException.class, () -> connection.writeBytes(DaveArea.DB, 1, 0, 1, new byte[]{1}),
                "transport exception should propagate from writeBytes");

        assertEquals(1, connection.semaphore.availablePermits(),
                "writeBytes should not leak semaphore permits when exchange throws");
    }

    @Test
    void writeBytesKeepsSingleSemaphorePermitWhenWriteSucceeds() throws IOException {
        final SuccessfulWriteConnection connection = new SuccessfulWriteConnection();

        assertEquals(Nodave.RESULT_OK, connection.writeBytes(DaveArea.DB, 1, 0, 1, new byte[]{1}),
                "successful write should return RESULT_OK");

        assertEquals(1, connection.semaphore.availablePermits(),
                "writeBytes should release exactly one semaphore permit after success");
    }

    private static final class ThrowingReadConnection extends S7Connection {
        private int exchangeCount;

        private ThrowingReadConnection() {
            super(new PLCinterface(Nodave.PROTOCOL_ISOTCP));
        }

        @Override
        public int exchange(final PDU p1) throws IOException {
            this.exchangeCount++;
            if (this.exchangeCount == 1) {
                throw new IOException("simulated transport failure");
            }
            return Nodave.RESULT_CPU_RETURNED_NO_DATA;
        }
    }

    private static final class ThrowingWriteConnection extends S7Connection {
        private ThrowingWriteConnection() {
            super(new PLCinterface(Nodave.PROTOCOL_ISOTCP));
        }

        @Override
        public int exchange(final PDU p1) throws IOException {
            throw new IOException("simulated write transport failure");
        }
    }

    private static final class SuccessfulWriteConnection extends S7Connection {
        private SuccessfulWriteConnection() {
            super(new PLCinterface(Nodave.PROTOCOL_ISOTCP));
        }

        @Override
        public int exchange(final PDU p1) {
            this.msgIn[0] = 0x32;
            this.msgIn[1] = 0x03;
            this.msgIn[6] = 0x00;
            this.msgIn[7] = 0x02;
            this.msgIn[8] = 0x00;
            this.msgIn[9] = 0x01;
            this.msgIn[12] = PDU.FUNC_WRITE;
            this.msgIn[13] = 0x01;
            this.msgIn[14] = (byte) 0xFF;
            return Nodave.RESULT_OK;
        }
    }
}
