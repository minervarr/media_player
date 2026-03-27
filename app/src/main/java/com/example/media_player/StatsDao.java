package com.example.media_player;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class StatsDao {

    private static final String TAG = "StatsDao";

    /** Threshold: a play counts as "completed" if >= 80% of track duration was played. */
    private static final double COMPLETION_THRESHOLD = 0.80;

    private final MatrixPlayerDatabase dbHelper;

    public StatsDao(MatrixPlayerDatabase dbHelper) {
        this.dbHelper = dbHelper;
    }

    /**
     * Record the start of a playback event. Returns the play_history row ID
     * to pass to recordPlayEnd().
     */
    public long recordPlayStart(Track track, String outputDevice) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("track_id", track.id);
        cv.put("source", track.source == Track.Source.TIDAL ? 1 : 0);
        cv.put("started_at", System.currentTimeMillis());
        cv.put("output_device", outputDevice);
        long rowId = db.insert("play_history", null, cv);
        Log.d(TAG, "recordPlayStart: track=" + track.id + " historyId=" + rowId);
        return rowId;
    }

    /**
     * Record the end of a playback event. Updates play_history and track_stats.
     *
     * @param historyId    the row ID from recordPlayStart()
     * @param durationPlayedMs how long the user listened (ms)
     * @param trackDurationMs  total track duration (ms), used for completion check
     * @param sampleRate   sample rate during playback (0 if unknown)
     * @param bitDepth     bit depth during playback (0 if unknown)
     * @param tidalQuality Tidal quality string, or null
     */
    public void recordPlayEnd(long historyId, long durationPlayedMs, long trackDurationMs,
                              int sampleRate, int bitDepth, String tidalQuality) {
        if (historyId < 0) return;

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean completed = trackDurationMs > 0
                && durationPlayedMs >= (long) (trackDurationMs * COMPLETION_THRESHOLD);

        db.beginTransaction();
        try {
            // Update play_history row
            ContentValues hcv = new ContentValues();
            hcv.put("duration_played", durationPlayedMs);
            hcv.put("completed", completed ? 1 : 0);
            hcv.put("sample_rate", sampleRate);
            hcv.put("bit_depth", bitDepth);
            hcv.put("tidal_quality", tidalQuality);
            db.update("play_history", hcv, "id = ?", new String[]{String.valueOf(historyId)});

            // Get track_id from history row
            long trackId = -1;
            Cursor c = db.rawQuery("SELECT track_id FROM play_history WHERE id = ?",
                    new String[]{String.valueOf(historyId)});
            try {
                if (c.moveToFirst()) trackId = c.getLong(0);
            } finally {
                c.close();
            }
            if (trackId < 0) {
                db.setTransactionSuccessful();
                return;
            }

            // Update track_stats
            long now = System.currentTimeMillis();
            boolean isSkip = !completed && durationPlayedMs < 10000; // <10s = skip

            // Ensure row exists
            db.execSQL("INSERT OR IGNORE INTO track_stats (track_id) VALUES (?)",
                    new Object[]{trackId});

            if (completed) {
                db.execSQL("UPDATE track_stats SET"
                        + " play_count = play_count + 1,"
                        + " total_listen_ms = total_listen_ms + ?,"
                        + " last_played_at = ?,"
                        + " first_played_at = COALESCE(first_played_at, ?)"
                        + " WHERE track_id = ?",
                        new Object[]{durationPlayedMs, now, now, trackId});
            } else if (isSkip) {
                db.execSQL("UPDATE track_stats SET"
                        + " skip_count = skip_count + 1,"
                        + " total_listen_ms = total_listen_ms + ?,"
                        + " last_played_at = ?"
                        + " WHERE track_id = ?",
                        new Object[]{durationPlayedMs, now, trackId});
            } else {
                // Partial listen (>10s but <80%)
                db.execSQL("UPDATE track_stats SET"
                        + " total_listen_ms = total_listen_ms + ?,"
                        + " last_played_at = ?,"
                        + " first_played_at = COALESCE(first_played_at, ?)"
                        + " WHERE track_id = ?",
                        new Object[]{durationPlayedMs, now, now, trackId});
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        Log.d(TAG, "recordPlayEnd: historyId=" + historyId
                + " played=" + durationPlayedMs + "ms completed=" + completed);
    }

    // -- Query methods --

    /** Most played tracks, ordered by play_count DESC. */
    public List<Track> getMostPlayed(int limit) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<Track> results = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT t.* FROM tracks t"
                + " JOIN track_stats ts ON ts.track_id = t.id"
                + " WHERE ts.play_count > 0"
                + " ORDER BY ts.play_count DESC"
                + " LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                results.add(TrackDao.cursorToTrack(c));
            }
        } finally {
            c.close();
        }
        return results;
    }

    /** Recently played tracks (most recent first), grouped by track. */
    public List<Track> getRecentlyPlayed(int limit) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<Track> results = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT t.* FROM tracks t"
                + " JOIN (SELECT track_id, MAX(started_at) AS last_start"
                + "       FROM play_history GROUP BY track_id) ph"
                + " ON ph.track_id = t.id"
                + " ORDER BY ph.last_start DESC"
                + " LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                results.add(TrackDao.cursorToTrack(c));
            }
        } finally {
            c.close();
        }
        return results;
    }

    /** Total listen time in ms within a time range (epoch ms). */
    public long getTotalListenTime(long fromMs, long toMs) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT COALESCE(SUM(duration_played), 0) FROM play_history"
                + " WHERE started_at BETWEEN ? AND ?",
                new String[]{String.valueOf(fromMs), String.valueOf(toMs)});
        try {
            if (c.moveToFirst()) return c.getLong(0);
        } finally {
            c.close();
        }
        return 0;
    }

    /** Total listen time across all history. */
    public long getTotalListenTime() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT COALESCE(SUM(duration_played), 0) FROM play_history", null);
        try {
            if (c.moveToFirst()) return c.getLong(0);
        } finally {
            c.close();
        }
        return 0;
    }

    /** Most played artists: returns list of {artist, totalPlayCount}. */
    public List<ArtistPlayCount> getMostPlayedArtists(int limit) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<ArtistPlayCount> results = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT t.artist, SUM(ts.play_count) AS total"
                + " FROM tracks t"
                + " JOIN track_stats ts ON ts.track_id = t.id"
                + " WHERE ts.play_count > 0"
                + " GROUP BY t.artist"
                + " ORDER BY total DESC"
                + " LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                ArtistPlayCount apc = new ArtistPlayCount();
                apc.artist = c.getString(0);
                apc.totalPlayCount = c.getInt(1);
                results.add(apc);
            }
        } finally {
            c.close();
        }
        return results;
    }

    /** Get play count for a specific track. */
    public int getPlayCount(long trackId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT play_count FROM track_stats WHERE track_id = ?",
                new String[]{String.valueOf(trackId)});
        try {
            if (c.moveToFirst()) return c.getInt(0);
        } finally {
            c.close();
        }
        return 0;
    }

    // -- Rating --

    /** Set rating for a track (0=unrated, 1-5). */
    public void setRating(long trackId, int rating) {
        if (rating < 0 || rating > 5) return;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.execSQL("INSERT OR IGNORE INTO track_stats (track_id) VALUES (?)",
                new Object[]{trackId});
        ContentValues cv = new ContentValues();
        cv.put("rating", rating);
        db.update("track_stats", cv, "track_id = ?",
                new String[]{String.valueOf(trackId)});
    }

    /** Get rating for a track (0 if unrated or no stats row). */
    public int getRating(long trackId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT rating FROM track_stats WHERE track_id = ?",
                new String[]{String.valueOf(trackId)});
        try {
            if (c.moveToFirst()) return c.getInt(0);
        } finally {
            c.close();
        }
        return 0;
    }

    /** Get all tracks with rating >= minRating. */
    public List<Track> getTracksByRating(int minRating) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<Track> results = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT t.* FROM tracks t"
                + " JOIN track_stats ts ON ts.track_id = t.id"
                + " WHERE ts.rating >= ?"
                + " ORDER BY ts.rating DESC, t.title COLLATE NOCASE ASC",
                new String[]{String.valueOf(minRating)});
        try {
            while (c.moveToNext()) {
                results.add(TrackDao.cursorToTrack(c));
            }
        } finally {
            c.close();
        }
        return results;
    }

    public static class ArtistPlayCount {
        public String artist;
        public int totalPlayCount;
    }
}
