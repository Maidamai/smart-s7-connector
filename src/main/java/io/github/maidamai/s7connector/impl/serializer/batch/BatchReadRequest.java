package io.github.maidamai.s7connector.impl.serializer.batch;

import io.github.maidamai.s7connector.api.DaveArea;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BatchReadRequest {
    private final DaveArea area;
    private final int dbNum;
    private final int startOffset;
    private final int length;
    private final List<PlannedPointRead> plannedPointReads;

    public BatchReadRequest(
            final DaveArea area,
            final int dbNum,
            final int startOffset,
            final int length,
            final List<PlannedPointRead> plannedPointReads) {
        if (area == null) {
            throw new IllegalArgumentException("area must not be null");
        }
        if (startOffset < 0) {
            throw new IllegalArgumentException("startOffset must not be negative: " + startOffset);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive: " + length);
        }
        if (plannedPointReads == null || plannedPointReads.isEmpty()) {
            throw new IllegalArgumentException("plannedPointReads must not be empty");
        }
        this.area = area;
        this.dbNum = dbNum;
        this.startOffset = startOffset;
        this.length = length;
        this.plannedPointReads = Collections.unmodifiableList(new ArrayList<>(plannedPointReads));
    }

    public DaveArea getArea() {
        return this.area;
    }

    public int getDbNum() {
        return this.dbNum;
    }

    public int getStartOffset() {
        return this.startOffset;
    }

    public int getLength() {
        return this.length;
    }

    public List<PlannedPointRead> getPlannedPointReads() {
        return this.plannedPointReads;
    }
}
