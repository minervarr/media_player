package com.example.media_player;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class EqAssignmentDao {

    private static final String TAG = "EqAssignmentDao";

    public static final int TYPE_TRACK  = 0;
    public static final int TYPE_ALBUM  = 1;
    public static final int TYPE_ARTIST = 2;
    public static final int TYPE_FOLDER = 3;

    private final MatrixPlayerDatabase dbHelper;

    public static class Assignment {
        public final int entityType;
        public final long entityId;
        public final String profileName;
        public final String profileSource;
        public final String profileForm;

        Assignment(int entityType, long entityId, String profileName,
                   String profileSource, String profileForm) {
            this.entityType = entityType;
            this.entityId = entityId;
            this.profileName = profileName;
            this.profileSource = profileSource;
            this.profileForm = profileForm;
        }
    }

    public EqAssignmentDao(MatrixPlayerDatabase dbHelper) {
        this.dbHelper = dbHelper;
    }

    public void setAssignment(int entityType, long entityId,
                              String profileName, String profileSource, String profileForm) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("entity_type", entityType);
        cv.put("entity_id", entityId);
        cv.put("profile_name", profileName);
        cv.put("profile_source", profileSource != null ? profileSource : "");
        cv.put("profile_form", profileForm != null ? profileForm : "");
        cv.put("created_at", System.currentTimeMillis());
        db.insertWithOnConflict(MatrixPlayerDatabase.TABLE_EQ_ASSIGNMENTS, null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
        Log.d(TAG, "setAssignment: type=" + entityType + " id=" + entityId
                + " profile=" + profileName);
    }

    public Assignment getAssignment(int entityType, long entityId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(MatrixPlayerDatabase.TABLE_EQ_ASSIGNMENTS,
                new String[]{"profile_name", "profile_source", "profile_form"},
                "entity_type = ? AND entity_id = ?",
                new String[]{String.valueOf(entityType), String.valueOf(entityId)},
                null, null, null);
        try {
            if (c.moveToFirst()) {
                return new Assignment(entityType, entityId,
                        c.getString(0), c.getString(1), c.getString(2));
            }
            return null;
        } finally {
            c.close();
        }
    }

    public void removeAssignment(int entityType, long entityId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(MatrixPlayerDatabase.TABLE_EQ_ASSIGNMENTS,
                "entity_type = ? AND entity_id = ?",
                new String[]{String.valueOf(entityType), String.valueOf(entityId)});
    }

    public List<Assignment> getAssignmentsByProfile(String profileName) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(MatrixPlayerDatabase.TABLE_EQ_ASSIGNMENTS,
                new String[]{"entity_type", "entity_id", "profile_name",
                        "profile_source", "profile_form"},
                "profile_name = ?",
                new String[]{profileName},
                null, null, null);
        List<Assignment> result = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                result.add(new Assignment(c.getInt(0), c.getLong(1),
                        c.getString(2), c.getString(3), c.getString(4)));
            }
        } finally {
            c.close();
        }
        return result;
    }

    public void clearAllAssignments() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(MatrixPlayerDatabase.TABLE_EQ_ASSIGNMENTS, null, null);
    }

    /**
     * Resolve the EQ profile for a track using the cascading priority:
     * track -> album -> artist -> folder.
     * Returns null if no per-entity assignment exists.
     */
    public Assignment resolveForTrack(Track track) {
        if (track == null) return null;

        // 1. Track-specific
        Assignment a = getAssignment(TYPE_TRACK, track.id);
        if (a != null) return a;

        // 2. Album-level
        a = getAssignment(TYPE_ALBUM, track.albumId);
        if (a != null) return a;

        // 3. Artist-level
        if (track.artist != null && !track.artist.isEmpty()) {
            long artistHash = (long) track.artist.hashCode();
            a = getAssignment(TYPE_ARTIST, artistHash);
            if (a != null) return a;
        }

        // 4. Folder-level
        if (track.folderPath != null && !track.folderPath.isEmpty()) {
            long folderHash = (long) track.folderPath.hashCode();
            a = getAssignment(TYPE_FOLDER, folderHash);
            if (a != null) return a;
        }

        return null;
    }

    /** Hash helper for callers setting artist-level assignments. */
    public static long artistEntityId(String artistName) {
        return artistName != null ? (long) artistName.hashCode() : 0;
    }

    /** Hash helper for callers setting folder-level assignments. */
    public static long folderEntityId(String folderPath) {
        return folderPath != null ? (long) folderPath.hashCode() : 0;
    }
}
