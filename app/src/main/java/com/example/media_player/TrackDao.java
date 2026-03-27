package com.example.media_player;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.Log;

import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TrackDao {

    private static final String TAG = "TrackDao";

    private final MatrixPlayerDatabase dbHelper;

    public TrackDao(MatrixPlayerDatabase dbHelper) {
        this.dbHelper = dbHelper;
    }

    // -- Scan cache --

    /** Returns map of file_path -> {file_size, file_last_modified} for all local tracks. */
    public Map<String, long[]> loadScanCache() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Map<String, long[]> cache = new HashMap<>();
        Cursor c = db.rawQuery(
                "SELECT file_path, file_size, file_last_modified FROM tracks WHERE source = 0 AND file_path IS NOT NULL",
                null);
        try {
            while (c.moveToNext()) {
                String path = c.getString(0);
                long size = c.getLong(1);
                long modified = c.getLong(2);
                cache.put(path, new long[]{size, modified});
            }
        } finally {
            c.close();
        }
        Log.d(TAG, "loadScanCache: " + cache.size() + " entries");
        return cache;
    }

    /** Load a full Track from DB by file_path. */
    public Track loadTrackByFilePath(String filePath) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT * FROM tracks WHERE file_path = ?", new String[]{filePath});
        try {
            if (c.moveToFirst()) {
                return cursorToTrack(c);
            }
        } finally {
            c.close();
        }
        return null;
    }

    /**
     * Bulk load tracks by file paths. Uses batched IN clauses (max ~900 per query)
     * to avoid hitting the SQLite parameter limit.
     */
    public Map<String, Track> loadTracksByFilePaths(Set<String> paths) {
        if (paths.isEmpty()) return new HashMap<>();

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Map<String, Track> result = new HashMap<>();
        List<String> pathList = new ArrayList<>(paths);
        int batchSize = 900;

        for (int i = 0; i < pathList.size(); i += batchSize) {
            int end = Math.min(i + batchSize, pathList.size());
            List<String> batch = pathList.subList(i, end);

            StringBuilder placeholders = new StringBuilder();
            for (int j = 0; j < batch.size(); j++) {
                if (j > 0) placeholders.append(',');
                placeholders.append('?');
            }

            Cursor c = db.rawQuery(
                    "SELECT * FROM tracks WHERE file_path IN (" + placeholders + ")",
                    batch.toArray(new String[0]));
            try {
                while (c.moveToNext()) {
                    Track track = cursorToTrack(c);
                    String fp = c.getString(c.getColumnIndexOrThrow("file_path"));
                    result.put(fp, track);
                }
            } finally {
                c.close();
            }
        }

        Log.d(TAG, "loadTracksByFilePaths: " + result.size() + "/" + paths.size() + " found");
        return result;
    }

    /** Upsert a local track (INSERT OR REPLACE). */
    public void upsertLocalTrack(Track track, String filePath, long fileSize, long fileLastModified) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = trackToContentValues(track);
        cv.put("file_path", filePath);
        cv.put("file_size", fileSize);
        cv.put("file_last_modified", fileLastModified);
        cv.put("scan_timestamp", System.currentTimeMillis());
        cv.put("updated_at", System.currentTimeMillis());
        db.insertWithOnConflict("tracks", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Bulk upsert within a transaction. */
    public void upsertLocalTracks(List<TrackScanResult> results) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (TrackScanResult r : results) {
                ContentValues cv = trackToContentValues(r.track);
                cv.put("file_path", r.filePath);
                cv.put("file_size", r.fileSize);
                cv.put("file_last_modified", r.fileLastModified);
                cv.put("scan_timestamp", System.currentTimeMillis());
                cv.put("updated_at", System.currentTimeMillis());
                db.insertWithOnConflict("tracks", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        Log.d(TAG, "upsertLocalTracks: " + results.size() + " tracks");
    }

    /**
     * Remove local tracks whose file_path is not in the given set.
     * Uses a temp table + single DELETE instead of loading all paths into Java.
     */
    public int removeStaleLocalTracks(Set<String> scannedPaths) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("CREATE TEMP TABLE IF NOT EXISTS temp_scanned (path TEXT PRIMARY KEY)");
            db.execSQL("DELETE FROM temp_scanned");

            SQLiteStatement stmt = db.compileStatement(
                    "INSERT OR IGNORE INTO temp_scanned (path) VALUES (?)");
            for (String path : scannedPaths) {
                stmt.bindString(1, path);
                stmt.executeInsert();
            }
            stmt.close();

            int removed = db.delete("tracks",
                    "source = 0 AND file_path IS NOT NULL"
                    + " AND file_path NOT IN (SELECT path FROM temp_scanned)",
                    null);

            db.execSQL("DROP TABLE IF EXISTS temp_scanned");
            db.setTransactionSuccessful();

            if (removed > 0) {
                Log.d(TAG, "removeStaleLocalTracks: removed " + removed);
            }
            return removed;
        } finally {
            db.endTransaction();
        }
    }

    // -- Query --

    /** Get all local tracks. */
    public List<Track> getAllLocalTracks() {
        return queryTracks("source = 0", null, "title COLLATE NOCASE ASC");
    }

    /** Get tracks by album_id. */
    public List<Track> getTracksByAlbum(long albumId) {
        return queryTracks("album_id = ?", new String[]{String.valueOf(albumId)},
                "disc_number ASC, track_number ASC, title COLLATE NOCASE ASC");
    }

    /** Get tracks by artist name. */
    public List<Track> getTracksByArtist(String artist) {
        return queryTracks("artist = ? COLLATE NOCASE", new String[]{artist},
                "album COLLATE NOCASE ASC, disc_number ASC, track_number ASC");
    }

    /** Get tracks by folder_path. */
    public List<Track> getTracksByFolder(String folderPath) {
        return queryTracks("folder_path = ?", new String[]{folderPath},
                "title COLLATE NOCASE ASC");
    }

    /** Get a single track by id. */
    public Track getTrackById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT * FROM tracks WHERE id = ?",
                new String[]{String.valueOf(id)});
        try {
            if (c.moveToFirst()) {
                return cursorToTrack(c);
            }
        } finally {
            c.close();
        }
        return null;
    }

    /** Upsert a Tidal track. */
    public void upsertTidalTrack(Track track) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = trackToContentValues(track);
        cv.put("updated_at", System.currentTimeMillis());
        db.insertWithOnConflict("tracks", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // -- Internal --

    private List<Track> queryTracks(String where, String[] args, String orderBy) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<Track> tracks = new ArrayList<>();
        Cursor c = db.query("tracks", null, where, args, null, null, orderBy);
        try {
            while (c.moveToNext()) {
                tracks.add(cursorToTrack(c));
            }
        } finally {
            c.close();
        }
        return tracks;
    }

    static Track cursorToTrack(Cursor c) {
        long id = c.getLong(c.getColumnIndexOrThrow("id"));
        String title = c.getString(c.getColumnIndexOrThrow("title"));
        String artist = c.getString(c.getColumnIndexOrThrow("artist"));
        String album = c.getString(c.getColumnIndexOrThrow("album"));
        long albumId = c.getLong(c.getColumnIndexOrThrow("album_id"));
        long durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms"));
        int trackNumber = c.getInt(c.getColumnIndexOrThrow("track_number"));
        int discNumber = c.getInt(c.getColumnIndexOrThrow("disc_number"));
        int year = c.getInt(c.getColumnIndexOrThrow("year"));
        String uriStr = c.getString(c.getColumnIndexOrThrow("uri"));
        String folderPath = c.getString(c.getColumnIndexOrThrow("folder_path"));
        String folderName = c.getString(c.getColumnIndexOrThrow("folder_name"));
        int sourceInt = c.getInt(c.getColumnIndexOrThrow("source"));
        String tidalTrackId = c.getString(c.getColumnIndexOrThrow("tidal_track_id"));
        String artworkUrl = c.getString(c.getColumnIndexOrThrow("artwork_url"));

        String albumArtist = c.getString(c.getColumnIndexOrThrow("album_artist"));
        String genre = c.getString(c.getColumnIndexOrThrow("genre"));
        String composer = c.getString(c.getColumnIndexOrThrow("composer"));
        int bitrate = c.getInt(c.getColumnIndexOrThrow("bitrate"));
        int sampleRate = c.getInt(c.getColumnIndexOrThrow("sample_rate"));
        int bitDepth = c.getInt(c.getColumnIndexOrThrow("bit_depth"));
        int channels = c.getInt(c.getColumnIndexOrThrow("channels"));
        String format = c.getString(c.getColumnIndexOrThrow("format"));

        Uri uri = uriStr != null ? Uri.parse(uriStr) : null;
        Track.Source source = sourceInt == 1 ? Track.Source.TIDAL : Track.Source.LOCAL;

        return new Track(id, title, artist, durationMs, uri,
                album, albumId, trackNumber, discNumber, year,
                folderPath, folderName, source, tidalTrackId, artworkUrl,
                albumArtist, genre, composer, bitrate, sampleRate, bitDepth, channels, format);
    }

    private ContentValues trackToContentValues(Track track) {
        ContentValues cv = new ContentValues();
        cv.put("id", track.id);
        cv.put("title", track.title);
        cv.put("artist", track.artist);
        cv.put("album", track.album);
        cv.put("album_id", track.albumId);
        cv.put("duration_ms", track.durationMs);
        cv.put("track_number", track.trackNumber);
        cv.put("disc_number", track.discNumber);
        cv.put("year", track.year);
        cv.put("uri", track.uri != null ? track.uri.toString() : null);
        cv.put("folder_path", track.folderPath);
        cv.put("folder_name", track.folderName);
        cv.put("source", track.source == Track.Source.TIDAL ? 1 : 0);
        cv.put("tidal_track_id", track.tidalTrackId);
        cv.put("artwork_url", track.artworkUrl);
        cv.put("album_artist", track.albumArtist);
        cv.put("genre", track.genre);
        cv.put("composer", track.composer);
        cv.put("bitrate", track.bitrate);
        cv.put("sample_rate", track.sampleRate);
        cv.put("bit_depth", track.bitDepth);
        cv.put("channels", track.channels);
        cv.put("format", track.format);
        return cv;
    }

    /** Holder for a scan result before bulk upsert. */
    public static class TrackScanResult {
        public final Track track;
        public final String filePath;
        public final long fileSize;
        public final long fileLastModified;

        public TrackScanResult(Track track, String filePath, long fileSize, long fileLastModified) {
            this.track = track;
            this.filePath = filePath;
            this.fileSize = fileSize;
            this.fileLastModified = fileLastModified;
        }
    }
}
