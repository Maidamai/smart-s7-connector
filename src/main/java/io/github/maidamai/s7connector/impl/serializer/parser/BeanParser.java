
package io.github.maidamai.s7connector.impl.serializer.parser;

import io.github.maidamai.s7connector.bean.PlcS7PointVariable;
import io.github.maidamai.s7connector.api.S7Serializable;
import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.api.annotation.S7Variable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

public final class BeanParser {
    private static final Logger log = LoggerFactory.getLogger(BeanParser.class);
    /**
     * Returns the wrapper for the primitive type
     *
     * @param primitiveType
     * @return
     */
    private static Class<?> getWrapperForPrimitiveType(final Class<?> primitiveType) {
        if (primitiveType == boolean.class) {
            return Boolean.class;
        } else if (primitiveType == byte.class) {
            return Byte.class;
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
     * @return
     * @throws ReflectiveOperationException when serializer construction fails
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

                final int offset = dataAnnotation.byteOffset();

                // update max offset
                if (offset > res.blockSize) {
                    res.blockSize = offset;
                }

                if (dataAnnotation.type() == S7Type.STRUCT) {
                    // recurse
                    log.trace("Recursing...");
                    final BeanParseResult subResult = parse(field.getType());
                    res.blockSize += subResult.blockSize;
                    log.trace("	New blocksize: " + res.blockSize);
                }

                log.trace("	New blocksize (+offset): " + res.blockSize);

                // Add dynamic size
                res.blockSize += dataAnnotation.size();

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

                res.blockSize += (s.getSizeInBytes() * dataAnnotation.arraySize());
                log.trace("	New blocksize (+array): " + res.blockSize);

                if (s.getSizeInBits() > 0) {
                    boolean offsetOfBitAlreadyKnown = false;
                    for (final BeanEntry parsedEntry : res.entries) {
                        if (parsedEntry.byteOffset == entry.byteOffset) {
                            offsetOfBitAlreadyKnown = true;
                        }
                    }
                    if (!offsetOfBitAlreadyKnown) {
                        res.blockSize++;
                    }
                }

                res.entries.add(entry);
            }
        }

        log.trace("Parsing done, overall size: " + res.blockSize);

        return res;
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
