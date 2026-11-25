package com.intentionalmovement.orbit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps audio (Audible) playing with the screen off or in another tab: a media-playback foreground service with a wake
 * lock, a notification (play / pause, back / forward) and a media session for headset buttons. Started when a tab plays;
 * stops itself 10 minutes after the audio pauses.
 */
public class PlaybackService extends Service {
    static volatile boolean running = false;
    private static final String CH = "playback";
    private PowerManager.WakeLock wake;
    private MediaSession session;
    private boolean playing;
    private String title = "";
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable stopLater = () -> { if (!playing) stopSelf(); };

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Audio playing", NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, "Orbit");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { MainActivity.command("play"); }
            @Override public void onPause() { MainActivity.command("pause"); }
            @Override public void onSkipToNext() { MainActivity.command("fwd"); }
            @Override public void onSkipToPrevious() { MainActivity.command("back"); }
            @Override public void onFastForward() { MainActivity.command("fwd"); }
            @Override public void onRewind() { MainActivity.command("back"); }
        });
        session.setActive(true);
        PowerManager pm = getSystemService(PowerManager.class);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Orbit:audio");
        wake.setReferenceCounted(false);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String act = i != null ? i.getAction() : null;
        if (act != null) { MainActivity.command(act); return START_NOT_STICKY; } // a notification button
        playing = i != null && i.getBooleanExtra("playing", false);
        if (i != null && i.getStringExtra("title") != null && !i.getStringExtra("title").isEmpty()) title = i.getStringExtra("title");
        startForeground(1, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND)
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build());
        ui.removeCallbacks(stopLater);
        if (playing) wake.acquire(6 * 60 * 60 * 1000L);
        else { if (wake.isHeld()) wake.release(); ui.postDelayed(stopLater, 10 * 60 * 1000L); }
        return START_NOT_STICKY;
    }

    private PendingIntent action(String a, int code) {
        return PendingIntent.getService(this, code, new Intent(this, PlaybackService.class).setAction(a), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(title.isEmpty() ? "Orbit" : title)
            .setContentText(playing ? "Playing" : "Paused")
            .setContentIntent(open)
            .setOngoing(playing)
            .addAction(new Notification.Action.Builder(null, "Back", action("back", 1)).build())
            .addAction(new Notification.Action.Builder(null, playing ? "Pause" : "Play", action(playing ? "pause" : "play", 2)).build())
            .addAction(new Notification.Action.Builder(null, "Forward", action("fwd", 3)).build())
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2))
            .build();
    }

    @Override public void onDestroy() {
        running = false;
        ui.removeCallbacks(stopLater);
        if (wake != null && wake.isHeld()) wake.release();
        if (session != null) session.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
