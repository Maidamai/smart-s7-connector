
package io.github.maidamai.s7connector.api;

import io.github.maidamai.s7connector.exception.S7Exception;

import java.io.Closeable;
import java.io.IOException;

/**
 * A connection to a Siemens S7 PLC over TCP.
 *
 * <p>See {@code docs/api-contract.md} for the full contract (thread model,
 * error categories, response validation, and the post-failure connection
 * state).</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Implementations serialize every request/response exchange on an internal
 * monitor, so a single connector may safely be shared by multiple threads.
 * Requests are executed one at a time; there is no protocol-level
 * multiplexing.</p>
 *
 * <h2>Resource management</h2>
 *
 * <p>This interface extends {@link Closeable}. A connector holds a TCP
 * connection and worker threads and must be closed when no longer needed;
 * use try-with-resources. Using a connector after {@link #close()} (or after
 * any failure that invalidated it, see below) is not supported.</p>
 *
 * <h2>Error categories</h2>
 *
 * <ul>
 *   <li>{@link IOException} signals a transport failure (connect, timeout,
 *   I/O error, or a response that violates the S7 protocol). After such a
 *   failure the underlying transport is closed and the connector is
 *   permanently unusable; create a new connector.</li>
 *   <li>{@link io.github.maidamai.s7connector.exception.S7Exception} signals
 *   that the PLC rejected the operation or the response was invalid. For a
 *   rejected write, the message carries the fields {@code status},
 *   {@code area}, {@code db}, {@code offset}, {@code length}, and
 *   {@code confirmedWrittenBytes}; already-acknowledged chunks are not rolled
 *   back.</li>
 *   <li>{@link IllegalArgumentException} signals an invalid argument (null
 *   area, negative areaNumber/offset/bytes, null buffer).</li>
 * </ul>
 *
 * @author Thomas Rudin
 */
public interface S7Connector extends Closeable {
    /**
     * Reads a range of bytes from an S7 memory area.
     *
     * <p>Requests larger than the negotiated read window (PDU length minus
     * 18 bytes of protocol overhead) are transparently split into multiple
     * requests and reassembled; the returned array is exactly {@code bytes}
     * long. Reading zero bytes returns an empty array without contacting the
     * PLC.</p>
     *
     * @param area       the memory area to read, e.g. {@link DaveArea#DB},
     *                   {@link DaveArea#INPUTS}, {@link DaveArea#OUTPUTS} or
     *                   {@link DaveArea#FLAGS}; must not be null
     * @param areaNumber the DB number when {@code area} is
     *                   {@link DaveArea#DB}; for non-DB areas use 0 or the
     *                   value your application convention dictates; must not
     *                   be negative
     * @param bytes      number of bytes to read; must not be negative
     * @param offset     start byte offset within the area; must not be
     *                   negative
     * @return the bytes read, in PLC byte order, of length {@code bytes}
     * @throws IOException              on a transport failure; the connection
     *                                  is closed and unusable afterwards
     * @throws S7Exception              if the PLC rejects the read or the
     *                                  response violates the protocol
     * @throws IllegalArgumentException if an argument is invalid
     */
    public byte[] read(DaveArea area, int areaNumber, int bytes, int offset) throws IOException;

    /**
     * Writes a buffer of bytes to an S7 memory area.
     *
     * <p>The write covers exactly {@code buffer.length} bytes starting at
     * {@code offset}: every byte of the buffer is sent, so surrounding PLC
     * memory in the written range is overwritten. There is no rollback: if
     * the PLC rejects a chunk of a multi-chunk write, earlier chunks that the
     * PLC already acknowledged stay written; the thrown
     * {@link S7Exception} message reports {@code confirmedWrittenBytes}.</p>
     *
     * <p>Buffers larger than the negotiated write window (PDU length minus
     * 28 bytes of protocol overhead; smaller than the read window because the
     * write request carries the payload) are split into multiple requests.</p>
     *
     * @param area       the memory area to write, e.g. {@link DaveArea#DB};
     *                   must not be null
     * @param areaNumber the DB number when {@code area} is
     *                   {@link DaveArea#DB}; for non-DB areas use 0 or your
     *                   application convention; must not be negative
     * @param offset     start byte offset within the area; must not be
     *                   negative
     * @param buffer     the bytes to write; must not be null; an empty buffer
     *                   contacts no PLC
     * @throws IOException              on a transport failure; the connection
     *                                  is closed and unusable afterwards
     * @throws S7Exception              if the PLC rejects the write; the
     *                                  message carries {@code status},
     *                                  {@code area}, {@code db},
     *                                  {@code offset}, {@code length} and
     *                                  {@code confirmedWrittenBytes}
     * @throws IllegalArgumentException if an argument is invalid
     */
    public void write(DaveArea area, int areaNumber, int offset, byte[] buffer) throws IOException;

}
