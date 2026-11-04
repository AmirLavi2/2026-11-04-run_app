package app.runtracker;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONException;

import java.io.IOException;
import java.util.List;

import app.runtracker.core.Export;
import app.runtracker.core.Fix;
import app.runtracker.core.RunRecord;
import app.runtracker.core.SplitTracker;
import app.runtracker.core.TrackFilter;
import app.runtracker.core.TrackPoint;

/** Tracks the run in the foreground so it continues with the screen locked. */
public final class RunService extends Service {
    static final String ACTION_START = "app.runtracker.START";
    static final String ACTION_STOP = "app.runtracker.STOP";
    static final String ACTION_RESUME = "app.runtracker.RESUME";

    /** What the screen shows. Null means no run is being tracked. */
    static final class Snapshot {
        final long startElapsedMs;
        final double distanceM;
        final boolean hadFix;
        final long lastGoodElapsedMs;
        /** NaN until a clean 400 m has been completed. */
        final double averageSecondsPerKm;
        final long startWallMs;
        /** Null until the temperature has been fetched. */
        final Double temperatureC;
        /** From the newest reading of any quality; latestFixElapsedMs is 0 before the first one. */
        final float latestAccuracyM;
        final long latestFixElapsedMs;

        Snapshot(long startElapsedMs, double distanceM, boolean hadFix, long lastGoodElapsedMs,
                 double averageSecondsPerKm, long startWallMs, Double temperatureC,
                 float latestAccuracyM, long latestFixElapsedMs) {
            this.latestAccuracyM = latestAccuracyM;
            this.latestFixElapsedMs = latestFixElapsedMs;
            this.startWallMs = startWallMs;
            this.temperatureC = temperatureC;
            this.startElapsedMs = startElapsedMs;
            this.distanceM = distanceM;
            this.hadFix = hadFix;
            this.lastGoodElapsedMs = lastGoodElapsedMs;
            this.averageSecondsPerKm = averageSecondsPerKm;
        }

        boolean gpsOk() {
            return hadFix && SystemClock.elapsedRealtime() - lastGoodElapsedMs < TrackFilter.GAP_MS;
        }
    }

    static volatile Snapshot current;

    private static final String TAG = "RunService";
    private static final String CHANNEL = "run_status";
    private static final String OLD_CHANNEL = "active_run";
    private static final int NOTIFICATION_ID = 1;
    private static final long NOTIFICATION_INTERVAL_MS = 1_000;
    private static final long FIX_INTERVAL_MS = 1_000;
    private static final long WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L;
    private static final int MAX_WEATHER_ATTEMPTS = 10;
    private static final long WEATHER_RETRY_MS = 60_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable notificationTick = new Runnable() {
        @Override
        public void run() {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
            handler.postDelayed(this, NOTIFICATION_INTERVAL_MS);
        }
    };
    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            handleLocation(location);
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

    private RunDatabase db;
    private TonePlayer tones;
    private PowerManager.WakeLock wakeLock;
    private long runId = -1;
    private long startElapsedMs;
    private TrackFilter filter;
    private SplitTracker splits;
    private boolean hadFix;
    private long lastGoodElapsedMs;
    private long startWallMs;
    private Double temperatureC;
    private boolean weatherInFlight;
    private int weatherAttempts;
    private long lastWeatherAttemptMs;
    private float latestAccuracyM;
    private long latestFixElapsedMs;

    @Override
    public void onCreate() {
        super.onCreate();
        db = new RunDatabase(this);
        tones = new TonePlayer(this);
        // Low-importance notifications can be hidden on the lock screen. An existing channel's importance
        // can't be raised, so this replaces the old one. It stays silent: the only sounds are the pace tones.
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.deleteNotificationChannel(OLD_CHANNEL);
        notifications.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            finishCurrentRun();
            return START_NOT_STICKY;
        }
        if (runId != -1) {
            return START_STICKY;
        }

        // Android must get a foreground notification before anything else happens. It can refuse when
        // the system restarts us in the background; the screen resumes the run when it is next opened.
        try {
            startInForeground();
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "Could not enter the foreground", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        RunRecord active = db.activeRun();
        if (active != null) {
            resumeRun(active);
        } else if (ACTION_START.equals(action)) {
            startNewRun();
        } else {
            stopTracking();
        }
        return START_STICKY;
    }

    private void startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
    }

    private void startNewRun() {
        startElapsedMs = SystemClock.elapsedRealtime();
        startWallMs = System.currentTimeMillis();
        runId = db.createRun(startWallMs, startElapsedMs, bootCount());
        filter = new TrackFilter();
        splits = new SplitTracker();
        beginTracking();
    }

    private void resumeRun(RunRecord run) {
        List<TrackPoint> points = db.points(run.id);
        TrackPoint last = points.isEmpty() ? null : points.get(points.size() - 1);

        // The monotonic clock restarts after a reboot, so close the run at its last known moment.
        if (run.bootCount != bootCount()) {
            db.finishRun(run.id, last == null ? 0 : last.fix.tMs);
            DriveSync.schedule(this);
            stopTracking();
            return;
        }

        runId = run.id;
        startElapsedMs = run.startElapsedMs;
        startWallMs = run.startWallMs;
        temperatureC = run.temperatureC;
        filter = last == null ? new TrackFilter() : new TrackFilter(last, run.estimatedM);
        splits = new SplitTracker();
        for (TrackPoint p : points) {
            splits.onPoint(p);
        }
        beginTracking();
    }

    @SuppressLint("MissingPermission") // MainActivity only starts this service with permission granted.
    private void beginTracking() {
        hadFix = false;
        wakeLock = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RunTracker:run");
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        getSystemService(LocationManager.class).requestLocationUpdates(
                LocationManager.GPS_PROVIDER, FIX_INTERVAL_MS, 0f, listener, Looper.getMainLooper());
        publish();
        fetchTemperatureFromLastKnownLocation();
        handler.post(notificationTick);
    }

    private void handleLocation(Location location) {
        long fixElapsedMs = location.getElapsedRealtimeNanos() / 1_000_000L;
        long tMs = fixElapsedMs - startElapsedMs;
        if (tMs < 0) {
            return; // A stale reading from before the run started.
        }
        float accuracy = location.hasAccuracy() ? location.getAccuracy() : Float.MAX_VALUE;
        latestAccuracyM = accuracy;
        latestFixElapsedMs = fixElapsedMs;
        Fix fix = new Fix(tMs, location.getLatitude(), location.getLongitude(), accuracy);

        fetchTemperatureIfNeeded(fix.lat, fix.lon);
        TrackFilter.Result result = filter.onFix(fix);
        if (result.status != TrackFilter.Status.REJECTED && result.status != TrackFilter.Status.PENDING) {
            hadFix = true;
            lastGoodElapsedMs = fixElapsedMs;
        }
        if (!result.points.isEmpty()) {
            for (TrackPoint p : result.points) {
                db.addPoint(runId, p);
                for (SplitTracker.Cue cue : splits.onPoint(p)) {
                    tones.play(cue);
                }
            }
            db.updateProgress(runId, filter.totalM(), filter.estimatedM());
        }
        publish();
    }

    /**
     * Any fix will do, even one too inaccurate for distance, since temperature only needs a rough
     * location. A failed fetch never affects tracking; it retries a little later.
     */
    private void fetchTemperatureIfNeeded(double lat, double lon) {
        long now = SystemClock.elapsedRealtime();
        if (temperatureC != null || weatherInFlight || weatherAttempts >= MAX_WEATHER_ATTEMPTS
                || (weatherAttempts > 0 && now - lastWeatherAttemptMs < WEATHER_RETRY_MS)) {
            return;
        }
        weatherInFlight = true;
        weatherAttempts++;
        lastWeatherAttemptMs = now;
        long forRun = runId;
        new Thread(() -> {
            Double celsius = null;
            try {
                celsius = WeatherClient.currentCelsius(lat, lon);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "Could not fetch the temperature", e);
            }
            Double result = celsius;
            handler.post(() -> onTemperature(forRun, result));
        }).start();
    }

    private void onTemperature(long forRun, Double celsius) {
        weatherInFlight = false;
        if (celsius == null) {
            return;
        }
        db.setTemperature(forRun, celsius);
        if (forRun == runId) {
            temperatureC = celsius;
            publish();
        } else if (runId == -1) {
            db.close(); // The run was stopped first and the service is gone; the write reopened the database.
        }
    }

    /** Lets the temperature appear right after Start, before GPS has a fix. */
    private void fetchTemperatureFromLastKnownLocation() {
        Location recent = WeatherClient.recentLastKnownLocation(this);
        if (recent != null) {
            fetchTemperatureIfNeeded(recent.getLatitude(), recent.getLongitude());
        }
    }

    private void publish() {
        current = new Snapshot(startElapsedMs, filter.totalM(), hadFix, lastGoodElapsedMs,
                splits.averageSecondsPerKm(), startWallMs, temperatureC,
                latestAccuracyM, latestFixElapsedMs);
    }

    private void finishCurrentRun() {
        if (runId != -1) {
            db.finishRun(runId, SystemClock.elapsedRealtime() - startElapsedMs);
            DriveSync.schedule(this);
        }
        stopTracking();
    }

    private void stopTracking() {
        releaseTracking();
        runId = -1;
        current = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releaseTracking() {
        handler.removeCallbacks(notificationTick);
        getSystemService(LocationManager.class).removeUpdates(listener);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        // The run stays unfinished in the database unless the user stopped it, so it can be resumed.
        releaseTracking();
        runId = -1;
        current = null;
        db.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int bootCount() {
        return Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, 0);
    }

    private Notification buildNotification() {
        Snapshot snapshot = current;
        String title = snapshot == null ? getString(R.string.app_name)
                : Export.km(snapshot.distanceM) + " " + getString(R.string.km) + "  ·  "
                + Export.duration(SystemClock.elapsedRealtime() - snapshot.startElapsedMs);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_run)
                .setContentTitle(title)
                .setContentText(notificationDetail(snapshot))
                .setContentIntent(open)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    /** Average pace when it's reliable, then the GPS state: the same rules as the main screen. */
    private String notificationDetail(Snapshot snapshot) {
        if (snapshot == null || !snapshot.hadFix) {
            return getString(R.string.gps_waiting);
        }
        if (!snapshot.gpsOk()) {
            return getString(R.string.gps_lost);
        }
        String gps = getString(R.string.gps_ok);
        return Double.isNaN(snapshot.averageSecondsPerKm) ? gps
                : getString(R.string.avg_pace, Export.pace(snapshot.averageSecondsPerKm)) + "  ·  " + gps;
    }

    static void send(Context context, String action) {
        Intent intent = new Intent(context, RunService.class).setAction(action);
        if (ACTION_STOP.equals(action)) {
            context.startService(intent);
        } else {
            context.startForegroundService(intent);
        }
    }
}
