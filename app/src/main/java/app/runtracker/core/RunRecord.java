package app.runtracker.core;

/** A saved run. */
public final class RunRecord {
    public final long id;
    public final long startWallMs;
    public final long startElapsedMs;
    public final int bootCount;
    public final long durationMs;
    public final double distanceM;
    public final double estimatedM;
    /** Air temperature at the start of the run, or null if it couldn't be fetched. */
    public final Double temperatureC;
    public final TravelMode mode;

    public RunRecord(long id, long startWallMs, long startElapsedMs, int bootCount,
                     long durationMs, double distanceM, double estimatedM, Double temperatureC,
                     TravelMode mode) {
        this.mode = mode;
        this.id = id;
        this.startWallMs = startWallMs;
        this.startElapsedMs = startElapsedMs;
        this.bootCount = bootCount;
        this.durationMs = durationMs;
        this.distanceM = distanceM;
        this.estimatedM = estimatedM;
        this.temperatureC = temperatureC;
    }
}
