package com.adnanalhasani.hospital;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import android.util.Base64;

public class MainActivity extends Activity {
    private static final String PREFS = "hospital_mobile";
    private static final String TOKEN_KEY = "local_api_token";
    private static final int PORT = 8080;
    private WebView webView;
    private String localToken;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(15, 61, 80));
        getWindow().setNavigationBarColor(Color.rgb(15, 61, 80));
        localToken = getSharedPreferences(PREFS, MODE_PRIVATE).getString(TOKEN_KEY, "");
        if (localToken.isEmpty()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            localToken = Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(TOKEN_KEY, localToken).apply();
        }
        startEmbeddedApi();
    }

    private void startEmbeddedApi() {
        Thread serverThread = new Thread(() -> {
            try {
                if (!Python.isStarted()) Python.start(new AndroidPlatform(getApplicationContext()));
                Python python = Python.getInstance();
                python.getModule("os").get("environ").callAttr("__setitem__", "HOSPITAL_API_TOKEN", localToken);
                python.getModule("os").get("environ").callAttr("__setitem__", "HOSPITAL_API_HOST", "127.0.0.1");
                python.getModule("os").get("environ").callAttr("__setitem__", "HOSPITAL_API_PORT", String.valueOf(PORT));
                File database = new File(getFilesDir(), "hospital.sqlite3");
                python.getModule("os").get("environ").callAttr("__setitem__", "HOSPITAL_DB_PATH", database.getAbsolutePath());
                python.getModule("hospital.api").callAttr("serve");
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "تعذر تشغيل قاعدة البيانات المحلية: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "hospital-local-api");
        serverThread.setDaemon(true);
        serverThread.start();
        new Thread(() -> {
            boolean ready = false;
            for (int attempt = 0; attempt < 60 && !ready; attempt++) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", PORT), 400);
                    ready = true;
                } catch (Exception ignored) {
                    try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            }
            if (ready) runOnUiThread(this::openLocalDashboard);
            else runOnUiThread(() -> Toast.makeText(this, "تعذر بدء قاعدة البيانات المحلية. أعد تشغيل التطبيق.", Toast.LENGTH_LONG).show());
        }, "hospital-api-ready-check").start();
    }

    private void openLocalDashboard() {
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                String script = "(function(){"
                    + "var base=document.getElementById('base');if(base)base.value='http://127.0.0.1:" + PORT + "';"
                    + "var token=document.getElementById('token');if(token)token.value='" + localToken + "';"
                    + "var managerToken=document.getElementById('api-token');if(managerToken)managerToken.value='" + localToken + "';"
                    + "var connect=document.getElementById('connect');if(connect)connect.click();"
                    + "})();";
                view.evaluateJavascript(script, null);
            }
        });
        setContentView(webView);
        webView.loadUrl("http://127.0.0.1:" + PORT + "/");
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
