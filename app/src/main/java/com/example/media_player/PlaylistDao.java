package com.example.media_player;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class PlaylistDao {

    private static final String TAG = "PlaylistDao";

    private final MatrixPlayerDatabase dbHelper;
    private final SearchDao searchDao;

    public PlaylistDao(MatrixPlayerDatabase dbHelper) {
        this.dbHelper = dbHelper;
        this.searchDao = new SearchDao(dbHelper);
    }

    // -- Create --

    /** Create a manual playlist. Returns the new playlist ID. */
    public long createPlaylist(String name, String description) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("description", description);
        cv.put("is_smart", 0);
        long now = System.currentTimeMillis();
        cv.put("created_at", now);
        cv.put("updated_at", now);
        long id = db.insert("playlists", null, cv);
        Log.d(TAG, "createPlaylist: id=" + id + " name=" + name);
        return id;
    }

    /** Create a smart playlist backed by a SearchCriteria query. Returns the new playlist ID. */
    public long createSmartPlaylist(String name, SearchCriteria criteria) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("is_smart", 1);
        cv.put("smart_query", criteria.toJson());
        long now = System.currentTimeMillis();
        cv.put("created_at", now);
        cv.put("updated_at", now);
        long id = db.insert("playlists", null, cv);
        Log.d(TAG, "createSmartPlaylist: id=" + id + " name=" + name);
        return id;
    }

    // -- Delete / Rename --

    public void deletePlaylist(long playlistId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        // playlist_tracks rows cascade-deleted via FK
        db.delete("playlists", "id = ?", new String[]{String.valueOf(playlistId)});
    }

    public void renamePlaylist(long playlistId, String name) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("updated_at", System.currentTimeMillis());
        db.update("playlists", cv, "id = ?", new String[]{String.valueOf(playlistId)});
    }

    // -- Query --

    /** Get all playlists (manual and smart). */
    public List<PlaylistInfo> getAllPlaylists() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<PlaylistInfo> playlists = new ArrayList<>();
        Cursor c = db.query("playlists", null, null, null, null, null, "name COLLATE NOCASE ASC");
        try {
            while (c.moveToNext()) {
                playlists.add(cursorToPlaylistInfo(c));
            }
        } finally {
            c.close();
        }
        return playlists;
    }

    /** Get tracks for a manual playlist, ordered by position. */
    public List<Track> getPlaylistTracks(long playlistId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<Track> tracks = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT t.* FROM playlist_tracks pt"
                + " JOIN tracks t ON t.id = pt.track_id"
                + " WHERE pt.playlist_id = ?"
                + " ORDER BY pt.position ASC",
                new String[]{String.valueOf(playlistId)});
        try {
            while (c.moveToNext()) {
                tracks.add(TrackDao.cursorToTrack(c));
            }
        } finally {
            c.close();
        }
        return tracks;
    }

    /** Get tracks for a smart playlist by running its stored SearchCriteria. */
    public List<Track> getSmartPlaylistTracks(long playlistId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT smart_query FROM playlists WHERE id = ? AND is_smart = 1",
                new String[]{String.valueOf(playlistId)});
        String json = null;
        try {
            if (c.moveToFirst()) json = c.getString(0);
        } finally {
            c.close();
        }
        if (json == null || json.isEmpty()) return new ArrayList<>();

        SearchCriteria criteria = SearchCriteria.fromJson(json);
        return searchDao.advancedSearch(criteria);
    }

    // -- Track management (manual playlists) --

    /** Append a track at the end of a manual playlist. */
    public void addTrackToPlaylist(long playlistId, long trackId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.rawQuery(
                    "SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlist_id = ?",
                    new String[]{String.valueOf(playlistId)});
            int maxPos = -1;
            try {
                if (c.moveToFirst()) maxPos = c.getInt(0);
            } finally {
                c.close();
            }

            ContentValues cv = new ContentValues();
            cv.put("playlist_id", playlistId);
            cv.put("track_id", trackId);
            cv.put("position", maxPos + 1);
            cv.put("added_at", System.currentTimeMillis());
            db.insert("playlist_tracks", null, cv);

            updatePlaylistCounts(db, playlistId);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Remove a track from a manual playlist. Compacts positions. */
    public void removeTrackFromPlaylist(long playlistId, long trackId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.rawQuery(
                    "SELECT position FROM playlist_tracks WHERE playlist_id = ? AND track_id = ?",
                    new String[]{String.valueOf(playlistId), String.valueOf(trackId)});
            int pos = -1;
            try {
                if (c.moveToFirst()) pos = c.getInt(0);
            } finally {
                c.close();
            }
            if (pos < 0) {
                db.setTransactionSuccessful();
                return;
            }

            db.delete("playlist_tracks", "playlist_id = ? AND track_id = ?",
                    new String[]{String.valueOf(playlistId), String.valueOf(trackId)});

            // Compact positions above the removed one
            db.execSQL("UPDATE playlist_tracks SET position = position - 1"
                    + " WHERE playlist_id = ? AND position > ?",
                    new Object[]{playlistId, pos});

            updatePlaylistCounts(db, playlistId);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Move a track from one position to another within a manual playlist. */
    public void reorderPlaylistTrack(long playlistId, int fromPos, int toPos) {
        if (fromPos == toPos) return;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            // Park the moving track at a temporary position
            db.execSQL("UPDATE playlist_tracks SET position = -1"
                    + " WHERE playlist_id = ? AND position = ?",
                    new Object[]{playlistId, fromPos});

            if (fromPos < toPos) {
                // Moving down: shift items in (fromPos, toPos] up by 1
                db.execSQL("UPDATE playlist_tracks SET position = position - 1"
                        + " WHERE playlist_id = ? AND position > ? AND position <= ?",
                        new Object[]{playlistId, fromPos, toPos});
            } else {
                // Moving up: shift items in [toPos, fromPos) down by 1
                db.execSQL("UPDATE playlist_tracks SET position = position + 1"
                        + " WHERE playlist_id = ? AND position >= ? AND position < ?",
                        new Object[]{playlistId, toPos, fromPos});
            }

            // Place the track at its new position
            db.execSQL("UPDATE playlist_tracks SET position = ?"
                    + " WHERE playlist_id = ? AND position = -1",
                    new Object[]{toPos, playlistId});

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Recalculate track_count and total_duration for a playlist. */
    public void updatePlaylistCounts(long playlistId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        updatePlaylistCounts(db, playlistId);
    }

    // -- Internal --

    private void updatePlaylistCounts(SQLiteDatabase db, long playlistId) {
        db.execSQL("UPDATE playlists SET"
                + " track_count = (SELECT COUNT(*) FROM playlist_tracks WHERE playlist_id = ?),"
                + " total_duration = COALESCE((SELECT SUM(t.duration_ms)"
                + "   FROM playlist_tracks pt JOIN tracks t ON t.id = pt.track_id"
                + "   WHERE pt.playlist_id = ?), 0),"
                + " updated_at = ?"
                + " WHERE id = ?",
                new Object[]{playlistId, playlistId, System.currentTimeMillis(), playlistId});
    }

    private PlaylistInfo cursorToPlaylistInfo(Cursor c) {
        PlaylistInfo info = new PlaylistInfo();
        info.id = c.getLong(c.getColumnIndexOrThrow("id"));
        info.name = c.getString(c.getColumnIndexOrThrow("name"));
        info.description = c.getString(c.getColumnIndexOrThrow("description"));
        info.isSmart = c.getInt(c.getColumnIndexOrThrow("is_smart")) == 1;
        info.trackCount = c.getInt(c.getColumnIndexOrThrow("track_count"));
        info.totalDuration = c.getLong(c.getColumnIndexOrThrow("total_duration"));
        info.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return info;
    }
}
