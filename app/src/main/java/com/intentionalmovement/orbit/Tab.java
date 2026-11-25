package com.intentionalmovement.orbit;

import android.net.Uri;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.MediaSession;

/** One open page. Its engine session can be closed and made again (VPN pause), keeping the address. */
final class Tab {
    GeckoSession session;
    String url = "", title = "", home = "";
    String pending;            // address to load the first time the tab is shown
    boolean isDefault, desktop, shield = true;
    boolean canBack, canForward, loading;
    boolean playing;
    MediaSession media;
    String mediaTitle = "";

    static GeckoSessionSettings settings(boolean desktop, boolean shield) {
        return new GeckoSessionSettings.Builder()
            .useTrackingProtection(shield)
            .userAgentMode(desktop ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP : GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(desktop ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP : GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .suspendMediaWhenInactive(false)   // audio keeps playing in a tab you are not looking at
            .build();
    }

    String host() {
        try { String h = Uri.parse(url.isEmpty() ? (pending != null ? pending : home) : url).getHost(); return h == null ? "" : h.replaceFirst("^www\\.", ""); }
        catch (Exception e) { return ""; }
    }

    String label() {
        if (title != null && !title.trim().isEmpty()) return title.trim();
        String h = host();
        return h.isEmpty() ? "New tab" : h;
    }

    boolean isKindle() { return host().startsWith("read.amazon."); }
}
