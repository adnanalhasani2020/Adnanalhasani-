package com.adnanalhasani.hospital;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputType;

public class MainActivity extends Activity {
    private static final String PREFS = "hospital_mobile";
    private static final String URL_KEY = "server_url";
    private WebView webView;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(15, 67, 88));
        getWindow().setNavigationBarColor(Color.rgb(15, 67, 88));
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(URL_KEY, "");
        if (saved.isEmpty()) showSetup(); else openServer(saved);
    }

    private void showSetup() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(42, 60, 42, 32);
        box.setBackgroundColor(Color.rgb(244, 249, 252));
        TextView title = new TextView(this);
        title.setText("إدارة المستشفى");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(15, 67, 88));
        title.setPadding(0, 0, 0, 18);
        TextView hint = new TextView(this);
        hint.setText("أدخل عنوان الخادم الذي يعمل عليه نظام المستشفى. يجب تشغيل واجهة API أولاً.");
        hint.setTextSize(16);
        hint.setTextColor(Color.DKGRAY);
        hint.setPadding(0, 0, 0, 18);
        EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setHint("http://192.168.1.10:8080");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(URL_KEY, ""));
        Button connect = new Button(this);
        connect.setText("الاتصال بالنظام");
        connect.setOnClickListener(v -> {
            String value = url.getText().toString().trim();
            if (!(value.startsWith("http://") || value.startsWith("https://"))) {
                Toast.makeText(this, "أدخل عنواناً يبدأ بـ http:// أو https://", Toast.LENGTH_LONG).show();
                return;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(URL_KEY, value.replaceAll("/+$", "")).apply();
            openServer(value.replaceAll("/+$", ""));
        });
        Button change = new Button(this);
        change.setText("تعديل عنوان الخادم");
        change.setOnClickListener(v -> showSetup());
        box.addView(title);
        box.addView(hint);
        box.addView(url, new LinearLayout.LayoutParams(-1, -2));
        box.addView(connect, new LinearLayout.LayoutParams(-1, -2));
        setContentView(box);
    }

    private void openServer(String baseUrl) {
        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        setContentView(webView);
        webView.loadUrl(baseUrl);
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else showSetup();
    }
}
