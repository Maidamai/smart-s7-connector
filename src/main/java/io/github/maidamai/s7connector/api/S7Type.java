
package io.github.maidamai.s7connector.api;


import io.github.maidamai.s7connector.impl.serializer.converter.*;

/**
 * Type of the Address
 *
 * @author Thomas Rudin Libnodave: http://libnodave.sourceforge.net/
 */
public enum S7Type {
    /**
     * Boolean type
     */
    BOOL(BitConverter.class, 0, 1),

    /**
     * Byte type
     */
    BYTE(ByteConverter.class, 1, 0),

    /**
     * A INT-type
     */
    INT(ShortConverter.class, 2, 0),

    /**
     * A DINT-type (same as DWORD-type)
     *
     * <p>Current behavior contract: the 32 raw bits are interpreted through
     * {@link io.github.maidamai.s7connector.impl.serializer.converter.LongConverter}
     * as a <b>signed</b> value that is sign-extended to a Java long. For
     * example 0xFFFFFFFF reads as {@code -1L} and 0x80000000 reads as
     * {@code -2147483648L}. This mirrors the historical behavior and is kept
     * for compatibility.</p>
     */
    DINT(LongConverter.class, 4, 0),

    /**
     * A Word-type (same as int-type)
     */
    WORD(IntegerConverter.class, 2, 0),
    /**
     * Double word
     *
     * <p>Current behavior contract: like {@link #DINT}, the 32 raw bits are
     * interpreted through {@link io.github.maidamai.s7connector.impl.serializer.converter.LongConverter}
     * as a <b>signed</b> value that is sign-extended to a Java long, even for
     * DWORD where an unsigned interpretation might be expected: 0xFFFFFFFF
     * reads as {@code -1L}. This mirrors the historical behavior and is kept
     * for compatibility.</p>
     */
    DWORD(LongConverter.class, 4, 0),

    /**
     * Real-type, corresponds to float or double
     */
    REAL(RealConverter.class, 4, 0),

    /**
     * String type, size must be specified manually
     */
    STRING(StringConverter.class, 2, 0),

    /**
     * Simple Date with 2 bytes in length
     */
    DATE(DateConverter.class, 2, 0),

    /**
     * Time-type, 4 bytes in length, number of millis
     */
    TIME(TimeConverter.class, 4, 0),

    /**
     * Full Date and time format with precision in milliseconds
     */
    DATE_AND_TIME(DateAndTimeConverter.class, 8, 0),

    /**
     * Structure type
     */
    STRUCT(StructConverter.class, 0, 0);

    private int byteSize, bitSize;

    private Class<? extends S7Serializable> serializer;

    /**
     * Enum Constructor
     *
     * @param serializer
     * @param byteSize
     * @param bitSize
     */
    S7Type(final Class<? extends S7Serializable> serializer, final int byteSize, final int bitSize) {
        this.serializer = serializer;
        this.bitSize = bitSize;
        this.byteSize = byteSize;
    }

    public int getBitSize() {
        return this.bitSize;
    }

    public int getByteSize() {
        return this.byteSize;
    }

    public Class<? extends S7Serializable> getSerializer() {
        return this.serializer;
    }
}
