package com.kanha.firkaizenmod;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String TARGET_URL = "https://play.firkaizen.id/";
    private static final String PREF_NAME = "KanhaModPrefs";

    private WebView webView;
    private LinearLayout modPanel;
    private TextView hudOverlay;
    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isAdBlockActive = true;
    private boolean isAutoLoginActive = true;
    private boolean isAutoClickerActive = false;
    private boolean isAntiAfkActive = true;
    private float currentSpeedMultiplier = 1.0f;
    private float tapX = 500f, tapY = 500f;

    private static final Set<String> AD_HOSTS = new HashSet<>(Arrays.asList(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "adnxs.com", "advertising.com",
            "popads.net", "propellerads.com", "exoclick.com", "juicyads.com",
            "unityads.unity3d.com", "applovin.com", "vungle.com", "ironsrc.com",
            "taboola.com", "outbrain.com", "adcolony.com", "chartboost.com",
            "yandex.ru", "mc.yandex.ru", "analytics.google.com"
    ));

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        isAdBlockActive = prefs.getBoolean("adblock", true);
        isAutoLoginActive = prefs.getBoolean("autologin", true);

        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        setupWebView(webView);
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setupHudOverlay(rootLayout);
        setupFloatingModUI(rootLayout);
        setContentView(rootLayout);

        restoreSessionAndLoad();
        startBackgroundLoops();
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void setupWebView(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        );

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);

        wv.addJavascriptInterface(new KanhaBridge(), "KanhaBridge");
        wv.setWebChromeClient(new WebChromeClient());
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if (isAdBlockActive && req.getUrl() != null) {
                    String host = req.getUrl().getHost();
                    String url = req.getUrl().toString().toLowerCase();
                    if ((host != null && isAdDomain(host.toLowerCase())) ||
                        url.contains("/ads/") || url.contains("popunder") || url.contains("adserver")) {
                        return new WebResourceResponse(
                                "text/plain", "UTF-8",
                                new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8))
                        );
                    }
                }
                return super.shouldInterceptRequest(view, req);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (req.getUrl() == null) return false;
                String host = req.getUrl().getHost();
                if (isAdBlockActive && host != null && !host.contains("firkaizen.id")) {
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                injectMasterPayload();
            }
        });
    }

    private void restoreSessionAndLoad() {
        String savedToken = prefs.getString("session_token", "");
        if (!savedToken.isEmpty()) {
            CookieManager.getInstance().setCookie(TARGET_URL, savedToken);
            CookieManager.getInstance().flush();
        }
        webView.loadUrl(TARGET_URL);
    }

    private boolean isAdDomain(String host) {
        for (String ad : AD_HOSTS) {
            if (host.equals(ad) || host.endsWith("." + ad)) return true;
        }
        return false;
    }

    private void injectMasterPayload() {
        String savedUser = prefs.getString("saved_user", "");
        String savedPass = prefs.getString("saved_pass", "");

        String js = "javascript:(function() {" +
                "if (" + isAdBlockActive + ") {" +
                "  window.open = function() { return null; };" +
                "  const cleanAds = () => {" +
                "    const sel = ['iframe[src*=\"ads\"]', '.ad-banner', '.adsbox', '[id*=\"google_ads\"]', '.pop-ad', '.overlay-ad', 'ins.adsbygoogle'];" +
                "    sel.forEach(s => document.querySelectorAll(s).forEach(e => e.remove()));" +
                "  };" +
                "  cleanAds();" +
                "  if (!window._adObs) {" +
                "    window._adObs = new MutationObserver(cleanAds);" +
                "    window._adObs.observe(document.body, { childList: true, subtree: true });" +
                "  }" +
                "}" +
                "if (" + isAutoLoginActive + ") {" +
                "  const uField = document.querySelector('input[type=\"text\"], input[type=\"email\"], input[name*=\"user\"], input[id*=\"user\"]');" +
                "  const pField = document.querySelector('input[type=\"password\"]');" +
                "  if (uField && pField) {" +
                "    const sUser = '" + savedUser.replace("'", "\\'") + "';" +
                "    const sPass = '" + savedPass.replace("'", "\\'") + "';" +
                "    if (sUser && sPass && !uField.value) {" +
                "      uField.value = sUser;" +
                "      pField.value = sPass;" +
                "      uField.dispatchEvent(new Event('input', { bubbles: true }));" +
                "      pField.dispatchEvent(new Event('input', { bubbles: true }));" +
                "      const btn = document.querySelector('button[type=\"submit\"], input[type=\"submit\"], .btn-login, #login-btn');" +
                "      if (btn && !window._autoSubmitted) { window._autoSubmitted = true; setTimeout(() => btn.click(), 400); }" +
                "    }" +
                "    const form = pField.closest('form') || document.body;" +
                "    form.addEventListener('submit', () => {" +
                "      if (uField.value && pField.value) KanhaBridge.saveCredentials(uField.value, pField.value);" +
                "    });" +
                "  }" +
                "}" +
                "if (!window._kanhaSpeedInit) {" +
                "  window._kanhaSpeedInit = true;" +
                "  window._speedMult = " + currentSpeedMultiplier + ";" +
                "  const origRAF = window.requestAnimationFrame;" +
                "  window.requestAnimationFrame = function(cb) {" +
                "    return origRAF(function(ts) { cb(ts * window._speedMult); });" +
                "  };" +
                "  const origPerf = performance.now.bind(performance);" +
                "  performance.now = function() { return origPerf() * window._speedMult; };" +
                "} else {" +
                "  window._speedMult = " + currentSpeedMultiplier + ";" +
                "}" +
                "if (" + isAntiAfkActive + " && !window._afkSpoofed) {" +
                "  window._afkSpoofed = true;" +
                "  Object.defineProperty(document, 'hidden', { get: () => false });" +
                "  Object.defineProperty(document, 'visibilityState', { get: () => 'visible' });" +
                "}" +
                "if (!window._fpsInit) {" +
                "  window._fpsInit = true;" +
                "  let frames = 0, lastTime = performance.now() / (window._speedMult || 1);" +
                "  function calcFps() {" +
                "    frames++;" +
                "    const now = performance.now() / (window._speedMult || 1);" +
                "    if (now - lastTime >= 1000) {" +
                "      KanhaBridge.updateFps(frames);" +
                "      frames = 0; lastTime = now;" +
                "    }" +
                "    requestAnimationFrame(calcFps);" +
                "  }" +
                "  requestAnimationFrame(calcFps);" +
                "}" +
                "})();";

        webView.evaluateJavascript(js, null);
    }

    private void setupHudOverlay(FrameLayout root) {
        hudOverlay = new TextView(this);
        hudOverlay.setText("FPS: -- | SPD: 1.0x | LOGIN: ON");
        hudOverlay.setTextColor(Color.parseColor("#00FF66"));
        hudOverlay.setTextSize(10f);
        hudOverlay.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        hudOverlay.setPadding(16, 6, 16, 6);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(160, 0, 0, 0));
        bg.setCornerRadius(12f);
        hudOverlay.setBackground(bg);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.topMargin = 20;
        params.rightMargin = 20;
        root.addView(hudOverlay, params);
    }

    @SuppressLint({"ClickableViewAccessibility", "SetTextI18n"})
    private void setupFloatingModUI(FrameLayout root) {
        Button toggleBtn = new Button(this);
        toggleBtn.setText("K-MOD");
        toggleBtn.setTextColor(Color.parseColor("#00FF66"));
        toggleBtn.setTextSize(10f);
        toggleBtn.setTypeface(Typeface.DEFAULT_BOLD);

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.argb(220, 15, 15, 20));
        btnBg.setCornerRadius(100f);
        btnBg.setStroke(3, Color.parseColor("#00FF66"));
        toggleBtn.setBackground(btnBg);

        FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(135, 135);
        btnParams.gravity = Gravity.TOP | Gravity.START;
        btnParams.leftMargin = 35;
        btnParams.topMargin = 90;
        root.addView(toggleBtn, btnParams);

        final TextView crosshair = new TextView(this);
        crosshair.setText("[+]");
        crosshair.setTextColor(Color.RED);
        crosshair.setTextSize(18f);
        crosshair.setTypeface(Typeface.DEFAULT_BOLD);
        crosshair.setVisibility(View.GONE);
        FrameLayout.LayoutParams chParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        chParams.gravity = Gravity.TOP | Gravity.START;
        chParams.leftMargin = (int) tapX;
        chParams.topMargin = (int) tapY;
        root.addView(crosshair, chParams);

        crosshair.setOnTouchListener((v, e) -> {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                tapX = e.getRawX() - (v.getWidth() / 2f);
                tapY = e.getRawY() - (v.getHeight() / 2f);
                p.leftMargin = (int) tapX;
                p.topMargin = (int) tapY;
                v.setLayoutParams(p);
            }
            return true;
        });

        modPanel = new LinearLayout(this);
        modPanel.setOrientation(LinearLayout.VERTICAL);
        modPanel.setPadding(36, 30, 36, 30);
        modPanel.setVisibility(View.GONE);

        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(Color.argb(240, 14, 16, 22));
        panelBg.setCornerRadius(28f);
        panelBg.setStroke(2, Color.parseColor("#00FF66"));
        modPanel.setBackground(panelBg);

        TextView header = new TextView(this);
        header.setText("FIRKAIZEN PRO ENGINE v2");
        header.setTextColor(Color.parseColor("#00FF66"));
        header.setTextSize(14f);
        header.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.setPadding(0, 0, 0, 16);
        modPanel.addView(header);

        Switch autoLoginSw = new Switch(this);
        autoLoginSw.setText("Auto-Login & Cookie Lock ");
        autoLoginSw.setTextColor(Color.WHITE);
        autoLoginSw.setChecked(isAutoLoginActive);
        autoLoginSw.setOnCheckedChangeListener((b, val) -> {
            isAutoLoginActive = val;
            prefs.edit().putBoolean("autologin", val).apply();
            if (val) injectMasterPayload();
        });
        modPanel.addView(autoLoginSw);

        Switch adBlockSw = new Switch(this);
        adBlockSw.setText("Deep AdBlock & Anti-Redirect ");
        adBlockSw.setTextColor(Color.WHITE);
        adBlockSw.setChecked(isAdBlockActive);
        adBlockSw.setOnCheckedChangeListener((b, val) -> {
            isAdBlockActive = val;
            prefs.edit().putBoolean("adblock", val).apply();
            if (val) injectMasterPayload();
        });
        modPanel.addView(adBlockSw);

        Switch afkSw = new Switch(this);
        afkSw.setText("Anti-AFK / Background Awake ");
        afkSw.setTextColor(Color.WHITE);
        afkSw.setChecked(isAntiAfkActive);
        afkSw.setOnCheckedChangeListener((b, val) -> {
            isAntiAfkActive = val;
            if (val) injectMasterPayload();
        });
        modPanel.addView(afkSw);

        Switch clickerSw = new Switch(this);
        clickerSw.setText("Auto-Tapper (10 Taps/sec) ");
        clickerSw.setTextColor(Color.WHITE);
        clickerSw.setChecked(false);
        clickerSw.setOnCheckedChangeListener((b, val) -> {
            isAutoClickerActive = val;
            crosshair.setVisibility(val ? View.VISIBLE : View.GONE);
        });
        modPanel.addView(clickerSw);

        final TextView speedLabel = new TextView(this);
        speedLabel.setText("Engine Speed Multiplier: 1.0x");
        speedLabel.setTextColor(Color.CYAN);
        speedLabel.setPadding(0, 14, 0, 4);
        modPanel.addView(speedLabel);

        SeekBar speedSeek = new SeekBar(this);
        speedSeek.setMax(45);
        speedSeek.setProgress(5);
        speedSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentSpeedMultiplier = (progress + 5) / 10.0f;
                speedLabel.setText("Engine Speed Multiplier: " + currentSpeedMultiplier + "x");
                injectMasterPayload();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        modPanel.addView(speedSeek);

        Button credBtn = createStyledButton("Set Auto-Login Account");
        credBtn.setOnClickListener(v -> showCredentialDialog());
        modPanel.addView(credBtn);

        Button storageEditorBtn = createStyledButton("Live Web Storage Editor (VIP/Coins)");
        storageEditorBtn.setOnClickListener(v -> openLiveStorageInspector());
        modPanel.addView(storageEditorBtn);

        Button reloadBtn = createStyledButton("Save Session & Reload Web");
        reloadBtn.setOnClickListener(v -> {
            String cookies = CookieManager.getInstance().getCookie(TARGET_URL);
            if (cookies != null) prefs.edit().putString("session_token", cookies).apply();
            CookieManager.getInstance().flush();
            webView.reload();
            Toast.makeText(this, "Session Lock Saved!", Toast.LENGTH_SHORT).show();
        });
        modPanel.addView(reloadBtn);

        ScrollView scrollWrapper = new ScrollView(this);
        scrollWrapper.addView(modPanel);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(680, FrameLayout.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.CENTER;
        root.addView(scrollWrapper, panelParams);

        toggleBtn.setOnTouchListener(new View.OnTouchListener() {
            private int initX, initY;
            private float touchX, touchY;
            private long startClick;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
                switch (ev.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startClick = System.currentTimeMillis();
                        initX = p.leftMargin;
                        initY = p.topMargin;
                        touchX = ev.getRawX();
                        touchY = ev.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        p.leftMargin = initX + (int) (ev.getRawX() - touchX);
                        p.topMargin = initY + (int) (ev.getRawY() - touchY);
                        v.setLayoutParams(p);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (System.currentTimeMillis() - startClick < 200) {
                            modPanel.setVisibility(
                                    modPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE
                            );
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private Button createStyledButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(11f);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.argb(255, 35, 40, 52));
        d.setCornerRadius(14f);
        d.setStroke(1, Color.parseColor("#00FF66"));
        b.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = 12;
        b.setLayoutParams(lp);
        return b;
    }

    private void showCredentialDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        final EditText userIn = new EditText(this);
        userIn.setHint("Username / Email / ID");
        userIn.setText(prefs.getString("saved_user", ""));
        layout.addView(userIn);

        final EditText passIn = new EditText(this);
        passIn.setHint("Password");
        passIn.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passIn.setText(prefs.getString("saved_pass", ""));
        layout.addView(passIn);

        new AlertDialog.Builder(this)
                .setTitle("Auto-Login Vault")
                .setView(layout)
                .setPositiveButton("Save & Inject", (d, w) -> {
                    prefs.edit()
                            .putString("saved_user", userIn.getText().toString().trim())
                            .putString("saved_pass", passIn.getText().toString().trim())
                            .apply();
                    injectMasterPayload();
                    Toast.makeText(this, "Auto-Login Active!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openLiveStorageInspector() {
        String js = "javascript:(function() {" +
                "let out = {};" +
                "for (let i = 0; i < localStorage.length; i++) {" +
                "  let k = localStorage.key(i);" +
                "  out[k] = localStorage.getItem(k);" +
                "}" +
                "return JSON.stringify(out);" +
                "})();";

        webView.evaluateJavascript(js, rawJson -> {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(40, 20, 40, 20);

            TextView dumpView = new TextView(this);
            dumpView.setText("Current Web Data:\n" + rawJson);
            dumpView.setTextSize(10f);
            dumpView.setPadding(0, 0, 0, 16);
            box.addView(dumpView);

            final EditText keyInput = new EditText(this);
            keyInput.setHint("Key (contoh: coins / vip)");
            box.addView(keyInput);

            final EditText valInput = new EditText(this);
            valInput.setHint("New Value (contoh: 999999)");
            box.addView(valInput);

            new AlertDialog.Builder(this)
                    .setTitle("Live localStorage Editor")
                    .setView(box)
                    .setPositiveButton("Write Key", (d, w) -> {
                        String k = keyInput.getText().toString().trim();
                        String v = valInput.getText().toString().trim();
                        if (!k.isEmpty()) {
                            webView.evaluateJavascript(
                                    "localStorage.setItem('" + k + "', '" + v + "');", null
                            );
                            Toast.makeText(this, "Injected: " + k + " = " + v, Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNeutralButton("Quick VIP Preset", (d, w) -> {
                        String preset = "localStorage.setItem('vip','true');" +
                                "localStorage.setItem('is_premium','1');" +
                                "localStorage.setItem('coins','9999999');";
                        webView.evaluateJavascript(preset, null);
                        Toast.makeText(this, "VIP & Currency Presets Injected!", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Close", null)
                    .show();
        });
    }

    private void startBackgroundLoops() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isAutoClickerActive && webView != null) {
                    String tapJs = "javascript:(function() {" +
                            "const el = document.elementFromPoint(" + (tapX / webView.getScale()) + ", " + (tapY / webView.getScale()) + ");" +
                            "if (el) {" +
                            "  el.click();" +
                            "  el.dispatchEvent(new MouseEvent('mousedown', {bubbles:true}));" +
                            "  el.dispatchEvent(new MouseEvent('mouseup', {bubbles:true}));" +
                            "}" +
                            "})();";
                    webView.evaluateJavascript(tapJs, null);
                }
                mainHandler.postDelayed(this, 100);
            }
        }, 100);
    }

    private class KanhaBridge {
        @JavascriptInterface
        public void saveCredentials(String user, String pass) {
            prefs.edit()
                    .putString("saved_user", user)
                    .putString("saved_pass", pass)
                    .apply();
            CookieManager.getInstance().flush();
        }

        @JavascriptInterface
        public void updateFps(final int fps) {
            mainHandler.post(() -> {
                if (hudOverlay != null) {
                    String loginState = (!prefs.getString("saved_user", "").isEmpty()) ? "LOCKED" : "WAIT";
                    hudOverlay.setText(
                            "FPS: " + fps +
                            " | SPD: " + currentSpeedMultiplier + "x" +
                            " | LOGIN: " + loginState
                    );
                }
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    public void onBackPressed() {
        if (modPanel != null && modPanel.getVisibility() == View.VISIBLE) {
            modPanel.setVisibility(View.GONE);
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
