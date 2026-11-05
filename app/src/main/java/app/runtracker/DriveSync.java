package app.runtracker;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.common.api.Scope;

import java.util.Collections;

/** Whether Google Drive is connected, and scheduling of the upload job. */
final class DriveSync {
    private static final String TAG = "DriveSync";
    private static final int JOB_ID = 1;
    private static final String PREFS = "drive";
    private static final String KEY_CONNECTED = "connected";
    private static final String KEY_ERROR = "lastError";
    /** Only gives access to files this app created, not the rest of the user's Drive. */
    private static final String SCOPE = "https://www.googleapis.com/auth/drive.file";

    private DriveSync() {
    }

    static AuthorizationRequest authorizationRequest() {
        return AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(SCOPE)))
                .build();
    }

    static boolean isConnected(Context context) {
        return prefs(context).getBoolean(KEY_CONNECTED, false);
    }

    static void setConnected(Context context, boolean connected) {
        prefs(context).edit().putBoolean(KEY_CONNECTED, connected).apply();
    }

    /** Why the last upload attempt failed, or null if it worked or none has failed yet. */
    static String lastError(Context context) {
        return prefs(context).getString(KEY_ERROR, null);
    }

    static void setLastError(Context context, String error) {
        prefs(context).edit().putString(KEY_ERROR, error).apply();
    }

    /** Skips any waiting period left over from a failed attempt. */
    static void retryNow(Context context) {
        context.getSystemService(JobScheduler.class).cancel(JOB_ID);
        schedule(context);
    }

    /** Uploads as soon as there is internet, surviving reboots. Does nothing until Drive is connected. */
    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (!isConnected(context) || scheduler.getPendingJob(JOB_ID) != null) {
            return;
        }
        // A backup problem must never stop the app or a run; the next launch or run tries again.
        try {
            scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, UploadJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build());
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not schedule the upload", e);
            setLastError(context, e.toString());
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
