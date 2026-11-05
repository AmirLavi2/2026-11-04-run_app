package app.runtracker.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import org.junit.Test;

public class ExportTest {
    private static final RunRecord RUN =
            new RunRecord(1, 1_700_000_000_000L, 0, 0, 3_000_000, 10_000, 250, 18.44, TravelMode.RUN);
    private static final RunRecord CAR =
            new RunRecord(2, 1_700_000_000_000L, 0, 0, 3_600_000, 56_000, 0, null, TravelMode.CAR);

    @Test
    public void averageSpeedIncludesStopsBecauseItUsesTotalTime() {
        assertEquals(56, Export.averageKmh(56_000, 3_600_000), 0.001);
        assertEquals(28, Export.averageKmh(56_000, 7_200_000), 0.001);
        assertEquals(0, Export.averageKmh(100, 0), 0.001);
        assertEquals("56", Export.wholeKmh(55.6));
    }

    @Test
    public void csvMarksVehiclesAndLeavesTheirPaceEmpty() throws IOException {
        StringWriter w = new StringWriter();
        Export.csv(Collections.singletonList(CAR), w);
        assertTrue(w.toString(), w.toString().endsWith(",1:00:00,56.000,0.000,,,car,56.0\n"));
    }

    @Test
    public void gpxTypeFollowsTheMode() throws IOException {
        StringWriter w = new StringWriter();
        Export.gpx(CAR, Collections.emptyList(), w);
        assertTrue(w.toString(), w.toString().contains("<type>driving</type>"));
    }

    @Test
    public void durationAndKm() {
        assertEquals("1:02:03", Export.duration(3_723_999));
        assertEquals("5.25", Export.km(5250));
    }

    @Test
    public void paceRoundsToWholeSeconds() {
        assertEquals("4:50", Export.pace(290));
        assertEquals("5:00", Export.pace(299.6));
    }

    @Test
    public void speedComesFromPace() {
        assertEquals("12.0", Export.speedKmh(300));
        assertEquals("10.4", Export.speedKmh(345));
    }

    @Test
    public void csvHasTotalsAndPace() throws IOException {
        StringWriter w = new StringWriter();
        Export.csv(Collections.singletonList(RUN), w);
        String[] lines = w.toString().split("\n");
        assertEquals("start,duration,distance_km,estimated_km,avg_pace_min_per_km,temperature_c,mode,avg_speed_kmh", lines[0]);
        assertTrue(lines[1], lines[1].endsWith(",0:50:00,10.000,0.250,5:00,18.4,run,12.0"));
    }

    @Test
    public void csvLeavesMissingTemperatureEmpty() throws IOException {
        StringWriter w = new StringWriter();
        Export.csv(Collections.singletonList(new RunRecord(1, 0, 0, 0, 60_000, 1000, 0, null, TravelMode.RUN)), w);
        assertTrue(w.toString(), w.toString().endsWith(",1:00,,run,60.0\n"));
    }

    @Test
    public void numbersUseDotsInAnyLocale() throws IOException {
        Locale saved = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            StringWriter w = new StringWriter();
            Export.csv(Collections.singletonList(RUN), w);
            assertTrue(w.toString().contains("10.000,0.250"));
            assertEquals("5.25", Export.km(5250));
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    public void gpxSplitsSegmentsAtGaps() throws IOException {
        StringWriter w = new StringWriter();
        Export.gpx(RUN, Arrays.asList(
                new TrackPoint(new Fix(0, 32.1, 34.8, 5f), 0, 0, false),
                new TrackPoint(new Fix(60_000, 32.2, 34.9, 5f), 100, 1, true)), w);
        String gpx = w.toString();
        assertEquals(2, gpx.split("<trkseg>", -1).length - 1);
        assertEquals(2, gpx.split("</trkseg>", -1).length - 1);
        assertTrue(gpx, gpx.contains("<trkpt lat=\"32.1000000\" lon=\"34.8000000\"><time>2023-11-14T22:13:20Z</time>"));
        assertTrue(gpx, gpx.contains("<time>2023-11-14T22:14:20Z</time>"));
    }
}
