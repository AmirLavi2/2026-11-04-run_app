package app.runtracker.core;

/** A fix the filter has accepted into the run. */
public final class TrackPoint {
    public final Fix fix;
    /** Total run distance at this point, in metres. */
    public final double cumM;
    /** Increases after each GPS gap; exported as a separate GPX track segment. */
    public final int segment;
    /** True when pace up to this point can't be trusted (a gap just ended here). */
    public final boolean tainted;

    public TrackPoint(Fix fix, double cumM, int segment, boolean tainted) {
        this.fix = fix;
        this.cumM = cumM;
        this.segment = segment;
        this.tainted = tainted;
    }
}
