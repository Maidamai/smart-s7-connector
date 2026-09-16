
package io.github.maidamai.s7connector.impl;

import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Connector;
import io.github.maidamai.s7connector.exception.S7Exception;
import io.github.maidamai.s7connector.impl.nodave.Nodave;
import io.github.maidamai.s7connector.impl.nodave.S7Connection;

import java.io.IOException;
import java.util.Locale;

/**
 * Base-Connection for the S7-PLC Connection Libnodave:
 * http://libnodave.sourceforge.net/
 *
 * @author Thomas Rudin
 */
public abstract class S7BaseConnection implements S7Connector, S7ReadWindowProvider {

    /**
     * The Constant MAX_SIZE.
     */
    public static final int DEFAULT_MAX_READ_BYTES = 96;
    private static final int PDU_READ_OVERHEAD_BYTES = 18;

    /**
     * The Constant PROPERTY_AREA.
     */
    public static final String PROPERTY_AREA = "area";

    /**
     * The Constant PROPERTY_AREANUMBER.
     */
    public static final String PROPERTY_AREANUMBER = "areanumber";

    /**
     * The Constant PROPERTY_BYTES.
     */
    public static final String PROPERTY_BYTES = "bytes";

    /**
     * The Constant PROPERTY_OFFSET.
     */
    public static final String PROPERTY_OFFSET = "offset";

    /**
     * Checks the Result.
     *
     * @param libnodaveResult the libnodave result
     */
    public static void checkResult(final int libnodaveResult) {
        if (libnodaveResult != Nodave.RESULT_OK) {
            final String msg = Nodave.strerror(libnodaveResult);
            throw new IllegalArgumentException("Result: " + msg);
        }
    }

    /**
     * The dc.
     */
    private S7Connection dc;
    private int maxReadBytes = DEFAULT_MAX_READ_BYTES;

    /**
     * Initialize the connection
     *
     * @param dc the connection instance
     */
    protected void init(final S7Connection dc) {
        this.init(dc, DEFAULT_MAX_READ_BYTES);
    }

    protected void init(final S7Connection dc, final int negotiatedPduLength) {
        this.dc = dc;
        this.maxReadBytes = calculateMaxReadBytes(negotiatedPduLength);
    }

    @Override
    public int getMaxReadBytes() {
        return this.maxReadBytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized byte[] read(final DaveArea area, final int areaNumber, final int bytes, final int offset) throws IOException {
        if (bytes > this.maxReadBytes) {
            final byte[] ret = new byte[bytes];

            final byte[] currentBuffer = this.read(area, areaNumber, this.maxReadBytes, offset);
            System.arraycopy(currentBuffer, 0, ret, 0, currentBuffer.length);

            final byte[] nextBuffer = this.read(area, areaNumber, bytes - this.maxReadBytes, offset + this.maxReadBytes);
            System.arraycopy(nextBuffer, 0, ret, currentBuffer.length, nextBuffer.length);

            return ret;
        } else {
            final byte[] buffer = new byte[bytes];
            final int ret = this.dc.readBytes(area, areaNumber, offset, bytes, buffer);

            checkResult(ret);
            return buffer;
        }
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void write(final DaveArea area, final int areaNumber, final int offset, final byte[] buffer) throws IOException {
        this.writeChunked(area, areaNumber, offset, buffer, 0);
    }

    private void writeChunked(
            final DaveArea area,
            final int areaNumber,
            final int offset,
            final byte[] buffer,
            final int confirmedWrittenBytes) throws IOException {
        if (buffer.length > this.maxReadBytes) {
            // Split buffer
            final byte[] subBuffer = new byte[this.maxReadBytes];
            final byte[] nextBuffer = new byte[buffer.length - subBuffer.length];

            System.arraycopy(buffer, 0, subBuffer, 0, subBuffer.length);
            System.arraycopy(buffer, this.maxReadBytes, nextBuffer, 0, nextBuffer.length);

            this.writeChunked(area, areaNumber, offset, subBuffer, confirmedWrittenBytes);
            this.writeChunked(area, areaNumber, offset + subBuffer.length, nextBuffer,
                    confirmedWrittenBytes + subBuffer.length);
        } else {
            // Size fits
            final int ret = this.dc.writeBytes(area, areaNumber, offset, buffer.length, buffer);
            if (ret != Nodave.RESULT_OK) {
                throw writeFailure(area, areaNumber, offset, buffer.length, confirmedWrittenBytes, ret);
            }
        }
    }

    /**
     * Builds the public failure for a rejected write: it keeps the raw PLC
     * status code and the target coordinates, and states how many bytes the
     * PLC has already acknowledged. Acknowledged chunks are not rolled back;
     * after a timeout the outcome of the failing chunk itself is unknown.
     */
    private static S7Exception writeFailure(
            final DaveArea area,
            final int areaNumber,
            final int offset,
            final int length,
            final int confirmedWrittenBytes,
            final int result) {
        final StringBuilder message = new StringBuilder();
        message.append("S7 write failed: status=").append(result)
                .append(" (0x").append(String.format(Locale.ROOT, "%04X", result & 0xFFFF)).append(')')
                .append(": ").append(Nodave.strerror(result))
                .append("; area=").append(area.name())
                .append(", db=").append(areaNumber)
                .append(", offset=").append(offset)
                .append(", length=").append(length)
                .append(", confirmedWrittenBytes=").append(confirmedWrittenBytes);
        if (confirmedWrittenBytes > 0) {
            message.append(" (the PLC acknowledged the earlier chunks; they were not rolled back)");
        }
        return new S7Exception(message.toString());
    }

    private static int calculateMaxReadBytes(final int negotiatedPduLength) {
        if (negotiatedPduLength <= 0 || negotiatedPduLength == DEFAULT_MAX_READ_BYTES) {
            return DEFAULT_MAX_READ_BYTES;
        }
        final int calculatedMaxReadBytes = negotiatedPduLength - PDU_READ_OVERHEAD_BYTES;
        if (calculatedMaxReadBytes <= 0) {
            return DEFAULT_MAX_READ_BYTES;
        }
        return calculatedMaxReadBytes;
    }

}
