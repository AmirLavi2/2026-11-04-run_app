package app.runtracker;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Saves the details of a crash, so the next launch can show them even with no USB connection. */
public final class RunTrackerApp extends Application {
    private static final String TAG = "RunTrackerApp";
    private static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            String report = "v" + BuildConfig.VERSION_NAME + " on thread " + thread.getName() + "\n"
                    + Log.getStackTraceString(error);
            try {
                Files.write(new File(getFilesDir(), CRASH_FILE).toPath(), report.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.w(TAG, "Could not save the crash report", e);
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    /** Returns the saved crash report, or null. It stays saved until clearCrashReport is called. */
    static String readCrashReport(Context context) {
        File file = new File(context.getFilesDir(), CRASH_FILE);
        if (!file.exists()) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "Could not read the crash report", e);
            return null;
        }
    }

    static void clearCrashReport(Context context) {
        new File(context.getFilesDir(), CRASH_FILE).delete();
    }
}
