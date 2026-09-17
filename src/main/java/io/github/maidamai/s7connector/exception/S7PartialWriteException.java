
package io.github.maidamai.s7connector.exception;

import java.io.IOException;

/**
 * A transport-level failure (timeout, I/O error, interruption) during a
 * public write call.
 *
 * <p>The exception keeps the write progress that the PLC had already
 * confirmed before the failure: earlier acknowledged chunks are not rolled
 * back, and the outcome of the failing chunk itself is unknown — no response
 * was received, so it may or may not have been written. Callers must not
 * interpret a missing response as "nothing was written".</p>
 *
 * <p>It extends {@link IOException} so existing callers that handle transport
 * failures keep working; the confirmed progress is available through the
 * getters.</p>
 */
public final class S7PartialWriteException extends IOException {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = 1L;

    private final int confirmedWrittenBytes;

    private final int failingChunkOffset;

    private final int failingChunkLength;

    /**
     * Instantiates a new partial write exception.
     *
     * @param message              the message carrying area, DB, the failing
     *                             chunk coordinates and the confirmed byte count
     * @param cause                the original transport failure
     * @param confirmedWrittenBytes bytes the PLC acknowledged in earlier chunks of this call
     * @param failingChunkOffset   the byte offset of the chunk that was in flight when the transport failed
     * @param failingChunkLength   the length of the chunk that was in flight when the transport failed
     */
    public S7PartialWriteException(final String message, final Throwable cause, final int confirmedWrittenBytes,
            final int failingChunkOffset, final int failingChunkLength) {
        super(message, cause);
        this.confirmedWrittenBytes = confirmedWrittenBytes;
        this.failingChunkOffset = failingChunkOffset;
        this.failingChunkLength = failingChunkLength;
    }

    /**
     * Returns how many bytes of this write call the PLC acknowledged before
     * the transport failure. These bytes are not rolled back.
     */
    public int getConfirmedWrittenBytes() {
        return this.confirmedWrittenBytes;
    }

    /**
     * Returns the byte offset of the chunk that was in flight when the
     * transport failed. Its outcome is unknown.
     */
    public int getFailingChunkOffset() {
        return this.failingChunkOffset;
    }

    /**
     * Returns the length of the chunk that was in flight when the transport
     * failed. Its outcome is unknown.
     */
    public int getFailingChunkLength() {
        return this.failingChunkLength;
    }

}
