package io.github.maidamai.s7connector.impl.serializer.batch;

import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.bean.PlcS7PointVariable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PointReadPlanner {
    private static final int MAX_NEAR_CONTINUOUS_GAP_BYTES = 4;

    public List<BatchReadRequest> plan(final List<PlcS7PointVariable> points, final int maxWindowLength) {
        if (points == null) {
            throw new IllegalArgumentException("points must not be null");
        }
        if (maxWindowLength <= 0) {
            throw new IllegalArgumentException("maxWindowLength must be positive: " + maxWindowLength);
        }
        final List<IndexedPoint> indexedPoints = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            final PlcS7PointVariable point = points.get(i);
            this.validatePoint(point, i, maxWindowLength);
            indexedPoints.add(new IndexedPoint(i, point));
        }
        indexedPoints.sort(Comparator
                .comparingInt((IndexedPoint indexedPoint) -> indexedPoint.point.getRegisterType().getCode())
                .thenComparingInt(indexedPoint -> indexedPoint.point.getDbNum())
                .thenComparingInt(indexedPoint -> indexedPoint.point.getByteOffset())
                .thenComparingInt(indexedPoint -> indexedPoint.point.getBitOffset())
                .thenComparingInt(IndexedPoint::getOriginalIndex));

        final List<BatchReadRequest> requests = new ArrayList<>();
        CurrentWindow currentWindow = null;
        for (final IndexedPoint indexedPoint : indexedPoints) {
            if (currentWindow == null || !currentWindow.canAppend(indexedPoint, maxWindowLength)) {
                if (currentWindow != null) {
                    requests.add(currentWindow.toBatchReadRequest());
                }
                currentWindow = new CurrentWindow(indexedPoint);
            } else {
                currentWindow.append(indexedPoint);
            }
        }
        if (currentWindow != null) {
            requests.add(currentWindow.toBatchReadRequest());
        }
        return requests;
    }

    private void validatePoint(final PlcS7PointVariable point, final int index, final int maxWindowLength) {
        if (point == null) {
            throw new IllegalArgumentException("point must not be null at index " + index);
        }
        if (point.getRegisterType() == null) {
            throw new IllegalArgumentException("registerType must not be null at index " + index);
        }
        if (point.getType() == null) {
            throw new IllegalArgumentException("type must not be null at index " + index);
        }
        if (point.getByteOffset() < 0) {
            throw new IllegalArgumentException("byteOffset must not be negative at index " + index + ": " + point.getByteOffset());
        }
        if (point.getBitOffset() < 0 || point.getBitOffset() > 7) {
            throw new IllegalArgumentException("bitOffset must be between 0 and 7 at index " + index + ": " + point.getBitOffset());
        }
        if (point.getType() != S7Type.BOOL && point.getSize() <= 0) {
            throw new IllegalArgumentException("size must be positive for non-BOOL types at index " + index
                    + ": size=" + point.getSize() + ", type=" + point.getType()
                    + ", byteOffset=" + point.getByteOffset() + ", dbNum=" + point.getDbNum());
        }
        final int coverageLength = coverageLength(point);
        if (coverageLength <= 0) {
            throw new IllegalArgumentException("coverage length must be positive at index " + index + ": " + coverageLength);
        }
        if (coverageLength > maxWindowLength) {
            throw new IllegalArgumentException("point coverage length exceeds maxWindowLength at index " + index
                    + ", coverageLength=" + coverageLength + ", maxWindowLength=" + maxWindowLength);
        }
    }

    private static int coverageEndOffset(final PlcS7PointVariable point) {
        return point.getByteOffset() + coverageLength(point);
    }

    private static int coverageLength(final PlcS7PointVariable point) {
        if (point.getType() == S7Type.BOOL) {
            return 1;
        }
        // size is validated to be positive for non-BOOL types up front;
        // masking it here would silently plan reads of the wrong length
        return point.getSize();
    }

    private static final class IndexedPoint {
        private final int originalIndex;
        private final PlcS7PointVariable point;

        private IndexedPoint(final int originalIndex, final PlcS7PointVariable point) {
            this.originalIndex = originalIndex;
            this.point = point;
        }

        private int getOriginalIndex() {
            return this.originalIndex;
        }
    }

    private static final class CurrentWindow {
        private final DaveArea area;
        private final int dbNum;
        private final int startOffset;
        private final List<IndexedPoint> points;
        private int endOffset;

        private CurrentWindow(final IndexedPoint firstPoint) {
            this.area = firstPoint.point.getRegisterType();
            this.dbNum = firstPoint.point.getDbNum();
            this.startOffset = firstPoint.point.getByteOffset();
            this.endOffset = coverageEndOffset(firstPoint.point);
            this.points = new ArrayList<>();
            this.points.add(firstPoint);
        }

        private boolean canAppend(final IndexedPoint indexedPoint, final int maxWindowLength) {
            if (this.area != indexedPoint.point.getRegisterType()) {
                return false;
            }
            if (this.dbNum != indexedPoint.point.getDbNum()) {
                return false;
            }
            if (indexedPoint.point.getByteOffset() - this.endOffset > MAX_NEAR_CONTINUOUS_GAP_BYTES) {
                return false;
            }
            final int candidateEndOffset = Math.max(this.endOffset, coverageEndOffset(indexedPoint.point));
            return candidateEndOffset - this.startOffset <= maxWindowLength;
        }

        private void append(final IndexedPoint indexedPoint) {
            this.points.add(indexedPoint);
            this.endOffset = Math.max(this.endOffset, coverageEndOffset(indexedPoint.point));
        }

        private BatchReadRequest toBatchReadRequest() {
            final List<PlannedPointRead> plannedReads = new ArrayList<>();
            for (final IndexedPoint indexedPoint : this.points) {
                plannedReads.add(new PlannedPointRead(
                        indexedPoint.originalIndex,
                        indexedPoint.point,
                        indexedPoint.point.getByteOffset() - this.startOffset));
            }
            return new BatchReadRequest(this.area, this.dbNum, this.startOffset, this.endOffset - this.startOffset, plannedReads);
        }
    }
}
