package app.runtracker.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TrackFilterTest {
    /** Metres of latitude per degree, using the radius in Fix.distanceTo. */
    private static final double M_PER_DEG = 111195.08;
    private static final float GOOD = 5f;

    private static Fix at(long seconds, double northM) {
        return new Fix(seconds * 1000, northM / M_PER_DEG, 0, GOOD);
    }

    private static TrackFilter runTo(long seconds) {
        TrackFilter f = new TrackFilter();
        for (long t = 0; t <= seconds; t++) {
            f.onFix(at(t, t * 3.0));
        }
        return f;
    }

    @Test
    public void steadyRunCountsAllDistance() {
        assertEquals(300, runTo(100).totalM(), 3.5);
    }

    @Test
    public void standingStillWithDriftAddsNothing() {
        TrackFilter f = new TrackFilter();
        for (int t = 0; t < 120; t++) {
            f.onFix(at(t, t % 2 == 0 ? 0 : 3));
        }
        assertEquals(0, f.totalM(), 0.001);
    }

    @Test
    public void standingStillForLongIsNotAGap() {
        TrackFilter f = new TrackFilter();
        for (int t = 0; t <= 60; t++) {
            f.onFix(at(t, 0));
        }
        TrackFilter.Result r = f.onFix(at(61, 6));
        assertEquals(TrackFilter.Status.ACCEPTED, r.status);
        assertFalse(r.points.get(0).tainted);
    }

    @Test
    public void lowAccuracyFixIsRejected() {
        TrackFilter f = runTo(10);
        double before = f.totalM();
        TrackFilter.Result r = f.onFix(new Fix(11_000, 1, 1, 26f));
        assertEquals(TrackFilter.Status.REJECTED, r.status);
        assertEquals(before, f.totalM(), 0.001);
    }

    @Test
    public void singleJumpIsIgnoredAndRunContinues() {
        TrackFilter f = runTo(100);
        TrackFilter.Result jump = f.onFix(at(101, 5000));
        assertEquals(TrackFilter.Status.PENDING, jump.status);
        for (long t = 102; t <= 200; t++) {
            f.onFix(at(t, t * 3.0));
        }
        assertEquals(600, f.totalM(), 6);
        assertEquals(0, f.estimatedM(), 0.001);
    }

    @Test
    public void signalLossIsBridgedOnlyAfterThreeAgreeingFixes() {
        TrackFilter f = runTo(100);
        double before = f.totalM();
        assertEquals(TrackFilter.Status.PENDING, f.onFix(at(160, 480)).status);
        assertEquals(TrackFilter.Status.PENDING, f.onFix(at(161, 483)).status);
        assertEquals(before, f.totalM(), 0.001);

        TrackFilter.Result r = f.onFix(at(162, 486));
        assertEquals(TrackFilter.Status.BRIDGED, r.status);
        assertTrue(r.points.get(0).tainted);
        assertEquals(486, f.totalM(), 6);
        assertEquals(180, f.estimatedM(), 3);
    }

    @Test
    public void falsePositionAfterGapIsNotBridged() {
        TrackFilter f = runTo(100);
        double before = f.totalM();
        // Realistic speed from the last good point, but the next fix is back on the real route.
        assertEquals(TrackFilter.Status.PENDING, f.onFix(at(300, 1500)).status);
        assertEquals(TrackFilter.Status.PENDING, f.onFix(at(301, 900)).status);
        assertEquals(before, f.totalM(), 0.001);
    }

    @Test
    public void lastingJumpRelocatesWithoutAddingDistance() {
        TrackFilter f = runTo(100);
        double before = f.totalM();
        TrackFilter.Result r = null;
        for (long t = 101; t <= 105; t++) {
            r = f.onFix(at(t, 5000 + (t - 101) * 3));
        }
        assertEquals(TrackFilter.Status.RELOCATED, r.status);
        assertTrue(r.points.get(0).tainted);
        assertEquals(5000, r.points.get(0).fix.lat * M_PER_DEG, 1);
        assertEquals(before + 12, f.totalM(), 6);

        f.onFix(at(106, 5015));
        f.onFix(at(107, 5018));
        assertEquals(before + 18, f.totalM(), 6);
    }

    @Test
    public void bridgedDistanceMovesToANewGpxSegment() {
        TrackFilter f = runTo(10);
        f.onFix(at(60, 180));
        f.onFix(at(61, 183));
        TrackFilter.Result r = f.onFix(at(62, 186));
        assertEquals(1, r.points.get(0).segment);
    }

    @Test
    public void lateFirstFixIsTainted() {
        assertFalse(new TrackFilter().onFix(at(9, 0)).points.get(0).tainted);
        assertTrue(new TrackFilter().onFix(at(10, 0)).points.get(0).tainted);
    }

    @Test
    public void restoredFilterContinuesFromLastPoint() {
        TrackPoint last = new TrackPoint(at(100, 300), 300, 2, false);
        TrackFilter f = new TrackFilter(last, 50);
        TrackFilter.Result r = f.onFix(at(103, 309));
        assertEquals(TrackFilter.Status.ACCEPTED, r.status);
        assertEquals(2, r.points.get(0).segment);
        assertEquals(309, f.totalM(), 0.1);
        assertEquals(50, f.estimatedM(), 0.001);
    }
}
