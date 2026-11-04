package app.runtracker;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.common.api.Scope;

import java.util.Collections;

/** Whether Google Drive is connected, and scheduling of the upload job. */
final class DriveSync {
    private static final int JOB_ID = 1;
    private static final String PREFS = "drive";
    private static final String KEY_CONNECTED = "connected";
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

    /** Uploads as soon as there is internet, surviving reboots. Does nothing until Drive is connected. */
    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (!isConnected(context) || scheduler.getPendingJob(JOB_ID) != null) {
            return;
        }
        scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, UploadJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
