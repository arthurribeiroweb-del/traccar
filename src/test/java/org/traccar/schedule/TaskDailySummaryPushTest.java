package org.traccar.schedule;

import org.junit.jupiter.api.Test;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Position;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for private helper methods in {@link TaskDailySummaryPush}.
 * Uses reflection because these are internal computation methods that should
 * not be public API,
 * but their correctness is critical for the daily summary feature.
 */
public class TaskDailySummaryPushTest {

    // ── countLongStops tests ────────────────────────────────────────────

    @Test
    public void testCountLongStops_normalTrip() throws Exception {
        // Simulate: move 10 min, stop 20 min, move 10 min, stop 16 min, move 5 min
        List<Position> positions = new ArrayList<>();
        long baseTime = System.currentTimeMillis();
        double movingSpeedKnots = UnitsConverter.knotsFromKph(50); // 50 km/h
        double stoppedSpeedKnots = 0.0;

        // Moving segment: 10 minutes (10 positions, 1 min apart)
        for (int i = 0; i < 10; i++) {
            positions.add(makePosition(baseTime + i * 60_000L, movingSpeedKnots));
        }
        // Stopped segment: 20 minutes (20 positions, 1 min apart) → ≥ 15 min → counts
        // as 1 long stop
        for (int i = 10; i < 30; i++) {
            positions.add(makePosition(baseTime + i * 60_000L, stoppedSpeedKnots));
        }
        // Moving segment: 10 minutes
        for (int i = 30; i < 40; i++) {
            positions.add(makePosition(baseTime + i * 60_000L, movingSpeedKnots));
        }
        // Stopped segment: 16 minutes → ≥ 15 min → counts as 1 long stop
        for (int i = 40; i < 56; i++) {
            positions.add(makePosition(baseTime + i * 60_000L, stoppedSpeedKnots));
        }
        // Moving segment: 5 minutes
        for (int i = 56; i < 61; i++) {
            positions.add(makePosition(baseTime + i * 60_000L, movingSpeedKnots));
        }

        int result = invokeCountLongStops(positions);
        assertEquals(2, result, "Should detect 2 long stops (20min and 16min)");
    }

    @Test
    public void testCountLongStops_exactlyFifteenMinutes() throws Exception {
        // Stop exactly 15 minutes (>= 15 counts)
        List<Position> positions = new ArrayList<>();
        long baseTime = System.currentTimeMillis();
        double movingSpeedKnots = UnitsConverter.knotsFromKph(50);

        // Moving 5 minutes
        for (int i = 0; i < 5; i++) {
            positions.add(makePosition(baseTime + i * 60_000L, movingSpeedKnots));
        }
        // Stopped exactly 15 minutes
        for (int i = 5; i < 20; i++) {
            positions.add(makePosition(baseTime + i * 60_000L, 0.0));
        }
        // Moving again
        positions.add(makePosition(baseTime + 20 * 60_000L, movingSpeedKnots));

        int result = invokeCountLongStops(positions);
        assertEquals(1, result, "Stop of exactly 15 min should count (>= 15)");
    }

    @Test
    public void testCountLongStops_shortStopDoesNotCount() throws Exception {
        // Stop 14 minutes (< 15, should NOT count)
        List<Position> positions = new ArrayList<>();
        long baseTime = System.currentTimeMillis();
        double movingSpeedKnots = UnitsConverter.knotsFromKph(50);

        for (int i = 0; i < 5; i++) {
            positions.add(makePosition(baseTime + i * 60_000L, movingSpeedKnots));
        }
        for (int i = 5; i < 19; i++) {
            positions.add(makePosition(baseTime + i * 60_000L, 0.0));
        }
        positions.add(makePosition(baseTime + 19 * 60_000L, movingSpeedKnots));

        int result = invokeCountLongStops(positions);
        assertEquals(0, result, "Stop of 14 min should NOT count");
    }

    @Test
    public void testCountLongStops_emptyPositions() throws Exception {
        assertEquals(0, invokeCountLongStops(new ArrayList<>()));
        assertEquals(0, invokeCountLongStops(null));
    }

    @Test
    public void testCountLongStops_singlePosition() throws Exception {
        List<Position> positions = List.of(makePosition(System.currentTimeMillis(), 0.0));
        assertEquals(0, invokeCountLongStops(positions));
    }

    @Test
    public void testCountLongStops_stoppedAtEnd() throws Exception {
        // Stop at the end of the day with no resume → should still count
        List<Position> positions = new ArrayList<>();
        long baseTime = System.currentTimeMillis();
        double movingSpeedKnots = UnitsConverter.knotsFromKph(50);

        for (int i = 0; i < 5; i++) {
            positions.add(makePosition(baseTime + i * 60_000L, movingSpeedKnots));
        }
        // Stopped 20 minutes at end, never resumes
        for (int i = 5; i < 25; i++) {
            positions.add(makePosition(baseTime + i * 60_000L, 0.0));
        }

        int result = invokeCountLongStops(positions);
        assertEquals(1, result, "Stop at end of positions should still count");
    }

    // ── formatMotion tests ──────────────────────────────────────────────

    @Test
    public void testFormatMotion_zero() throws Exception {
        assertEquals("0m", invokeFormatMotion(0));
    }

    @Test
    public void testFormatMotion_lessThanOneMinute() throws Exception {
        assertEquals("1m", invokeFormatMotion(30));
    }

    @Test
    public void testFormatMotion_exactlyOneMinute() throws Exception {
        assertEquals("1m", invokeFormatMotion(60));
    }

    @Test
    public void testFormatMotion_minutesOnly() throws Exception {
        assertEquals("45m", invokeFormatMotion(45 * 60));
    }

    @Test
    public void testFormatMotion_exactlyOneHour() throws Exception {
        assertEquals("1h00", invokeFormatMotion(3600));
    }

    @Test
    public void testFormatMotion_hoursAndMinutes() throws Exception {
        assertEquals("2h30", invokeFormatMotion(2 * 3600 + 30 * 60));
    }

    @Test
    public void testFormatMotion_paddedMinutes() throws Exception {
        assertEquals("1h05", invokeFormatMotion(3600 + 5 * 60));
    }

    // ── calculateAverageSpeedKph tests ──────────────────────────────────

    @Test
    public void testCalculateAverageSpeedKph_normal() throws Exception {
        // 100 km in 2 hours = 50 km/h
        assertEquals(50, invokeCalculateAverageSpeedKph(100.0, 7200));
    }

    @Test
    public void testCalculateAverageSpeedKph_zeroDistance() throws Exception {
        assertEquals(0, invokeCalculateAverageSpeedKph(0.0, 3600));
    }

    @Test
    public void testCalculateAverageSpeedKph_zeroTime() throws Exception {
        assertEquals(0, invokeCalculateAverageSpeedKph(100.0, 0));
    }

    @Test
    public void testCalculateAverageSpeedKph_bothZero() throws Exception {
        assertEquals(0, invokeCalculateAverageSpeedKph(0.0, 0));
    }

    // ── buildDistanceDeltaInsight tests ──────────────────────────────────

    @Test
    public void testBuildDistanceDeltaInsight_increase() throws Exception {
        String result = invokeBuildDistanceDeltaInsight(110.0, 100.0);
        assertTrue(result.contains("+10"), "Should show +10% increase: " + result);
    }

    @Test
    public void testBuildDistanceDeltaInsight_decrease() throws Exception {
        String result = invokeBuildDistanceDeltaInsight(80.0, 100.0);
        assertTrue(result.contains("-20"), "Should show -20% decrease: " + result);
    }

    @Test
    public void testBuildDistanceDeltaInsight_noPreviousData() throws Exception {
        String result = invokeBuildDistanceDeltaInsight(100.0, 0.0);
        assertEquals("", result, "Should return empty when no previous data");
    }

    @Test
    public void testBuildDistanceDeltaInsight_same() throws Exception {
        String result = invokeBuildDistanceDeltaInsight(100.0, 100.0);
        assertTrue(result.contains("0"), "Should show 0% change: " + result);
    }

    // ── Helper methods ──────────────────────────────────────────────────

    private static Position makePosition(long timeMillis, double speedKnots) {
        Position position = new Position();
        position.setFixTime(new Date(timeMillis));
        position.setSpeed(speedKnots);
        return position;
    }

    private int invokeCountLongStops(List<Position> positions) throws Exception {
        Method method = TaskDailySummaryPush.class
                .getDeclaredMethod("countLongStops", List.class);
        method.setAccessible(true);
        return (int) method.invoke(newInstance(), positions);
    }

    private String invokeFormatMotion(long motionSeconds) throws Exception {
        Method method = TaskDailySummaryPush.class
                .getDeclaredMethod("formatMotion", long.class);
        method.setAccessible(true);
        return (String) method.invoke(newInstance(), motionSeconds);
    }

    private int invokeCalculateAverageSpeedKph(double distanceKm, long motionSeconds) throws Exception {
        Method method = TaskDailySummaryPush.class
                .getDeclaredMethod("calculateAverageSpeedKph", double.class, long.class);
        method.setAccessible(true);
        return (int) method.invoke(newInstance(), distanceKm, motionSeconds);
    }

    private String invokeBuildDistanceDeltaInsight(double currentDistanceKm, double previousDistanceKm)
            throws Exception {
        Method method = TaskDailySummaryPush.class
                .getDeclaredMethod("buildDistanceDeltaInsight", double.class, double.class);
        method.setAccessible(true);
        return (String) method.invoke(newInstance(), currentDistanceKm, previousDistanceKm);
    }

    /**
     * Creates an instance without calling the constructor.
     * Uses Unsafe.allocateInstance to bypass constructor logic that reads
     * injected Config. Safe for testing private helper methods that don't
     * touch injected fields (countLongStops, formatMotion, etc.).
     */
    private TaskDailySummaryPush newInstance() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        return (TaskDailySummaryPush) unsafe.allocateInstance(TaskDailySummaryPush.class);
    }
}
