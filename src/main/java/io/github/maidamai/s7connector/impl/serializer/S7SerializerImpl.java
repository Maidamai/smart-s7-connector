
package io.github.maidamai.s7connector.impl.serializer;

import io.github.maidamai.s7connector.bean.PlcS7PointVariable;
import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Connector;
import io.github.maidamai.s7connector.api.S7Serializer;
import io.github.maidamai.s7connector.exception.S7Exception;
import io.github.maidamai.s7connector.impl.S7BaseConnection;
import io.github.maidamai.s7connector.impl.S7ReadWindowProvider;
import io.github.maidamai.s7connector.impl.serializer.batch.BatchReadRequest;
import io.github.maidamai.s7connector.impl.serializer.batch.PlannedPointRead;
import io.github.maidamai.s7connector.impl.serializer.batch.PointReadPlanner;
import io.github.maidamai.s7connector.impl.serializer.parser.BeanEntry;
import io.github.maidamai.s7connector.impl.serializer.parser.BeanParseResult;
import io.github.maidamai.s7connector.impl.serializer.parser.BeanParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Class S7Serializer is responsible for serializing S7 TCP Connection
 */
public final class S7SerializerImpl implements S7Serializer {
    private static final Logger log = LoggerFactory.getLogger(S7SerializerImpl.class);
    private static final PointReadPlanner POINT_READ_PLANNER = new PointReadPlanner();

    public static Object extractBytes(PlcS7PointVariable plcs7PointVariable, final byte[] buffer, final int byteOffset) {
        try {
            final BeanEntry entry = BeanParser.parse(plcs7PointVariable);
            return entry.serializer.extract(entry.type, buffer, byteOffset, entry.bitOffset);
        } catch (final ReflectiveOperationException e) {
            throw new S7Exception("extractBytes point dbnum(" + plcs7PointVariable.getDbNum()
                    + ") registerType(" + plcs7PointVariable.getRegisterType()
                    + ") byteoffset(" + plcs7PointVariable.getByteOffset()
                    + ") bitoffset(" + plcs7PointVariable.getBitOffset() + ")", e);
        } catch (final IllegalArgumentException | ClassCastException | IndexOutOfBoundsException e) {
            throw new S7Exception("extractBytes point dbnum(" + plcs7PointVariable.getDbNum()
                    + ") registerType(" + plcs7PointVariable.getRegisterType()
                    + ") byteoffset(" + plcs7PointVariable.getByteOffset()
                    + ") bitoffset(" + plcs7PointVariable.getBitOffset()
                    + ") bufferLength(" + buffer.length + ") relativeByteOffset(" + byteOffset + ")", e);
        }
    }

    public static Object extractBytes(List<PlcS7PointVariable> plcs7PointVariableList, final byte[] buffer, final int byteOffset) {
        try {
            final BeanEntry entry = BeanParser.parse(plcs7PointVariableList.get(0));
            List<String> results = new ArrayList<>();
            plcs7PointVariableList.forEach(plcS7PointVariable -> {
                results.add(String.valueOf(entry.serializer.extract(entry.type, buffer, byteOffset, plcS7PointVariable.getBitOffset())));
            });
            return results;
        } catch (final ReflectiveOperationException e) {
            throw new S7Exception("extractBytes pointList firstDbnum(" + plcs7PointVariableList.get(0).getDbNum()
                    + ") firstRegisterType(" + plcs7PointVariableList.get(0).getRegisterType()
                    + ") pointCount(" + plcs7PointVariableList.size() + ")", e);
        } catch (final IllegalArgumentException | ClassCastException | IndexOutOfBoundsException e) {
            throw new S7Exception("extractBytes pointList pointCount(" + plcs7PointVariableList.size()
                    + ") bufferLength(" + buffer.length + ") byteOffset(" + byteOffset + ")", e);
        }
    }

    /**
     * Extracts bytes from a buffer.
     *
     * @param <T>        the generic type
     * @param beanClass  the bean class
     * @param buffer     the buffer
     * @param byteOffset the byte offset
     * @return the t
     */
    public static <T> T extractBytes(final Class<T> beanClass, final byte[] buffer, final int byteOffset) {
        log.trace("Extracting type {} from buffer with size: {} at offset {}", beanClass.getName(), buffer.length, byteOffset);

        try {
            final T obj = beanClass.newInstance();
            final BeanParseResult result = BeanParser.parse(beanClass);
            for (final BeanEntry entry : result.entries) {
                Object value = null;
                if (entry.isArray) {
                    value = Array.newInstance(entry.type, entry.arraySize);
                    for (int i = 0; i < entry.arraySize; i++) {
                        // Element position = entry start + element size * index;
                        // BOOL elements advance bit by bit and cross byte
                        // boundaries, see BeanEntry#getElementByteOffset.
                        final Object component = entry.serializer.extract(entry.type, buffer,
                                entry.getElementByteOffset(i) + byteOffset,
                                entry.getElementBitOffset(i));
                        Array.set(value, i, component);
                    }
                } else {
                    value = entry.serializer.extract(entry.type, buffer, entry.byteOffset + byteOffset, entry.bitOffset);
                }

                if (entry.field.getType() == byte[].class) {
                    //Special case issue #45
                    Byte[] oldValue = (Byte[]) value;

                    value = new byte[oldValue.length];

                    for (int i = 0; i < oldValue.length; i++) {
                        ((byte[]) value)[i] = oldValue[i];
                    }
                }

                entry.field.set(obj, value);
            }

            return obj;
        } catch (final ReflectiveOperationException e) {
            throw new S7Exception("extractBytes beanClass(" + beanClass.getName()
                    + ") byteoffset(" + byteOffset + ")", e);
        } catch (final IllegalArgumentException | ClassCastException | IndexOutOfBoundsException e) {
            throw new S7Exception("extractBytes beanClass(" + beanClass.getName()
                    + ") byteoffset(" + byteOffset + ") bufferLength(" + buffer.length + ")", e);
        }
    }

    /**
     * Inserts the bytes to the buffer.
     *
     * @param bean       the bean
     * @param buffer     the buffer
     * @param byteOffset the byte offset
     */
    public static void insertBytes(final Object bean, final byte[] buffer, final int byteOffset) {
        log.trace("Inerting buffer with size: {} at offset {} into bean: {}", buffer.length, byteOffset, bean);

        try {
            final BeanParseResult result = BeanParser.parse(bean);

            for (final BeanEntry entry : result.entries) {
                final Object fieldValue = entry.field.get(bean);

                if (fieldValue != null) {
                    if (entry.isArray) {
                        for (int i = 0; i < entry.arraySize; i++) {
                            final Object arrayItem = Array.get(fieldValue, i);

                            if (arrayItem != null) {
                                // Element position = entry start + element size * index;
                                // BOOL elements advance bit by bit and cross byte
                                // boundaries, see BeanEntry#getElementByteOffset.
                                entry.serializer.insert(arrayItem, buffer,
                                        entry.getElementByteOffset(i) + byteOffset,
                                        entry.getElementBitOffset(i), entry.size);
                            }
                        }
                    } else {
                        entry.serializer.insert(fieldValue, buffer, entry.byteOffset + byteOffset, entry.bitOffset,
                                entry.size);
                    }
                }
            }
        } catch (final ReflectiveOperationException e) {
            throw new S7Exception("insertBytes beanClass(" + bean.getClass().getName()
                    + ") byteoffset(" + byteOffset + ")", e);
        } catch (final IllegalArgumentException | ClassCastException | IndexOutOfBoundsException e) {
            throw new S7Exception("insertBytes beanClass(" + bean.getClass().getName()
                    + ") byteoffset(" + byteOffset + ") bufferLength(" + buffer.length + ")", e);
        }
    }

    /**
     * The Connector.
     */
    private final S7Connector connector;

    /**
     * Instantiates a new s7 serializer.
     *
     * @param connector the connector
     */
    public S7SerializerImpl(final S7Connector connector) {
        this.connector = connector;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized <T> T dispense(final Class<T> beanClass, final int dbNum, final int byteOffset) throws S7Exception {
        try {
            final BeanParseResult result = BeanParser.parse(beanClass);
            final byte[] buffer = this.connector.read(DaveArea.DB, dbNum, result.blockSize, byteOffset);
            return extractBytes(beanClass, buffer, 0);
        } catch (final IOException e) {
            throw new S7Exception("dispense dbnum(" + dbNum + ") byteoffset(" + byteOffset
                    + ") beanClass(" + beanClass.getName() + ")", e);
        } catch (final ReflectiveOperationException e) {
            throw new S7Exception("dispense beanClass(" + beanClass.getName() + ")", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized <T> T dispense(final Class<T> beanClass, final int dbNum, final int byteOffset, final int blockSize) throws S7Exception {
        try {
            final byte[] buffer = this.connector.read(DaveArea.DB, dbNum, blockSize, byteOffset);
            return extractBytes(beanClass, buffer, 0);
        } catch (final IOException e) {
            throw new S7Exception("dispense dbnum(" + dbNum + ") byteoffset(" + byteOffset + ") blocksize(" + blockSize + ")", e);
        } catch (final IllegalArgumentException | ClassCastException | IndexOutOfBoundsException e) {
            throw new S7Exception("dispense dbnum(" + dbNum + ") byteoffset(" + byteOffset + ") blocksize(" + blockSize + ")", e);
        }
    }

    /**
     * add by pnoker
     */
    @Override
    public Object dispense(PlcS7PointVariable plcs7PointVariable) throws S7Exception {
        try {
            final byte[] buffer = this.connector.read(plcs7PointVariable.getRegisterType(), plcs7PointVariable.getDbNum(), plcs7PointVariable.getSize(), plcs7PointVariable.getByteOffset());
            return extractBytes(plcs7PointVariable, buffer, 0);
        } catch (final IOException e) {
            throw new S7Exception("dispense dbnum(" + plcs7PointVariable.getDbNum() + ") registerType(" + plcs7PointVariable.getRegisterType() + ") byteoffset(" + plcs7PointVariable.getByteOffset() + ") blocksize(" + plcs7PointVariable.getSize() + ")", e);
        } catch (final IllegalArgumentException | ClassCastException | IndexOutOfBoundsException e) {
            throw new S7Exception("dispense dbnum(" + plcs7PointVariable.getDbNum() + ") registerType(" + plcs7PointVariable.getRegisterType() + ") byteoffset(" + plcs7PointVariable.getByteOffset() + ") blocksize(" + plcs7PointVariable.getSize() + ")", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void store(final Object bean, final int dbNum, final int byteOffset) {
        try {
            final BeanParseResult result = BeanParser.parse(bean);

            final byte[] buffer = new byte[result.blockSize];
            log.trace("store-buffer-size: " + buffer.length);

            insertBytes(bean, buffer, 0);

            this.connector.write(DaveArea.DB, dbNum, byteOffset, buffer);
        } catch (final IOException e) {
            throw new S7Exception("store dbnum(" + dbNum + ") byteoffset(" + byteOffset
                    + ") beanClass(" + bean.getClass().getName() + ")", e);
        } catch (final ReflectiveOperationException e) {
            throw new S7Exception("store beanClass(" + bean.getClass().getName() + ")", e);
        }
    }

    /**
     * {@inheritDoc}
     * add by caijiangtao
     */
    @Override
    public synchronized <T> T dispense(final Class<T> beanClass, DaveArea daveArea, final int dbNum, final int byteOffset) throws S7Exception {
        try {
            final BeanParseResult result = BeanParser.parse(beanClass);
            final byte[] buffer = this.connector.read(daveArea, dbNum, result.blockSize, byteOffset);
            return extractBytes(beanClass, buffer, 0);
        } catch (final IOException e) {
            throw new S7Exception("dispense area(" + daveArea + ") dbnum(" + dbNum
                    + ") byteoffset(" + byteOffset + ") beanClass(" + beanClass.getName() + ")", e);
        } catch (final ReflectiveOperationException e) {
            throw new S7Exception("dispense beanClass(" + beanClass.getName() + ")", e);
        }
    }

    /**
     * {@inheritDoc}
     * add by caijiangtao
     */
    @Override
    public synchronized <T> T dispense(final Class<T> beanClass, DaveArea daveArea, final int dbNum, final int byteOffset, final int blockSize) throws S7Exception {
        try {
            final byte[] buffer = this.connector.read(daveArea, dbNum, blockSize, byteOffset);
            return extractBytes(beanClass, buffer, 0);
        } catch (final IOException e) {
            throw new S7Exception("dispense area(" + daveArea + ") dbnum(" + dbNum
                    + ") byteoffset(" + byteOffset + ") blocksize(" + blockSize + ")", e);
        } catch (final IllegalArgumentException | ClassCastException | IndexOutOfBoundsException e) {
            throw new S7Exception("dispense dbnum(" + dbNum + ") byteoffset(" + byteOffset + ") blocksize(" + blockSize + ")", e);
        }
    }


    /**
     * add by caijiangtao
     */
    @Override
    public Object dispense(List<PlcS7PointVariable> plcs7PointVariableList) throws S7Exception {
        if (plcs7PointVariableList == null) {
            throw new S7Exception("dispense point list must not be null");
        }
        if (plcs7PointVariableList.isEmpty()) {
            return new ArrayList<Object>();
        }
        final List<BatchReadRequest> batchReadRequests = POINT_READ_PLANNER.plan(plcs7PointVariableList, this.resolveMaxReadBytes());
        final List<Object> results = new ArrayList<>(Collections.nCopies(plcs7PointVariableList.size(), null));
        for (final BatchReadRequest batchReadRequest : batchReadRequests) {
            try {
                final byte[] buffer = this.connector.read(
                        batchReadRequest.getArea(),
                        batchReadRequest.getDbNum(),
                        batchReadRequest.getLength(),
                        batchReadRequest.getStartOffset());
                for (final PlannedPointRead plannedPointRead : batchReadRequest.getPlannedPointReads()) {
                    results.set(plannedPointRead.getOriginalIndex(), extractBytes(
                            plannedPointRead.getPoint(),
                            buffer,
                            plannedPointRead.getRelativeByteOffset()));
                }
            } catch (final IOException e) {
                throw new S7Exception("dispense batch area(" + batchReadRequest.getArea()
                        + ") dbnum(" + batchReadRequest.getDbNum()
                        + ") byteoffset(" + batchReadRequest.getStartOffset()
                        + ") blocksize(" + batchReadRequest.getLength() + ")", e);
            }
        }
        return results;
    }

    private int resolveMaxReadBytes() {
        if (this.connector instanceof S7ReadWindowProvider) {
            return ((S7ReadWindowProvider) this.connector).getMaxReadBytes();
        }
        return S7BaseConnection.DEFAULT_MAX_READ_BYTES;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void store(final Object bean, DaveArea daveArea, final int dbNum, final int byteOffset) {
        try {
            final BeanParseResult result = BeanParser.parse(bean);

            final byte[] buffer = new byte[result.blockSize];
            log.trace("store-buffer-size: " + buffer.length);
            insertBytes(bean, buffer, 0);
            this.connector.write(daveArea, dbNum, byteOffset, buffer);
        } catch (final IOException e) {
            throw new S7Exception("store area(" + daveArea + ") dbnum(" + dbNum
                    + ") byteoffset(" + byteOffset + ") beanClass(" + bean.getClass().getName() + ")", e);
        } catch (final ReflectiveOperationException e) {
            throw new S7Exception("store beanClass(" + bean.getClass().getName() + ")", e);
        }
    }

    @Override
    public synchronized void store(Object bean,PlcS7PointVariable plcS7PointVariable) {
        try {
            // One critical section for the whole read-modify-write, on the
            // same monitor the connector's read/write use, so two serializers
            // sharing a connector cannot interleave and lose updates on the
            // same byte.
            synchronized (this.connector) {
                final byte[] buffer = this.connector.read(plcS7PointVariable.getRegisterType(), plcS7PointVariable.getDbNum(), plcS7PointVariable.getSize(), plcS7PointVariable.getByteOffset());
                plcS7PointVariable.getType().getSerializer().newInstance().insert(bean,buffer,0,plcS7PointVariable.getBitOffset(),plcS7PointVariable.getSize());
                this.connector.write(plcS7PointVariable.getRegisterType(),  plcS7PointVariable.getDbNum(), plcS7PointVariable.getByteOffset(), buffer);
            }
        } catch (final IOException e) {
            throw new S7Exception("store dbnum(" + plcS7PointVariable.getDbNum()
                    + ") registerType(" + plcS7PointVariable.getRegisterType()
                    + ") byteoffset(" + plcS7PointVariable.getByteOffset()
                    + ") blocksize(" + plcS7PointVariable.getSize() + ")", e);
        } catch (final ReflectiveOperationException e) {
            throw new S7Exception("store point serializer dbnum(" + plcS7PointVariable.getDbNum()
                    + ") registerType(" + plcS7PointVariable.getRegisterType()
                    + ") byteoffset(" + plcS7PointVariable.getByteOffset() + ")", e);
        } catch (final IllegalArgumentException | ClassCastException | IndexOutOfBoundsException e) {
            throw new S7Exception("store dbnum(" + plcS7PointVariable.getDbNum()
                    + ") registerType(" + plcS7PointVariable.getRegisterType()
                    + ") byteoffset(" + plcS7PointVariable.getByteOffset()
                    + ") blocksize(" + plcS7PointVariable.getSize() + ")", e);
        }

    }
}
