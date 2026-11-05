package app.runtracker;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

import com.google.android.gms.auth.api.identity.Identity;

import org.json.JSONException;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import app.runtracker.core.Export;
import app.runtracker.core.RunRecord;

/** Uploads each finished run that isn't on Drive yet, then refreshes the CSV of all runs. */
public final class UploadJobService extends JobService {
    private static final String TAG = "UploadJobService";
    private static final String FOLDER = "Run Tracker";

    @Override
    public boolean onStartJob(JobParameters params) {
        Identity.getAuthorizationClient(this).authorize(DriveSync.authorizationRequest())
                .addOnSuccessListener(result -> {
                    if (result.hasResolution()) {
                        // Permission is needed again, which only the screen can ask for.
                        DriveSync.setConnected(this, false);
                        jobFinished(params, false);
                    } else {
                        String token = result.getAccessToken();
                        new Thread(() -> jobFinished(params, !uploadPending(token))).start();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Could not get Drive access", e);
                    DriveSync.setLastError(this, e.toString());
                    jobFinished(params, true);
                });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    /** Returns false if the job should retry later. Runs finished so far stay marked as uploaded. */
    private boolean uploadPending(String token) {
        try (RunDatabase db = new RunDatabase(this)) {
            List<RunRecord> pending = db.runsToUpload();
            if (pending.isEmpty()) {
                DriveSync.setLastError(this, null);
                return true;
            }
            DriveClient drive = new DriveClient(token);
            String folderId = drive.ensureFolder(FOLDER);
            SimpleDateFormat fileTime = new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US);
            for (RunRecord run : pending) {
                StringWriter gpx = new StringWriter();
                Export.gpx(run, db.points(run.id), gpx);
                String name = run.mode.label + "-" + fileTime.format(new Date(run.startWallMs)) + ".gpx";
                drive.createOrReplace(folderId, name,
                        "application/gpx+xml", gpx.toString().getBytes(StandardCharsets.UTF_8));
                db.markUploaded(run.id);
            }
            StringWriter csv = new StringWriter();
            Export.csv(db.finishedRuns(), csv);
            drive.createOrReplace(folderId, "runs.csv", "text/csv",
                    csv.toString().getBytes(StandardCharsets.UTF_8));
            DriveSync.setLastError(this, null);
            return true;
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "Upload failed, will retry", e);
            DriveSync.setLastError(this, e.toString());
            return false;
        }
    }
}
