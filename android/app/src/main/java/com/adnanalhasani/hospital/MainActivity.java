package com.adnanalhasani.hospital;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputType;

public class MainActivity extends Activity {
    private static final String PREFS = "hospital_mobile";
    private static final String URL_KEY = "server_url";
    private static final int INK = Color.rgb(20, 45, 59);
    private static final int BRAND = Color.rgb(8, 127, 140);
    private static final int MUTED = Color.rgb(92, 117, 130);
    private WebView webView;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(15, 61, 80));
        getWindow().setNavigationBarColor(Color.rgb(15, 61, 80));
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(URL_KEY, "");
        if (saved.isEmpty()) showSetup(); else openServer(saved);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (strokeColor != Color.TRANSPARENT) shape.setStroke(dp(1), strokeColor);
        return shape;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.RIGHT);
        view.setTextDirection(View.TEXT_DIRECTION_RTL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void showSetup() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(20), dp(28), dp(20), dp(24));
        page.setBackgroundColor(Color.rgb(243, 247, 251));
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, -1);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        TextView badge = text("✚  الرعاية تبدأ باتصال آمن", 13, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(16), dp(9), dp(16), dp(9));
        badge.setBackground(rounded(BRAND, 24, Color.TRANSPARENT));

        TextView title = text("إدارة المستشفى", 29, INK, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(20), 0, dp(5));
        TextView subtitle = text("لوحة تشغيل المستشفى على هاتفك", 15, MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(24));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(22), dp(20), dp(22));
        card.setBackground(rounded(Color.WHITE, 22, Color.rgb(224, 234, 240)));
        card.setElevation(dp(3));

        TextView cardTitle = text("الاتصال بالخادم", 19, INK, true);
        cardTitle.setPadding(0, 0, 0, dp(8));
        TextView hint = text("أدخل عنوان الخادم الذي يعمل عليه نظام المستشفى. يجب تشغيل واجهة API أولاً، وأن يكون الهاتف قادرًا على الوصول إليها.", 14, MUTED, false);
        hint.setLineSpacing(dp(3), 1f);
        hint.setPadding(0, 0, 0, dp(18));

        EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setTextDirection(View.TEXT_DIRECTION_LTR);
        url.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        url.setHint("http://192.168.1.10:8080");
        url.setTextSize(15);
        url.setPadding(dp(13), dp(12), dp(13), dp(12));
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setBackground(rounded(Color.rgb(249, 252, 253), 12, Color.rgb(215, 227, 233)));
        url.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(URL_KEY, ""));

        Button connect = new Button(this);
        connect.setText("الاتصال بالنظام");
        connect.setAllCaps(false);
        connect.setTextSize(16);
        connect.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        connect.setTextColor(Color.WHITE);
        connect.setBackground(rounded(BRAND, 13, Color.TRANSPARENT));
        connect.setPadding(dp(12), dp(8), dp(12), dp(8));
        connect.setOnClickListener(v -> {
            String value = url.getText().toString().trim();
            if (!(value.startsWith("http://") || value.startsWith("https://"))) {
                Toast.makeText(this, "أدخل عنواناً يبدأ بـ http:// أو https://", Toast.LENGTH_LONG).show();
                return;
            }
            String normalized = value.replaceAll("/+$", "");
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(URL_KEY, normalized).apply();
            openServer(normalized);
        });

        TextView note = text("ملاحظة: هذا التطبيق يحتاج إلى خادم متاح عبر الشبكة، ولا يعمل حاليًا دون اتصال.", 12, MUTED, false);
        note.setPadding(dp(3), dp(16), dp(3), 0);
        card.addView(cardTitle);
        card.addView(hint);
        card.addView(url, new LinearLayout.LayoutParams(-1, dp(54)));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(52));
        buttonParams.topMargin = dp(14);
        card.addView(connect, buttonParams);
        card.addView(note);

        page.addView(badge);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(10);
        page.addView(title, titleParams);
        page.addView(subtitle);
        page.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView footer = text("نسخة تطويرية • لا تستخدم بيانات مرضى حقيقية", 12, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(22), 0, 0);
        page.addView(footer);
        setContentView(scroll);
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
