package com.example.media_player.tidal;

import android.media.MediaDataSource;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * MediaDataSource backed by HTTP Range requests.
 * Feeds TIDAL FLAC streams directly into MediaExtractor.
 */
public class HttpMediaDataSource extends MediaDataSource {

    private static final String TAG = "HttpMediaDataSource";
    private static final int READ_AHEAD_SIZE = 256 * 1024; // 256KB
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 500;

    private final String url;
    private final long totalSize;
    private final Map<String, String> headers;

    // Multi-slot LRU block cache (avoids thrashing on M4A moov/mdat seek cycles)
    private static final int NUM_CACHE_BLOCKS = 4;

    private final byte[][] cacheData = new byte[NUM_CACHE_BLOCKS][READ_AHEAD_SIZE];
    private final long[] cacheStart = new long[NUM_CACHE_BLOCKS];
    private final int[] cacheLen = new int[NUM_CACHE_BLOCKS];
    private final long[] cacheAccess = new long[NUM_CACHE_BLOCKS];
    private long accessCounter = 0;

    private volatile boolean closed;

    private final Object lock = new Object();

    public HttpMediaDataSource(String url, long totalSize, Map<String, String> headers) {
        this.url = url;
        this.totalSize = totalSize;
        this.headers = headers;
        for (int i = 0; i < NUM_CACHE_BLOCKS; i++) {
            cacheStart[i] = -1;
        }
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (closed) return -1;
        if (position >= totalSize) return -1;

        synchronized (lock) {
            // Full hit: check all blocks for position..position+size entirely within one block
            for (int i = 0; i < NUM_CACHE_BLOCKS; i++) {
                if (cacheStart[i] >= 0
                        && position >= cacheStart[i]
                        && position + size <= cacheStart[i] + cacheLen[i]) {
                    int off = (int) (position - cacheStart[i]);
                    System.arraycopy(cacheData[i], off, buffer, offset, size);
                    cacheAccess[i] = ++accessCounter;
                    return size;
                }
            }

            // Partial hit: position is within a block but requested end overflows
            for (int i = 0; i < NUM_CACHE_BLOCKS; i++) {
                if (cacheStart[i] >= 0
                        && position >= cacheStart[i]
                        && position < cacheStart[i] + cacheLen[i]) {
                    int off = (int) (position - cacheStart[i]);
                    int available = cacheLen[i] - off;
                    System.arraycopy(cacheData[i], off, buffer, offset, available);
                    cacheAccess[i] = ++accessCounter;
                    return available;
                }
            }

            // Cache miss -- find LRU block (lowest access count, or first empty slot)
            int victim = 0;
            long minAccess = Long.MAX_VALUE;
            for (int i = 0; i < NUM_CACHE_BLOCKS; i++) {
                if (cacheStart[i] < 0) {
                    victim = i;
                    break;
                }
                if (cacheAccess[i] < minAccess) {
                    minAccess = cacheAccess[i];
                    victim = i;
                }
            }

            long fetchSize = Math.min(READ_AHEAD_SIZE, totalSize - position);
            byte[] fetched = fetchRange(position, (int) fetchSize);
            if (fetched == null || fetched.length == 0) {
                Log.e(TAG, "readAt: fetch failed at pos=" + position);
                return -1;
            }

            // Store in victim block
            cacheStart[victim] = position;
            cacheLen[victim] = fetched.length;
            System.arraycopy(fetched, 0, cacheData[victim], 0, fetched.length);
            cacheAccess[victim] = ++accessCounter;

            int toReturn = Math.min(size, fetched.length);
            System.arraycopy(cacheData[victim], 0, buffer, offset, toReturn);
            return toReturn;
        }
    }

    @Override
    public long getSize() {
        return totalSize;
    }

    @Override
    public void close() throws IOException {
        closed = true;
    }

    public void resetForReuse() {
        synchronized (lock) {
            closed = false;
            for (int i = 0; i < NUM_CACHE_BLOCKS; i++) {
                cacheStart[i] = -1;
                cacheLen[i] = 0;
            }
            accessCounter = 0;
        }
    }

    private byte[] fetchRange(long start, int length) throws IOException {
        int attempts = 0;
        while (attempts < MAX_RETRIES) {
            if (closed) return null;
            attempts++;

            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                long end = Math.min(start + length - 1, totalSize - 1);
                conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
                if (headers != null) {
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        conn.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                int code = conn.getResponseCode();
                if (code == 206 || code == 200) {
                    Log.d(TAG, "fetch: " + start + "-" + end + " (" + conn.getContentLength() + " bytes)");
                }
                if (code != 206 && code != 200) {
                    if (code >= 500 && attempts < MAX_RETRIES) {
                        sleep(RETRY_DELAY_MS * attempts);
                        continue;
                    }
                    throw new IOException("HTTP " + code + " fetching range");
                }

                int contentLength = conn.getContentLength();
                if (contentLength <= 0) contentLength = length;
                byte[] data = new byte[contentLength];
                InputStream is = conn.getInputStream();
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int r = is.read(data, totalRead, contentLength - totalRead);
                    if (r <= 0) break;
                    totalRead += r;
                }
                is.close();

                if (totalRead < contentLength) {
                    byte[] trimmed = new byte[totalRead];
                    System.arraycopy(data, 0, trimmed, 0, totalRead);
                    return trimmed;
                }
                return data;

            } catch (IOException e) {
                if (attempts >= MAX_RETRIES) throw e;
                Log.w(TAG, "fetchRange retry " + attempts + ": " + e.getMessage());
                sleep(RETRY_DELAY_MS * attempts);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    private static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
