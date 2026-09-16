
package io.github.maidamai.s7connector.api;

import io.github.maidamai.s7connector.bean.PlcS7PointVariable;
import io.github.maidamai.s7connector.exception.S7Exception;

import java.util.List;

/**
 * Maps between S7 memory and Java beans or point variables.
 *
 * <p>See {@code docs/api-contract.md} for the full mapping contract
 * (public-field requirement, block layout, and write semantics).</p>
 *
 * @author Thomas Rudin
 */
public interface S7Serializer {

    /**
     * Dispenses an Object from the mapping of the Datablock. Reads from the
     * default area {@link DaveArea#DB} starting at {@code byteOffset}.
     *
     * @param <T>        the generic type
     * @param beanClass  the bean class; mapped fields must be public
     * @param dbNum      the DB number
     * @param byteOffset the byte offset
     * @return the t
     * @throws S7Exception the s7 exception
     */
    <T> T dispense(Class<T> beanClass, int dbNum, int byteOffset) throws S7Exception;

    /**
     * Dispenses an Object from the mapping of the Datablock. Reads from the
     * default area {@link DaveArea#DB} starting at {@code byteOffset}.
     *
     * <p>{@code blockSize} fixes the number of bytes read from the PLC,
     * independent of the fields mapped in {@code beanClass}: it must be at
     * least the size of the mapped block layout, and reading more simply
     * fetches surrounding bytes that no field consumes. Field layout itself
     * is derived from the annotations, not from the argument order.</p>
     *
     * @param <T>        the generic type
     * @param beanClass  the bean class
     * @param dbNum      the db num
     * @param byteOffset the byte offset
     * @param blockSize  the block size in bytes to read
     * @return the t
     * @throws S7Exception the s7 exception
     */
    <T> T dispense(Class<T> beanClass, int dbNum, int byteOffset, int blockSize) throws S7Exception;



    /**
     * Stores an Object to the Datablock.
     *
     * <p><b>Full-block overwrite semantics:</b> the mapped block is assembled
     * from an all-zero buffer, so memory not covered by a mapped field
     * (gaps, unset fields, and other bits in partially used bytes of mapped
     * fields) is written as zero. To update a single point without touching
     * surrounding bytes, use {@link #store(Object, PlcS7PointVariable)}.</p>
     *
     * @param bean       the bean
     * @param dbNum      the db num
     * @param byteOffset the byte offset
     */
    void store(Object bean, int dbNum, int byteOffset);


    /**
     * Dispenses an Object from the mapping of the given area, starting at
     * {@code byteOffset}.
     *
     * @param <T>        the generic type
     * @param beanClass  the bean class
     * @param daveArea   the daveArea
     * @param dbNum      the db num (area number for non-DB areas)
     * @param byteOffset the byte offset
     * @return the t
     * @throws S7Exception the s7 exception
     */
    <T> T dispense(Class<T> beanClass,DaveArea daveArea, int dbNum, int byteOffset) throws S7Exception;

    /**
     * Dispenses an Object from the mapping of the given area. See
     * {@link #dispense(Class, int, int, int)} for the {@code blockSize}
     * semantics.
     *
     * @param <T>        the generic type
     * @param beanClass  the bean class
     * @param daveArea   the daveArea
     * @param dbNum      the db num (area number for non-DB areas)
     * @param byteOffset the byte offset
     * @param blockSize  the block size in bytes to read
     * @return the t
     * @throws S7Exception the s7 exception
     */
    <T> T dispense(Class<T> beanClass,DaveArea daveArea, int dbNum, int byteOffset, int blockSize) throws S7Exception;


    /**
     * Dispenses a single point value.
     *
     * @param plcs7PointVariable the point
     * @return
     * @throws S7Exception the s7 exception
     */
    Object dispense(PlcS7PointVariable plcs7PointVariable) throws S7Exception;

    /**
     * Dispenses the values of a point list. The points are planned into
     * batched PLC reads, but the returned values always follow the input
     * order: the element at index {@code i} corresponds to
     * {@code plcs7PointVariableList.get(i)}.
     *
     * @param plcs7PointVariableList the pointList
     * @return a {@link List} with one value per input point, in input order
     * @throws S7Exception the s7 exception
     */
    Object dispense(List<PlcS7PointVariable> plcs7PointVariableList) throws S7Exception;

    /**
     * Dispenses the values of a point list and returns them as a
     * {@link List}, avoiding the cast from {@link Object} that
     * {@link #dispense(List)} requires at call sites.
     *
     * <p>This default method is exactly equivalent to
     * {@code (List&lt;?&gt;) dispense(points)}: it delegates to that method
     * and casts the result. The older {@link #dispense(List)} is retained for
     * source and binary compatibility. Values follow the input order (see
     * {@link #dispense(List)}).</p>
     *
     * @param points the point list
     * @return a {@link List} with one value per input point, in input order
     * @throws S7Exception the s7 exception
     */
    default List<?> dispensePoints(final List<PlcS7PointVariable> points) throws S7Exception {
        return (List<?>) dispense(points);
    }

    /**
     * Stores an Object to the Datablock with full-block overwrite semantics
     * (see {@link #store(Object, int, int)}).
     *
     * @param bean       the bean
     * @param daveArea   the daveArea
     * @param dbNum      the db num
     * @param byteOffset the byte offset
     */
    void store(Object bean,DaveArea daveArea, int dbNum, int byteOffset);

    /**
     * Stores a single point value.
     *
     * <p><b>Read-modify-write semantics:</b> the point's memory range is
     * read, the value is merged into it, and the range is written back. The
     * read-modify-write runs as one critical section on the connector's
     * monitor, so concurrent serializers sharing the same connector cannot
     * lose updates against each other. This is <em>not</em> an end-to-end
     * guarantee: the PLC program or other clients can still change the same
     * memory outside this process. Write the containing block explicitly if
     * you need to control every byte.</p>
     *
     * @param plcS7PointVariable plcS7PointVariable
     */
    void store(Object bean,PlcS7PointVariable plcS7PointVariable);

}
