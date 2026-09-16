
package io.github.maidamai.s7connector.api;

import io.github.maidamai.s7connector.bean.PlcS7PointVariable;
import io.github.maidamai.s7connector.exception.S7Exception;

import java.util.List;

/**
 * @author Thomas Rudin
 */
public interface S7Serializer {

    /**
     * Dispenses an Object from the mapping of the Datablock.
     *
     * @param <T>        the generic type
     * @param beanClass  the bean class
     * @param dbNum      the db num
     * @param byteOffset the byte offset
     * @return the t
     * @throws S7Exception the s7 exception
     */
    <T> T dispense(Class<T> beanClass, int dbNum, int byteOffset) throws S7Exception;

    /**
     * Dispense.
     *
     * @param <T>        the generic type
     * @param beanClass  the bean class
     * @param dbNum      the db num
     * @param byteOffset the byte offset
     * @param blockSize  the block size
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
     * Dispenses an Object from the mapping of the Datablock.
     *
     * @param <T>        the generic type
     * @param beanClass  the bean class
     * @param daveArea   the daveArea
     * @param dbNum      the db num
     * @param byteOffset the byte offset
     * @return the t
     * @throws S7Exception the s7 exception
     */
    <T> T dispense(Class<T> beanClass,DaveArea daveArea, int dbNum, int byteOffset) throws S7Exception;

    /**
     * Dispense.
     *
     * @param <T>        the generic type
     * @param beanClass  the bean class
     * @param daveArea   the daveArea
     * @param dbNum      the db num
     * @param byteOffset the byte offset
     * @param blockSize  the block size
     * @return the t
     * @throws S7Exception the s7 exception
     */
    <T> T dispense(Class<T> beanClass,DaveArea daveArea, int dbNum, int byteOffset, int blockSize) throws S7Exception;


    /**
     * Dispense.
     *
     * @param plcs7PointVariable the point
     * @return
     * @throws S7Exception the s7 exception
     */
    Object dispense(PlcS7PointVariable plcs7PointVariable) throws S7Exception;

    /**
     * Dispense.
     *
     * @param plcs7PointVariableList the pointList
     * @return
     * @throws S7Exception the s7 exception
     */
    Object dispense(List<PlcS7PointVariable> plcs7PointVariableList) throws S7Exception;

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
