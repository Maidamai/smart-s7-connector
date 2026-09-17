
package io.github.maidamai.s7connector.impl.serializer.converter;

import io.github.maidamai.s7connector.api.S7Serializable;
import io.github.maidamai.s7connector.api.S7Type;

import java.nio.charset.StandardCharsets;

/**
 * Converts S7 STRING values.
 *
 * <p>An S7 STRING is a single-byte character type using the device charset
 * (ASCII by convention): byte 0 holds the maximum length, byte 1 the current
 * length and the payload starts at byte 2. This converter therefore handles
 * ASCII content only; inserting a string with any character above 0x7F is
 * rejected instead of being silently truncated or mis-lengthed by a
 * multi-byte platform charset.</p>
 */
public final class StringConverter implements S7Serializable {

    /**
     * Maximum encodable S7 STRING capacity: the max-length header byte is
     * unsigned (so 255 would fit numerically), but the S7 format reserves
     * 254 as the largest payload capacity.
     */
    public static final int MAX_CAPACITY = 254;

    private static final int OFFSET_CURRENT_LENGTH = 1;
    private static final int OFFSET_OVERALL_LENGTH = 0;
    private static final int OFFSET_START = 2;

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException when the header is invalid, i.e. the
     *                                  current length exceeds the maximum length or the remaining buffer
     */
    @Override
    public <T> T extract(final Class<T> targetClass, final byte[] buffer, final int byteOffset, final int bitOffset) {
        final int maxLength = buffer[byteOffset + OFFSET_OVERALL_LENGTH] & 0xFF;
        final int currentLength = buffer[byteOffset + OFFSET_CURRENT_LENGTH] & 0xFF;

        if (currentLength > maxLength) {
            throw new IllegalArgumentException("Invalid S7 STRING header at byteOffset " + byteOffset
                    + ": currentLength=" + currentLength + " exceeds maxLength=" + maxLength);
        }
        final int available = buffer.length - byteOffset - OFFSET_START;
        if (currentLength > available) {
            throw new IllegalArgumentException("Invalid S7 STRING header at byteOffset " + byteOffset
                    + ": currentLength=" + currentLength + " exceeds remaining buffer bytes=" + available);
        }

        return targetClass.cast(new String(buffer, byteOffset + OFFSET_START, currentLength,
                StandardCharsets.US_ASCII));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public S7Type getS7Type() {
        return S7Type.STRING;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getSizeInBits() {
        // Not static
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getSizeInBytes() {
        // Not static
        return 2; // 2 bytes overhead
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException when the value contains non-ASCII
     *                                  characters or does not fit the given size
     */
    @Override
    public void insert(final Object javaType, final byte[] buffer, final int byteOffset, final int bitOffset,
                       final int size) {
        final String value = (String) javaType;

        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) {
                throw new IllegalArgumentException("S7 STRING supports ASCII characters only: character '"
                        + value.charAt(i) + "' (0x" + Integer.toHexString(value.charAt(i))
                        + ") at index " + i + " is non-ASCII");
            }
        }

        // ASCII characters encode to exactly one byte each, so the encoded
        // byte count equals the character count.
        final int len = value.length();

        if (len > size) {
            throw new IllegalArgumentException("String to big: " + len + " > size " + size);
        }
        if (size > MAX_CAPACITY) {
            throw new IllegalArgumentException("S7 STRING capacity " + size + " exceeds the encodable maximum "
                    + MAX_CAPACITY + " (unsigned max-length header byte)");
        }

        buffer[byteOffset + OFFSET_OVERALL_LENGTH] = (byte) size;
        buffer[byteOffset + OFFSET_CURRENT_LENGTH] = (byte) len;

        final byte[] strBytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(strBytes, 0, buffer, byteOffset + OFFSET_START, len);
    }

}
