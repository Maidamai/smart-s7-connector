package io.github.maidamai.s7connector.impl.serializer.batch;

import io.github.maidamai.s7connector.bean.PlcS7PointVariable;

public final class PlannedPointRead {
    private final int originalIndex;
    private final PlcS7PointVariable point;
    private final int relativeByteOffset;

    public PlannedPointRead(
            final int originalIndex,
            final PlcS7PointVariable point,
            final int relativeByteOffset) {
        if (originalIndex < 0) {
            throw new IllegalArgumentException("originalIndex must not be negative: " + originalIndex);
        }
        if (point == null) {
            throw new IllegalArgumentException("point must not be null");
        }
        if (relativeByteOffset < 0) {
            throw new IllegalArgumentException("relativeByteOffset must not be negative: " + relativeByteOffset);
        }
        this.originalIndex = originalIndex;
        this.point = point;
        this.relativeByteOffset = relativeByteOffset;
    }

    public int getOriginalIndex() {
        return this.originalIndex;
    }

    public PlcS7PointVariable getPoint() {
        return this.point;
    }

    public int getRelativeByteOffset() {
        return this.relativeByteOffset;
    }
}
