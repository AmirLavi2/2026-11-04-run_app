package app.runtracker.core;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class DriveMultipartTest {
    @Test
    public void bodyHasMetadataThenContentBetweenBoundaries() {
        byte[] body = DriveMultipart.body("{\"name\":\"a.gpx\"}", "application/gpx+xml",
                "<gpx/>".getBytes(StandardCharsets.UTF_8));
        String b = DriveMultipart.BOUNDARY;
        assertEquals("--" + b + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                + "{\"name\":\"a.gpx\"}\r\n"
                + "--" + b + "\r\n"
                + "Content-Type: application/gpx+xml\r\n\r\n"
                + "<gpx/>\r\n"
                + "--" + b + "--\r\n", new String(body, StandardCharsets.UTF_8));
    }
}
