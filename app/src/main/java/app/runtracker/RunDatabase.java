package app.runtracker;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

import app.runtracker.core.Fix;
import app.runtracker.core.RunRecord;
import app.runtracker.core.TrackPoint;

/** Runs and their points. Points are written as they arrive, so a crash loses nothing. */
final class RunDatabase extends SQLiteOpenHelper {
    private static final String RUN_COLUMNS =
            "id, start_wall, start_elapsed, boot_count, duration_ms, distance_m, estimated_m, temperature_c";

    RunDatabase(Context context) {
        super(context, "runs.db", null, 3);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE runs (id INTEGER PRIMARY KEY AUTOINCREMENT, start_wall INTEGER NOT NULL,"
                + " start_elapsed INTEGER NOT NULL, boot_count INTEGER NOT NULL,"
                + " duration_ms INTEGER NOT NULL DEFAULT 0, distance_m REAL NOT NULL DEFAULT 0,"
                + " estimated_m REAL NOT NULL DEFAULT 0, finished INTEGER NOT NULL DEFAULT 0,"
                + " temperature_c REAL, uploaded INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE points (run_id INTEGER NOT NULL, t_ms INTEGER NOT NULL, lat REAL NOT NULL,"
                + " lon REAL NOT NULL, accuracy REAL NOT NULL, cum_m REAL NOT NULL,"
                + " segment INTEGER NOT NULL, tainted INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX points_run ON points (run_id, t_ms)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE runs ADD COLUMN temperature_c REAL");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE runs ADD COLUMN uploaded INTEGER NOT NULL DEFAULT 0");
        }
    }

    long createRun(long startWallMs, long startElapsedMs, int bootCount) {
        ContentValues v = new ContentValues();
        v.put("start_wall", startWallMs);
        v.put("start_elapsed", startElapsedMs);
        v.put("boot_count", bootCount);
        return getWritableDatabase().insertOrThrow("runs", null, v);
    }

    void addPoint(long runId, TrackPoint p) {
        ContentValues v = new ContentValues();
        v.put("run_id", runId);
        v.put("t_ms", p.fix.tMs);
        v.put("lat", p.fix.lat);
        v.put("lon", p.fix.lon);
        v.put("accuracy", p.fix.accuracyM);
        v.put("cum_m", p.cumM);
        v.put("segment", p.segment);
        v.put("tainted", p.tainted ? 1 : 0);
        getWritableDatabase().insertOrThrow("points", null, v);
    }

    void updateProgress(long runId, double distanceM, double estimatedM) {
        ContentValues v = new ContentValues();
        v.put("distance_m", distanceM);
        v.put("estimated_m", estimatedM);
        update(runId, v);
    }

    void setTemperature(long runId, double celsius) {
        ContentValues v = new ContentValues();
        v.put("temperature_c", celsius);
        update(runId, v);
    }

    void finishRun(long runId, long durationMs) {
        ContentValues v = new ContentValues();
        v.put("duration_ms", durationMs);
        v.put("finished", 1);
        update(runId, v);
    }

    private void update(long runId, ContentValues v) {
        getWritableDatabase().update("runs", v, "id = ?", new String[]{Long.toString(runId)});
    }

    void markUploaded(long runId) {
        ContentValues v = new ContentValues();
        v.put("uploaded", 1);
        update(runId, v);
    }

    List<RunRecord> runsToUpload() {
        List<RunRecord> runs = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + RUN_COLUMNS + " FROM runs WHERE finished = 1 AND uploaded = 0 ORDER BY id", null)) {
            while (c.moveToNext()) {
                runs.add(readRun(c));
            }
        }
        return runs;
    }

    int uploadsWaiting() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM runs WHERE finished = 1 AND uploaded = 0", null)) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    void deleteRun(long runId) {
        String[] args = {Long.toString(runId)};
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("points", "run_id = ?", args);
            db.delete("runs", "id = ?", args);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** The unfinished run, if the app stopped while one was in progress. */
    RunRecord activeRun() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + RUN_COLUMNS + " FROM runs WHERE finished = 0 ORDER BY id DESC LIMIT 1", null)) {
            return c.moveToFirst() ? readRun(c) : null;
        }
    }

    List<RunRecord> finishedRuns() {
        List<RunRecord> runs = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + RUN_COLUMNS + " FROM runs WHERE finished = 1 ORDER BY id DESC", null)) {
            while (c.moveToNext()) {
                runs.add(readRun(c));
            }
        }
        return runs;
    }

    List<TrackPoint> points(long runId) {
        List<TrackPoint> points = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT t_ms, lat, lon, accuracy, cum_m, segment, tainted FROM points"
                        + " WHERE run_id = ? ORDER BY rowid", new String[]{Long.toString(runId)})) {
            while (c.moveToNext()) {
                Fix fix = new Fix(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getFloat(3));
                points.add(new TrackPoint(fix, c.getDouble(4), c.getInt(5), c.getInt(6) != 0));
            }
        }
        return points;
    }

    private static RunRecord readRun(Cursor c) {
        return new RunRecord(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3),
                c.getLong(4), c.getDouble(5), c.getDouble(6), c.isNull(7) ? null : c.getDouble(7));
    }
}
