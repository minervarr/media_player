package com.example.media_player;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class AlbumDao {

    private static final String TAG = "AlbumDao";

    private static final Pattern REMIX_PATTERN = Pattern.compile(
            "\\b(remix|remixes|remixed|rmx)\\b", Pattern.CASE_INSENSITIVE);

    private final MatrixPlayerDatabase dbHelper;

    public AlbumDao(MatrixPlayerDatabase dbHelper) {
        this.dbHelper = dbHelper;
    }

    /**
     * Rebuild the albums table from the tracks table.
     * Classifies each album by release type (album/EP/single/remix).
     */
    public void rebuildAlbums() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long start = System.currentTimeMillis();

        db.beginTransaction();
        try {
            db.delete("albums", null, null);

            // Aggregate per album_id
            Cursor c = db.rawQuery(
                    "SELECT album_id, album, artist, COUNT(*) AS cnt, SUM(duration_ms) AS total_dur,"
                    + " MAX(year) AS max_year, genre, source, tidal_album_id, tidal_quality"
                    + " FROM tracks GROUP BY album_id", null);
            try {
                while (c.moveToNext()) {
                    long albumId = c.getLong(0);
                    String name = c.getString(1);
                    String artist = c.getString(2);
                    int trackCount = c.getInt(3);
                    long totalDuration = c.getLong(4);
                    int year = c.getInt(5);
                    String genre = c.getString(6);
                    int source = c.getInt(7);
                    Integer tidalAlbumId = c.isNull(8) ? null : c.getInt(8);
                    String tidalQuality = c.getString(9);

                    int releaseType = classifyRelease(db, albumId, name, trackCount);

                    ContentValues cv = new ContentValues();
                    cv.put("album_id", albumId);
                    cv.put("name", name);
                    cv.put("artist", artist);
                    cv.put("track_count", trackCount);
                    cv.put("total_duration", totalDuration);
                    cv.put("year", year);
                    cv.put("release_type", releaseType);
                    cv.put("genre", genre);
                    cv.put("artwork_key", "album:" + albumId);
                    cv.put("source", source);
                    if (tidalAlbumId != null) cv.put("tidal_album_id", tidalAlbumId);
                    cv.put("tidal_quality", tidalQuality);
                    cv.put("updated_at", System.currentTimeMillis());

                    db.insertWithOnConflict("albums", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            } finally {
                c.close();
            }

            // Also update release_type and is_remix on individual tracks
            updateTrackReleaseTypes(db);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        long elapsed = System.currentTimeMillis() - start;
        Log.d(TAG, "rebuildAlbums: done in " + elapsed + "ms");
    }

    /** Query albums by release type. */
    public List<AlbumInfo> getAlbumsByReleaseType(int releaseType) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<AlbumInfo> albums = new ArrayList<>();
        Cursor c = db.query("albums", null,
                "release_type = ?", new String[]{String.valueOf(releaseType)},
                null, null, "name COLLATE NOCASE ASC");
        try {
            while (c.moveToNext()) {
                albums.add(cursorToAlbumInfo(c));
            }
        } finally {
            c.close();
        }
        return albums;
    }

    /** Query all albums. */
    public List<AlbumInfo> getAllAlbums() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<AlbumInfo> albums = new ArrayList<>();
        Cursor c = db.query("albums", null, null, null, null, null, "name COLLATE NOCASE ASC");
        try {
            while (c.moveToNext()) {
                albums.add(cursorToAlbumInfo(c));
            }
        } finally {
            c.close();
        }
        return albums;
    }

    // -- Classification --

    private int classifyRelease(SQLiteDatabase db, long albumId, String albumName, int trackCount) {
        if (isRemixAlbum(db, albumId, albumName, trackCount)) return 3; // REMIX
        if (trackCount == 1) return 2; // SINGLE
        if (trackCount <= 4) return 1; // EP
        return 0; // ALBUM
    }

    private boolean isRemixAlbum(SQLiteDatabase db, long albumId, String albumName, int trackCount) {
        if (albumName != null && REMIX_PATTERN.matcher(albumName).find()) {
            return true;
        }
        // Check individual track titles
        Cursor c = db.rawQuery("SELECT title FROM tracks WHERE album_id = ?",
                new String[]{String.valueOf(albumId)});
        int remixCount = 0;
        try {
            while (c.moveToNext()) {
                String title = c.getString(0);
                if (isRemixTrack(title)) remixCount++;
            }
        } finally {
            c.close();
        }
        return remixCount == trackCount || (remixCount >= 2 && remixCount * 2 > trackCount);
    }

    private static boolean isRemixTrack(String title) {
        if (title == null || title.isEmpty()) return false;
        String lower = title.trim().toLowerCase();
        if (lower.equals("remix") || lower.equals("mix")
                || lower.equals("the remix") || lower.equals("the mix")) {
            return false;
        }
        if (lower.contains("remix") || lower.contains("rmx")) return true;
        if (Pattern.compile("\\b\\w+\\s+mix\\b", Pattern.CASE_INSENSITIVE).matcher(title).find()) return true;
        if (Pattern.compile("\\(.*mix.*\\)", Pattern.CASE_INSENSITIVE).matcher(title).find()) return true;
        if (Pattern.compile("\\[.*mix.*\\]", Pattern.CASE_INSENSITIVE).matcher(title).find()) return true;
        return false;
    }

    private void updateTrackReleaseTypes(SQLiteDatabase db) {
        db.execSQL("UPDATE tracks SET release_type = ("
                + "SELECT a.release_type FROM albums a WHERE a.album_id = tracks.album_id"
                + ") WHERE EXISTS ("
                + "SELECT 1 FROM albums a WHERE a.album_id = tracks.album_id"
                + ")");

        // Batch is_remix detection via SQL pattern matching (replaces per-row Java iteration)
        db.execSQL("UPDATE tracks SET is_remix = 0");
        db.execSQL("UPDATE tracks SET is_remix = 1"
                + " WHERE ("
                + "title LIKE '%remix%'"
                + " OR title LIKE '%rmx%'"
                + " OR title LIKE '% mix'"
                + " OR title LIKE '% mix %'"
                + " OR title LIKE '% mix)%'"
                + " OR title LIKE '% mix]%'"
                + " OR title LIKE '%(mix)%'"
                + " OR title LIKE '%[mix]%'"
                + ")"
                + " AND LOWER(TRIM(title)) NOT IN ('remix', 'mix', 'the remix', 'the mix')");
    }

    private AlbumInfo cursorToAlbumInfo(Cursor c) {
        AlbumInfo info = new AlbumInfo();
        info.albumId = c.getLong(c.getColumnIndexOrThrow("album_id"));
        info.name = c.getString(c.getColumnIndexOrThrow("name"));
        info.artist = c.getString(c.getColumnIndexOrThrow("artist"));
        info.trackCount = c.getInt(c.getColumnIndexOrThrow("track_count"));
        info.totalDuration = c.getLong(c.getColumnIndexOrThrow("total_duration"));
        info.year = c.getInt(c.getColumnIndexOrThrow("year"));
        info.releaseType = c.getInt(c.getColumnIndexOrThrow("release_type"));
        info.genre = c.getString(c.getColumnIndexOrThrow("genre"));
        info.artworkKey = c.getString(c.getColumnIndexOrThrow("artwork_key"));
        info.source = c.getInt(c.getColumnIndexOrThrow("source"));
        return info;
    }

    /** Lightweight album info from the albums table. */
    public static class AlbumInfo {
        public long albumId;
        public String name;
        public String artist;
        public int trackCount;
        public long totalDuration;
        public int year;
        public int releaseType;
        public String genre;
        public String artworkKey;
        public int source;
    }
}
