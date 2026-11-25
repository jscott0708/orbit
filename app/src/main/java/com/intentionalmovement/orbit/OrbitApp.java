package com.intentionalmovement.orbit;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

/**
 * Orbit: a private browser on Firefox's engine (GeckoView). One engine per app process, set up here with the privacy
 * settings; the settings that need a restart are read from the "orbit" preferences when the app starts.
 */
public class OrbitApp extends Application {
    private static GeckoRuntime runtime;
    private static Vpn vpn;

    static synchronized Vpn vpn(Context c) { if (vpn == null) vpn = new Vpn(c); return vpn; }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("orbit", MODE_PRIVATE); }

    static synchronized GeckoRuntime runtime(Context c) {
        if (runtime != null) return runtime;
        SharedPreferences p = prefs(c);
        boolean strict = p.getBoolean("strict", true), httpsOnly = p.getBoolean("httpsOnly", true), doh = p.getBoolean("doh", true);
        ContentBlocking.Settings cb = new ContentBlocking.Settings.Builder()
            // trackers: ad networks, analytics, social-media trackers, fingerprinters, cryptominers, email trackers
            .antiTracking(strict ? ContentBlocking.AntiTracking.STRICT : ContentBlocking.AntiTracking.DEFAULT)
            .enhancedTrackingProtectionLevel(strict ? ContentBlocking.EtpLevel.STRICT : ContentBlocking.EtpLevel.DEFAULT)
            .strictSocialTrackingProtection(strict)
            // every site gets its own cookie jar: a site cannot follow you onto another
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .cookiePurging(true)                     // trackers' leftover cookies are cleared
            .queryParameterStrippingEnabled(true)    // tracking codes are removed from links (e.g. ?fbclid=)
            .safeBrowsing(ContentBlocking.SafeBrowsing.NONE) // (would check pages with Google)
            .build();
        GeckoRuntimeSettings.Builder b = new GeckoRuntimeSettings.Builder()
            .contentBlocking(cb)
            .globalPrivacyControlEnabled(true)       // tells sites: do not sell or share my data
            .allowInsecureConnections(httpsOnly ? GeckoRuntimeSettings.HTTPS_ONLY : GeckoRuntimeSettings.ALLOW_ALL)
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_LIGHT) // E Ink
            .remoteDebuggingEnabled(false)
            .consoleOutput(false)
            .aboutConfigEnabled(false);
        if (doh) b.trustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_FIRST)
                  .trustedRecursiveResolverUri("https://dns.quad9.net/dns-query"); // encrypted DNS (Quad9, no logs)
        runtime = GeckoRuntime.create(c.getApplicationContext(), b.build());
        return runtime;
    }
}
