package app.runtracker.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** The multipart/related request body Google Drive expects when creating a file with content. */
public final class DriveMultipart {
    public static final String BOUNDARY = "run_tracker_upload_boundary";
    public static final String CONTENT_TYPE = "multipart/related; boundary=" + BOUNDARY;

    private DriveMultipart() {
    }

    public static byte[] body(String metadataJson, String mimeType, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 512);
        write(out, "--" + BOUNDARY + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                + metadataJson + "\r\n--" + BOUNDARY + "\r\nContent-Type: " + mimeType + "\r\n\r\n");
        out.write(content, 0, content.length);
        write(out, "\r\n--" + BOUNDARY + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
    }
}
