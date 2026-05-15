package com.example.media_player.qobuz;

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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Qobuz authentication: bundle token extraction, login, session persistence, request signing.
 * All methods are synchronous -- call from background threads.
 */
public class QobuzAuth {

    private static final String TAG = "QobuzAuth";
    private static final String PREFS_NAME = "qobuz_auth";
    private static final String KEY_AUTH_TOKEN = "user_auth_token";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_DISPLAY_NAME = "display_name";
    private static final String KEY_APP_ID = "app_id";
    private static final String KEY_SECRET = "validated_secret";
    private static final String KEY_PRIVATE_KEY = "private_key";
    private static final String KEY_SUBSCRIPTION = "subscription_label";

    private static final String API_BASE = "https://www.qobuz.com/api.json/0.2";
    private static final String LOGIN_PAGE_URL = "https://play.qobuz.com/login";
    private static final String BUNDLE_BASE_URL = "https://play.qobuz.com";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:120.0) Gecko/20100101 Firefox/120.0";

    private static final long TEST_TRACK_ID = 5966783L;

    private final SharedPreferences prefs;

    private String appId;
    private String validatedSecret;
    private String privateKey;
    private String userAuthToken;
    private long userId;
    private String displayName;
    private String subscriptionLabel;

    public QobuzAuth(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        userAuthToken = prefs.getString(KEY_AUTH_TOKEN, null);
        userId = prefs.getLong(KEY_USER_ID, 0);
        displayName = prefs.getString(KEY_DISPLAY_NAME, "");
        appId = prefs.getString(KEY_APP_ID, null);
        validatedSecret = prefs.getString(KEY_SECRET, null);
        privateKey = prefs.getString(KEY_PRIVATE_KEY, null);
        subscriptionLabel = prefs.getString(KEY_SUBSCRIPTION, "");
    }

    public boolean isLoggedIn() {
        return userAuthToken != null;
    }

    public boolean isInitialized() {
        return appId != null && validatedSecret != null;
    }

    public String getAppId() { return appId; }
    public String getUserAuthToken() { return userAuthToken; }
    public String getValidatedSecret() { return validatedSecret; }
    public String getPrivateKey() { return privateKey; }
    public long getUserId() { return userId; }
    public String getDisplayName() { return displayName; }
    public String getSubscriptionLabel() { return subscriptionLabel; }

    // ---- Bundle extraction ----

    /**
     * Extract app_id and secrets from the Qobuz web player bundle,
     * then validate the correct secret. Must be called before login.
     */
    public void init() throws IOException {
        Log.d(TAG, "init: extracting bundle tokens...");

        // Step 1: Fetch login page to find bundle URL
        String loginHtml = httpGet(LOGIN_PAGE_URL, null);
        String bundlePath = extractBundleUrl(loginHtml);
        if (bundlePath == null) {
            throw new IOException("Could not find bundle URL in Qobuz login page");
        }

        // Step 2: Download bundle.js
        String bundleUrl = BUNDLE_BASE_URL + bundlePath;
        Log.d(TAG, "init: fetching bundle from " + bundleUrl);
        String bundle = httpGet(bundleUrl, null);

        // Step 3: Extract app_id
        appId = extractAppId(bundle);
        if (appId == null) {
            throw new IOException("Could not extract app_id from bundle");
        }
        Log.d(TAG, "init: app_id=" + appId);

        // Step 4: Extract secrets
        List<String> secrets = extractSecrets(bundle);
        if (secrets.isEmpty()) {
            throw new IOException("No secrets found in bundle");
        }
        Log.d(TAG, "init: found " + secrets.size() + " candidate secrets");

        // Step 5: Validate secrets
        validatedSecret = null;
        for (String secret : secrets) {
            if (testSecret(secret)) {
                validatedSecret = secret;
                break;
            }
        }
        if (validatedSecret == null) {
            throw new IOException("No valid secret found");
        }
        Log.d(TAG, "init: secret validated");

        // Step 6: Extract OAuth private_key (newer bundles only)
        privateKey = extractPrivateKey(bundle);
        if (privateKey != null) {
            Log.d(TAG, "init: OAuth private_key extracted");
        } else {
            Log.w(TAG, "init: OAuth private_key not found in bundle");
        }

        // Persist app_id, secret, and private_key for session restore
        prefs.edit()
                .putString(KEY_APP_ID, appId)
                .putString(KEY_SECRET, validatedSecret)
                .putString(KEY_PRIVATE_KEY, privateKey)
                .apply();
    }

    static String extractBundleUrl(String html) {
        Pattern p = Pattern.compile(
                "<script src=\"(/resources/\\d+\\.\\d+\\.\\d+-[a-z]\\d{3}/bundle\\.js)\"></script>");
        Matcher m = p.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    static String extractAppId(String bundle) {
        Pattern p = Pattern.compile("production:\\{api:\\{appId:\"(\\d{9})\"");
        Matcher m = p.matcher(bundle);
        return m.find() ? m.group(1) : null;
    }

    static String extractPrivateKey(String bundle) {
        // Pattern: privateKey:"VALUE" -- the static OAuth key in /oauth/callback exchange
        Pattern p = Pattern.compile("privateKey:\\s*\"([A-Za-z0-9]{6,30})\"");
        Matcher m = p.matcher(bundle);
        return m.find() ? m.group(1) : null;
    }

    static List<String> extractSecrets(String bundle) {
        List<String> secrets = new ArrayList<>();

        // Extract seeds: X.initialSeed("SEED",window.utimezone.TIMEZONE)
        Pattern seedPat = Pattern.compile(
                "[a-z]\\.initialSeed\\(\"([\\w=]+)\",window\\.utimezone\\.([a-z]+)\\)");
        Matcher seedMatcher = seedPat.matcher(bundle);
        Map<String, String> seeds = new HashMap<>();
        List<String> timezones = new ArrayList<>();
        while (seedMatcher.find()) {
            String seed = seedMatcher.group(1);
            String tz = seedMatcher.group(2);
            seeds.put(tz, seed);
            timezones.add(tz);
        }

        if (seeds.isEmpty()) {
            // Fallback: try simple appSecret pattern
            Pattern simplePat = Pattern.compile("appSecret:\"([a-f0-9]{32})\"");
            Matcher simpleMatcher = simplePat.matcher(bundle);
            while (simpleMatcher.find()) {
                secrets.add(simpleMatcher.group(1));
            }
            return secrets;
        }

        // Build regex for timezone info/extras
        StringBuilder tzAlts = new StringBuilder();
        for (String tz : timezones) {
            if (tzAlts.length() > 0) tzAlts.append('|');
            tzAlts.append(Character.toUpperCase(tz.charAt(0))).append(tz.substring(1));
        }
        String infoPatStr = "name:\"\\w+/(" + tzAlts + ")\",info:\"([\\w=]+)\",extras:\"([\\w=]+)\"";
        Pattern infoPat = Pattern.compile(infoPatStr);
        Matcher infoMatcher = infoPat.matcher(bundle);

        while (infoMatcher.find()) {
            String tz = infoMatcher.group(1).toLowerCase();
            String info = infoMatcher.group(2);
            String extras = infoMatcher.group(3);
            String seed = seeds.get(tz);
            if (seed == null) continue;

            String combined = seed + info + extras;
            if (combined.length() <= 44) continue;

            String trimmed = combined.substring(0, combined.length() - 44);
            try {
                byte[] decoded = Base64.decode(trimmed, Base64.DEFAULT);
                String secret = new String(decoded, StandardCharsets.UTF_8);
                if (!secret.isEmpty()) {
                    secrets.add(secret);
                }
            } catch (Exception e) {
                Log.d(TAG, "Base64 decode failed for timezone " + tz);
            }
        }

        return secrets;
    }

    private boolean testSecret(String secret) {
        try {
            long timestamp = System.currentTimeMillis() / 1000;
            String signature = signFileUrl(TEST_TRACK_ID, QobuzModels.QUALITY_MP3, timestamp, secret);

            String url = API_BASE + "/track/getFileUrl"
                    + "?track_id=" + TEST_TRACK_ID
                    + "&format_id=" + QobuzModels.QUALITY_MP3
                    + "&intent=stream"
                    + "&request_ts=" + timestamp
                    + "&request_sig=" + signature;

            HttpURLConnection conn = openConnection(url);
            conn.setRequestProperty("X-App-Id", appId);
            int code = conn.getResponseCode();
            conn.disconnect();

            // 400 = bad secret, anything else (including 401 not-logged-in) = secret is valid
            return code != 400;
        } catch (Exception e) {
            Log.w(TAG, "testSecret failed: " + e.getMessage());
            return false;
        }
    }

    // ---- Login ----

    /**
     * OAuth flow step 1: exchange the authorization code (captured by WebView)
     * for a short-lived auth token. Requires init() to have populated appId
     * and privateKey.
     *
     * @return the token string returned by /oauth/callback
     */
    public String exchangeOAuthCode(String code) throws IOException {
        if (appId == null) throw new IOException("Not initialized -- call init() first");
        if (privateKey == null) {
            throw new IOException("OAuth private_key missing -- bundle too old?");
        }

        String url = API_BASE + "/oauth/callback"
                + "?code=" + java.net.URLEncoder.encode(code, "UTF-8")
                + "&private_key=" + java.net.URLEncoder.encode(privateKey, "UTF-8")
                + "&app_id=" + java.net.URLEncoder.encode(appId, "UTF-8");

        HttpURLConnection conn = openConnection(url);
        conn.setRequestProperty("X-App-Id", appId);

        int code_ = conn.getResponseCode();
        if (code_ != 200) {
            String errBody = readErrorResponse(conn);
            conn.disconnect();
            Log.w(TAG, "exchangeOAuthCode failed: HTTP " + code_ + " " + errBody);
            throw new IOException("OAuth exchange failed: HTTP " + code_);
        }

        String body = readResponse(conn);
        conn.disconnect();

        try {
            JSONObject json = new JSONObject(body);
            String token = json.optString("token", null);
            if (token == null || token.isEmpty()) {
                throw new IOException("OAuth callback: no token in response");
            }
            return token;
        } catch (Exception e) {
            throw new IOException("OAuth callback: failed to parse response", e);
        }
    }

    /**
     * OAuth flow step 2: present the token from exchangeOAuthCode() to /user/login
     * and obtain a full UserSession (user_auth_token, user_id, display_name, ...).
     */
    public boolean loginWithOAuthToken(String token) throws IOException {
        if (appId == null) throw new IOException("Not initialized -- call init() first");

        String url = API_BASE + "/user/login";
        HttpURLConnection conn = openConnection(url);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("X-App-Id", appId);
        conn.setRequestProperty("X-User-Auth-Token", token);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write("extra=partner".getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            String errBody = readErrorResponse(conn);
            conn.disconnect();
            Log.w(TAG, "loginWithOAuthToken failed: HTTP " + code + " " + errBody);
            return false;
        }

        String body = readResponse(conn);
        conn.disconnect();

        return parseLoginResponse(body);
    }

    /**
     * Login with email and password.
     * init() must be called first to have app_id.
     *
     * @deprecated Qobuz has moved to OAuth -- this endpoint no longer works for
     *     ordinary users. Use exchangeOAuthCode() + loginWithOAuthToken() via
     *     the {@code QobuzOAuthActivity} WebView flow. Kept for debug fallback.
     */
    @Deprecated
    public boolean login(String email, String password) throws IOException {
        if (appId == null) throw new IOException("Not initialized — call init() first");

        String url = API_BASE + "/user/login";
        String formBody = "email=" + java.net.URLEncoder.encode(email, "UTF-8")
                + "&password=" + java.net.URLEncoder.encode(password, "UTF-8");

        HttpURLConnection conn = openConnection(url);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("X-App-Id", appId);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(formBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();

        if (code != 200) {
            String errBody = "";
            try { errBody = readErrorResponse(conn); } catch (Exception ignored) {}
            conn.disconnect();
            Log.w(TAG, "login failed: HTTP " + code + " " + errBody);
            return false;
        }

        String body = readResponse(conn);
        conn.disconnect();

        return parseLoginResponse(body);
    }

    /**
     * Restore session from a saved user_auth_token.
     * init() must be called first.
     */
    public boolean restoreSession() throws IOException {
        if (appId == null || userAuthToken == null) return false;

        String url = API_BASE + "/user/login";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("X-App-Id", appId);
        conn.setRequestProperty("X-User-Auth-Token", userAuthToken);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write("extra=partner".getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            Log.w(TAG, "restoreSession failed: HTTP " + code);
            return false;
        }

        String body = readResponse(conn);
        conn.disconnect();

        return parseLoginResponse(body);
    }

    private boolean parseLoginResponse(String body) {
        try {
            JSONObject json = new JSONObject(body);
            userAuthToken = json.optString("user_auth_token", null);
            if (userAuthToken == null) return false;

            JSONObject user = json.optJSONObject("user");
            if (user == null) return false;

            userId = user.optLong("id", 0);
            displayName = user.optString("display_name",
                    user.optString("login", ""));

            // Check subscription
            JSONObject credential = user.optJSONObject("credential");
            if (credential != null) {
                JSONObject params = credential.optJSONObject("parameters");
                if (params != null && params.length() > 0) {
                    subscriptionLabel = params.optString("short_label", "Qobuz");
                } else {
                    Log.w(TAG, "No active subscription");
                    userAuthToken = null;
                    return false;
                }
            }

            // Persist
            prefs.edit()
                    .putString(KEY_AUTH_TOKEN, userAuthToken)
                    .putLong(KEY_USER_ID, userId)
                    .putString(KEY_DISPLAY_NAME, displayName)
                    .putString(KEY_SUBSCRIPTION, subscriptionLabel)
                    .apply();

            Log.d(TAG, "login success: " + displayName + " (" + subscriptionLabel + ")");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "parseLoginResponse failed", e);
            return false;
        }
    }

    public void logout() {
        userAuthToken = null;
        userId = 0;
        displayName = "";
        subscriptionLabel = "";
        prefs.edit()
                .remove(KEY_AUTH_TOKEN)
                .remove(KEY_USER_ID)
                .remove(KEY_DISPLAY_NAME)
                .remove(KEY_SUBSCRIPTION)
                .apply();
    }

    // ---- Request signing ----

    /**
     * MD5 signature for /track/getFileUrl.
     * Format: MD5("trackgetFileUrl" + "format_id{FID}intentstreamtrack_id{TID}" + timestamp + secret)
     */
    public String signFileUrl(long trackId, int formatId, long timestamp) {
        return signFileUrl(trackId, formatId, timestamp, validatedSecret);
    }

    static String signFileUrl(long trackId, int formatId, long timestamp, String secret) {
        String raw = "trackgetFileUrl"
                + "format_id" + formatId
                + "intentstream"
                + "track_id" + trackId
                + timestamp
                + secret;
        return md5Hex(raw);
    }

    /**
     * MD5 signature for /favorite/getUserFavorites.
     * Format: MD5("favoritegetUserFavorites" + "" + timestamp + secret)
     */
    public String signFavorites(long timestamp) {
        String raw = "favoritegetUserFavorites" + timestamp + validatedSecret;
        return md5Hex(raw);
    }

    static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    // ---- HTTP helpers ----

    private HttpURLConnection openConnection(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        return conn;
    }

    static String httpGet(String urlStr, Map<String, String> headers) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        int code = conn.getResponseCode();
        if (code >= 400) {
            conn.disconnect();
            throw new HttpException(code, "HTTP " + code);
        }
        String body = readResponse(conn);
        conn.disconnect();
        return body;
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private static String readErrorResponse(HttpURLConnection conn) {
        try {
            java.io.InputStream es = conn.getErrorStream();
            if (es == null) return "";
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(es, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } catch (Exception e) {
            return "";
        }
    }

    static class HttpException extends IOException {
        final int code;
        HttpException(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
