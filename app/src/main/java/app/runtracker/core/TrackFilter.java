package app.runtracker.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns raw GPS fixes into trustworthy distance.
 *
 * Normal fixes add distance straight away. A fix that arrives after a gap, or at an impossible
 * speed, is held until later fixes agree with it. Then it is either bridged (a realistic speed from
 * the last good position, so the straight line counts as estimated distance) or treated as a
 * relocation (an impossible speed, so the position is accepted and no distance is added).
 */
public final class TrackFilter {
    public static final float MAX_ACCURACY_M = 25f;
    /** 8 m/s is 2:05 per km. */
    public static final double MAX_SPEED_MPS = 8.0;
    public static final long GAP_MS = 10_000;
    public static final double MIN_MOVE_M = 5.0;
    static final int BRIDGE_CONFIRMATIONS = 3;
    static final int RELOCATE_CONFIRMATIONS = 5;

    public enum Status { ACCEPTED, STILL, REJECTED, PENDING, BRIDGED, RELOCATED }

    public static final class Result {
        public final Status status;
        public final List<TrackPoint> points;

        Result(Status status, List<TrackPoint> points) {
            this.status = status;
            this.points = points;
        }
    }

    private static final Result REJECTED = new Result(Status.REJECTED, Collections.emptyList());
    private static final Result PENDING = new Result(Status.PENDING, Collections.emptyList());
    private static final Result STILL = new Result(Status.STILL, Collections.emptyList());

    private final List<Fix> pending = new ArrayList<>();
    /** Position the distance total was last measured from. */
    private Fix anchor;
    /** Latest trusted fix, moved or not. Speed and gaps are measured from here. */
    private Fix lastSeen;
    private double totalM;
    private double estimatedM;
    private int segment;

    public TrackFilter() {
    }

    /** Continues a run after the app process was killed. */
    public TrackFilter(TrackPoint last, double estimatedM) {
        anchor = last.fix;
        lastSeen = last.fix;
        totalM = last.cumM;
        this.estimatedM = estimatedM;
        segment = last.segment;
    }

    public double totalM() {
        return totalM;
    }

    public double estimatedM() {
        return estimatedM;
    }

    public Result onFix(Fix f) {
        if (f.accuracyM > MAX_ACCURACY_M) {
            return REJECTED;
        }
        if (lastSeen == null) {
            anchor = f;
            lastSeen = f;
            // Time passed before the first fix with no distance counted, so that split is unreliable.
            TrackPoint first = new TrackPoint(f, 0, segment, f.tMs >= GAP_MS);
            return new Result(Status.ACCEPTED, Collections.singletonList(first));
        }
        if (f.tMs <= lastSeen.tMs) {
            return REJECTED;
        }
        if (f.tMs - lastSeen.tMs < GAP_MS && speed(lastSeen, f) <= MAX_SPEED_MPS) {
            pending.clear();
            List<TrackPoint> points = new ArrayList<>(1);
            countNormal(f, points);
            return points.isEmpty() ? STILL : new Result(Status.ACCEPTED, points);
        }
        return onSuspect(f);
    }

    private Result onSuspect(Fix f) {
        if (!pending.isEmpty() && !agrees(pending.get(pending.size() - 1), f)) {
            pending.clear();
        }
        pending.add(f);
        Fix first = pending.get(0);
        boolean plausible = speed(lastSeen, first) <= MAX_SPEED_MPS;
        if (pending.size() < (plausible ? BRIDGE_CONFIRMATIONS : RELOCATE_CONFIRMATIONS)) {
            return PENDING;
        }

        segment++;
        if (plausible) {
            double bridged = anchor.distanceTo(first);
            totalM += bridged;
            estimatedM += bridged;
        }
        anchor = first;
        lastSeen = first;
        List<TrackPoint> points = new ArrayList<>(pending.size());
        points.add(new TrackPoint(first, totalM, segment, true));
        for (int i = 1; i < pending.size(); i++) {
            countNormal(pending.get(i), points);
        }
        pending.clear();
        return new Result(plausible ? Status.BRIDGED : Status.RELOCATED, points);
    }

    private void countNormal(Fix f, List<TrackPoint> out) {
        lastSeen = f;
        double moved = anchor.distanceTo(f);
        if (moved < Math.max(MIN_MOVE_M, f.accuracyM)) {
            return;
        }
        totalM += moved;
        anchor = f;
        out.add(new TrackPoint(f, totalM, segment, false));
    }

    private static boolean agrees(Fix a, Fix b) {
        long dt = b.tMs - a.tMs;
        return dt > 0 && dt < GAP_MS && speed(a, b) <= MAX_SPEED_MPS;
    }

    private static double speed(Fix a, Fix b) {
        return a.distanceTo(b) / ((b.tMs - a.tMs) / 1000.0);
    }
}
