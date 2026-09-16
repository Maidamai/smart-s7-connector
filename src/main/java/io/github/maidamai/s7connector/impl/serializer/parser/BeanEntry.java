
package io.github.maidamai.s7connector.impl.serializer.parser;

import io.github.maidamai.s7connector.api.S7Serializable;
import io.github.maidamai.s7connector.api.S7Type;

import java.lang.reflect.Field;

/**
 * A Bean-Entry
 *
 * @author Thomas Rudin
 */
public final class BeanEntry {
    /**
     * The Array size
     */
    public int arraySize;

    /**
     * Offsets and size
     */
    public int byteOffset, bitOffset, size;

    /**
     * The corresponding field
     */
    public Field field;

    /**
     * Array type
     */
    public boolean isArray;

    /**
     * The S7 Type
     */
    public S7Type s7type;

    /**
     * The corresponding serializer
     */
    public S7Serializable serializer;

    /**
     * The Java type
     */
    public Class<?> type;

    /**
     * Computes the byte offset of the array element with the given index.
     *
     * The element index advances the position by the element size: for byte
     * sized types by {@code index * byteSize}, for bit sized types (BOOL) the
     * absolute bit number {@code bitOffset + index * bitSize} is split into
     * whole bytes and the remaining bits, so arrays with more than 8 elements
     * (or a non-zero starting bitOffset) cross byte boundaries correctly.
     *
     * @param index the array element index (&gt;= 0)
     * @return the byte offset of the element relative to the entry start
     */
    public int getElementByteOffset(final int index) {
        final int absoluteBit = this.bitOffset + index * this.s7type.getBitSize();
        return this.byteOffset + absoluteBit / 8 + index * this.s7type.getByteSize();
    }

    /**
     * Computes the bit offset (0-7) of the array element with the given index.
     *
     * @param index the array element index (&gt;= 0)
     * @return the bit offset within the byte returned by {@link #getElementByteOffset(int)}
     */
    public int getElementBitOffset(final int index) {
        return (this.bitOffset + index * this.s7type.getBitSize()) % 8;
    }
}
