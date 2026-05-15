package com.example.media_player.qobuz;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Hosts a WebView that drives the Qobuz OAuth flow:
 *
 *   https://www.qobuz.com/signin/oauth?ext_app_id={app_id}
 *       &redirect_url=https://play.qobuz.com/discover
 *
 * The user logs in via Qobuz's own form (or Google/Apple/Facebook). When the
 * WebView navigates to play.qobuz.com/discover with a {@code code_autorisation}
 * (or {@code code}) query parameter, we capture it, set the result, and finish.
 *
 * Multi-window popups (Google/Apple/Facebook OAuth) are handled by attaching
 * a child WebView with the same redirect interception logic.
 */
public class QobuzOAuthActivity extends AppCompatActivity {

    private static final String TAG = "QobuzOAuth";

    public static final String EXTRA_APP_ID = "app_id";
    public static final String EXTRA_CODE = "code";

    private static final String OAUTH_URL_TEMPLATE =
            "https://www.qobuz.com/signin/oauth"
                    + "?ext_app_id=%s"
                    + "&redirect_url=https%%3A%%2F%%2Fplay.qobuz.com%%2Fdiscover";

    private FrameLayout root;
    private WebView mainWebView;
    private final List<WebView> popupWebViews = new ArrayList<>();
    private boolean codeReturned = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String appId = getIntent().getStringExtra(EXTRA_APP_ID);
        if (appId == null || appId.isEmpty()) {
            Log.e(TAG, "Missing EXTRA_APP_ID");
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        mainWebView = createWebView();
        root.addView(mainWebView);

        // Some Qobuz OAuth providers (Apple, Facebook) set a third-party cookie
        // for the popup -> we must allow it for the WebView to keep state.
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(mainWebView, true);

        String url = String.format(OAUTH_URL_TEMPLATE, appId);
        Log.d(TAG, "Loading OAuth URL: " + url);
        mainWebView.loadUrl(url);
    }

    /** Create a new WebView configured for the Qobuz OAuth flow. */
    private WebView createWebView() {
        WebView wv = new WebView(this);
        wv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        wv.setWebViewClient(new RedirectInterceptingClient());
        wv.setWebChromeClient(new PopupChromeClient());
        return wv;
    }

    /** Returns true if the URL contained an OAuth code and we consumed it. */
    private boolean tryCaptureCode(Uri uri) {
        if (uri == null) return false;
        if (!"play.qobuz.com".equals(uri.getHost())) return false;

        String code = uri.getQueryParameter("code_autorisation");
        if (code == null || code.isEmpty()) {
            code = uri.getQueryParameter("code");
        }
        if (code == null || code.isEmpty()) return false;
        if (codeReturned) return true; // already captured -- swallow further hits

        codeReturned = true;
        Log.d(TAG, "Captured OAuth code (length=" + code.length() + ")");

        Intent data = new Intent();
        data.putExtra(EXTRA_CODE, code);
        setResult(RESULT_OK, data);
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        for (WebView popup : popupWebViews) {
            try {
                popup.stopLoading();
                popup.destroy();
            } catch (Exception ignored) {}
        }
        popupWebViews.clear();
        if (mainWebView != null) {
            try {
                mainWebView.stopLoading();
                root.removeView(mainWebView);
                mainWebView.destroy();
            } catch (Exception ignored) {}
            mainWebView = null;
        }
        super.onDestroy();
    }

    /** Intercepts navigations to capture the OAuth redirect. */
    private class RedirectInterceptingClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return tryCaptureCode(request.getUrl());
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            // Some redirects (302) bypass shouldOverrideUrlLoading -- catch them here too.
            tryCaptureCode(Uri.parse(url));
        }
    }

    /** Handles window.open() popups for Google/Apple/Facebook OAuth providers. */
    private class PopupChromeClient extends WebChromeClient {
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog,
                                      boolean isUserGesture, Message resultMsg) {
            WebView popup = createWebView();
            popupWebViews.add(popup);
            root.addView(popup);

            CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true);

            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            if (popupWebViews.remove(window)) {
                root.removeView(window);
                try {
                    window.destroy();
                } catch (Exception ignored) {}
            }
        }
    }
}
