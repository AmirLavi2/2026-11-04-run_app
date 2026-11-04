package app.runtracker.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * At each 400 m mark, checks whether the run's average pace has moved at least 10 s/km from the
 * average at the last tone (or the first split). Measuring from the last tone, not the last mark,
 * means a slow steady drift still produces a tone once it adds up.
 *
 * Splits touched by a GPS gap are silent and left out of the average, because their distance is
 * estimated or missing and would skew every comparison for the rest of the run.
 */
public final class SplitTracker {
    public static final double SPLIT_M = 400.0;
    private static final long MIN_CHANGE_MS_PER_KM = 10_000;

    public enum Cue { FASTER, SLOWER }

    private int completed;
    private double lastCumM;
    private long lastTMs;
    private double splitStartMs;
    private boolean splitTainted;
    private double cleanMs;
    private int cleanSplits;
    private double referenceSecondsPerKm = Double.NaN;

    public List<Cue> onPoint(TrackPoint p) {
        splitTainted |= p.tainted;
        List<Cue> cues = Collections.emptyList();
        while (p.cumM >= (completed + 1) * SPLIT_M) {
            double boundary = (completed + 1) * SPLIT_M;
            double fraction = (boundary - lastCumM) / (p.cumM - lastCumM);
            double boundaryMs = lastTMs + (p.fix.tMs - lastTMs) * fraction;

            if (!splitTainted) {
                Cue cue = addCleanSplit(boundaryMs - splitStartMs);
                if (cue != null) {
                    if (cues.isEmpty()) {
                        cues = new ArrayList<>(1);
                    }
                    cues.add(cue);
                }
            }
            // A gap that crossed this boundary also covers the start of the next split.
            splitTainted = p.tainted;
            splitStartMs = boundaryMs;
            completed++;
        }
        lastCumM = p.cumM;
        lastTMs = p.fix.tMs;
        return cues;
    }

    /** Average pace over completed clean splits, or NaN until there is one. */
    public double averageSecondsPerKm() {
        return cleanSplits == 0 ? Double.NaN : cleanMs / cleanSplits / SPLIT_M;
    }

    /** Returns the tone to play, or null for the first clean split or a change under the minimum. */
    private Cue addCleanSplit(double durationMs) {
        cleanMs += durationMs;
        cleanSplits++;
        double average = averageSecondsPerKm();
        if (Double.isNaN(referenceSecondsPerKm)) {
            referenceSecondsPerKm = average;
            return null;
        }
        long changeMs = Math.round((average - referenceSecondsPerKm) * 1000);
        if (Math.abs(changeMs) < MIN_CHANGE_MS_PER_KM) {
            return null;
        }
        referenceSecondsPerKm = average;
        return changeMs < 0 ? Cue.FASTER : Cue.SLOWER;
    }
}
