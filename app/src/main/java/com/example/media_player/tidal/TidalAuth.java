package com.example.media_player.tidal;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class TidalAuth {

    private static final String TAG = "TidalAuth";
    private static final String PREFS_NAME = "tidal_auth";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_EXPIRES_AT = "expires_at";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_COUNTRY_CODE = "country_code";

    private static final String CLIENT_ID = "fX2JxdmntZWK0ixT";
    private static final String CLIENT_SECRET = "1Nm5AfDAjxrgJFJbKNWLeAyKGVGmINuXPPLHVXAvxAg=";

    private static final String AUTH_BASE = "https://auth.tidal.com/v1/oauth2";
    private static final String API_BASE = "https://api.tidal.com/v1";
    private static final int MAX_REFRESH_RETRIES = 2;

    private final SharedPreferences prefs;
    private final Object refreshLock = new Object();

    private String accessToken;
    private String refreshToken;
    private long expiresAt;

    public TidalAuth(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        accessToken = prefs.getString(KEY_ACCESS_TOKEN, null);
        refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null);
        expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0);
    }

    public boolean isLoggedIn() {
        return accessToken != null;
    }

    public String getAccessToken() throws IOException {
        if (accessToken != null && System.currentTimeMillis() < expiresAt - 60_000) {
            return accessToken;
        }
        synchronized (refreshLock) {
            // Double-check: another thread may have refreshed while we waited
            if (accessToken != null && System.currentTimeMillis() < expiresAt - 60_000) {
                return accessToken;
            }
            if (refreshToken == null) throw new IOException("Not authenticated");
            refreshWithRetry();
            return accessToken;
        }
    }

    public void forceRefresh() throws IOException {
        synchronized (refreshLock) {
            if (refreshToken == null) throw new IOException("Not authenticated");
            refreshWithRetry();
        }
    }

    private void refreshWithRetry() throws IOException {
        for (int attempt = 1; attempt <= MAX_REFRESH_RETRIES; attempt++) {
            try {
                refreshAccessToken(refreshToken);
                return;
            } catch (HttpException e) {
                if (e.code == 400 || e.code == 401) {
                    clearTokens();
                    throw new IOException("Session expired, re-authentication required");
                }
                if (attempt == MAX_REFRESH_RETRIES) throw new IOException(e);
                try { Thread.sleep(1000L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during retry");
                }
            } catch (Exception e) {
                if (attempt == MAX_REFRESH_RETRIES) throw new IOException(e);
                try { Thread.sleep(1000L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during retry");
                }
            }
        }
    }

    private void clearTokens() {
        accessToken = null;
        refreshToken = null;
        expiresAt = 0;
        prefs.edit().clear().commit();
    }

    public long getUserId() {
        return prefs.getLong(KEY_USER_ID, 0);
    }

    public String getCountryCode() {
        return prefs.getString(KEY_COUNTRY_CODE, "US");
    }

    /**
     * Step 1: Start device authorization flow. Call from background thread.
     */
    public TidalModels.DeviceAuth startDeviceAuth() throws Exception {
        String body = "client_id=" + CLIENT_ID + "&scope=r_usr+w_usr+w_sub";
        Log.d(TAG, "startDeviceAuth: POST " + AUTH_BASE + "/device_authorization");
        String response = httpPost(AUTH_BASE + "/device_authorization", body, null);
        Log.d(TAG, "startDeviceAuth response: " + response);
        JSONObject json = new JSONObject(response);

        return new TidalModels.DeviceAuth(
                json.getString("deviceCode"),
                json.getString("userCode"),
                json.optString("verificationUri", json.optString("verificationUriComplete", "https://link.tidal.com")),
                json.optInt("expiresIn", 300),
                json.optInt("interval", 5)
        );
    }

    /**
     * Step 2: Poll for token after user has entered the code.
     * Call from background thread. Blocks until success, expiry, or error.
     * Returns true on success, false on expiry/denied.
     */
    public boolean pollForToken(TidalModels.DeviceAuth auth) throws Exception {
        String body = "client_id=" + CLIENT_ID
                + "&client_secret=" + CLIENT_SECRET
                + "&device_code=" + auth.deviceCode
                + "&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"
                + "&scope=r_usr+w_usr+w_sub";

        long deadline = System.currentTimeMillis() + auth.expiresIn * 1000L;
        int interval = Math.max(auth.interval, 2) * 1000;

        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                return false;
            }

            try {
                String response = httpPost(AUTH_BASE + "/token", body, null);
                JSONObject json = new JSONObject(response);

                if (json.has("access_token")) {
                    saveTokens(json);
                    fetchSessionInfo();
                    return true;
                }
            } catch (HttpException e) {
                if (e.code == 400) {
                    // Parse sub-status from error response
                    try {
                        JSONObject err = new JSONObject(e.body);
                        String subStatus = err.optString("sub_status", "");
                        String error = err.optString("error", "");
                        if ("authorization_pending".equals(error) || "1002".equals(subStatus)) {
                            continue; // Still waiting for user
                        }
                        if ("expired_token".equals(error) || "access_denied".equals(error)) {
                            return false;
                        }
                    } catch (Exception ignored) {}
                    continue;
                }
                throw e;
            }
        }
        return false;
    }

    /**
     * Refresh the access token using refresh_token grant.
     */
    private void refreshAccessToken(String currentRefreshToken) throws Exception {
        String body = "client_id=" + CLIENT_ID
                + "&client_secret=" + CLIENT_SECRET
                + "&refresh_token=" + currentRefreshToken
                + "&grant_type=refresh_token";

        String response = httpPost(AUTH_BASE + "/token", body, null);
        JSONObject json = new JSONObject(response);
        saveTokens(json);
    }

    private void saveTokens(JSONObject json) throws Exception {
        String newAccessToken = json.getString("access_token");
        String newRefreshToken = json.optString("refresh_token", null);
        long expiresIn = json.optLong("expires_in", 86400);
        long userId = json.optLong("user", json.optLong("userId", 0));

        // Try to extract userId from the access token JWT payload
        if (userId == 0) {
            userId = extractUserIdFromJwt(newAccessToken);
        }

        // Update in-memory state
        this.accessToken = newAccessToken;
        if (newRefreshToken != null) {
            this.refreshToken = newRefreshToken;
        }
        this.expiresAt = System.currentTimeMillis() + expiresIn * 1000;

        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_ACCESS_TOKEN, this.accessToken);
        if (newRefreshToken != null) {
            editor.putString(KEY_REFRESH_TOKEN, this.refreshToken);
        }
        editor.putLong(KEY_EXPIRES_AT, this.expiresAt);
        if (userId != 0) {
            editor.putLong(KEY_USER_ID, userId);
        }
        editor.commit();

        Log.d(TAG, "Tokens saved, expires in " + expiresIn + "s, userId=" + userId);
    }

    private long extractUserIdFromJwt(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length >= 2) {
                String payload = new String(Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING), StandardCharsets.UTF_8);
                JSONObject obj = new JSONObject(payload);
                return obj.optLong("uid", obj.optLong("sub", 0));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse JWT for userId", e);
        }
        return 0;
    }

    private void fetchSessionInfo() {
        try {
            if (accessToken == null) return;
            String response = httpGet(API_BASE + "/sessions", accessToken);
            JSONObject json = new JSONObject(response);
            String countryCode = json.optString("countryCode", "US");
            long userId = json.optLong("userId", 0);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_COUNTRY_CODE, countryCode);
            if (userId != 0) {
                editor.putLong(KEY_USER_ID, userId);
            }
            editor.commit();
            Log.d(TAG, "Session info: country=" + countryCode + " userId=" + userId);
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch session info", e);
        }
    }

    public void logout() {
        clearTokens();
        Log.d(TAG, "Logged out");
    }

    // HTTP helpers

    static String httpPost(String urlStr, String body, String bearerToken) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            if (bearerToken != null) {
                conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String responseBody = readResponse(conn, code >= 400);
            if (code >= 400) {
                Log.e(TAG, "httpPost " + urlStr + " -> HTTP " + code + ": " + responseBody);
                throw new HttpException(code, responseBody);
            }
            return responseBody;
        } finally {
            conn.disconnect();
        }
    }

    static String httpGet(String urlStr, String bearerToken) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            if (bearerToken != null) {
                conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            int code = conn.getResponseCode();
            String responseBody = readResponse(conn, code >= 400);
            if (code >= 400) {
                Log.e(TAG, "httpGet " + urlStr.substring(0, Math.min(80, urlStr.length())) + " -> HTTP " + code);
                throw new HttpException(code, responseBody);
            }
            return responseBody;
        } finally {
            conn.disconnect();
        }
    }

    private static String readResponse(HttpURLConnection conn, boolean errorStream) throws Exception {
        java.io.InputStream is = errorStream ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    static class HttpException extends Exception {
        final int code;
        final String body;

        HttpException(int code, String body) {
            super("HTTP " + code);
            this.code = code;
            this.body = body;
        }
    }
}
