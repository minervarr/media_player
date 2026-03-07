package com.example.media_player;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ArtworkCache {

    private static ArtworkCache instance;

    private final LruCache<String, Bitmap> cache;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Context appContext;

    private static Bitmap placeholderBitmap;

    private final ConcurrentHashMap<Long, AlbumInfo> albumRegistry = new ConcurrentHashMap<>();

    private static final List<String> COVER_NAMES = Arrays.asList(
            "cover", "folder", "front", "albumart", "album");
    private static final List<String> IMAGE_EXTENSIONS = Arrays.asList(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp");

    private static class AlbumInfo {
        final Uri trackUri;
        final String folderPath;

        AlbumInfo(Uri trackUri, String folderPath) {
            this.trackUri = trackUri;
            this.folderPath = folderPath;
        }
    }

    private ArtworkCache(Context context) {
        appContext = context.getApplicationContext();
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        cache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
    }

    public static synchronized ArtworkCache getInstance(Context context) {
        if (instance == null) {
            instance = new ArtworkCache(context);
        }
        return instance;
    }

    public void registerAlbum(long albumId, Uri trackUri, String folderPath) {
        albumRegistry.put(albumId, new AlbumInfo(trackUri, folderPath));
    }

    public void clearAlbumRegistry() {
        albumRegistry.clear();
    }

    public Bitmap getCachedBitmap(String artworkKey) {
        return cache.get(artworkKey);
    }

    public void loadFullSize(String artworkKey, ImageView target, int sizePx) {
        String fullKey = artworkKey + "@full";
        target.setTag(R.id.artwork_tag, fullKey);

        Bitmap fullCached = cache.get(fullKey);
        if (fullCached != null) {
            target.setImageBitmap(fullCached);
            return;
        }

        Bitmap lowRes = cache.get(artworkKey);
        if (lowRes != null) {
            target.setImageBitmap(lowRes);
        }

        executor.execute(() -> {
            Bitmap bitmap = resolveFullSize(artworkKey, sizePx);
            if (bitmap != null) {
                cache.put(fullKey, bitmap);
            }
            Bitmap result = bitmap != null ? bitmap : getPlaceholder(sizePx);
            mainHandler.post(() -> {
                Object tag = target.getTag(R.id.artwork_tag);
                if (fullKey.equals(tag)) {
                    target.setImageBitmap(result);
                }
            });
        });
    }

    public void loadArtwork(String artworkKey, ImageView target, int sizePx) {
        if (artworkKey == null) {
            target.setImageBitmap(getPlaceholder(sizePx));
            return;
        }

        target.setTag(R.id.artwork_tag, artworkKey);

        Bitmap cached = cache.get(artworkKey);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }

        target.setImageBitmap(getPlaceholder(sizePx));

        executor.execute(() -> {
            Bitmap bitmap = resolve(artworkKey, sizePx);
            if (bitmap != null) {
                cache.put(artworkKey, bitmap);
            }
            Bitmap result = bitmap != null ? bitmap : getPlaceholder(sizePx);
            mainHandler.post(() -> {
                Object tag = target.getTag(R.id.artwork_tag);
                if (artworkKey.equals(tag)) {
                    target.setImageBitmap(result);
                }
            });
        });
    }

    private Bitmap resolve(String artworkKey, int sizePx) {
        if (artworkKey.startsWith("album:")) {
            return resolveAlbum(artworkKey, sizePx);
        } else if (artworkKey.startsWith("folder:")) {
            String folderPath = artworkKey.substring(7);
            return resolveFolder(folderPath, sizePx);
        }
        return null;
    }

    private Bitmap resolveFullSize(String artworkKey, int sizePx) {
        if (artworkKey.startsWith("album:")) {
            return resolveAlbumFullSize(artworkKey, sizePx);
        }
        return resolve(artworkKey, sizePx);
    }

    private Bitmap resolveAlbumFullSize(String artworkKey, int sizePx) {
        long albumId;
        try {
            albumId = Long.parseLong(artworkKey.substring(6));
        } catch (NumberFormatException e) {
            return null;
        }

        AlbumInfo info = albumRegistry.get(albumId);
        if (info == null) return null;

        // Folder cover file first (highest quality)
        if (info.folderPath != null && !info.folderPath.isEmpty()) {
            Bitmap result = resolveFolder(info.folderPath, sizePx);
            if (result != null) return result;
        }

        // Album art content URI
        Bitmap result = tryAlbumArtUri(albumId, sizePx);
        if (result != null) return result;

        // Embedded picture as fallback
        return tryEmbeddedPicture(info.trackUri, sizePx);
    }

    private Bitmap resolveAlbum(String artworkKey, int sizePx) {
        long albumId;
        try {
            albumId = Long.parseLong(artworkKey.substring(6));
        } catch (NumberFormatException e) {
            return null;
        }

        AlbumInfo info = albumRegistry.get(albumId);
        if (info == null) return null;

        // Step 1: Embedded picture via MediaMetadataRetriever
        Bitmap result = tryEmbeddedPicture(info.trackUri, sizePx);
        if (result != null) return result;

        // Step 2: Album art content URI
        result = tryAlbumArtUri(albumId, sizePx);
        if (result != null) return result;

        // Step 3: Folder cover file
        if (info.folderPath != null && !info.folderPath.isEmpty()) {
            result = resolveFolder(info.folderPath, sizePx);
            if (result != null) return result;
        }

        return null;
    }

    private Bitmap tryEmbeddedPicture(Uri trackUri, int sizePx) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(appContext, trackUri);
            byte[] art = retriever.getEmbeddedPicture();
            if (art != null) {
                return decodeSampled(art, sizePx);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Bitmap tryAlbumArtUri(long albumId, int sizePx) {
        Uri albumArtUri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"), albumId);
        ContentResolver cr = appContext.getContentResolver();
        try (InputStream is = cr.openInputStream(albumArtUri)) {
            if (is != null) {
                byte[] data = readStream(is);
                return decodeSampled(data, sizePx);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Bitmap resolveFolder(String folderPath, int sizePx) {
        File dir = new File(folderPath);
        if (!dir.isDirectory()) return null;

        File[] files = dir.listFiles();
        if (files == null) return null;

        // Pass 1: known cover names
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName().toLowerCase();
            int dotIdx = name.lastIndexOf('.');
            if (dotIdx < 0) continue;
            String baseName = name.substring(0, dotIdx);
            String ext = name.substring(dotIdx);
            if (COVER_NAMES.contains(baseName) && IMAGE_EXTENSIONS.contains(ext)) {
                Bitmap bmp = decodeSampledFile(f.getAbsolutePath(), sizePx);
                if (bmp != null) return bmp;
            }
        }

        // Pass 2: if exactly 1 image file, use it
        File singleImage = null;
        int imageCount = 0;
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName().toLowerCase();
            int dotIdx = name.lastIndexOf('.');
            if (dotIdx < 0) continue;
            String ext = name.substring(dotIdx);
            if (IMAGE_EXTENSIONS.contains(ext)) {
                singleImage = f;
                imageCount++;
                if (imageCount > 1) break;
            }
        }
        if (imageCount == 1 && singleImage != null) {
            return decodeSampledFile(singleImage.getAbsolutePath(), sizePx);
        }

        return null;
    }

    private Bitmap decodeSampled(byte[] data, int targetSize) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        opts.inSampleSize = calcSampleSize(opts, targetSize);
        opts.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    private Bitmap decodeSampledFile(String path, int targetSize) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        opts.inSampleSize = calcSampleSize(opts, targetSize);
        opts.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, opts);
    }

    private int calcSampleSize(BitmapFactory.Options opts, int targetSize) {
        int w = opts.outWidth;
        int h = opts.outHeight;
        int inSampleSize = 1;
        if (w > targetSize || h > targetSize) {
            int halfW = w / 2;
            int halfH = h / 2;
            while ((halfW / inSampleSize) >= targetSize && (halfH / inSampleSize) >= targetSize) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private byte[] readStream(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) != -1) {
            bos.write(buf, 0, len);
        }
        return bos.toByteArray();
    }

    private static synchronized Bitmap getPlaceholder(int sizePx) {
        if (placeholderBitmap != null && placeholderBitmap.getWidth() == sizePx) {
            return placeholderBitmap;
        }
        int size = Math.max(sizePx, 2);
        placeholderBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(placeholderBitmap);
        canvas.drawColor(0xFF1A1A1A);
        Paint borderPaint = new Paint();
        borderPaint.setColor(0xFF00C853);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f);
        canvas.drawRect(1, 1, size - 1, size - 1, borderPaint);
        return placeholderBitmap;
    }
}
