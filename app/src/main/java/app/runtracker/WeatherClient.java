package app.runtracker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Reads the current air temperature from Open-Meteo. Call off the main thread. */
final class WeatherClient {
    private static final int TIMEOUT_MS = 10_000;
    private static final long LAST_KNOWN_MAX_AGE_MS = 60 * 60 * 1000L;

    private WeatherClient() {
    }

    /** The phone's newest known location from the last hour, or null. Good enough for a temperature. */
    @SuppressLint("MissingPermission") // Callers check the location permission first.
    static Location recentLastKnownLocation(Context context) {
        LocationManager manager = context.getSystemService(LocationManager.class);
        Location best = null;
        for (String provider : manager.getProviders(true)) {
            Location location = manager.getLastKnownLocation(provider);
            if (location != null && (best == null
                    || location.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos())) {
                best = location;
            }
        }
        boolean recent = best != null && SystemClock.elapsedRealtime()
                - best.getElapsedRealtimeNanos() / 1_000_000L < LAST_KNOWN_MAX_AGE_MS;
        return recent ? best : null;
    }

    /** The location is rounded to 2 decimals (about 1 km), which is all a temperature reading needs. */
    static double currentCelsius(double lat, double lon) throws IOException, JSONException {
        URL url = new URL(String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m",
                lat, lon));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + connection.getResponseCode());
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            return new JSONObject(body.toString()).getJSONObject("current").getDouble("temperature_2m");
        } finally {
            connection.disconnect();
        }
    }
}
