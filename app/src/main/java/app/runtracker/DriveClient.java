package app.runtracker;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import app.runtracker.core.DriveMultipart;

/**
 * Minimal Google Drive REST client. Files are looked up by name before writing, so a retried upload
 * replaces the file and never creates a duplicate. Call off the main thread.
 */
final class DriveClient {
    private static final String FILES = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final int TIMEOUT_MS = 30_000;

    private final String accessToken;

    DriveClient(String accessToken) {
        this.accessToken = accessToken;
    }

    /** Finds or creates a folder in the top level of Drive and returns its ID. */
    String ensureFolder(String name) throws IOException, JSONException {
        String existing = find("name='" + name + "' and mimeType='" + FOLDER_MIME + "' and trashed=false");
        if (existing != null) {
            return existing;
        }
        byte[] metadata = new JSONObject().put("name", name).put("mimeType", FOLDER_MIME)
                .toString().getBytes(StandardCharsets.UTF_8);
        return new JSONObject(send(FILES, "POST", "application/json; charset=UTF-8", metadata)).getString("id");
    }

    void createOrReplace(String folderId, String name, String mimeType, byte[] content)
            throws IOException, JSONException {
        String existing = find("name='" + name + "' and '" + folderId + "' in parents and trashed=false");
        if (existing != null) {
            send(UPLOAD + "/" + existing + "?uploadType=media", "PATCH", mimeType, content);
            return;
        }
        String metadata = new JSONObject().put("name", name)
                .put("parents", new JSONArray().put(folderId)).toString();
        send(UPLOAD + "?uploadType=multipart", "POST", DriveMultipart.CONTENT_TYPE,
                DriveMultipart.body(metadata, mimeType, content));
    }

    private String find(String query) throws IOException, JSONException {
        String url = FILES + "?fields=files(id)&q=" + URLEncoder.encode(query, "UTF-8");
        JSONArray files = new JSONObject(send(url, "GET", null, null)).getJSONArray("files");
        return files.length() == 0 ? null : files.getJSONObject(0).getString("id");
    }

    private String send(String url, String method, String contentType, byte[] body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        if ("PATCH".equals(method)) {
            // HttpURLConnection doesn't reliably accept PATCH; Google's APIs honour this override.
            connection.setRequestMethod("POST");
            connection.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        } else {
            connection.setRequestMethod(method);
        }
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Drive returned HTTP " + status + ": " + read(connection.getErrorStream()));
            }
            return read(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            return out.toString("UTF-8");
        }
    }
}
