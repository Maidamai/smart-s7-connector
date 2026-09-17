

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

    /**
     * Default write window when no PDU length negotiation is available.
     */
    public static final int DEFAULT_MAX_WRITE_BYTES = 96;

    /**
     * Read responses occupy an S7 PDU of 12-byte header + 2-byte parameter
     * (function, item count) + 4-byte data head + user data.
     */
    private static final int PDU_READ_OVERHEAD_BYTES = 18;

    /**
     * A single-item byte write request occupies an S7 PDU of 10-byte header +
     * 14-byte parameter (2 request head + 12 item) + 4-byte data head +
     * payload. The write window is therefore smaller than the read window for
     * the same negotiated PDU length.
     */
    private static final int PDU_WRITE_OVERHEAD_BYTES = 28;

    /**
     * TPKT(4) + COTP(3) precede the S7 PDU in the transport frame, and the
     * connection buffers are {@link Nodave#MAX_RAW_LEN} bytes.
     */
    private static final int TRANSPORT_HEADER_BYTES = 7;

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
    private int maxWriteBytes = DEFAULT_MAX_WRITE_BYTES;

    /**
     * Initialize the connection with the default windows (no PDU negotiation).
     *
     * @param dc the connection instance
     */
    protected void init(final S7Connection dc) {
        this.dc = dc;
        this.maxReadBytes = DEFAULT_MAX_READ_BYTES;
        this.maxWriteBytes = DEFAULT_MAX_WRITE_BYTES;
    }

    /**
     * Initialize the connection from the negotiated PDU length.
     *
     * @param dc                 the connection instance
     * @param negotiatedPduLength the negotiated S7 PDU length, must be positive
     *                            and large enough to carry at least one byte
     *                            of user data in a single-item write request
     */
    protected void init(final S7Connection dc, final int negotiatedPduLength) {
        this.dc = dc;
        this.maxReadBytes = calculateWindow(negotiatedPduLength, PDU_READ_OVERHEAD_BYTES, "read");
        this.maxWriteBytes = calculateWindow(negotiatedPduLength, PDU_WRITE_OVERHEAD_BYTES, "write");
    }

    private static int calculateWindow(final int negotiatedPduLength, final int pduOverheadBytes, final String windowKind) {
        if (negotiatedPduLength <= 0) {
            throw new S7Exception("negotiated PDU length must be positive, got " + negotiatedPduLength);
        }
        if (negotiatedPduLength > Nodave.MAX_RAW_LEN - TRANSPORT_HEADER_BYTES) {
            throw new S7Exception("negotiated PDU length " + negotiatedPduLength
                    + " exceeds the transport buffer capacity " + (Nodave.MAX_RAW_LEN - TRANSPORT_HEADER_BYTES));
        }
        final int window = negotiatedPduLength - pduOverheadBytes;
        if (window < 1) {
            throw new S7Exception("negotiated PDU length " + negotiatedPduLength + " cannot carry a single byte of "
                    + windowKind + " user data (overhead " + pduOverheadBytes + ")");
        }
        return window;
    }

    @Override
    public int getMaxReadBytes() {
        return this.maxReadBytes;
    }

    /**
     * Returns the payload size of the largest single-item write request that
     * fits into the negotiated PDU (read and write windows differ because the
     * write request carries the payload in the outgoing frame).
     */
    public int getMaxWriteBytes() {
        return this.maxWriteBytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized byte[] read(final DaveArea area, final int areaNumber, final int bytes, final int offset) throws IOException {
        requireArea(area);
        requireNonNegative("areaNumber", areaNumber);
        requireNonNegative("offset", offset);
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must not be negative: " + bytes);
        }
        if (bytes > 0 && offset > Integer.MAX_VALUE - bytes) {
            throw new IllegalArgumentException("read range overflows the address space: offset=" + offset + ", bytes=" + bytes);
        }
        final byte[] result = new byte[bytes];
        if (bytes == 0) {
            return result;
        }
        final byte[] chunkBuffer = new byte[this.maxReadBytes];
        int position = 0;
        int currentOffset = offset;
        while (position < bytes) {
            final int chunk = Math.min(bytes - position, this.maxReadBytes);
            final int ret = this.dc.readBytes(area, areaNumber, currentOffset, chunk, chunkBuffer);
            if (ret != Nodave.RESULT_OK) {
                throw readFailure(area, areaNumber, currentOffset, chunk, ret);
            }
            System.arraycopy(chunkBuffer, 0, result, position, chunk);
            position += chunk;
            currentOffset += chunk;
        }
        return result;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void write(final DaveArea area, final int areaNumber, final int offset, final byte[] buffer) throws IOException {
        requireArea(area);
        requireNonNegative("areaNumber", areaNumber);
        requireNonNegative("offset", offset);
        if (buffer == null) {
            throw new IllegalArgumentException("buffer must not be null");
        }
        if (buffer.length > 0 && offset > Integer.MAX_VALUE - buffer.length) {
            throw new IllegalArgumentException("write range overflows the address space: offset=" + offset
                    + ", length=" + buffer.length);
        }
        int position = 0;
        int currentOffset = offset;
        while (position < buffer.length) {
            final int chunk = Math.min(buffer.length - position, this.maxWriteBytes);
            final byte[] chunkBuffer = new byte[chunk];
            System.arraycopy(buffer, position, chunkBuffer, 0, chunk);
            final int ret = this.dc.writeBytes(area, areaNumber, currentOffset, chunk, chunkBuffer);
            if (ret != Nodave.RESULT_OK) {
                throw writeFailure(area, areaNumber, currentOffset, chunk, position, ret);
            }
            position += chunk;
            currentOffset += chunk;
        }
    }

    private static void requireArea(final DaveArea area) {
        if (area == null) {
            throw new IllegalArgumentException("area must not be null");
        }
    }

    private static void requireNonNegative(final String name, final int value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value);
        }
    }

    /**
     * Builds the public failure for a rejected read: it keeps the raw PLC
     * status code and the target coordinates. Reads never produce partial
     * results: a failing chunk fails the whole call.
     */
    private static S7Exception readFailure(
            final DaveArea area,
            final int areaNumber,
            final int offset,
            final int length,
            final int result) {
        return new S7Exception("S7 read failed: status=" + result
                + " (0x" + String.format(Locale.ROOT, "%04X", result & 0xFFFF) + ')'
                + ": " + Nodave.strerror(result)
                + "; area=" + area.name()
                + ", db=" + areaNumber
                + ", offset=" + offset
                + ", length=" + length);
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

}
