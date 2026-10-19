package com.gathin.tvhome;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.List;

/**
 * Tells Home Assistant whether something is playing, paused or stopped on this TV, by watching Android's media
 * sessions. It needs notification access, which is how Android lets an app read media sessions (granted over adb by
 * basement/install.sh). The result is the state of sensor.home_theater_playback: "playing", "paused" or "stopped".
 */
public class PlaybackReporter extends NotificationListenerService {

    static final String ENTITY = "sensor.home_theater_playback";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessions;
    private ComponentName me;
    private final List<MediaController> watched = new ArrayList<>();
    private String lastSent = "";
    private AlertWatcher alerts;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = controllers -> {
        attach(controllers);
        evaluate();
    };

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            evaluate();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            evaluate();
        }

        @Override
        public void onSessionDestroyed() {
            handler.post(PlaybackReporter.this::refresh);
        }
    };

    @Override
    public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        me = new ComponentName(this, PlaybackReporter.class);
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, me, handler);
        } catch (SecurityException ignored) {
            return;   // notification access not granted yet
        }
        refresh();
        if (alerts == null) alerts = new AlertWatcher(this);
        alerts.start();
    }

    @Override
    public void onListenerDisconnected() {
        if (alerts != null) alerts.stop();
        detachAll();
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsListener);
    }

    private void refresh() {
        try {
            attach(sessions.getActiveSessions(me));
        } catch (SecurityException ignored) {
        }
        evaluate();
    }

    private void attach(List<MediaController> controllers) {
        detachAll();
        if (controllers == null) return;
        for (MediaController c : controllers) {
            c.registerCallback(callback, handler);
            watched.add(c);
        }
    }

    private void detachAll() {
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
    }

    /** playing beats paused beats stopped; buffering counts as playing so a stall doesn't brighten the room. */
    private void evaluate() {
        String state = "stopped";
        String pkg = "";
        String title = "";
        String subtitle = "";
        long durationMs = 0;
        boolean ad = false;
        for (MediaController c : watched) {
            PlaybackState ps = c.getPlaybackState();
            if (ps == null) continue;
            int s = ps.getState();
            String candidate = null;
            if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
                    || s == PlaybackState.STATE_FAST_FORWARDING || s == PlaybackState.STATE_REWINDING) {
                candidate = "playing";
            } else if (s == PlaybackState.STATE_PAUSED) {
                candidate = "paused";
            }
            if (candidate == null) continue;
            if (state.equals("stopped") || (candidate.equals("playing") && state.equals("paused"))) {
                state = candidate;
                pkg = c.getPackageName();
                MediaMetadata md = c.getMetadata();
                title = md != null && md.getString(MediaMetadata.METADATA_KEY_TITLE) != null
                        ? md.getString(MediaMetadata.METADATA_KEY_TITLE) : "";
                // What a player publishes about itself, kept so we can learn how each app marks ad breaks.
                subtitle = md != null && md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) != null
                        ? md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) : "";
                durationMs = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
                ad = md != null && md.getLong("android.media.metadata.ADVERTISEMENT") != 0;
            }
        }
        String key = state + "|" + pkg + "|" + title + "|" + subtitle + "|" + durationMs + "|" + ad;
        if (key.equals(lastSent)) return;
        lastSent = key;
        String attrs = "{\"friendly_name\":\"Home Theater Playback\",\"icon\":\"mdi:play-pause\",\"package\":"
                + json(pkg) + ",\"title\":" + json(title) + ",\"subtitle\":" + json(subtitle)
                + ",\"duration_s\":" + (durationMs / 1000) + ",\"ad\":" + ad + "}";
        Ha.setState(ENTITY, state, attrs, null);
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }
}
