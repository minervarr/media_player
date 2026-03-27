package com.example.media_player;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class DuplicateDetector {

    private static final String TAG = "DuplicateDetector";

    /** Duration tolerance in ms for confirming duplicates. */
    private static final long DURATION_TOLERANCE_MS = 1000;

    private final MatrixPlayerDatabase dbHelper;

    public DuplicateDetector(MatrixPlayerDatabase dbHelper) {
        this.dbHelper = dbHelper;
    }

    /**
     * Find duplicate tracks by title + artist (case insensitive),
     * confirmed by duration similarity (within 1s tolerance).
     */
    public List<DuplicateGroup> findDuplicates() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<DuplicateGroup> groups = new ArrayList<>();

        // Find title+artist combos that appear more than once
        Cursor gc = db.rawQuery(
                "SELECT title, artist FROM tracks"
                + " GROUP BY title COLLATE NOCASE, artist COLLATE NOCASE"
                + " HAVING COUNT(*) > 1", null);
        try {
            while (gc.moveToNext()) {
                String title = gc.getString(0);
                String artist = gc.getString(1);

                Cursor tc = db.rawQuery(
                        "SELECT * FROM tracks"
                        + " WHERE title = ? COLLATE NOCASE AND artist = ? COLLATE NOCASE"
                        + " ORDER BY duration_ms ASC",
                        new String[]{title, artist});
                try {
                    List<Track> candidates = new ArrayList<>();
                    while (tc.moveToNext()) {
                        candidates.add(TrackDao.cursorToTrack(tc));
                    }
                    // Cluster by duration to confirm duplicates
                    List<List<Track>> clusters = clusterByDuration(candidates);
                    for (List<Track> cluster : clusters) {
                        if (cluster.size() > 1) {
                            groups.add(new DuplicateGroup(title, artist, cluster));
                        }
                    }
                } finally {
                    tc.close();
                }
            }
        } finally {
            gc.close();
        }

        Log.d(TAG, "findDuplicates: " + groups.size() + " duplicate groups found");
        return groups;
    }

    /**
     * Cluster tracks by duration. Tracks within DURATION_TOLERANCE_MS of each
     * other are placed in the same cluster. Input must be sorted by duration_ms.
     */
    private List<List<Track>> clusterByDuration(List<Track> sorted) {
        List<List<Track>> clusters = new ArrayList<>();
        if (sorted.isEmpty()) return clusters;

        List<Track> current = new ArrayList<>();
        current.add(sorted.get(0));

        for (int i = 1; i < sorted.size(); i++) {
            Track prev = sorted.get(i - 1);
            Track curr = sorted.get(i);
            if (Math.abs(curr.durationMs - prev.durationMs) <= DURATION_TOLERANCE_MS) {
                current.add(curr);
            } else {
                clusters.add(current);
                current = new ArrayList<>();
                current.add(curr);
            }
        }
        clusters.add(current);
        return clusters;
    }

    /** A group of tracks that are likely duplicates. */
    public static class DuplicateGroup {
        public final String title;
        public final String artist;
        public final List<Track> tracks;

        public DuplicateGroup(String title, String artist, List<Track> tracks) {
            this.title = title;
            this.artist = artist;
            this.tracks = tracks;
        }
    }
}
