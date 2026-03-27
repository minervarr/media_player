package com.example.media_player;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;

public class ArtworkDiskCache {

    private static final String TAG = "ArtworkDiskCache";
    private static final long MAX_CACHE_BYTES = 100L * 1024 * 1024; // 100 MB
    private static final int JPEG_QUALITY = 85;

    private final MatrixPlayerDatabase dbHelper;
    private final File cacheDir;

    public ArtworkDiskCache(Context context, MatrixPlayerDatabase dbHelper) {
        this.dbHelper = dbHelper;
        this.cacheDir = new File(context.getCacheDir(), "artwork");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
    }

    /**
     * Check the disk cache for a previously cached bitmap.
     * Returns decoded Bitmap or null if not cached / file missing.
     */
    public Bitmap get(String cacheKey) {
        SQLiteDatabase db;
        try {
            db = dbHelper.getReadableDatabase();
        } catch (Exception e) {
            return null;
        }

        Cursor c = db.query(MatrixPlayerDatabase.TABLE_ARTWORK_CACHE,
                new String[]{"file_path", "expires_at"},
                "cache_key = ?", new String[]{cacheKey},
                null, null, null);
        try {
            if (!c.moveToFirst()) return null;

            String filePath = c.getString(0);
            long expiresAt = c.isNull(1) ? Long.MAX_VALUE : c.getLong(1);

            // Check expiry
            if (expiresAt != Long.MAX_VALUE && System.currentTimeMillis() > expiresAt) {
                removeEntry(cacheKey, filePath);
                return null;
            }

            // Check file exists
            File file = new File(filePath);
            if (!file.exists()) {
                removeEntry(cacheKey, null);
                return null;
            }

            return BitmapFactory.decodeFile(filePath);
        } finally {
            c.close();
        }
    }

    /**
     * Store a bitmap in the disk cache.
     * @param cacheKey  Unique key (e.g. "tidal:abc123", "album:12345")
     * @param bitmap    The bitmap to cache
     * @param expiresAt Expiry timestamp in ms, or 0 for never (local artwork)
     */
    public void put(String cacheKey, Bitmap bitmap, long expiresAt) {
        if (bitmap == null) return;

        String fileName = hashKey(cacheKey) + ".jpg";
        File file = new File(cacheDir, fileName);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
            fos.flush();
        } catch (Exception e) {
            Log.w(TAG, "put: failed to write " + cacheKey, e);
            file.delete();
            return;
        }

        long fileSize = file.length();
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("cache_key", cacheKey);
        cv.put("file_path", file.getAbsolutePath());
        cv.put("width", bitmap.getWidth());
        cv.put("height", bitmap.getHeight());
        cv.put("file_size", fileSize);
        cv.put("fetched_at", System.currentTimeMillis());
        if (expiresAt > 0) {
            cv.put("expires_at", expiresAt);
        } else {
            cv.putNull("expires_at");
        }
        db.insertWithOnConflict(MatrixPlayerDatabase.TABLE_ARTWORK_CACHE, null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);

        // Check total size and evict if needed
        evictIfOverLimit(db);
    }

    /**
     * Remove expired entries and their files.
     */
    public void evictExpired() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long now = System.currentTimeMillis();

        Cursor c = db.query(MatrixPlayerDatabase.TABLE_ARTWORK_CACHE,
                new String[]{"cache_key", "file_path"},
                "expires_at IS NOT NULL AND expires_at < ?",
                new String[]{String.valueOf(now)},
                null, null, null);
        try {
            while (c.moveToNext()) {
                String key = c.getString(0);
                String path = c.getString(1);
                removeEntry(key, path);
            }
        } finally {
            c.close();
        }
    }

    /**
     * Clear all cached artwork files and DB rows.
     */
    public void clear() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(MatrixPlayerDatabase.TABLE_ARTWORK_CACHE, null, null);

        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        Log.d(TAG, "clear: all artwork cache removed");
    }

    /**
     * Get total size of cached artwork in bytes.
     */
    public long getTotalSize() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COALESCE(SUM(file_size), 0) FROM "
                + MatrixPlayerDatabase.TABLE_ARTWORK_CACHE, null);
        try {
            if (c.moveToFirst()) return c.getLong(0);
            return 0;
        } finally {
            c.close();
        }
    }

    private void evictIfOverLimit(SQLiteDatabase db) {
        Cursor sizeC = db.rawQuery("SELECT COALESCE(SUM(file_size), 0) FROM "
                + MatrixPlayerDatabase.TABLE_ARTWORK_CACHE, null);
        long totalSize;
        try {
            totalSize = sizeC.moveToFirst() ? sizeC.getLong(0) : 0;
        } finally {
            sizeC.close();
        }

        if (totalSize <= MAX_CACHE_BYTES) return;

        long toFree = totalSize - (long) (MAX_CACHE_BYTES * 0.8); // free down to 80%
        long freed = 0;

        // Evict oldest-fetched entries first
        Cursor c = db.query(MatrixPlayerDatabase.TABLE_ARTWORK_CACHE,
                new String[]{"cache_key", "file_path", "file_size"},
                null, null, null, null, "fetched_at ASC");
        try {
            while (c.moveToNext() && freed < toFree) {
                String key = c.getString(0);
                String path = c.getString(1);
                long size = c.getLong(2);
                removeEntry(key, path);
                freed += size;
            }
        } finally {
            c.close();
        }

        Log.d(TAG, "evictIfOverLimit: freed " + (freed / 1024) + " KB");
    }

    private void removeEntry(String cacheKey, String filePath) {
        if (filePath != null) {
            new File(filePath).delete();
        }
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(MatrixPlayerDatabase.TABLE_ARTWORK_CACHE,
                "cache_key = ?", new String[]{cacheKey});
    }

    private static String hashKey(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            // Fallback: sanitize the key
            return key.replaceAll("[^a-zA-Z0-9_-]", "_");
        }
    }
}
