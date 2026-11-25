package com.intentionalmovement.orbit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Android's answer to an update: done, failed, or "ask the user first" (the first update; later ones install silently). */
public class UpdateReceiver extends BroadcastReceiver {
    @Override @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent ask = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (ask == null) return;
            ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Updater.confirm = ask;                 // shown now if Orbit is open, else the next time it opens
            if (MainActivity.visible) { Updater.confirm = null; try { c.startActivity(ask); } catch (Exception ignored) { } }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(c, "Orbit update not installed" + (m == null ? "." : ": " + m), Toast.LENGTH_LONG).show();
        }
    }
}
