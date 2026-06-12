package com.example.media_player.qobuz;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

import io.nava.kobuzapi.Kobuzapi;

/**
 * Process-wide owner of the single Kobuzapi service handle.
 * The native handle is created lazily on first API call, after credentials
 * have been provided via {@link #setCredentials}. All methods are synchronous
 * -- call from background threads.
 */
public final class QobuzClient {

    private static final String TAG = "QobuzClient";
    private static final String CA_ASSET = "cacert.pem";

    private static volatile QobuzClient sInstance;

    private final Context appContext;
    private long handle;
    private String appId;
    private String appSecret;
    private String authToken;
    private String caPath;

    public static QobuzClient get(Context context) {
        if (sInstance == null) {
            synchronized (QobuzClient.class) {
                if (sInstance == null) {
                    sInstance = new QobuzClient(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private QobuzClient(Context appContext) {
        this.appContext = appContext;
    }

    /** Stores app credentials; an existing handle built on different ones is discarded. */
    public synchronized void setCredentials(String appId, String appSecret) {
        if (appId == null || appSecret == null) return;
        boolean changed = !appId.equals(this.appId) || !appSecret.equals(this.appSecret);
        this.appId = appId;
        this.appSecret = appSecret;
        if (changed && handle != 0) {
            Kobuzapi.destroy(handle);
            handle = 0;
        }
    }

    /** Sets the user auth token, applying it immediately if the handle exists. */
    public synchronized void setAuthToken(String token) {
        authToken = token;
        if (handle != 0 && token != null) {
            Kobuzapi.setAuthToken(handle, token);
        }
    }

    /** Validates a persisted token against the API. Returns false on rejection. */
    public synchronized boolean loginWithToken(String userId, String token) throws IOException {
        ensureHandle();
        String country = Kobuzapi.loginWithToken(handle, userId, token);
        if (country == null) {
            Log.w(TAG, "loginWithToken failed: " + Kobuzapi.getLastError());
            return false;
        }
        authToken = token;
        return true;
    }

    /** Generic signed GET, e.g. {@code apiGet("/album/search", params)}. Returns raw JSON. */
    public synchronized String apiGet(String endpoint, Map<String, String> params)
            throws IOException {
        ensureHandle();
        String[] keys = new String[params.size()];
        String[] values = new String[params.size()];
        int i = 0;
        for (Map.Entry<String, String> e : params.entrySet()) {
            keys[i] = e.getKey();
            values[i] = e.getValue();
            i++;
        }
        String result = Kobuzapi.apiGet(handle, endpoint, keys, values);
        if (result == null) {
            throw new IOException("Qobuz API " + endpoint + " failed: " + Kobuzapi.getLastError());
        }
        return result;
    }

    /** Signed /track/getFileUrl request. Returns the raw JSON response. */
    public synchronized String getTrackFileUrl(long trackId, int formatId) throws IOException {
        ensureHandle();
        String result = Kobuzapi.getTrackFileUrl(handle, trackId, formatId);
        if (result == null) {
            throw new IOException("Qobuz getFileUrl failed: " + Kobuzapi.getLastError());
        }
        return result;
    }

    /** Drops the native handle and forgets the auth token (logout / service teardown). */
    public synchronized void destroy() {
        if (handle != 0) {
            Kobuzapi.destroy(handle);
            handle = 0;
        }
        authToken = null;
    }

    private void ensureHandle() throws IOException {
        if (handle != 0) return;
        if (appId == null || appSecret == null) {
            throw new IOException("Qobuz credentials not initialized");
        }
        if (caPath == null) {
            caPath = extractCaCert();
        }
        handle = Kobuzapi.create(appId, appSecret, caPath, null);
        if (handle == 0) {
            throw new IOException("Kobuzapi.create failed: " + Kobuzapi.getLastError());
        }
        if (authToken != null) {
            Kobuzapi.setAuthToken(handle, authToken);
        }
    }

    // The kobuzapi AAR ships cacert.pem in its assets; libcurl needs it as a
    // real file path, so copy it to app-private storage once.
    private String extractCaCert() throws IOException {
        File out = new File(appContext.getFilesDir(), CA_ASSET);
        if (!out.exists() || out.length() == 0) {
            try (InputStream in = appContext.getAssets().open(CA_ASSET);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        }
        return out.getAbsolutePath();
    }
}
