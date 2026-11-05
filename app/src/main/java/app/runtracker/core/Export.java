package app.runtracker.core;

import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Display formatting plus the CSV and GPX file formats. */
public final class Export {
    private Export() {
    }

    public static String duration(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    public static String km(double metres) {
        return String.format(Locale.US, "%.2f", metres / 1000.0);
    }

    public static void csv(List<RunRecord> runs, Writer w) throws IOException {
        SimpleDateFormat local = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        w.write("start,duration,distance_km,estimated_km,avg_pace_min_per_km,temperature_c\n");
        for (RunRecord r : runs) {
            w.write(local.format(new Date(r.startWallMs)) + ","
                    + duration(r.durationMs) + ","
                    + String.format(Locale.US, "%.3f,%.3f,", r.distanceM / 1000, r.estimatedM / 1000)
                    + pace(r) + ","
                    + (r.temperatureC == null ? "" : String.format(Locale.US, "%.1f", r.temperatureC))
                    + "\n");
        }
    }

    private static String pace(RunRecord r) {
        if (r.distanceM <= 0) {
            return "";
        }
        return pace(r.durationMs / 1000.0 / (r.distanceM / 1000.0));
    }

    public static String speedKmh(double secondsPerKm) {
        return String.format(Locale.US, "%.1f", 3600 / secondsPerKm);
    }

    public static String pace(double secondsPerKm) {
        long s = Math.round(secondsPerKm);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    public static void gpx(RunRecord run, List<TrackPoint> points, Writer w) throws IOException {
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<gpx version=\"1.1\" creator=\"Run Tracker\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
                + "<trk><type>running</type>\n");
        int segment = Integer.MIN_VALUE;
        for (TrackPoint p : points) {
            if (p.segment != segment) {
                w.write(segment == Integer.MIN_VALUE ? "<trkseg>\n" : "</trkseg>\n<trkseg>\n");
                segment = p.segment;
            }
            w.write(String.format(Locale.US, "<trkpt lat=\"%.7f\" lon=\"%.7f\"><time>%s</time></trkpt>\n",
                    p.fix.lat, p.fix.lon, utc.format(new Date(run.startWallMs + p.fix.tMs))));
        }
        if (segment != Integer.MIN_VALUE) {
            w.write("</trkseg>\n");
        }
        w.write("</trk>\n</gpx>\n");
    }
}
