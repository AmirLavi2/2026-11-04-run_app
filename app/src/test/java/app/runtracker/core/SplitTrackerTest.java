package app.runtracker.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import app.runtracker.core.SplitTracker.Cue;

/** Paces are in seconds per 400 m; multiply by 2.5 for seconds per km. */
public class SplitTrackerTest {
    private final SplitTracker tracker = new SplitTracker();
    private long tMs;
    private double cumM;

    private static TrackPoint point(long tMs, double cumM, boolean tainted) {
        return new TrackPoint(new Fix(tMs, 0, 0, 5f), cumM, 0, tainted);
    }

    /** Runs a distance at a steady pace in 10 m steps and returns the cues it produced. */
    private List<Cue> run(double metres, double secondsPer400m) {
        List<Cue> cues = new ArrayList<>();
        double end = cumM + metres;
        while (cumM < end - 1e-9) {
            cumM += 10;
            tMs += Math.round(10 / 400.0 * secondsPer400m * 1000);
            cues.addAll(tracker.onPoint(point(tMs, cumM, false)));
        }
        return cues;
    }

    private List<Cue> gap(double metres, long seconds) {
        cumM += metres;
        tMs += seconds * 1000;
        return tracker.onPoint(point(tMs, cumM, true));
    }

    @Test
    public void firstSplitIsSilent() {
        assertEquals(Collections.emptyList(), run(400, 150));
    }

    @Test
    public void checksEvery400m() {
        run(400, 150);
        assertEquals("before the mark", Collections.emptyList(), run(390, 140));
        assertEquals("at the mark", Arrays.asList(Cue.FASTER), run(10, 140));
    }

    @Test
    public void improvingAverageCuesUpAndWorseningAverageCuesDown() {
        run(400, 150);
        assertEquals("375 to 362.5 s/km", Arrays.asList(Cue.FASTER), run(400, 140));
        assertEquals("362.5 to 379.2 s/km", Arrays.asList(Cue.SLOWER), run(400, 165));
    }

    @Test
    public void changeOfExactlyTenSecondsPerKmCues() {
        run(400, 150);
        assertEquals("375 to 365 s/km", Arrays.asList(Cue.FASTER), run(400, 142));
    }

    @Test
    public void changeUnderTenSecondsPerKmIsSilent() {
        run(400, 150);
        assertEquals("375 to 366.25 s/km", Collections.emptyList(), run(400, 143));
    }

    @Test
    public void smallChangesAddUpUntilTheyCue() {
        run(400, 150);
        assertEquals("370 s/km", Collections.emptyList(), run(400, 146));
        assertEquals("368.3 s/km", Collections.emptyList(), run(400, 146));
        assertEquals("363.75 s/km", Arrays.asList(Cue.FASTER), run(400, 140));
    }

    @Test
    public void nextToneIsMeasuredFromTheLastTone() {
        run(400, 150);
        assertEquals("362.5 s/km", Arrays.asList(Cue.FASTER), run(400, 140));
        assertEquals("back within 10 s/km of 362.5", Collections.emptyList(), run(400, 150));
    }

    @Test
    public void comparesTheAverageNotThePreviousSplit() {
        run(400, 150);
        run(400, 100);
        // Slower than the 100 s split, but it still pulls the average down from 312.5 to 295.8 s/km.
        assertEquals(Arrays.asList(Cue.FASTER), run(400, 105));
    }

    @Test
    public void unchangedAverageIsSilent() {
        run(400, 150);
        assertEquals(Collections.emptyList(), run(400, 150));
    }

    @Test
    public void gapSilencesItsSplitAndStaysOutOfTheAverage() {
        run(400, 150);
        run(160, 150);
        assertEquals(Collections.emptyList(), gap(80, 30));
        assertEquals("split with the gap", Collections.emptyList(), run(160, 100));
        // Within 10 s/km of the clean 375 s/km. Counting the 130 s gap split would cue SLOWER here.
        assertEquals("next clean split", Collections.emptyList(), run(400, 155));
        assertEquals("comparison resumes", Arrays.asList(Cue.SLOWER), run(400, 200));
    }

    @Test
    public void gapCrossingAMarkSilencesBothSplits() {
        run(400, 150);
        run(320, 150);
        assertTrue(gap(160, 60).isEmpty());
        assertEquals("rest of the second gap split", Collections.emptyList(), run(320, 100));
        assertEquals("next clean split", Arrays.asList(Cue.FASTER), run(400, 130));
    }

    @Test
    public void relocationWithNoDistanceStillSilencesTheSplit() {
        run(400, 150);
        run(80, 150);
        assertTrue(gap(0, 60).isEmpty());
        assertEquals(Collections.emptyList(), run(320, 100));
        assertEquals(Arrays.asList(Cue.SLOWER), run(400, 200));
    }

    @Test
    public void firstCleanSplitAfterAnEarlyGapSetsTheBaseline() {
        assertTrue(gap(80, 30).isEmpty());
        assertEquals("split with the gap", Collections.emptyList(), run(320, 150));
        assertEquals("baseline", Collections.emptyList(), run(400, 150));
        assertEquals(Arrays.asList(Cue.FASTER), run(400, 130));
    }

    @Test
    public void averagePaceIsUnknownUntilTheFirstCleanSplit() {
        run(390, 150);
        assertTrue(Double.isNaN(tracker.averageSecondsPerKm()));
        run(10, 150);
        assertEquals(375, tracker.averageSecondsPerKm(), 0.001);
    }

    @Test
    public void averagePaceCoversCleanSplitsOnly() {
        run(400, 150);
        run(400, 140);
        assertEquals(362.5, tracker.averageSecondsPerKm(), 0.001);
        gap(80, 200);
        run(320, 150);
        assertEquals("gap split left out", 362.5, tracker.averageSecondsPerKm(), 0.001);
    }

    @Test
    public void averagePaceIsUnknownWhenEverySplitHasAGap() {
        gap(80, 30);
        run(320, 150);
        assertTrue(Double.isNaN(tracker.averageSecondsPerKm()));
    }

    @Test
    public void markTimeIsInterpolatedBetweenPoints() {
        tracker.onPoint(point(100_000, 390, false));
        tracker.onPoint(point(110_000, 410, false));
        tracker.onPoint(point(205_000, 790, false));
        // First split ended at 105 s. Second reaches 800 m at 210 s, so it took 105 s: no cue.
        assertEquals(Collections.emptyList(), tracker.onPoint(point(215_000, 810, false)));
    }
}
