
package io.github.maidamai.s7connector.impl.serializer.parser;

import io.github.maidamai.s7connector.bean.PlcS7PointVariable;
import io.github.maidamai.s7connector.api.S7Serializable;
import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.api.annotation.S7Variable;
import io.github.maidamai.s7connector.exception.S7Exception;
import io.github.maidamai.s7connector.impl.serializer.converter.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Parses bean classes into {@link BeanParseResult}s.
 *
 * Mapping contract: only <b>public</b> fields annotated with {@link S7Variable}
 * participate in the mapping. Private fields are ignored entirely (no
 * setAccessible fallback), so beans with no public mapped field are rejected
 * instead of silently producing an empty mapping (which would lead to
 * zero-length requests).
 */
public final class BeanParser {
    private static final Logger log = LoggerFactory.getLogger(BeanParser.class);
    /**
     * Returns the wrapper for the primitive type
     *
     * @param primitiveType
     * @return the wrapper class, or the input class if it is no known primitive
     */
    private static Class<?> getWrapperForPrimitiveType(final Class<?> primitiveType) {
        if (primitiveType == boolean.class) {
            return Boolean.class;
        } else if (primitiveType == byte.class) {
            return Byte.class;
        } else if (primitiveType == char.class) {
            return Character.class;
        } else if (primitiveType == short.class) {
            return Short.class;
        } else if (primitiveType == int.class) {
            return Integer.class;
        } else if (primitiveType == float.class) {
            return Float.class;
        } else if (primitiveType == double.class) {
            return Double.class;
        } else if (primitiveType == long.class) {
            return Long.class;
        } else {
            // Fallback
            return primitiveType;
        }
    }


    public static BeanEntry parse(PlcS7PointVariable plcs7PointVariable) throws ReflectiveOperationException {
        final BeanEntry entry = new BeanEntry();
        entry.byteOffset = plcs7PointVariable.getByteOffset();
        entry.bitOffset = plcs7PointVariable.getBitOffset();
        entry.size = plcs7PointVariable.getSize();
        entry.s7type = plcs7PointVariable.getType();
        entry.type = getWrapperForPrimitiveType(plcs7PointVariable.getFieldType());
        entry.serializer = entry.s7type.getSerializer().newInstance();
        applyElementLayout(entry);

        return entry;
    }

    /**
     * Parses a Class
     *
     * @param jclass
     * @return the parse result with a blockSize that covers every entry
     * @throws ReflectiveOperationException when serializer construction fails
     * @throws S7Exception                 when the class has no public field annotated with {@link S7Variable},
     *                                     an annotation value is not layable out, or the STRUCT nesting is recursive
     */
    public static BeanParseResult parse(final Class<?> jclass) throws ReflectiveOperationException {
        return parse(jclass, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static BeanParseResult parse(final Class<?> jclass, final Set<Class<?>> visiting)
            throws ReflectiveOperationException {
        if (!visiting.add(jclass)) {
            throw new S7Exception("Recursive STRUCT mapping rejected: " + jclass.getName()
                    + " participates in a nesting cycle; mapped beans must form a tree");
        }
        try {
            return parseClass(jclass, visiting);
        } finally {
            visiting.remove(jclass);
        }
    }

    private static BeanParseResult parseClass(final Class<?> jclass, final Set<Class<?>> visiting)
            throws ReflectiveOperationException {
        final BeanParseResult res = new BeanParseResult();
        log.trace("Parsing: " + jclass.getName());

        for (final Field field : jclass.getFields()) {
            final S7Variable dataAnnotation = field.getAnnotation(S7Variable.class);

            if (dataAnnotation != null) {
                log.trace("Parsing field: " + field.getName());
                log.trace("		type: " + dataAnnotation.type());
                log.trace("		byteOffset: " + dataAnnotation.byteOffset());
                log.trace("		bitOffset: " + dataAnnotation.bitOffset());
                log.trace("		size: " + dataAnnotation.size());
                log.trace("		arraySize: " + dataAnnotation.arraySize());

                // Plain element
                final BeanEntry entry = new BeanEntry();
                entry.byteOffset = dataAnnotation.byteOffset();
                entry.bitOffset = dataAnnotation.bitOffset();
                entry.field = field;
                entry.type = getWrapperForPrimitiveType(field.getType());
                entry.size = dataAnnotation.size();
                entry.s7type = dataAnnotation.type();
                entry.isArray = field.getType().isArray();
                entry.arraySize = dataAnnotation.arraySize();

                if (entry.isArray) {
                    entry.type = getWrapperForPrimitiveType(entry.type.getComponentType());
                }

                // Reject annotations that cannot be laid out before any size
                // math runs: negative values would silently produce
                // nonsensical strides and coverage.
                requireNonNegative("byteOffset", entry.byteOffset, field);
                requireNonNegative("bitOffset", entry.bitOffset, field);
                requireNonNegative("arraySize", entry.arraySize, field);
                requireNonNegative("size", entry.size, field);

                // Create new serializer
                final S7Serializable s = entry.s7type.getSerializer().newInstance();
                entry.serializer = s;

                // The block size must cover every entry independently of the
                // field declaration order: blockSize = max(byteOffset + coverage)
                final int coverage = entryCoverageInBytes(entry, visiting);
                final long endOffset = (long) entry.byteOffset + coverage;
                if (endOffset > res.blockSize) {
                    res.blockSize = (int) endOffset;
                }

                res.entries.add(entry);
            }
        }

        if (res.entries.isEmpty()) {
            throw new S7Exception("No public field with @S7Variable annotation found in class "
                    + jclass.getName() + "; mapped fields must be public, private fields are not mapped");
        }

        log.trace("Parsing done, overall size: " + res.blockSize);

        return res;
    }

    /**
     * Computes how many bytes an entry covers starting at its byteOffset and
     * stores the element stride on the entry, so that the covered range and
     * the per-element read/write offsets (see
     * {@link BeanEntry#getElementByteOffset(int)}) are derived from one
     * layout rule.
     *
     * @param entry the parsed entry
     * @return the number of covered bytes (at least 1)
     */
    private static int entryCoverageInBytes(final BeanEntry entry, final Set<Class<?>> visiting)
            throws ReflectiveOperationException {
        if (entry.s7type == S7Type.STRUCT) {
            // recurse: a struct element covers its own parsed block size. An
            // array field carries the bean class as component type.
            final Class<?> elementClass = entry.isArray
                    ? entry.field.getType().getComponentType()
                    : entry.field.getType();
            final BeanParseResult subResult = parse(elementClass, visiting);
            final int stride = Math.max(1, subResult.blockSize);
            entry.elementStride = stride;
            return checkedCoverage(entry, stride);
        } else if (entry.s7type == S7Type.STRING) {
            if (entry.size > StringConverter.MAX_CAPACITY) {
                throw new S7Exception("STRING size " + entry.size + " of field " + entry.field.getName()
                        + " exceeds the encodable capacity " + StringConverter.MAX_CAPACITY
                        + " (the max-length header byte is unsigned and reserves 254 for the payload)");
            }
            // element = capacity + the 2 header bytes (max/current length)
            final int stride = entry.size + entry.s7type.getByteSize();
            entry.elementStride = stride;
            return checkedCoverage(entry, stride);
        } else if (entry.s7type.getBitSize() > 0) {
            // bit types (BOOL): round up to the byte boundary from the bit offset
            final long coveredBits = (long) entry.bitOffset + (long) entry.s7type.getBitSize() * entry.arraySize;
            return (int) requireCoverable(entry, (coveredBits + 7) / 8);
        } else {
            // fixed size types; the annotation size only matters when it exceeds the type size
            final int stride = Math.max(entry.s7type.getByteSize(), entry.size);
            entry.elementStride = stride;
            return checkedCoverage(entry, stride);
        }
    }

    private static int checkedCoverage(final BeanEntry entry, final int stride) {
        return (int) requireCoverable(entry, (long) entry.arraySize * stride);
    }

    /**
     * Rejects layouts whose covered byte count cannot be represented: an
     * int overflow would otherwise silently shrink the block size and
     * misplace every read/write that follows.
     */
    private static long requireCoverable(final BeanEntry entry, final long coveredBytes) {
        if (coveredBytes > Integer.MAX_VALUE) {
            throw new S7Exception("@" + S7Variable.class.getSimpleName() + " layout of field "
                    + entry.field.getName() + " covers " + coveredBytes
                    + " bytes, which exceeds the representable block size of " + Integer.MAX_VALUE);
        }
        return coveredBytes;
    }

    /**
     * Computes the element stride for single-point entries (no array, no
     * field context): STRING uses capacity + header, everything else the
     * fixed width.
     */
    private static void applyElementLayout(final BeanEntry entry) {
        if (entry.s7type == S7Type.STRING) {
            if (entry.size > StringConverter.MAX_CAPACITY) {
                throw new S7Exception("STRING size " + entry.size
                        + " exceeds the encodable capacity " + StringConverter.MAX_CAPACITY);
            }
            entry.elementStride = entry.size + entry.s7type.getByteSize();
        } else {
            entry.elementStride = Math.max(entry.s7type.getByteSize(), entry.size);
        }
    }

    private static void requireNonNegative(final String name, final int value, final Field field) {
        if (value < 0) {
            throw new S7Exception("@" + S7Variable.class.getSimpleName() + " " + name + " must not be negative: "
                    + value + " (field " + field.getName() + " of " + field.getDeclaringClass().getName() + ")");
        }
    }

    /**
     * Parses an Object
     *
     * @param obj
     * @return
     * @throws ReflectiveOperationException when serializer construction fails
     */
    public static BeanParseResult parse(final Object obj) throws ReflectiveOperationException {
        return parse(obj.getClass());
    }

}
