package com.example.media_player;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

public class SearchDao {

    private final MatrixPlayerDatabase dbHelper;

    public SearchDao(MatrixPlayerDatabase dbHelper) {
        this.dbHelper = dbHelper;
    }

    /**
     * Free-text search. Uses FTS5/FTS4 MATCH when available,
     * falls back to LIKE on title/artist/album otherwise.
     */
    public List<Track> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<Track> results = new ArrayList<>();

        String sql;
        String[] args;

        if (MatrixPlayerDatabase.isFts5()) {
            sql = "SELECT t.* FROM tracks t"
                    + " JOIN search_index si ON si.rowid = t.id"
                    + " WHERE search_index MATCH ?"
                    + " ORDER BY rank";
            args = new String[]{query.trim()};
        } else if (MatrixPlayerDatabase.isFtsAvailable()) {
            // FTS4 -- no rank function
            sql = "SELECT t.* FROM tracks t"
                    + " JOIN search_index si ON si.docid = t.id"
                    + " WHERE search_index MATCH ?"
                    + " ORDER BY t.title COLLATE NOCASE";
            args = new String[]{query.trim()};
        } else {
            // No FTS -- LIKE fallback
            String like = "%" + query.trim() + "%";
            sql = "SELECT * FROM tracks"
                    + " WHERE title LIKE ? COLLATE NOCASE"
                    + " OR artist LIKE ? COLLATE NOCASE"
                    + " OR album LIKE ? COLLATE NOCASE"
                    + " ORDER BY title COLLATE NOCASE";
            args = new String[]{like, like, like};
        }

        Cursor c = db.rawQuery(sql, args);
        try {
            while (c.moveToNext()) {
                results.add(TrackDao.cursorToTrack(c));
            }
        } finally {
            c.close();
        }
        return results;
    }

    /**
     * Structured search with dynamic WHERE clauses. All criteria are AND-combined.
     */
    public List<Track> advancedSearch(SearchCriteria criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return new ArrayList<>();
        }

        StringBuilder where = new StringBuilder("1=1");
        List<String> args = new ArrayList<>();

        if (criteria.freeText != null && !criteria.freeText.trim().isEmpty()) {
            if (MatrixPlayerDatabase.isFtsAvailable()) {
                String idCol = MatrixPlayerDatabase.isFts5() ? "rowid" : "docid";
                where.append(" AND t.id IN (SELECT ").append(idCol)
                        .append(" FROM search_index WHERE search_index MATCH ?)");
                args.add(criteria.freeText.trim());
            } else {
                String like = "%" + criteria.freeText.trim() + "%";
                where.append(" AND (t.title LIKE ? COLLATE NOCASE"
                        + " OR t.artist LIKE ? COLLATE NOCASE"
                        + " OR t.album LIKE ? COLLATE NOCASE)");
                args.add(like);
                args.add(like);
                args.add(like);
            }
        }

        if (criteria.artist != null && !criteria.artist.isEmpty()) {
            where.append(" AND t.artist LIKE ? COLLATE NOCASE");
            args.add("%" + criteria.artist + "%");
        }

        if (criteria.albumArtist != null && !criteria.albumArtist.isEmpty()) {
            where.append(" AND t.album_artist LIKE ? COLLATE NOCASE");
            args.add("%" + criteria.albumArtist + "%");
        }

        if (criteria.composer != null && !criteria.composer.isEmpty()) {
            where.append(" AND t.composer LIKE ? COLLATE NOCASE");
            args.add("%" + criteria.composer + "%");
        }

        if (criteria.genre != null && !criteria.genre.isEmpty()) {
            where.append(" AND t.genre LIKE ? COLLATE NOCASE");
            args.add("%" + criteria.genre + "%");
        }

        if (criteria.yearFrom != null) {
            where.append(" AND t.year >= ?");
            args.add(String.valueOf(criteria.yearFrom));
        }

        if (criteria.yearTo != null) {
            where.append(" AND t.year <= ?");
            args.add(String.valueOf(criteria.yearTo));
        }

        if (criteria.source != null) {
            where.append(" AND t.source = ?");
            args.add(String.valueOf(criteria.source));
        }

        if (criteria.format != null && !criteria.format.isEmpty()) {
            where.append(" AND t.format = ?");
            args.add(criteria.format);
        }

        if (criteria.minBitrate != null) {
            where.append(" AND t.bitrate >= ?");
            args.add(String.valueOf(criteria.minBitrate));
        }

        if (criteria.minSampleRate != null) {
            where.append(" AND t.sample_rate >= ?");
            args.add(String.valueOf(criteria.minSampleRate));
        }

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<Track> results = new ArrayList<>();

        String sql = "SELECT t.* FROM tracks t WHERE " + where
                + " ORDER BY t.title COLLATE NOCASE ASC";
        Cursor c = db.rawQuery(sql, args.toArray(new String[0]));
        try {
            while (c.moveToNext()) {
                results.add(TrackDao.cursorToTrack(c));
            }
        } finally {
            c.close();
        }
        return results;
    }

    /** Rebuild the FTS index from scratch (useful after bulk operations). */
    public void rebuildIndex() {
        if (!MatrixPlayerDatabase.isFtsAvailable()) return;
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.execSQL("INSERT INTO search_index(search_index) VALUES ('rebuild')");
        } catch (Exception e) {
            // FTS table may have been dropped or corrupted -- ignore
        }
    }
}
