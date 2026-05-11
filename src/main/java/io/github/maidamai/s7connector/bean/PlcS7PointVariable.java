package io.github.maidamai.s7connector.bean;

import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Type;

import java.util.Date;

/**
 * @Author CaiJiangTao
 * @Date 8/2/2022 上午10:40
 * @Version 1.0
 */
public class PlcS7PointVariable {
    private int dbNum;
    private int byteOffset;
    private int bitOffset;
    private int size;
    private S7Type type;
    private DaveArea registerType;
    private Class<?> fieldType;

    public PlcS7PointVariable(int dbNum, int byteOffset, int bitOffset, int size, String registerType, String s7Type) {
        this.dbNum = dbNum;
        this.byteOffset = byteOffset;
        this.bitOffset = bitOffset;
        this.size = size;
        this.getDaveAreaType(registerType);
        this.getS7TypeAndType(s7Type);
    }

    private void getDaveAreaType(String registerType) {
        switch (registerType) {
            case "I":
                this.registerType = DaveArea.INPUTS;
                break;
            case "Q":
                this.registerType = DaveArea.OUTPUTS;
                break;
            case "M":
                this.registerType = DaveArea.FLAGS;
                break;
            default:
                this.registerType = DaveArea.FLAGS;
                break;
        }
    }

    private void getS7TypeAndType(String s7Type) {
        switch (s7Type) {
            case "bool":
                this.type = S7Type.BOOL;
                this.fieldType = Boolean.class;
                break;
            case "byte":
                this.type = S7Type.BYTE;
                this.fieldType = Byte.class;
                break;
            case "int":
                this.type = S7Type.INT;
                this.fieldType = Short.class;
                break;
            case "dint":
                this.type = S7Type.DINT;
                this.fieldType = Long.class;
                break;
            case "word":
                this.type = S7Type.WORD;
                this.fieldType = Integer.class;
                break;
            case "dword":
                this.type = S7Type.DWORD;
                this.fieldType = Long.class;
                break;
            case "real":
                this.type = S7Type.REAL;
                this.fieldType = Float.class;
                break;
            case "date":
                this.type = S7Type.DATE;
                this.fieldType = Date.class;
                break;
            case "time":
                this.type = S7Type.TIME;
                this.fieldType = Long.class;
                break;
            case "datetime":
                this.type = S7Type.DATE_AND_TIME;
                this.fieldType = Long.class;
                break;
            default:
                this.type = S7Type.STRING;
                this.fieldType = Boolean.class;
                break;
        }
    }

    public int getDbNum() {
        return this.dbNum;
    }

    public int getByteOffset() {
        return this.byteOffset;
    }

    public int getBitOffset() {
        return this.bitOffset;
    }

    public int getSize() {
        return this.size;
    }

    public S7Type getType() {
        return this.type;
    }

    public Class<?> getFieldType() {
        return this.fieldType;
    }


    public DaveArea getRegisterType() {
        return registerType;
    }

    public PlcS7PointVariable setRegisterType(DaveArea registerType) {
        this.registerType = registerType;
        return this;
    }

    public PlcS7PointVariable setDbNum(final int dbNum) {
        this.dbNum = dbNum;
        return this;
    }

    public PlcS7PointVariable setByteOffset(final int byteOffset) {
        this.byteOffset = byteOffset;
        return this;
    }

    public PlcS7PointVariable setBitOffset(final int bitOffset) {
        this.bitOffset = bitOffset;
        return this;
    }

    public PlcS7PointVariable setSize(final int size) {
        this.size = size;
        return this;
    }

    public PlcS7PointVariable setType(final S7Type type) {
        this.type = type;
        return this;
    }

    public PlcS7PointVariable setFieldType(final Class<?> fieldType) {
        this.fieldType = fieldType;
        return this;
    }

    @Override
    public String toString() {
        return "PlcS7PointVariable(dbNum=" + this.getDbNum() + ", byteOffset=" + this.getByteOffset() + ", bitOffset=" + this.getBitOffset() + ", size=" + this.getSize() + ", registerType=" + this.getRegisterType() + ", type=" + this.getType() + ", fieldType=" + this.getFieldType() + ")";
    }

    public PlcS7PointVariable() {
    }

    public PlcS7PointVariable(final int dbNum, final int byteOffset, final int bitOffset, final int size,final DaveArea registerType, final S7Type type, final Class<?> fieldType) {
        this.dbNum = dbNum;
        this.byteOffset = byteOffset;
        this.bitOffset = bitOffset;
        this.size = size;
        this.registerType = registerType;
        this.type = type;
        this.fieldType = fieldType;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof PlcS7PointVariable)) {
            return false;
        } else {
            PlcS7PointVariable other = (PlcS7PointVariable) o;
            if (!other.canEqual(this)) {
                return false;
            } else if (this.getDbNum() != other.getDbNum()) {
                return false;
            } else if (this.getByteOffset() != other.getByteOffset()) {
                return false;
            } else if (this.getBitOffset() != other.getBitOffset()) {
                return false;
            } else if (this.getSize() != other.getSize()) {
                return false;
            } else {
                Object this$registerType = this.getRegisterType();
                Object other$registerType = other.getRegisterType();
                if (this$registerType == null) {
                    if (other$registerType != null) {
                        return false;
                    }
                } else if (!this$registerType.equals(other$registerType)) {
                    return false;
                }

                Object this$type = this.getType();
                Object other$type = other.getType();
                if (this$type == null) {
                    if (other$type != null) {
                        return false;
                    }
                } else if (!this$type.equals(other$type)) {
                    return false;
                }

                Object this$fieldType = this.getFieldType();
                Object other$fieldType = other.getFieldType();
                if (this$fieldType == null) {
                    if (other$fieldType != null) {
                        return false;
                    }
                } else if (!this$fieldType.equals(other$fieldType)) {
                    return false;
                }

                return true;
            }
        }
    }

    protected boolean canEqual(final Object other) {
        return other instanceof PlcS7PointVariable;
    }

    @Override
    public int hashCode() {
        int PRIME = 1;
        int result = 1;
        result = result * 59 + this.getDbNum();
        result = result * 59 + this.getByteOffset();
        result = result * 59 + this.getBitOffset();
        result = result * 59 + this.getSize();
        Object $registerType = this.getRegisterType();
        result = result * 59 + ($registerType == null ? 43 : $registerType.hashCode());
        Object $type = this.getType();
        result = result * 59 + ($type == null ? 43 : $type.hashCode());
        Object $fieldType = this.getFieldType();
        result = result * 59 + ($fieldType == null ? 43 : $fieldType.hashCode());
        return result;
    }
}
