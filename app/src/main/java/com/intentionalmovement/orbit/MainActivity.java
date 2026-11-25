package com.intentionalmovement.orbit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.MediaSession;
import org.mozilla.geckoview.PanZoomController;
import org.mozilla.geckoview.ScreenLength;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.geckoview.WebResponse;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Orbit's screen: an address bar on top, the page, and big E Ink buttons below (◀ ▶ back/forward, ▲ ▼ a screen or a
 * Kindle page, ⟳ reload, ☰ menu). Starts with the default tabs (Kindle, Audible, Google; change them in ☰). With the
 * VPN on, nothing loads until Proton VPN is connected, and browsing pauses if it drops.
 */
public class MainActivity extends Activity implements Vpn.Listener {
    static final String[][] START = {
        { "Kindle", "https://read.amazon.com/", "0" },
        { "Audible", "https://www.audible.com/library/titles", "1" },
        { "Google", "https://www.google.com/", "0" },
    };
    private static final int REQ_VPN = 1, REQ_CONF = 2;
    static WeakReference<MainActivity> current = new WeakReference<>(null);
    static volatile boolean visible;

    private SharedPreferences prefs;
    private GeckoRuntime runtime;
    private Vpn vpn;
    private GeckoView view;
    private EditText address;
    private Button bTabs, bVpn, bReload;
    private LinearLayout banner;
    private TextView bannerText;
    private View flash;
    private final Handler ui = new Handler(Looper.getMainLooper());
    // the tabs live as long as the app does (the screen may be rebuilt while a tab keeps playing)
    private static final List<Tab> tabs = new ArrayList<>();
    private static Tab cur;
    private static boolean opened, paused;
    private String pendingLink;

    // ================================================================== start
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = new WeakReference<>(this);
        prefs = OrbitApp.prefs(this);
        runtime = OrbitApp.runtime(this);
        vpn = OrbitApp.vpn(this);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        buildScreen();
        pendingLink = linkOf(getIntent());
        if (!tabs.isEmpty()) { // the screen was rebuilt: reattach the tabs that are still open
            for (Tab t : tabs) if (t.session != null) wire(t);
            Tab c = cur; cur = null;
            if (!paused) show(c != null && tabs.contains(c) ? c : tabs.get(0));
            updateBars();
        }
        vpn.listen(this);
        if (vpn.wanted()) startVpn(); else openStartTabs();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String link = linkOf(i);
        if (link == null) return;
        if (opened && !paused) show(newTab(link, false, false, true)); else pendingLink = link;
    }

    private static String linkOf(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return null;
        String s = i.getData().getScheme();
        return ("http".equals(s) || "https".equals(s)) ? i.getData().toString() : null;
    }

    private void openStartTabs() {
        if (opened) return;
        opened = true;
        List<String[]> d = defaults();
        int last = Math.min(Math.max(prefs.getInt("lastDefault", 0), 0), Math.max(d.size() - 1, 0));
        for (String[] t : d) newTab(t[1], "1".equals(t[2]), true, false).title = t[0];
        if (tabs.isEmpty()) newTab("https://duckduckgo.com/", false, false, false);
        show(tabs.get(Math.min(last, tabs.size() - 1)));
        if (pendingLink != null) { show(newTab(pendingLink, false, false, true)); pendingLink = null; }
    }

    // ================================================================== the screen
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private Button button(ViewGroup bar, String label, float weight, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextSize(17); b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.BLACK); b.setBackgroundColor(Color.WHITE); b.setGravity(Gravity.CENTER);
        b.setPadding(dp(4), 0, dp(4), 0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = weight > 0 ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(1, 0, 1, 0);
        bar.addView(b, lp);
        return b;
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setBackgroundColor(Color.BLACK);
        top.setPadding(0, 0, 0, 2);
        bTabs = button(top, "1", 0, v -> tabsSheet());
        bTabs.setMinimumWidth(dp(48));
        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(15);
        address.setTextColor(Color.BLACK);
        address.setBackgroundColor(Color.WHITE);
        address.setPadding(dp(8), 0, dp(8), 0);
        address.setHint("Search or address");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setSelectAllOnFocus(true);
        address.setOnEditorActionListener((v, id, ev) -> { go(address.getText().toString()); return true; });
        address.setOnFocusChangeListener((v, has) -> { if (!has) showAddress(); });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        alp.setMargins(1, 0, 1, 0);
        top.addView(address, alp);
        bVpn = button(top, "VPN", 0, v -> vpnSheet());
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setBackgroundColor(Color.WHITE);
        banner.setPadding(dp(10), dp(8), dp(10), dp(8));
        bannerText = new TextView(this);
        bannerText.setTextColor(Color.BLACK); bannerText.setTextSize(15);
        banner.addView(bannerText);
        banner.setVisibility(View.GONE);
        root.addView(banner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout page = new FrameLayout(this);
        view = new GeckoView(this);
        page.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        flash = new View(this);
        flash.setVisibility(View.GONE);
        page.addView(flash, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(page, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.BLACK);
        bar.setPadding(0, 2, 0, 0);
        button(bar, "◀", 1, v -> { if (cur != null && cur.canBack) cur.session.goBack(); });
        button(bar, "▶", 1, v -> { if (cur != null && cur.canForward) cur.session.goForward(); });
        button(bar, "▲", 1, v -> page(false));
        button(bar, "▼", 1, v -> page(true));
        bReload = button(bar, "⟳", 1, v -> { if (cur == null) return; if (cur.loading) cur.session.stop(); else cur.session.reload(); });
        button(bar, "☰", 1, this::menu);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        setContentView(root);
    }

    private void showBanner(String text, String a1, Runnable r1, String a2, Runnable r2) {
        bannerText.setText(text);
        while (banner.getChildCount() > 1) banner.removeViewAt(1);
        if (a1 != null || a2 != null) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundColor(Color.BLACK);
            row.setPadding(0, 2, 0, 2);
            if (a1 != null) button(row, a1, 1, v -> r1.run());
            if (a2 != null) button(row, a2, 1, v -> r2.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            lp.topMargin = dp(6);
            banner.addView(row, lp);
        }
        banner.setVisibility(View.VISIBLE);
    }
    private void hideBanner() { banner.setVisibility(View.GONE); }

    void clearGhosting() {
        flash.setBackgroundColor(Color.BLACK); flash.setVisibility(View.VISIBLE);
        ui.postDelayed(() -> flash.setBackgroundColor(Color.WHITE), 120);
        ui.postDelayed(() -> flash.setVisibility(View.GONE), 240);
    }

    private void updateBars() {
        bTabs.setText(String.valueOf(tabs.size()));
        bReload.setText(cur != null && cur.loading ? "✕" : "⟳");
        if (!address.hasFocus()) showAddress();
    }
    private void showAddress() { address.setText(cur == null ? "" : (cur.url.isEmpty() && cur.pending != null ? cur.pending : cur.url)); }

    // ================================================================== going places
    private void go(String input) {
        String s = input.trim();
        if (s.isEmpty() || cur == null) return;
        String url;
        if (s.matches("(?i)^[a-z][a-z0-9+.-]*://.*") || s.startsWith("about:")) url = s;
        else if (!s.contains(" ") && s.matches("^[^/]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?$")) url = "https://" + s;
        else url = searchUrl(s);
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(address.getWindowToken(), 0);
        address.clearFocus();
        view.requestFocus();
        load(cur, url);
    }

    private String searchUrl(String q) {
        String e;
        try { e = URLEncoder.encode(q, "UTF-8"); } catch (java.io.UnsupportedEncodingException x) { e = Uri.encode(q); }
        switch (prefs.getString("search", "ddg")) {
            case "google": return "https://www.google.com/search?q=" + e;
            case "startpage": return "https://www.startpage.com/do/search?query=" + e;
            default: return "https://duckduckgo.com/?q=" + e;
        }
    }

    private void load(Tab t, String url) {
        if (paused) { t.url = url; toast("Browsing is paused until the VPN is back."); return; }
        t.pending = null;
        t.session.loadUri(url);
    }

    /** ▲ ▼: a page in the Kindle reader (the arrow keys it understands), a screen elsewhere. */
    private void page(boolean forward) {
        if (cur == null) return;
        if (cur.isKindle()) {
            int code = forward ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
            view.requestFocus();
            view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        } else {
            cur.session.getPanZoomController().scrollBy(ScreenLength.zero(), ScreenLength.fromVisualViewportHeight(forward ? 0.85 : -0.85), PanZoomController.SCROLL_BEHAVIOR_AUTO);
        }
        if (prefs.getBoolean("ghostEveryPage", false)) ui.postDelayed(this::clearGhosting, 250);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (prefs.getBoolean("volumeKeys", true) && cur != null && cur.isKindle() && (k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) page(k == KeyEvent.KEYCODE_VOLUME_DOWN);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public void onBackPressed() {
        if (cur != null && cur.canBack) cur.session.goBack();
        else moveTaskToBack(true); // tabs (and audio) stay alive
    }

    // ================================================================== tabs
    private Tab newTab(String url, boolean desktop, boolean isDefault, boolean loadNow) {
        Tab t = new Tab();
        t.desktop = desktop; t.isDefault = isDefault; t.home = url;
        makeSession(t, true);
        tabs.add(t);
        if (loadNow && !paused) t.session.loadUri(url); else t.pending = url;
        updateBars();
        return t;
    }

    private void makeSession(Tab t, boolean open) {
        t.session = new GeckoSession(Tab.settings(t.desktop, t.shield));
        wire(t);
        if (open) t.session.open(runtime);
    }

    private void show(Tab t) { show(t, 0); }

    private void show(Tab t, int tries) {
        if (t == null || t.session == null) return;
        if (paused) { cur = t; toast("Browsing is paused until the VPN is back."); return; }
        if (!t.session.isOpen()) { if (tries < 50) ui.postDelayed(() -> show(t, tries + 1), 100); return; } // (a pop-up the engine is still opening)
        if (cur != null && cur != t && cur.session != null) cur.session.setActive(cur.playing);
        if (view.getSession() != t.session) { view.releaseSession(); view.setSession(t.session); }
        t.session.setActive(true);
        cur = t;
        if (t.pending != null && !paused) { String u = t.pending; t.pending = null; t.session.loadUri(u); }
        if (t.isDefault) prefs.edit().putInt("lastDefault", Math.max(0, defaultIndex(t))).apply();
        updateBars();
        clearGhosting();
    }

    private int defaultIndex(Tab t) { int i = 0; for (Tab x : tabs) { if (x == t) return i; if (x.isDefault) i++; } return -1; }

    private void closeTab(Tab t) {
        int i = tabs.indexOf(t);
        if (i < 0) return;
        if (t.playing && t.media != null) t.media.pause();
        if (view.getSession() == t.session) view.releaseSession();
        if (t.session != null) t.session.close();
        tabs.remove(t);
        if (tabs.isEmpty()) { cur = null; newTab(defaults().isEmpty() ? "https://duckduckgo.com/" : defaults().get(0)[1], false, false, true); show(tabs.get(0)); return; }
        if (cur == t) { cur = null; show(tabs.get(Math.min(i, tabs.size() - 1))); }
        updateBars();
    }

    private void tabsSheet() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(4), dp(8), dp(4));
        AlertDialog[] d = new AlertDialog[1];
        for (Tab t : new ArrayList<>(tabs)) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundColor(Color.BLACK);
            row.setPadding(0, 1, 0, 1);
            String mark = (t == cur ? "▶ " : "") + (t.playing ? "♪ " : "");
            Button open = button(row, mark + t.label() + (t.isDefault ? "  ·  default" : ""), 1, v -> { d[0].dismiss(); show(t); });
            open.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            open.setSingleLine(true); open.setEllipsize(TextUtils.TruncateAt.END); open.setTextSize(15);
            button(row, "✕", 0, v -> { d[0].dismiss(); closeTab(t); tabsSheet(); }).setMinimumWidth(dp(52));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            lp.bottomMargin = dp(6);
            list.addView(row, lp);
        }
        ScrollView sv = new ScrollView(this); sv.addView(list);
        d[0] = new AlertDialog.Builder(this).setTitle("Tabs").setView(sv)
            .setPositiveButton("New tab", (x, w) -> { Tab t = newTab("https://duckduckgo.com/", false, false, true); show(t); address.requestFocus(); })
            .setNeutralButton("Default tabs…", (x, w) -> defaultsSheet())
            .setNegativeButton("Close", null).show();
    }

    // ================================================================== default tabs (opened every time Orbit starts)
    List<String[]> defaults() {
        List<String[]> out = new ArrayList<>();
        String s = prefs.getString("defaults", null);
        if (s == null) { for (String[] x : START) out.add(x.clone()); return out; }
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); out.add(new String[]{ o.getString("t"), o.getString("u"), o.optBoolean("d") ? "1" : "0" }); }
        } catch (Exception e) { for (String[] x : START) out.add(x.clone()); }
        return out;
    }
    void saveDefaults(List<String[]> d) {
        JSONArray a = new JSONArray();
        try { for (String[] x : d) a.put(new JSONObject().put("t", x[0]).put("u", x[1]).put("d", "1".equals(x[2]))); } catch (Exception ignored) { }
        prefs.edit().putString("defaults", a.toString()).apply();
    }

    private void addCurrentToDefaults() {
        if (cur == null || cur.url.isEmpty()) { toast("Open a page first."); return; }
        List<String[]> d = defaults();
        d.add(new String[]{ cur.label(), cur.url, cur.desktop ? "1" : "0" });
        saveDefaults(d);
        cur.isDefault = true;
        toast("“" + cur.label() + "” now opens every time Orbit starts.");
    }

    private void defaultsSheet() {
        List<String[]> d = defaults();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(4), dp(8), dp(4));
        TextView note = new TextView(this);
        note.setText("These tabs open every time Orbit starts, in this order.");
        note.setTextColor(Color.BLACK); note.setTextSize(15); note.setPadding(dp(4), 0, dp(4), dp(8));
        list.addView(note);
        AlertDialog[] dlg = new AlertDialog[1];
        for (int i = 0; i < d.size(); i++) {
            final int k = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundColor(Color.BLACK);
            row.setPadding(0, 1, 0, 1);
            Button name = button(row, d.get(i)[0] + ("1".equals(d.get(i)[2]) ? " (desktop)" : ""), 1, v -> { dlg[0].dismiss(); renameDefault(d, k); });
            name.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END); name.setTextSize(15);
            if (i > 0) button(row, "▲", 0, v -> { String[] x = d.remove(k); d.add(k - 1, x); saveDefaults(d); dlg[0].dismiss(); defaultsSheet(); }).setMinimumWidth(dp(48));
            button(row, "✕", 0, v -> { d.remove(k); saveDefaults(d); dlg[0].dismiss(); defaultsSheet(); }).setMinimumWidth(dp(48));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            lp.bottomMargin = dp(6);
            list.addView(row, lp);
        }
        ScrollView sv = new ScrollView(this); sv.addView(list);
        dlg[0] = new AlertDialog.Builder(this).setTitle("Default tabs").setView(sv)
            .setPositiveButton("Add this page", (x, w) -> { addCurrentToDefaults(); defaultsSheet(); })
            .setNeutralButton("Reset", (x, w) -> { prefs.edit().remove("defaults").apply(); toast("Back to Kindle, Audible and Google."); })
            .setNegativeButton("Close", null).show();
    }

    private void renameDefault(List<String[]> d, int k) {
        EditText in = new EditText(this);
        in.setText(d.get(k)[0]); in.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("Name").setMessage(d.get(k)[1]).setView(in)
            .setPositiveButton("Save", (x, w) -> { String n = in.getText().toString().trim(); if (!n.isEmpty()) { d.get(k)[0] = n; saveDefaults(d); } defaultsSheet(); })
            .setNegativeButton("Cancel", (x, w) -> defaultsSheet()).show();
    }

    // ================================================================== what each tab tells us
    private void wire(Tab t) {
        GeckoSession s = t.session;
        s.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public void onLocationChange(GeckoSession ss, String url, List<PermissionDelegate.ContentPermission> perms, Boolean gesture) {
                if (url != null && !url.startsWith("about:blank")) t.url = url;
                if (t == cur) updateBars();
            }
            @Override public void onCanGoBack(GeckoSession ss, boolean b) { t.canBack = b; }
            @Override public void onCanGoForward(GeckoSession ss, boolean b) { t.canForward = b; }
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession ss, LoadRequest r) { return allowLoad(r.uri); }
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession ss, String uri) {
                if (paused) return null;
                Tab n = new Tab();
                n.desktop = t.desktop; n.home = uri;
                makeSession(n, false); // the engine opens it
                tabs.add(n);
                ui.post(() -> show(n));
                return GeckoResult.fromValue(n.session);
            }
            @Override public GeckoResult<String> onLoadError(GeckoSession ss, String uri, WebRequestError error) { return null; }
        });
        s.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession ss, String url) { t.loading = true; if (t == cur) updateBars(); }
            @Override public void onPageStop(GeckoSession ss, boolean ok) { t.loading = false; if (t == cur) updateBars(); }
        });
        s.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onTitleChange(GeckoSession ss, String title) { t.title = title == null ? "" : title; }
            @Override public void onCloseRequest(GeckoSession ss) { closeTab(t); }
            @Override public void onExternalResponse(GeckoSession ss, WebResponse r) { download(r); }
            @Override public void onCrash(GeckoSession ss) { revive(t); }
            @Override public void onKill(GeckoSession ss) { revive(t); }
        });
        s.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override public GeckoResult<Integer> onContentPermissionRequest(GeckoSession ss, ContentPermission p) { return permission(p); }
            @Override public void onAndroidPermissionsRequest(GeckoSession ss, String[] perms, Callback cb) { cb.reject(); }
            @Override public void onMediaPermissionRequest(GeckoSession ss, String uri, MediaSource[] video, MediaSource[] audio, MediaCallback cb) { cb.reject(); }
        });
        s.setPromptDelegate(new Prompts(this, () -> toast("Blocked a pop-up.")));
        s.setMediaSessionDelegate(new MediaSession.Delegate() {
            @Override public void onActivated(GeckoSession ss, MediaSession m) { t.media = m; }
            @Override public void onDeactivated(GeckoSession ss, MediaSession m) { t.media = null; playing(t, false); }
            @Override public void onMetadata(GeckoSession ss, MediaSession m, MediaSession.Metadata meta) { if (meta != null && meta.title != null) t.mediaTitle = meta.title; }
            @Override public void onPlay(GeckoSession ss, MediaSession m) { t.media = m; playing(t, true); }
            @Override public void onPause(GeckoSession ss, MediaSession m) { playing(t, false); }
            @Override public void onStop(GeckoSession ss, MediaSession m) { playing(t, false); }
        });
    }

    /** A page the engine lost (crash, or the phone reclaimed memory): start it again at the same address. */
    private void revive(Tab t) {
        boolean shown = view.getSession() == t.session;
        if (shown) view.releaseSession();
        makeSession(t, true);
        if (!t.url.isEmpty() && !paused) t.session.loadUri(t.url);
        if (shown) { cur = null; show(t); }
    }

    /** Web pages load; other links (mail, phone, app links) go to the phone. With the VPN on but not connected, nothing loads. */
    private GeckoResult<AllowOrDeny> allowLoad(String uri) {
        String u = uri == null ? "" : uri;
        String scheme = u.contains(":") ? u.substring(0, u.indexOf(':')).toLowerCase(Locale.ROOT) : "";
        boolean web = scheme.equals("http") || scheme.equals("https") || scheme.equals("about") || scheme.equals("blob") || scheme.equals("data") || scheme.equals("moz-extension") || scheme.equals("resource");
        if (web) {
            if ((scheme.equals("http") || scheme.equals("https")) && vpn.wanted() && vpn.state() != Vpn.State.ON) {
                ui.post(() -> toast("Waiting for the VPN: nothing loads without it."));
                return GeckoResult.deny();
            }
            return GeckoResult.allow();
        }
        ui.post(() -> {
            try {
                Intent i = scheme.equals("intent") ? Intent.parseUri(u, Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                i.addCategory(Intent.CATEGORY_BROWSABLE); i.setComponent(null); i.setSelector(null);
                startActivity(i);
            } catch (Exception e) {
                String fallback = null;
                try { fallback = Intent.parseUri(u, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url"); } catch (Exception ignored) { }
                if (fallback != null && cur != null) load(cur, fallback); else toast("No app on this phone opens that link.");
            }
        });
        return GeckoResult.deny();
    }

    /** What pages may do. Copy-protected playback (Audible) is allowed; location, notifications, camera and microphone are not. */
    private GeckoResult<Integer> permission(PermissionDelegate.ContentPermission p) {
        int allow = PermissionDelegate.ContentPermission.VALUE_ALLOW, deny = PermissionDelegate.ContentPermission.VALUE_DENY;
        String host = hostOf(p.uri);
        switch (p.permission) {
            case PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS: return GeckoResult.fromValue(allow);
            case PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE: return GeckoResult.fromValue(allow);
            case PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE: return GeckoResult.fromValue(host.contains("audible.") || host.contains("amazon.") ? allow : deny);
            case PermissionDelegate.PERMISSION_PERSISTENT_STORAGE: return GeckoResult.fromValue(allow); // e.g. Kindle books kept for offline reading
            case PermissionDelegate.PERMISSION_STORAGE_ACCESS: {   // a sign-in from another site ("Sign in with …")
                String other = hostOf(p.thirdPartyOrigin);
                if (amazonish(host) && amazonish(other)) return GeckoResult.fromValue(allow);
                GeckoResult<Integer> r = new GeckoResult<>();
                boolean[] done = { false };
                new AlertDialog.Builder(this).setTitle("Allow a sign-in?")
                    .setMessage((other.isEmpty() ? "Another site" : other) + " wants to use its cookies on " + host + " (for example to sign you in). Allow it?")
                    .setPositiveButton("Allow", (d, w) -> { if (!done[0]) { done[0] = true; r.complete(allow); } })
                    .setNegativeButton("Block", (d, w) -> { if (!done[0]) { done[0] = true; r.complete(deny); } })
                    .setOnDismissListener(d -> { if (!done[0]) { done[0] = true; r.complete(deny); } }).show();
                return r;
            }
            default: return GeckoResult.fromValue(deny);   // location, notifications, VR, local network
        }
    }
    private static boolean amazonish(String h) { return h.contains("amazon.") || h.contains("audible.") || h.contains("media-amazon.") || h.contains("ssl-images-amazon."); }
    private static String hostOf(String u) {
        if (u == null) return "";
        try { String h = Uri.parse(u).getHost(); return h == null ? "" : h.toLowerCase(Locale.ROOT); } catch (Exception e) { return ""; }
    }

    // ================================================================== downloads (and Proton's .conf files)
    private void download(WebResponse r) {
        Downloads.handle(getContentResolver(), r, new Downloads.Sink() {
            @Override public void vpnConfig(String name, String text) { ui.post(() -> offerConfig(name, text)); }
            @Override public void saved(String where) { ui.post(() -> toast("Saved to " + where)); }
            @Override public void failed(String why) { ui.post(() -> toast("Download failed: " + why)); }
        });
    }

    private void offerConfig(String fileName, String text) {
        String name;
        try { name = vpn.importConf(fileName, text); }
        catch (Exception e) { toast("That file is not a WireGuard configuration: " + e.getMessage()); return; }
        new AlertDialog.Builder(this).setTitle("Proton VPN configuration saved")
            .setMessage("“" + name + "” (server " + vpn.serverOf(name) + ") is stored in Orbit, encrypted. Turn the VPN on now? Only Orbit's own traffic goes through it.")
            .setPositiveButton("Turn on", (d, w) -> { vpn.select(name); vpn.setWanted(true); startVpn(); })
            .setNegativeButton("Later", null).show();
    }

    // ================================================================== the VPN
    private void startVpn() {
        Intent consent = vpn.consent();
        if (consent != null) {
            try { startActivityForResult(consent, REQ_VPN); return; }
            catch (ActivityNotFoundException e) { onVpn(Vpn.State.FAILED, "This phone has no VPN support."); return; }
        }
        vpn.up();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VPN) {
            if (res == RESULT_OK) vpn.up();
            else onVpn(Vpn.State.FAILED, "Orbit was not allowed to set up the VPN.");
        } else if (req == REQ_CONF && res == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try (InputStream in = getContentResolver().openInputStream(u)) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                if (in != null) { byte[] chunk = new byte[8192]; int n; while ((n = in.read(chunk)) > 0 && buf.size() < 256 * 1024) buf.write(chunk, 0, n); }
                byte[] b = buf.toByteArray();
                String n = displayName(u); if (n != null && n.contains("/")) n = n.substring(n.lastIndexOf('/') + 1); if (n != null && n.contains(":")) n = n.substring(n.lastIndexOf(':') + 1);
                offerConfig(n, new String(b, StandardCharsets.UTF_8));
            } catch (Exception e) { toast("Could not read that file: " + e.getMessage()); }
        }
    }

    private String displayName(Uri u) {
        try (android.database.Cursor c = getContentResolver().query(u, new String[]{ android.provider.OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return u.getLastPathSegment();
    }

    @Override public void onVpn(Vpn.State s, String detail) {
        boolean want = vpn.wanted();
        bVpn.setText(s == Vpn.State.ON ? "VPN ●" : s == Vpn.State.CONNECTING ? "VPN …" : (want && s == Vpn.State.FAILED) ? "VPN !" : "VPN");
        switch (s) {
            case ON:
                hideBanner();
                if (!opened) openStartTabs(); else if (paused) resumeAll();
                break;
            case CONNECTING:
                if (opened && !paused) pauseAll(); // nothing goes out until the VPN is connected
                showBanner(detail + (opened ? " Pages carry on once it is connected." : " Pages open once it is connected."), null, null, null, null);
                break;
            case FAILED:
                if (!want) { hideBanner(); if (!opened) openStartTabs(); break; }
                if (opened && !paused) pauseAll();
                showBanner(detail + " Browsing is paused, so nothing goes out without the VPN.",
                    "Try again", this::startVpn,
                    "Browse without VPN", () -> { vpn.setWanted(false); vpn.down(); hideBanner(); if (!opened) openStartTabs(); else if (paused) resumeAll(); });
                break;
            case OFF:
                if (!want) { hideBanner(); if (!opened) openStartTabs(); else if (paused) resumeAll(); }
                break;
        }
    }

    /** Kill switch: every page is closed (no traffic at all) until the VPN is back; the addresses are kept. */
    private void pauseAll() {
        paused = true;
        view.releaseSession();
        for (Tab t : tabs) {
            if (t.playing && t.media != null) t.media.pause();
            if (t.session != null) { t.session.close(); }
        }
    }

    private void resumeAll() {
        paused = false;
        for (Tab t : tabs) {
            if (t.session != null && t.session.isOpen()) t.session.close();
            makeSession(t, true);
            String u = !t.url.isEmpty() ? t.url : t.pending;
            if (u == null) u = t.home;
            if (t == cur) t.session.loadUri(u); else t.pending = u;
        }
        Tab c = cur; cur = null;
        show(c != null ? c : tabs.get(0));
    }

    private void vpnSheet() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView st = text(box, (vpn.state() == Vpn.State.ON ? "ON: " + vpn.detail() : vpn.state() == Vpn.State.CONNECTING ? vpn.detail() : vpn.state() == Vpn.State.FAILED && vpn.wanted() ? vpn.detail() : "Off.") , true);
        st.setPadding(0, 0, 0, dp(8));
        List<String> names = vpn.names();
        String curName = vpn.current();
        AlertDialog[] d = new AlertDialog[1];
        if (names.isEmpty()) {
            text(box, "No Proton configuration yet. To add one:\n\n"
                + "1. Tap “Get one from Proton”: Proton's site opens in a new tab. Sign in (a free account works).\n"
                + "2. Downloads → WireGuard configuration: give it a name, choose Android, pick a server, Create, then Download.\n"
                + "3. Orbit picks up the file by itself and offers to turn the VPN on.\n\n"
                + "Or make the file on a computer, copy it to the phone, and use “Import a file”.", false);
        } else {
            text(box, "Server (tap to use it):", true).setPadding(0, dp(8), 0, dp(4));
            for (String n : names) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBackgroundColor(Color.BLACK);
                row.setPadding(0, 1, 0, 1);
                Button b = button(row, (n.equals(curName) ? "● " : "○ ") + n + "  ·  " + vpn.serverOf(n), 1, v -> {
                    vpn.select(n); d[0].dismiss();
                    if (vpn.wanted()) { vpn.down(); ui.postDelayed(this::startVpn, 600); }
                    toast("Using " + n + ".");
                });
                b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); b.setTextSize(14); b.setSingleLine(true); b.setEllipsize(TextUtils.TruncateAt.END);
                button(row, "✕", 0, v -> new AlertDialog.Builder(this).setTitle("Delete " + n + "?")
                    .setPositiveButton("Delete", (x, w) -> { vpn.delete(n); d[0].dismiss(); vpnSheet(); }).setNegativeButton("Cancel", null).show()).setMinimumWidth(dp(48));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
                lp.bottomMargin = dp(6);
                box.addView(row, lp);
            }
            text(box, "Only Orbit's traffic goes through the VPN; the rest of the phone is untouched. While it is on, nothing loads unless it is connected.", false).setPadding(0, dp(8), 0, 0);
        }
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dp(10), 0, 0);
        addAction(actions, "Get one from Proton", () -> {
            d[0].dismiss();
            if (vpn.wanted() && vpn.state() != Vpn.State.ON) { vpn.setWanted(false); vpn.down(); toast("VPN turned off to reach Proton's site."); }
            show(newTab("https://account.protonvpn.com/downloads", false, false, true));
        });
        addAction(actions, "Import a file (.conf)…", () -> {
            d[0].dismiss();
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try { startActivityForResult(i, REQ_CONF); } catch (ActivityNotFoundException e) { toast("This phone has no file picker."); }
        });
        box.addView(actions);
        ScrollView sv = new ScrollView(this); sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Proton VPN (this browser only)").setView(sv).setNegativeButton("Close", null);
        if (!names.isEmpty()) {
            if (vpn.wanted()) b.setPositiveButton("Turn VPN off", (x, w) -> { vpn.setWanted(false); vpn.down(); });
            else b.setPositiveButton("Turn VPN on", (x, w) -> { vpn.setWanted(true); startVpn(); });
        }
        d[0] = b.show();
    }

    private TextView text(LinearLayout box, String s, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(Color.BLACK); t.setTextSize(15);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);
        return t;
    }

    private void addAction(LinearLayout box, String label, Runnable r) {
        LinearLayout row = new LinearLayout(this);
        row.setBackgroundColor(Color.BLACK);
        row.setPadding(1, 1, 1, 1);
        button(row, label, 1, v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.bottomMargin = dp(6);
        box.addView(row, lp);
    }

    // ================================================================== audio
    private void playing(Tab t, boolean on) {
        t.playing = on;
        if (on) makeAudible();
        Intent i = new Intent(this, PlaybackService.class).putExtra("playing", on).putExtra("title", t.mediaTitle.isEmpty() ? t.label() : t.mediaTitle);
        try { if (on) startForegroundService(i); else if (PlaybackService.running) startService(i); }
        catch (RuntimeException ignored) { } // Android may refuse from the background; audio still plays
    }

    /** play | pause | back | fwd, from the notification or headset, for the tab that plays. */
    static void command(String c) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Tab t = null;
            for (Tab x : tabs) if (x.media != null && (x.playing || t == null)) t = x;
            if (t == null || t.media == null) return;
            switch (c) {
                case "play": t.media.play(); break;
                case "pause": t.media.pause(); break;
                case "back": t.media.seekBackward(); break;
                case "fwd": t.media.seekForward(); break;
            }
        });
    }

    private void makeAudible() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        if (!am.isStreamMute(AudioManager.STREAM_MUSIC) && am.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) return;
        try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 2 / 5), AudioManager.FLAG_SHOW_UI);
            toast("Media volume was off: turned it up. The volume buttons change it.");
        } catch (SecurityException e) { toast("Media volume is off. Press volume up."); }
    }

    // ================================================================== ☰
    private void menu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        if (cur != null) {
            m.getMenu().add(0, 1, 0, "Desktop site").setCheckable(true).setChecked(cur.desktop);
            m.getMenu().add(0, 2, 0, "Tracker blocking on this tab").setCheckable(true).setChecked(cur.shield);
            m.getMenu().add(0, 3, 0, "Add this page to the default tabs");
        }
        m.getMenu().add(0, 4, 0, "Default tabs…");
        m.getMenu().add(0, 5, 0, "VPN…");
        m.getMenu().add(0, 6, 0, "Clear the screen (E Ink)");
        m.getMenu().add(0, 7, 0, "Settings…");
        m.getMenu().add(0, 8, 0, "Forget all sign-ins and site data…");
        m.getMenu().add(0, 10, 0, Updater.hasUpdate() ? "Install the update (" + Updater.updateName() + ")" : "Check for updates");
        m.getMenu().add(0, 11, 0, "About Orbit");
        m.getMenu().add(0, 9, 0, "Close Orbit");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: cur.desktop = !cur.desktop;
                    cur.session.getSettings().setUserAgentMode(cur.desktop ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP : GeckoSessionSettings.USER_AGENT_MODE_MOBILE);
                    cur.session.getSettings().setViewportMode(cur.desktop ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP : GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
                    cur.session.reload(); break;
                case 2: cur.shield = !cur.shield; cur.session.getSettings().setUseTrackingProtection(cur.shield); cur.session.reload();
                    toast(cur.shield ? "Trackers blocked on this tab." : "Trackers allowed on this tab (until it is closed)."); break;
                case 3: addCurrentToDefaults(); break;
                case 4: defaultsSheet(); break;
                case 5: vpnSheet(); break;
                case 6: clearGhosting(); break;
                case 7: settingsSheet(); break;
                case 8: forgetAll(); break;
                case 9: closeOrbit(); break;
                case 10: installNow(); break;
                case 11: new AlertDialog.Builder(this).setTitle("Orbit")
                    .setMessage("Version " + BuildConfig.VERSION_NAME + "\nFirefox engine " + Updater.engine()
                        + "\n\nUpdates: " + (Updater.configured() ? "from github.com/" + BuildConfig.UPDATE_REPO + (prefs.getBoolean("autoUpdate", true) ? ", installed by themselves" : ", automatic install off") : "not set up (README → Automatic updates)"))
                    .setPositiveButton("OK", null).show(); break;
            }
            return true;
        });
        m.show();
    }

    private void settingsSheet() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        CheckBox strict = check(box, "Strict tracker blocking", "strict", true);
        CheckBox https = check(box, "HTTPS only (no unencrypted pages)", "httpsOnly", true);
        CheckBox doh = check(box, "Encrypted DNS (Quad9)", "doh", true);
        text(box, "These three apply the next time Orbit starts (☰ → Close Orbit, then open it).", false).setPadding(0, 0, 0, dp(10));
        check(box, "Volume buttons turn Kindle pages", "volumeKeys", true);
        check(box, "Clear the screen on every page turn", "ghostEveryPage", false);
        check(box, "Install security updates by themselves", "autoUpdate", true);
        String[] engines = { "ddg", "google", "startpage" }, names = { "DuckDuckGo", "Google", "Startpage" };
        int sel = java.util.Arrays.asList(engines).indexOf(prefs.getString("search", "ddg"));
        addAction(box, "Search with: " + names[Math.max(sel, 0)], () -> new AlertDialog.Builder(this).setTitle("Search with")
            .setSingleChoiceItems(names, Math.max(sel, 0), (d, w) -> { prefs.edit().putString("search", engines[w]).apply(); d.dismiss(); toast("Searching with " + names[w] + "."); }).show());
        text(box, "Always on: each site gets its own cookies, tracking codes are removed from links, sites are told not to sell your data, and location, notifications, camera and microphone are refused.", false).setPadding(0, dp(8), 0, 0);
        ScrollView sv = new ScrollView(this); sv.addView(box);
        new AlertDialog.Builder(this).setTitle("Settings").setView(sv).setPositiveButton("Done", null).show();
        strict.setTag("restart"); https.setTag("restart"); doh.setTag("restart");
    }

    private CheckBox check(LinearLayout box, String label, String key, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(label); c.setTextSize(16); c.setTextColor(Color.BLACK);
        c.setChecked(prefs.getBoolean(key, def));
        c.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(key, on).apply());
        c.setMinHeight(dp(48));
        box.addView(c);
        return c;
    }

    private void forgetAll() {
        new AlertDialog.Builder(this).setTitle("Forget everything?")
            .setMessage("Signs you out of every site (Amazon, Audible, Google, Proton…) and removes cookies, saved site data and books kept for offline reading. Your default tabs and VPN configurations stay.")
            .setPositiveButton("Forget", (d, w) -> runtime.getStorageController().clearData(StorageController.ClearFlags.ALL).accept(v -> ui.post(() -> {
                toast("Done: signed out everywhere.");
                for (Tab t : tabs) if (t.session != null && t.session.isOpen()) t.session.reload();
            })))
            .setNegativeButton("Cancel", null).show();
    }

    private void closeOrbit() {
        for (Tab t : tabs) if (t.playing && t.media != null) t.media.pause();
        stopService(new Intent(this, PlaybackService.class));
        vpn.down();
        finishAndRemoveTask();
        ui.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 800); // the engine starts fresh next time
    }

    private void toast(String s) { Toast.makeText(this, s, s.length() > 60 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show(); }

    @Override protected void onResume() {
        super.onResume();
        current = new WeakReference<>(this);
        visible = true;
        // security updates: Android's "install this update?" screen (the first update only), the one-time permission, a new check
        Intent ask = Updater.confirm;
        if (ask != null) { Updater.confirm = null; try { startActivity(ask); } catch (Exception ignored) { } }
        else if (Updater.hasUpdate() && !Updater.allowed(this) && !prefs.getBoolean("askedInstall", false)) askInstallPermission();
        Updater.check(this, false, this::toast);
    }

    @Override protected void onStop() {
        super.onStop();
        visible = false;
        // an update waiting: install it now that Orbit is out of sight, unless something is playing
        boolean playing = false;
        for (Tab t : tabs) playing |= t.playing;
        if (Updater.hasUpdate() && !playing && Updater.allowed(this) && prefs.getBoolean("autoUpdate", true)) Updater.install(this, null);
    }

    private void askInstallPermission() {
        prefs.edit().putBoolean("askedInstall", true).apply();
        new AlertDialog.Builder(this).setTitle("Let Orbit install its updates?")
            .setMessage("A security update for Orbit's Firefox engine is ready. To install updates by itself, Orbit needs one permission: on the next screen switch on “Allow from this source”, then come back. Only updates signed with your own key are accepted.")
            .setPositiveButton("Open the setting", (d, w) -> {
                try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
                catch (Exception e) { toast("Open Settings → Apps → Orbit → Install unknown apps."); }
            })
            .setNegativeButton("Not now", null).show();
    }

    private void installNow() {
        if (!Updater.hasUpdate()) { Updater.check(this, true, this::toast); return; }
        boolean playing = false;
        for (Tab t : tabs) playing |= t.playing;
        if (!Updater.allowed(this)) { askInstallPermission(); return; }
        new AlertDialog.Builder(this).setTitle("Install " + Updater.updateName() + "?")
            .setMessage((playing ? "This stops the audio that is playing. " : "") + "Orbit closes to update; open it again in a moment.")
            .setPositiveButton("Install", (d, w) -> Updater.install(this, this::toast))
            .setNegativeButton("Later", null).show();
    }

    @Override protected void onDestroy() {
        vpn.unlisten(this);
        if (view != null) view.releaseSession(); // (the tabs stay open for the next screen)
        if (current.get() == this) current = new WeakReference<>(null);
        super.onDestroy();
    }
}
