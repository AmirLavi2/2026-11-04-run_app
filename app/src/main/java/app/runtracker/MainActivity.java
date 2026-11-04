package app.runtracker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;

import org.json.JSONException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.runtracker.core.Conditions;
import app.runtracker.core.Export;
import app.runtracker.core.RunRecord;
import app.runtracker.core.SplitTracker;
import app.runtracker.core.TrackFilter;
import app.runtracker.core.TrackPoint;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1;
    private static final int REQUEST_EXPORT_CSV = 2;
    private static final int REQUEST_EXPORT_GPX = 3;
    private static final int REQUEST_DRIVE = 4;
    private static final long REFRESH_MS = 500;
    private static final String STATE_GPX_RUN = "gpxRunId";
    private static final String PREF_ASKED_BATTERY = "askedBattery";
    private static final int COLOR_COLD = 0xFF4FC3F7;
    /** A blend of the cold and hot colours, for text that can't show the blue-red icon. */
    private static final int COLOR_MILD = 0xFFB388FF;
    private static final int COLOR_HOT = 0xFFFF5252;
    private static final int COLOR_READY = 0xFF6FD08C;
    private static final int COLOR_WEAK = 0xFFFFB020;
    private static final int COLOR_NONE = 0xFF6B737B;
    private static final String TAG = "MainActivity";
    private static final String DATE_PATTERN = "yyyy/MM/dd HH:mm:ss";
    private static final long IDLE_FIX_INTERVAL_MS = 1_000;
    private static final long IDLE_WEATHER_RETRY_MS = 60_000;
    private static final long IDLE_TEMPERATURE_MAX_AGE_MS = 15 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private RunDatabase db;
    private TextView time;
    private TextView distance;
    private TextView pace;
    private ImageView gnssIcon;
    private TextView gnssInfo;
    private ImageView temperatureIcon;
    private TextView temperatureInfo;
    private TextView timeOfDay;
    private TextView gps;
    private View tonePreview;
    private View exportCsv;
    private Button driveStatus;
    private Button action;
    private ListView history;
    private List<RunRecord> runs = new ArrayList<>();
    private final LocationListener idleListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            idleAccuracyM = location.hasAccuracy() ? location.getAccuracy() : Float.MAX_VALUE;
            idleFixElapsedMs = location.getElapsedRealtimeNanos() / 1_000_000L;
            fetchIdleTemperature(location.getLatitude(), location.getLongitude());
        }

        // Required on older Android versions, where these have no default implementation.
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };
    private boolean idleGpsOn;
    private float idleAccuracyM;
    private long idleFixElapsedMs;
    private Double idleTemperatureC;
    private long idleTemperatureAtMs;
    private long idleWeatherAttemptMs;
    private boolean idleWeatherInFlight;
    private final Map<Long, Double> paceByRun = new HashMap<>();
    private double lastRunAverage = Double.NaN;
    private boolean wasRunning;
    private long gpxRunId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db = new RunDatabase(this);
        time = findViewById(R.id.time);
        distance = findViewById(R.id.distance);
        pace = findViewById(R.id.pace);
        gnssIcon = findViewById(R.id.gnss_icon);
        gnssInfo = findViewById(R.id.gnss_info);
        temperatureIcon = findViewById(R.id.temperature_icon);
        temperatureInfo = findViewById(R.id.temperature_info);
        timeOfDay = findViewById(R.id.time_of_day);
        findViewById(R.id.gnss_status).setOnClickListener(v -> toggle(gnssInfo));
        findViewById(R.id.temperature_status).setOnClickListener(v -> toggle(temperatureInfo));
        gps = findViewById(R.id.gps);
        action = findViewById(R.id.action);
        history = findViewById(R.id.history);
        tonePreview = findViewById(R.id.tone_preview);
        exportCsv = findViewById(R.id.export_csv);
        driveStatus = findViewById(R.id.drive_status);
        driveStatus.setOnClickListener(v -> {
            if (!DriveSync.isConnected(this)) {
                connectDrive();
            }
        });
        TonePlayer tones = new TonePlayer(this);
        findViewById(R.id.preview_faster).setOnClickListener(v -> tones.play(SplitTracker.Cue.FASTER));
        findViewById(R.id.preview_slower).setOnClickListener(v -> tones.play(SplitTracker.Cue.SLOWER));
        if (savedInstanceState != null) {
            gpxRunId = savedInstanceState.getLong(STATE_GPX_RUN, -1);
        }

        action.setOnClickListener(v -> {
            if (RunService.current == null) {
                startRunIfAllowed();
            }
        });
        // A long press, so a pocket tap can't end the run.
        action.setOnLongClickListener(v -> {
            if (RunService.current == null) {
                return false;
            }
            RunService.send(this, RunService.ACTION_STOP);
            return true;
        });
        findViewById(R.id.export_csv).setOnClickListener(v -> createDocument(
                "text/csv", "runs.csv", REQUEST_EXPORT_CSV));
        history.setOnItemClickListener((parent, view, position, id) -> showSummary(runs.get(position)));
        history.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDelete(runs.get(position));
            return true;
        });

        requestPermissionsIfNeeded();
        askBatteryExemptionOnce();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Picks up a run that Android stopped, or closes one interrupted by a reboot.
        if (RunService.current == null && hasLocationPermission() && db.activeRun() != null) {
            RunService.send(this, RunService.ACTION_RESUME);
        }
        if (db.uploadsWaiting() > 0) {
            DriveSync.schedule(this);
        }
        loadHistory();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        setIdleGps(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        db.close();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_GPX_RUN, gpxRunId);
    }

    private void render() {
        RunService.Snapshot s = RunService.current;
        boolean running = s != null;
        if (wasRunning && !running) {
            loadHistory();
            // An unfinished run means Android stopped the service, not the user: it resumes on its own.
            if (!runs.isEmpty() && db.activeRun() == null) {
                showSummary(runs.get(0));
            }
        }
        // During a run this screen can appear over the lock screen, so past runs and export stay hidden.
        if (running != wasRunning && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(running);
        }
        wasRunning = running;
        int whenIdle = running ? View.GONE : View.VISIBLE;
        tonePreview.setVisibility(whenIdle);
        exportCsv.setVisibility(whenIdle);
        driveStatus.setVisibility(whenIdle);
        history.setVisibility(whenIdle);

        if (running) {
            time.setText(Export.duration(SystemClock.elapsedRealtime() - s.startElapsedMs));
            distance.setText(Export.km(s.distanceM));
            showPace(s.gpsOk() ? s.averageSecondsPerKm : Double.NaN);
            setIdleGps(false);
            showStatus(s.latestAccuracyM, s.latestFixElapsedMs, s.temperatureC, s.startWallMs);
            gps.setText(s.gpsOk() ? R.string.gps_ok : s.hadFix ? R.string.gps_lost : R.string.gps_waiting);
            action.setText(R.string.hold_to_stop);
        } else {
            showPace(lastRunAverage);
            gps.setText("");
            action.setText(R.string.start);
            if (!runs.isEmpty()) {
                time.setText(Export.duration(runs.get(0).durationMs));
                distance.setText(Export.km(runs.get(0).distanceM));
            } else {
                time.setText(Export.duration(0));
                distance.setText(Export.km(0));
            }
            showDriveStatus();
            setIdleGps(hasLocationPermission());
            showStatus(idleAccuracyM, idleFixElapsedMs, idleTemperatureC, System.currentTimeMillis());
        }
    }

    private void showDriveStatus() {
        if (!DriveSync.isConnected(this)) {
            driveStatus.setText(R.string.drive_connect);
            return;
        }
        int waiting = db.uploadsWaiting();
        driveStatus.setText(waiting == 0 ? getString(R.string.drive_all_uploaded)
                : getString(R.string.drive_waiting, waiting));
    }

    private void connectDrive() {
        Identity.getAuthorizationClient(this).authorize(DriveSync.authorizationRequest())
                .addOnSuccessListener(result -> {
                    if (!result.hasResolution()) {
                        onDriveConnected();
                        return;
                    }
                    try {
                        startIntentSenderForResult(result.getPendingIntent().getIntentSender(),
                                REQUEST_DRIVE, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException e) {
                        showDriveFailure(e);
                    }
                })
                .addOnFailureListener(this::showDriveFailure);
    }

    private void onDriveConnected() {
        DriveSync.setConnected(this, true);
        DriveSync.schedule(this);
    }

    private void showDriveFailure(Exception e) {
        Log.w(TAG, "Could not connect to Google Drive", e);
        Toast.makeText(this, getString(R.string.drive_failed, e.getMessage()), Toast.LENGTH_LONG).show();
    }

    private static void toggle(View view) {
        view.setVisibility(view.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    /** The GPS icon is green when ready, amber when too inaccurate for distance, grey with no signal. */
    private void showStatus(float accuracyM, long fixElapsedMs, Double celsius, long timeOfDayWallMs) {
        boolean hasFix = fixElapsedMs > 0 && accuracyM != Float.MAX_VALUE
                && SystemClock.elapsedRealtime() - fixElapsedMs < TrackFilter.GAP_MS;
        gnssIcon.setColorFilter(!hasFix ? COLOR_NONE
                : accuracyM <= TrackFilter.MAX_ACCURACY_M ? COLOR_READY : COLOR_WEAK);
        gnssInfo.setText(hasFix ? getString(R.string.gnss_accuracy, Math.round(accuracyM))
                : getString(R.string.gnss_no_signal));

        if (celsius != null && Conditions.tempBand(celsius) == Conditions.TempBand.MILD) {
            temperatureIcon.setImageResource(R.drawable.ic_temperature_mild);
            temperatureIcon.clearColorFilter();
        } else {
            temperatureIcon.setImageResource(R.drawable.ic_temperature);
            temperatureIcon.setColorFilter(celsius == null ? COLOR_NONE : color(Conditions.tempBand(celsius)));
        }
        temperatureInfo.setText(celsius == null
                ? getString(R.string.no_temperature) : Conditions.temperature(celsius));

        timeOfDay.setText(icon(Conditions.timeOfDay(timeOfDayWallMs)));
    }

    /** Runs only while this screen is showing with no active run, so it costs little battery. */
    @SuppressLint("MissingPermission") // The only caller that turns it on checks the permission.
    private void setIdleGps(boolean on) {
        if (on == idleGpsOn) {
            return;
        }
        idleGpsOn = on;
        LocationManager manager = getSystemService(LocationManager.class);
        if (on) {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, IDLE_FIX_INTERVAL_MS, 0f,
                    idleListener, Looper.getMainLooper());
            Location recent = WeatherClient.recentLastKnownLocation(this);
            if (recent != null) {
                fetchIdleTemperature(recent.getLatitude(), recent.getLongitude());
            }
        } else {
            manager.removeUpdates(idleListener);
        }
    }

    private void fetchIdleTemperature(double lat, double lon) {
        long now = SystemClock.elapsedRealtime();
        boolean fresh = idleTemperatureC != null && now - idleTemperatureAtMs < IDLE_TEMPERATURE_MAX_AGE_MS;
        boolean triedRecently = idleWeatherAttemptMs != 0 && now - idleWeatherAttemptMs < IDLE_WEATHER_RETRY_MS;
        if (fresh || idleWeatherInFlight || triedRecently) {
            return;
        }
        idleWeatherInFlight = true;
        idleWeatherAttemptMs = now;
        new Thread(() -> {
            Double celsius = null;
            try {
                celsius = WeatherClient.currentCelsius(lat, lon);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "Could not fetch the temperature", e);
            }
            Double result = celsius;
            handler.post(() -> {
                idleWeatherInFlight = false;
                if (result != null) {
                    idleTemperatureC = result;
                    idleTemperatureAtMs = SystemClock.elapsedRealtime();
                }
            });
        }).start();
    }

    private void showPace(double secondsPerKm) {
        pace.setText(Double.isNaN(secondsPerKm) ? "" : getString(R.string.avg_pace, Export.pace(secondsPerKm)));
    }

    private void loadHistory() {
        runs = db.finishedRuns();
        lastRunAverage = runs.isEmpty() ? Double.NaN : averagePace(runs.get(0));
        DateFormat date = new SimpleDateFormat(DATE_PATTERN, Locale.US);
        history.setAdapter(new ArrayAdapter<RunRecord>(this, R.layout.item_run, R.id.run_text, runs) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                RunRecord r = getItem(position);
                TextView text = row.findViewById(R.id.run_text);
                double average = averagePace(r);
                text.setText("#" + r.id + " " + date.format(new Date(r.startWallMs)) + " "
                        + Export.km(r.distanceM) + " " + getString(R.string.km) + " "
                        + (Double.isNaN(average) ? getString(R.string.no_temperature)
                                : Export.pace(average) + " /km"));
                TextView runWeather = row.findViewById(R.id.run_weather);
                runWeather.setText(weatherText(r.temperatureC, r.startWallMs));
                return row;
            }
        });
    }

    /** The temperature coloured by how it feels, followed by the time-of-day icon. */
    private CharSequence weatherText(Double celsius, long startWallMs) {
        String temperature = celsius == null
                ? getString(R.string.no_temperature) : Conditions.temperature(celsius);
        SpannableString text = new SpannableString(
                temperature + " " + icon(Conditions.timeOfDay(startWallMs)));
        if (celsius != null) {
            text.setSpan(new ForegroundColorSpan(color(Conditions.tempBand(celsius))),
                    0, temperature.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    private static String icon(Conditions.TimeOfDay timeOfDay) {
        switch (timeOfDay) {
            case MORNING:
                return "🌅"; // Sunrise
            case NOON:
                return "☀️"; // Sun
            case EVENING:
                return "🌆"; // Dusk
            default:
                return "🌙"; // Crescent moon
        }
    }

    private static int color(Conditions.TempBand band) {
        switch (band) {
            case COLD:
                return COLOR_COLD;
            case HOT:
                return COLOR_HOT;
            default:
                return COLOR_MILD;
        }
    }

    /** Worked out from the run's points the first time a row needs it. Finished runs don't change. */
    private double averagePace(RunRecord run) {
        Double cached = paceByRun.get(run.id);
        if (cached != null) {
            return cached;
        }
        SplitTracker splits = new SplitTracker();
        for (TrackPoint p : db.points(run.id)) {
            splits.onPoint(p);
        }
        double average = splits.averageSecondsPerKm();
        paceByRun.put(run.id, average);
        return average;
    }

    private void startRunIfAllowed() {
        if (!hasLocationPermission()) {
            requestPermissionsIfNeeded();
            Toast.makeText(this, R.string.need_location, Toast.LENGTH_LONG).show();
            return;
        }
        if (!getSystemService(LocationManager.class).isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, R.string.turn_on_gps, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        RunService.send(this, RunService.ACTION_START);
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionsIfNeeded() {
        List<String> needed = new ArrayList<>();
        if (!hasLocationPermission()) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    // Without this exemption, some phones stop background tracking mid-run.
    @SuppressLint("BatteryLife")
    private void askBatteryExemptionOnce() {
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        if (prefs.getBoolean(PREF_ASKED_BATTERY, false)
                || getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        prefs.edit().putBoolean(PREF_ASKED_BATTERY, true).apply();
        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())));
    }

    private void confirmDelete(RunRecord run) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.delete_run_title, run.id))
                .setMessage(R.string.delete_run_message)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    db.deleteRun(run.id);
                    paceByRun.remove(run.id);
                    loadHistory();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String summaryText(RunRecord run) {
        double average = averagePace(run);
        String pace = Double.isNaN(average) ? getString(R.string.no_temperature) : Export.pace(average);
        StringBuilder text = new StringBuilder()
                .append(new SimpleDateFormat(DATE_PATTERN, Locale.US).format(new Date(run.startWallMs)))
                .append("\n\n").append(getString(R.string.summary_time, Export.duration(run.durationMs)))
                .append('\n').append(getString(R.string.summary_distance, Export.km(run.distanceM)))
                .append('\n').append(getString(R.string.avg_pace, pace))
                .append('\n').append(getString(R.string.summary_temperature, run.temperatureC == null
                        ? getString(R.string.no_temperature) : Conditions.temperature(run.temperatureC)));
        if (run.estimatedM > 0) {
            text.append('\n').append(getString(R.string.summary_estimated, Export.km(run.estimatedM)));
        }
        return text.toString();
    }

    private void showSummary(RunRecord run) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.summary_title, run.id))
                .setMessage(summaryText(run))
                .setPositiveButton(R.string.export_gpx, (dialog, which) -> {
                    gpxRunId = run.id;
                    String name = new SimpleDateFormat("'run-'yyyy-MM-dd-HHmm'.gpx'", Locale.US)
                            .format(new Date(run.startWallMs));
                    createDocument("application/gpx+xml", name, REQUEST_EXPORT_GPX);
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void createDocument(String mimeType, String name, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DRIVE) {
            if (resultCode == RESULT_OK) {
                try {
                    Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data);
                    onDriveConnected();
                } catch (ApiException e) {
                    showDriveFailure(e);
                }
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        try (OutputStream out = getContentResolver().openOutputStream(data.getData());
             Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            if (requestCode == REQUEST_EXPORT_CSV) {
                Export.csv(db.finishedRuns(), writer);
            } else if (requestCode == REQUEST_EXPORT_GPX) {
                for (RunRecord run : db.finishedRuns()) {
                    if (run.id == gpxRunId) {
                        Export.gpx(run, db.points(run.id), writer);
                    }
                }
            }
            Toast.makeText(this, R.string.exported, Toast.LENGTH_SHORT).show();
        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show();
        }
    }
}
