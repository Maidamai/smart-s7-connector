package io.github.maidamai.s7connector.impl.serializer.batch;

import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.bean.PlcS7PointVariable;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointReadPlannerTest {
    private final PointReadPlanner planner = new PointReadPlanner();

    @Test
    void mergesContinuousPointsInSameAreaAndDb() {
        final List<PlcS7PointVariable> points = Arrays.asList(
                point(DaveArea.DB, 1, 0, 0, 2, S7Type.INT, Short.class),
                point(DaveArea.DB, 1, 2, 0, 2, S7Type.INT, Short.class),
                point(DaveArea.DB, 1, 4, 0, 1, S7Type.BYTE, Byte.class));

        final List<BatchReadRequest> requests = this.planner.plan(points, 96);

        assertEquals(1, requests.size(), "continuous points in the same DB should use one batch request");
        assertEquals(0, requests.get(0).getStartOffset(), "batch should start at the first byte offset");
        assertEquals(5, requests.get(0).getLength(), "batch should span the full continuous byte range");
        assertEquals(3, requests.get(0).getPlannedPointReads().size(), "batch should keep all original points");
    }

    @Test
    void rejectsNonPositiveSizeForNonBoolPoints() {
        final List<PlcS7PointVariable> zeroSize = Arrays.asList(
                point(DaveArea.DB, 1, 0, 0, 0, S7Type.INT, Short.class));
        final IllegalArgumentException zeroEx = assertThrows(IllegalArgumentException.class,
                () -> this.planner.plan(zeroSize, 96));
        assertTrue(zeroEx.getMessage().contains("size=0")
                && zeroEx.getMessage().contains("index 0"), zeroEx.getMessage());

        final List<PlcS7PointVariable> negativeSize = Arrays.asList(
                point(DaveArea.DB, 1, 4, 0, -3, S7Type.REAL, Float.class));
        final IllegalArgumentException negativeEx = assertThrows(IllegalArgumentException.class,
                () -> this.planner.plan(negativeSize, 96));
        assertTrue(negativeEx.getMessage().contains("size=-3"), negativeEx.getMessage());
    }

    @Test
    void acceptsBoolPointsWithAnySizeMarker() {
        final List<PlcS7PointVariable> points = Arrays.asList(
                point(DaveArea.DB, 1, 3, 5, 0, S7Type.BOOL, Boolean.class));

        final List<BatchReadRequest> requests = this.planner.plan(points, 96);

        assertEquals(1, requests.size());
        assertEquals(1, requests.get(0).getLength(), "a BOOL point covers exactly one byte");
    }

    @Test
    void rejectsSinglePointLargerThanTheWindowWithSizesInMessage() {
        final List<PlcS7PointVariable> points = Arrays.asList(
                point(DaveArea.DB, 1, 0, 0, 200, S7Type.STRING, String.class));

        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> this.planner.plan(points, 96));
        assertTrue(ex.getMessage().contains("coverageLength=200")
                && ex.getMessage().contains("maxWindowLength=96"), ex.getMessage());
    }

    @Test
    void keepsGroupsSeparateAcrossAreaAndDb() {
        final List<PlcS7PointVariable> points = Arrays.asList(
                point(DaveArea.DB, 1, 0, 0, 1, S7Type.BYTE, Byte.class),
                point(DaveArea.DB, 2, 1, 0, 1, S7Type.BYTE, Byte.class),
                point(DaveArea.FLAGS, 1, 2, 0, 1, S7Type.BYTE, Byte.class));

        final List<BatchReadRequest> requests = this.planner.plan(points, 96);

        assertEquals(3, requests.size(), "points from different area or DB must not be merged");
    }

    @Test
    void preservesOriginalIndexesForOutOfOrderInput() {
        final List<PlcS7PointVariable> points = Arrays.asList(
                point(DaveArea.DB, 1, 10, 0, 1, S7Type.BYTE, Byte.class),
                point(DaveArea.DB, 1, 0, 0, 1, S7Type.BYTE, Byte.class),
                point(DaveArea.DB, 1, 1, 0, 1, S7Type.BYTE, Byte.class));

        final List<BatchReadRequest> requests = this.planner.plan(points, 96);

        assertEquals(2, requests.size(), "non-continuous offsets should become separate windows");
        assertEquals(0, requests.get(0).getStartOffset(), "requests may be sorted by byte offset");
        assertEquals(1, requests.get(0).getPlannedPointReads().get(0).getOriginalIndex(), "first sorted point must keep original index");
        assertEquals(2, requests.get(0).getPlannedPointReads().get(1).getOriginalIndex(), "second sorted point must keep original index");
        assertEquals(0, requests.get(1).getPlannedPointReads().get(0).getOriginalIndex(), "late point must keep original index");
    }

    @Test
    void splitsWhenDynamicWindowLimitWouldBeExceeded() {
        final List<PlcS7PointVariable> points = Arrays.asList(
                point(DaveArea.DB, 1, 0, 0, 2, S7Type.INT, Short.class),
                point(DaveArea.DB, 1, 2, 0, 2, S7Type.INT, Short.class),
                point(DaveArea.DB, 1, 4, 0, 2, S7Type.INT, Short.class));

        final List<BatchReadRequest> requests = this.planner.plan(points, 4);

        assertEquals(2, requests.size(), "window limit should split otherwise continuous points");
        assertEquals(4, requests.get(0).getLength(), "first request should fill the dynamic window");
        assertEquals(4, requests.get(1).getStartOffset(), "second request should start after the first window");
    }

    @Test
    void mergesBoolPointsInSameByte() {
        final List<PlcS7PointVariable> points = Arrays.asList(
                point(DaveArea.DB, 1, 5, 0, 1, S7Type.BOOL, Boolean.class),
                point(DaveArea.DB, 1, 5, 7, 1, S7Type.BOOL, Boolean.class));

        final List<BatchReadRequest> requests = this.planner.plan(points, 96);

        assertEquals(1, requests.size(), "BOOL points in the same byte should use one read");
        assertEquals(5, requests.get(0).getStartOffset(), "BOOL batch should start at the containing byte");
        assertEquals(1, requests.get(0).getLength(), "BOOL batch should read exactly one containing byte");
    }

    @Test
    void mergesNearContinuousPointsWithinGapLimit() {
        final List<PlcS7PointVariable> points = Arrays.asList(
                point(DaveArea.DB, 1, 0, 0, 1, S7Type.BYTE, Byte.class),
                point(DaveArea.DB, 1, 5, 0, 1, S7Type.BYTE, Byte.class),
                point(DaveArea.DB, 1, 11, 0, 1, S7Type.BYTE, Byte.class));

        final List<BatchReadRequest> requests = this.planner.plan(points, 96);

        assertEquals(2, requests.size(), "points with a gap up to four bytes should be merged");
        assertEquals(0, requests.get(0).getStartOffset(), "near-continuous batch should start at first point");
        assertEquals(6, requests.get(0).getLength(), "near-continuous batch should include the small gap");
        assertEquals(11, requests.get(1).getStartOffset(), "gap above the limit should start a new request");
    }

    private static PlcS7PointVariable point(
            final DaveArea area,
            final int dbNum,
            final int byteOffset,
            final int bitOffset,
            final int size,
            final S7Type type,
            final Class<?> fieldType) {
        return new PlcS7PointVariable(dbNum, byteOffset, bitOffset, size, area, type, fieldType);
    }
}
