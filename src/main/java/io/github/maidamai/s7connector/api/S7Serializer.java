
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
     * Stores an Object to the Datablock.
     *
     * @param bean       the bean
     * @param daveArea   the daveArea
     * @param dbNum      the db num
     * @param byteOffset the byte offset
     */
    void store(Object bean,DaveArea daveArea, int dbNum, int byteOffset);

    /**
     * Stores an Object to the Datablock.
     *
     * @param plcS7PointVariable plcS7PointVariable
     */
    void store(Object bean,PlcS7PointVariable plcS7PointVariable);

}
