package com.omer.ceptavla;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView web;
    private NetBridge net;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#1b120d"));
        getWindow().setNavigationBarColor(Color.parseColor("#1b120d"));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#1b120d"));
        web.setWebViewClient(new WebViewClient());
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);

        net = new NetBridge(this, web);
        web.addJavascriptInterface(net, "TavlaNet");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.onAndroidBack&&window.onAndroidBack())?'1':'0'", value -> {
            if (value == null || !value.contains("1")) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onPause() { super.onPause(); web.onPause(); }

    @Override
    protected void onResume() { super.onResume(); web.onResume(); }

    @Override
    protected void onDestroy() {
        net.shutdown();
        web.destroy();
        super.onDestroy();
    }
}
