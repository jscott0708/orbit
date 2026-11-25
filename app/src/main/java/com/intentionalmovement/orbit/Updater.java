package com.intentionalmovement.orbit;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Security updates by themselves: the GitHub build publishes a new Orbit whenever Firefox's engine gets fixes; this looks
 * for it (at start and every 12 hours), downloads it, checks it is signed with the same key as this Orbit, and installs
 * it when you leave the app and nothing is playing. Android itself refuses an update signed with any other key.
 */
final class Updater {
    interface Ui { void say(String text); }

    private static final long EVERY = 12L * 60 * 60 * 1000;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;
    private static volatile File ready;        // downloaded and checked, waiting to be installed
    private static volatile String readyName = "";
    static volatile Intent confirm;             // Android's "Install this update?" screen, shown when Orbit is open

    static boolean configured() { return !BuildConfig.UPDATE_REPO.isEmpty(); }
    static boolean hasUpdate() { return ready != null && ready.exists(); }
    static String updateName() { return readyName; }

    /** Look for a newer Orbit (manual: now, and say the result; otherwise at most every 12 hours). */
    static void check(Context c, boolean manual, Ui out) {
        Context ctx = c.getApplicationContext();
        SharedPreferences p = OrbitApp.prefs(ctx);
        if (!configured()) { if (manual) out.say("Automatic updates are not set up yet (README → Automatic updates)."); return; }
        if (!manual && (!p.getBoolean("autoUpdate", true) || System.currentTimeMillis() - p.getLong("lastCheck", 0) < EVERY)) return;
        Vpn vpn = OrbitApp.vpn(ctx);
        if (vpn.wanted() && vpn.state() != Vpn.State.ON) { if (manual) out.say("Waiting for the VPN before looking for updates."); return; }
        if (busy) return;
        busy = true;
        new Thread(() -> {
            try {
                p.edit().putLong("lastCheck", System.currentTimeMillis()).apply();
                JSONObject rel = new JSONObject(get("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest"));
                long code = Long.parseLong(rel.optString("tag_name", "v0").replaceAll("\\D", "") + "0") / 10;
                if (code <= BuildConfig.VERSION_CODE) { if (manual) say(out, "Orbit is up to date (Firefox engine " + engine() + ")."); return; }
                String url = null;
                JSONArray assets = rel.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break; }
                }
                if (url == null) { if (manual) say(out, "The newest release has no app file yet; try again later."); return; }
                File f = new File(ctx.getCacheDir(), "orbit-update.apk");
                download(url, f);
                String why = verify(ctx, f, code);
                if (why != null) { f.delete(); say(out, "An update was found but not installed: " + why); return; }
                ready = f;
                readyName = rel.optString("name", "Orbit " + code);
                say(out, manual ? "Update ready: " + readyName + ". It installs when you leave Orbit (or now: ☰ → Install the update)." : null);
            } catch (Exception e) {
                if (manual) say(out, "Could not look for updates: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } finally { busy = false; }
        }, "orbit-update").start();
    }

    private static void say(Ui out, String text) { if (text != null && out != null) ui.post(() -> out.say(text)); }

    static String engine() { return org.mozilla.geckoview.BuildConfig.MOZILLA_VERSION; }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "Orbit");
        c.setConnectTimeout(20000); c.setReadTimeout(30000);
        if (c.getResponseCode() == 404) throw new Exception("no release published yet");
        if (c.getResponseCode() != 200) throw new Exception("GitHub answered " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }

    private static void download(String url, File to) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "Orbit");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000); c.setReadTimeout(60000);
        if (c.getResponseCode() != 200) throw new Exception("download answered " + c.getResponseCode());
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally { c.disconnect(); }
    }

    /** Same app, newer, and signed with the same key as the Orbit that is installed. Returns why not, or null. */
    @SuppressWarnings("deprecation")
    private static String verify(Context ctx, File f, long code) {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo n = pm.getPackageArchiveInfo(f.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (n == null) return "the file is not an app.";
        if (!ctx.getPackageName().equals(n.packageName)) return "it is a different app.";
        if (n.getLongVersionCode() <= BuildConfig.VERSION_CODE) return "it is not newer.";
        try {
            PackageInfo me = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            Signature[] a = me.signingInfo.getApkContentsSigners(), b = n.signingInfo == null ? null : n.signingInfo.getApkContentsSigners();
            if (b == null || !Arrays.equals(a, b)) return "it is not signed with your key.";
        } catch (Exception e) { return "its signature could not be checked."; }
        return null;
    }

    /** May Orbit install apps (Settings → Install unknown apps → Orbit)? */
    static boolean allowed(Context c) { return c.getPackageManager().canRequestPackageInstalls(); }

    /** Hands the downloaded update to Android. Orbit closes when it is installed; open it again. */
    static void install(Context c, Ui out) {
        File f = ready;
        if (f == null || !f.exists()) return;
        Context ctx = c.getApplicationContext();
        try {
            PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(ctx.getPackageName());
            if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            int id = pi.createSession(sp);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(f); OutputStream o = s.openWrite("orbit.apk", 0, f.length())) {
                    byte[] b = new byte[65536]; int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                    s.fsync(o);
                }
                Intent done = new Intent(ctx, UpdateReceiver.class);
                PendingIntent cb = PendingIntent.getBroadcast(ctx, id, done, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                s.commit(cb.getIntentSender());
            }
            ready = null;
        } catch (Exception e) {
            if (out != null) out.say("The update could not be installed: " + e.getMessage());
        }
    }
}
