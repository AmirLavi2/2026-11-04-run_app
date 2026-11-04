package app.runtracker.core;

/** One location reading. tMs is monotonic time since the run started. */
public final class Fix {
    public final long tMs;
    public final double lat;
    public final double lon;
    public final float accuracyM;

    public Fix(long tMs, double lat, double lon, float accuracyM) {
        this.tMs = tMs;
        this.lat = lat;
        this.lon = lon;
        this.accuracyM = accuracyM;
    }

    /** Great-circle distance in metres. */
    public double distanceTo(Fix other) {
        double r = 6371008.8;
        double p1 = Math.toRadians(lat);
        double p2 = Math.toRadians(other.lat);
        double dp = p2 - p1;
        double dl = Math.toRadians(other.lon - lon);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
