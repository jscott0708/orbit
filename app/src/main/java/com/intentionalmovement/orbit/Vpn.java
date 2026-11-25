package com.intentionalmovement.orbit;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Proton VPN inside the browser: WireGuard configuration files from your Proton account (account.protonvpn.com →
 * Downloads → WireGuard configuration), kept encrypted, and a WireGuard tunnel that carries ONLY this app's traffic
 * (IncludedApplications = this app), so the rest of the phone is untouched. No Proton app needed.
 */
final class Vpn {
    enum State { OFF, CONNECTING, ON, FAILED }
    interface Listener { void onVpn(State state, String detail); }

    private final Context ctx;
    private final SharedPreferences p;
    private final Secrets secrets;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private GoBackend backend;
    private volatile State state = State.OFF;
    private volatile String detail = "";
    private volatile boolean wholePhone;

    private final Tunnel tunnel = new Tunnel() {
        @Override public String getName() { return "orbit"; }
        @Override public void onStateChange(Tunnel.State s) {
            if (s == Tunnel.State.DOWN && state == Vpn.State.ON)   // (inside Tunnel, "State" is WireGuard's own)
                set(Vpn.State.FAILED, "The VPN stopped (another VPN app took over, or the phone ended it).");
        }
    };

    Vpn(Context c) { ctx = c.getApplicationContext(); p = OrbitApp.prefs(ctx); secrets = new Secrets(ctx); }

    void listen(Listener l) { listeners.add(l); l.onVpn(state, detail); }
    void unlisten(Listener l) { listeners.remove(l); }
    State state() { return state; }
    String detail() { return detail; }
    boolean wholePhone() { return wholePhone; }

    private void set(State s, String d) {
        state = s; detail = d == null ? "" : d;
        ui.post(() -> { for (Listener l : listeners) l.onVpn(state, detail); });
    }

    // ------------------------------------------------------------------ on / off (remembered)
    boolean wanted() { return p.getBoolean("vpnOn", false); }
    void setWanted(boolean on) { p.edit().putBoolean("vpnOn", on).apply(); }

    /** The system's one-time "allow this app to set up a VPN" screen, or null when already allowed. */
    Intent consent() { return VpnService.prepare(ctx); }

    void up() {
        String name = current();
        if (name == null) { set(State.FAILED, "No Proton VPN configuration yet: ☰ → VPN → Get one from Proton."); return; }
        String text = secrets.get("vpn:" + name);
        if (text.isEmpty()) { set(State.FAILED, "The configuration \"" + name + "\" could not be read. Import it again."); return; }
        set(State.CONNECTING, "Connecting to " + server(text) + "…");
        bg.execute(() -> {
            try {
                if (backend == null) backend = new GoBackend(ctx);
                Config cfg;
                try { cfg = parse(forThisApp(text, ctx.getPackageName())); wholePhone = false; }
                catch (Exception e) { cfg = parse(text); wholePhone = true; } // (a library that does not know the per-app line)
                backend.setState(tunnel, Tunnel.State.UP, cfg);
                set(State.ON, "Proton VPN · " + server(text) + (wholePhone ? " (whole phone)" : " (this browser only)"));
            } catch (Throwable e) {
                set(State.FAILED, "The VPN did not start: " + why(e));
            }
        });
    }

    void down() {
        bg.execute(() -> {
            try { if (backend != null) backend.setState(tunnel, Tunnel.State.DOWN, null); } catch (Throwable ignored) { }
            set(State.OFF, "");
        });
    }

    private static String why(Throwable e) {
        String m = e.getMessage();
        String s = (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
        if (s.contains("VPN_NOT_AUTHORIZED")) return "the phone has not allowed Orbit to set up a VPN.";
        if (s.contains("UNABLE_TO_START_VPN")) return "the phone would not start it (is another VPN app on?).";
        return s;
    }

    // ------------------------------------------------------------------ configurations (encrypted)
    List<String> names() {
        List<String> out = new ArrayList<>();
        for (String n : p.getString("vpnNames", "").split("\n")) if (!n.isEmpty()) out.add(n);
        return out;
    }
    String current() {
        String c = p.getString("vpnCurrent", null);
        List<String> n = names();
        if (c != null && n.contains(c)) return c;
        return n.isEmpty() ? null : n.get(0);
    }
    void select(String name) { p.edit().putString("vpnCurrent", name).apply(); }

    /** Checks and stores a WireGuard configuration; returns the name it is kept under. */
    String importConf(String suggested, String text) throws Exception {
        if (text == null || !text.contains("[Interface]") || !text.contains("PrivateKey"))
            throw new IllegalArgumentException("This is not a WireGuard configuration file.");
        parse(text); // throws if it is not valid
        String base = suggested == null ? "" : suggested.replaceAll("(?i)\\.conf$", "").replaceAll("[^\\w .()-]+", "").trim();
        if (base.isEmpty()) base = "Proton " + server(text);
        String name = base; int i = 2;
        List<String> n = names();
        while (n.contains(name)) name = base + " " + i++;
        secrets.put("vpn:" + name, text);
        n.add(name);
        p.edit().putString("vpnNames", String.join("\n", n)).putString("vpnCurrent", name).apply();
        return name;
    }

    void delete(String name) {
        List<String> n = names(); n.remove(name);
        secrets.remove("vpn:" + name);
        p.edit().putString("vpnNames", String.join("\n", n)).apply();
    }

    String serverOf(String name) { return server(secrets.get("vpn:" + name)); }

    /** The server's address from "Endpoint = host:port". */
    static String server(String text) {
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (t.toLowerCase(Locale.ROOT).startsWith("endpoint")) {
                int eq = t.indexOf('='); if (eq < 0) continue;
                String v = t.substring(eq + 1).trim();
                int colon = v.lastIndexOf(':');
                return colon > 0 ? v.substring(0, colon) : v;
            }
        }
        return "Proton";
    }

    /** The same configuration, limited to this app: only Orbit's traffic goes through the VPN. */
    static String forThisApp(String text, String pkg) {
        StringBuilder out = new StringBuilder();
        boolean added = false;
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("includedapplications") || t.startsWith("excludedapplications")) continue;
            out.append(line).append('\n');
            if (!added && t.equals("[interface]")) { out.append("IncludedApplications = ").append(pkg).append('\n'); added = true; }
        }
        return out.toString();
    }

    private static Config parse(String text) throws Exception {
        return Config.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
}
