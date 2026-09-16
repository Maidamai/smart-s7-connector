
package io.github.maidamai.s7connector.impl.serializer.parser;

import io.github.maidamai.s7connector.bean.PlcS7PointVariable;
import io.github.maidamai.s7connector.api.S7Serializable;
import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.api.annotation.S7Variable;
import io.github.maidamai.s7connector.exception.S7Exception;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

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

        return entry;
    }

    /**
     * Parses a Class
     *
     * @param jclass
     * @return the parse result with a blockSize that covers every entry
     * @throws ReflectiveOperationException when serializer construction fails
     * @throws S7Exception                 when the class has no public field annotated with {@link S7Variable}
     */
    public static BeanParseResult parse(final Class<?> jclass) throws ReflectiveOperationException {
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

                // Create new serializer
                final S7Serializable s = entry.s7type.getSerializer().newInstance();
                entry.serializer = s;

                // The block size must cover every entry independently of the
                // field declaration order: blockSize = max(byteOffset + coverage)
                final int endOffset = entry.byteOffset + entryCoverageInBytes(entry);
                if (endOffset > res.blockSize) {
                    res.blockSize = endOffset;
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
     * Computes how many bytes an entry covers starting at its byteOffset.
     *
     * @param entry the parsed entry
     * @return the number of covered bytes (at least 1)
     */
    private static int entryCoverageInBytes(final BeanEntry entry) throws ReflectiveOperationException {
        if (entry.s7type == S7Type.STRUCT) {
            // recurse: a struct covers its own parsed block size
            final BeanParseResult subResult = parse(entry.field.getType());
            return Math.max(1, subResult.blockSize);
        } else if (entry.s7type == S7Type.STRING) {
            // size = string capacity, plus the 2 header bytes (max/current length)
            return entry.arraySize * (entry.size + entry.s7type.getByteSize());
        } else if (entry.s7type.getBitSize() > 0) {
            // bit types (BOOL): round up to the byte boundary from the bit offset
            return (entry.bitOffset + entry.s7type.getBitSize() * entry.arraySize + 7) / 8;
        } else {
            // fixed size types; the annotation size only matters when it exceeds the type size
            return entry.arraySize * Math.max(entry.s7type.getByteSize(), entry.size);
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
