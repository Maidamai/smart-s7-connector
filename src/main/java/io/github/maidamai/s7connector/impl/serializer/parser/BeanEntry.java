
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
     * The corresponding Java type
     */
    public Class<?> type;

    /**
     * Distance in bytes between consecutive array elements, computed once at
     * parse time so that the covered block size and the element read/write
     * offsets share one layout rule. For STRING it is {@code size + 2}
     * (capacity plus the max/current length header), for STRUCT the nested
     * block size, for fixed width types {@code max(byteSize, size)}.
     * Bit-strided types (BOOL) do not use it and keep 0.
     */
    public int elementStride;

    /**
     * Computes the byte offset of the array element with the given index.
     *
     * The element index advances the position by the element stride:
     * {@link #elementStride} for byte sized types, and — for bit sized types
     * (BOOL) — the absolute bit number {@code bitOffset + index * bitSize}
     * split into whole bytes and the remaining bits, so arrays with more
     * than 8 elements (or a non-zero starting bitOffset) cross byte
     * boundaries correctly.
     *
     * @param index the array element index (&gt;= 0)
     * @return the byte offset of the element relative to the entry start
     */
    public int getElementByteOffset(final int index) {
        if (this.s7type.getBitSize() > 0) {
            // long math: an extreme bitOffset + index must not wrap int into
            // a negative buffer index (parse-time bounds keep the result <=
            // Integer.MAX_VALUE once computed in long)
            final long absoluteBit = (long) this.bitOffset + (long) index * this.s7type.getBitSize();
            return this.byteOffset + (int) (absoluteBit / 8);
        }
        return this.byteOffset + this.bitOffset / 8 + index * this.elementStride;
    }

    /**
     * Computes the bit offset (0-7) of the array element with the given index.
     *
     * @param index the array element index (&gt;= 0)
     * @return the bit offset within the byte returned by {@link #getElementByteOffset(int)}
     */
    public int getElementBitOffset(final int index) {
        final long absoluteBit = (long) this.bitOffset + (long) index * this.s7type.getBitSize();
        return (int) (absoluteBit % 8);
    }
}
