package com.example.media_player;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class QueueDao {

    private static final String TAG = "QueueDao";

    private final MatrixPlayerDatabase dbHelper;

    public QueueDao(MatrixPlayerDatabase dbHelper) {
        this.dbHelper = dbHelper;
    }

    /**
     * Save the current playback queue and state.
     *
     * @param queue        the current queue of tracks
     * @param currentIndex index of the currently playing track (-1 if none)
     * @param positionMs   current playback position in ms
     * @param isPlaying    whether playback is active
     */
    public void saveQueue(List<Track> queue, int currentIndex, int positionMs, boolean isPlaying) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            // Clear old queue
            db.delete("playback_queue", null, null);

            // Insert queue entries
            for (int i = 0; i < queue.size(); i++) {
                ContentValues cv = new ContentValues();
                cv.put("position", i);
                cv.put("track_id", queue.get(i).id);
                db.insert("playback_queue", null, cv);
            }

            // Update singleton playback state
            ContentValues state = new ContentValues();
            state.put("id", 1);
            state.put("current_index", currentIndex);
            state.put("position_ms", positionMs);
            state.put("is_playing", isPlaying ? 1 : 0);
            state.put("updated_at", System.currentTimeMillis());
            db.insertWithOnConflict("playback_state", null, state, SQLiteDatabase.CONFLICT_REPLACE);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        Log.d(TAG, "saveQueue: " + queue.size() + " tracks, index=" + currentIndex
                + " pos=" + positionMs + "ms");
    }

    /**
     * Restore the saved queue. Returns null if no queue is saved.
     */
    public SavedQueue restoreQueue() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // Read playback state
        int currentIndex = -1;
        int positionMs = 0;
        Cursor sc = db.rawQuery("SELECT current_index, position_ms FROM playback_state WHERE id = 1", null);
        try {
            if (sc.moveToFirst()) {
                currentIndex = sc.getInt(0);
                positionMs = sc.getInt(1);
            } else {
                return null;
            }
        } finally {
            sc.close();
        }

        if (currentIndex < 0) return null;

        // Read queue entries and join with tracks
        List<Track> queue = new ArrayList<>();
        Cursor qc = db.rawQuery(
                "SELECT t.* FROM playback_queue pq"
                + " JOIN tracks t ON t.id = pq.track_id"
                + " ORDER BY pq.position ASC", null);
        try {
            while (qc.moveToNext()) {
                queue.add(TrackDao.cursorToTrack(qc));
            }
        } finally {
            qc.close();
        }

        if (queue.isEmpty()) return null;

        // Clamp index in case queue shrank
        if (currentIndex >= queue.size()) {
            currentIndex = queue.size() - 1;
        }

        SavedQueue result = new SavedQueue();
        result.queue = queue;
        result.currentIndex = currentIndex;
        result.positionMs = positionMs;
        Log.d(TAG, "restoreQueue: " + queue.size() + " tracks, index=" + currentIndex
                + " pos=" + positionMs + "ms");
        return result;
    }

    /** Clear the saved queue. */
    public void clearQueue() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("playback_queue", null, null);
            ContentValues state = new ContentValues();
            state.put("id", 1);
            state.put("current_index", -1);
            state.put("position_ms", 0);
            state.put("is_playing", 0);
            state.put("updated_at", System.currentTimeMillis());
            db.insertWithOnConflict("playback_state", null, state, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        Log.d(TAG, "clearQueue");
    }

    public static class SavedQueue {
        public List<Track> queue;
        public int currentIndex;
        public int positionMs;
    }
}
