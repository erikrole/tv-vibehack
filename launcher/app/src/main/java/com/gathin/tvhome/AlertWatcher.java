package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Watches Home Assistant for doorbell, security and leak alerts and shows a card over whatever is on screen
 * (needs the "display over other apps" permission, granted over adb by basement/install.sh). One template request
 * every few seconds covers every sensor. Person and ring alerts show a camera snapshot for a few seconds;
 * leak and alarm alerts stay up until they clear.
 */
final class AlertWatcher {
    private static final long POLL_MS = 6000L;

    private static final class Alert {
        final String key, title, subtitle, camera;
        final boolean sticky;
        final int color;
        final long showMs;

        Alert(String key, String title, String subtitle, String camera, boolean sticky, int color, long showMs) {
            this.key = key;
            this.title = title;
            this.subtitle = subtitle;
            this.camera = camera;
            this.sticky = sticky;
            this.color = color;
            this.showMs = showMs;
        }
    }

    private static final Alert[] ALERTS = {
            new Alert("alarm", "Alarm triggered", "Alarmo has been triggered", "camera.doorbell_high_resolution_channel", true, 0xFFFF453A, 0),
            new Alert("leak", "Water leak detected", "A leak sensor is wet", null, true, 0xFFFF453A, 0),
            new Alert("ring", "Someone's at the door", "Front door", "camera.doorbell_high_resolution_channel", false, 0xFF0A84FF, 16000),
            new Alert("doorbell_person", "Person at the front door", "Doorbell camera", "camera.doorbell_high_resolution_channel", false, 0xFF0A84FF, 12000),
            new Alert("driveway", "Person in the driveway", "Driveway camera", "camera.g6_turret_high_resolution_channel", false, 0xFFFF9F0A, 10000),
            new Alert("garage", "Person in the garage", "Garage camera", "camera.g5_turret_ultra_high_resolution_channel", false, 0xFFFF9F0A, 10000),
            new Alert("patio", "Person on the patio", "Patio camera", "camera.patio_high_resolution_channel", false, 0xFFFF9F0A, 10000),
    };

    private static final String TEMPLATE = "{{ {"
            + "'alarm': is_state('alarm_control_panel.alarmo','triggered'),"
            + "'leak': is_state('binary_sensor.leak_sensors','on'),"
            + "'ring': is_state('binary_sensor.doorbell_doorbell','on'),"
            + "'doorbell_person': is_state('binary_sensor.doorbell_person_detected','on'),"
            + "'driveway': is_state('binary_sensor.driveway_person_detected','on'),"
            + "'garage': is_state('binary_sensor.garage_person_detected','on'),"
            + "'patio': is_state('binary_sensor.patio_person_detected','on'),"
            + "'n': states('input_text.tv_notification'),"
            + "'play': states('sensor.home_theater_playback'),"
            + "'mode': states('input_select.house_mode')"
            + "} | tojson }}";

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, Boolean> last = new HashMap<>();
    private final float density;
    private View overlay;
    private volatile boolean running;
    /** Notifications from Home Assistant (script.tv_notify) and local ones (game starts), waiting to be shown. */
    private final java.util.ArrayList<Pending> queue = new java.util.ArrayList<>();
    private long lastNotifId = -1;          // -1 until the first poll: what is already there is not replayed
    private long busyUntil;                 // the overlay is showing until then
    private static final long INFO_MAX_AGE_MS = 30 * 60 * 1000L;

    private static final class Pending {
        final Alert alert;
        final boolean high;
        final long queuedAt = System.currentTimeMillis();

        Pending(Alert alert, boolean high) {
            this.alert = alert;
            this.high = high;
        }
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            io.execute(AlertWatcher.this::pollOnce);
            main.postDelayed(this, POLL_MS);
        }
    };

    AlertWatcher(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.density = ctx.getResources().getDisplayMetrics().density;
    }

    /** `adb shell am broadcast -a com.gathin.tvhome.TEST_ALERT --es key ring` shows an alert card (adb only). */
    private final android.content.BroadcastReceiver testReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, android.content.Intent i) {
            String key = i.getStringExtra("key");
            for (Alert a : ALERTS) {
                if (a.key.equals(key)) {
                    io.execute(() -> show(a));
                    return;
                }
            }
        }
    };
    private boolean receiverRegistered;

    void start() {
        if (running || !Ha.configured()) return;
        running = true;
        if (!receiverRegistered) {
            ctx.registerReceiver(testReceiver, new android.content.IntentFilter("com.gathin.tvhome.TEST_ALERT"),
                    "android.permission.DUMP", main);   // only the adb shell holds DUMP
            receiverRegistered = true;
        }
        main.postDelayed(poll, 3000);
    }

    void stop() {
        running = false;
        if (receiverRegistered) {
            try {
                ctx.unregisterReceiver(testReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        main.removeCallbacks(poll);
        main.post(this::hide);
    }

    private void pollOnce() {
        String json = Ha.renderTemplate(TEMPLATE);
        if (json == null) return;
        try {
            JSONObject o = new JSONObject(json.trim());
            Alert sticky = null;
            for (Alert a : ALERTS) {
                boolean now = o.optBoolean(a.key, false);
                boolean before = Boolean.TRUE.equals(last.get(a.key));
                last.put(a.key, now);
                if (!now) continue;
                if (a.sticky) {
                    if (sticky == null) sticky = a;   // the first (most severe) active sticky alert stays up
                } else if (!before) {
                    show(a);
                }
            }
            if (sticky != null) show(sticky);
            else if (overlay != null && overlay.getTag() instanceof Boolean && (Boolean) overlay.getTag()) main.post(this::hide);
            handleNotifications(o.optString("n", ""), "playing".equals(o.optString("play")), "Sleep".equals(o.optString("mode")));
        } catch (Exception ignored) {
        }
    }

    /** Picks up a new script.tv_notify message, adds game-start reminders, and shows whatever is allowed right now. */
    private void handleNotifications(String raw, boolean playing, boolean sleeping) {
        try {
            if (raw != null && raw.startsWith("{")) {
                JSONObject n = new JSONObject(raw);
                long id = n.optLong("i", 0);
                if (lastNotifId == -1) {
                    lastNotifId = id;       // first poll: remember, don't replay an old notification
                } else if (id != lastNotifId) {
                    lastNotifId = id;
                    boolean high = "high".equals(n.optString("l"));
                    long ms = Math.max(3, Math.min(30, n.optInt("s", high ? 15 : 8))) * 1000L;
                    queue.add(new Pending(new Alert("n" + id, n.optString("t"), n.optString("m"), null, false,
                            high ? 0xFFFF9F0A : 0xFF64D2FF, ms), high));
                }
            } else if (lastNotifId == -1) {
                lastNotifId = 0;
            }
        } catch (Exception ignored) {
        }
        queueGameStarts();
        long now = System.currentTimeMillis();
        for (java.util.Iterator<Pending> it = queue.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (!p.high && (sleeping || now - p.queuedAt > INFO_MAX_AGE_MS)) it.remove();   // stale or quiet hours
        }
        if (now < busyUntil) return;
        for (java.util.Iterator<Pending> it = queue.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (!p.high && playing) continue;   // info waits until you pause or stop
            it.remove();
            busyUntil = now + p.alert.showMs + 800;
            show(p.alert);
            return;
        }
    }

    /** "Packers kick off in 10 minutes", once per game, from the cached sports schedule. */
    private void queueGameStarts() {
        try {
            org.json.JSONArray games = new org.json.JSONArray(
                    ctx.getSharedPreferences("sports", Context.MODE_PRIVATE).getString("games", "[]"));
            android.content.SharedPreferences sp = ctx.getSharedPreferences("sports_notified", Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            for (int i = 0; i < games.length(); i++) {
                JSONObject g = games.getJSONObject(i);
                long start = g.optLong("start");
                long until = start - now;
                String key = g.optString("team") + start;
                if (g.optBoolean("live") || until > 11 * 60_000L || until < 8 * 60_000L || sp.getBoolean(key, false)) continue;
                sp.edit().putBoolean(key, true).apply();
                String net = g.optString("net");
                queue.add(new Pending(new Alert("g" + key, g.optString("team") + " kick off in 10 minutes",
                        (g.optBoolean("home", true) ? "vs " : "at ") + g.optString("opp") + (net.isEmpty() ? "" : " · " + net),
                        null, false, 0xFF30D158, 14000), true));
            }
        } catch (Exception ignored) {
        }
    }

    private void show(Alert a) {
        if (!Settings.canDrawOverlays(ctx)) return;
        Bitmap snap = null;
        if (a.camera != null) {
            byte[] img = Ha.getBytes("/api/camera_proxy/" + a.camera);
            if (img != null) snap = BitmapFactory.decodeByteArray(img, 0, img.length);
        }
        final Bitmap bmp = snap;
        main.post(() -> {
            hide();
            overlay = build(a, bmp);
            overlay.setTag(a.sticky);
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = (int) (64 * density);
            lp.y = (int) (48 * density);
            try {
                wm.addView(overlay, lp);
            } catch (Exception e) {
                overlay = null;
                return;
            }
            overlay.setAlpha(0f);
            overlay.setTranslationX(24 * density);
            overlay.animate().alpha(1f).translationX(0f).setDuration(260).start();
            if (!a.sticky) main.postDelayed(this::hide, a.showMs);
        });
    }

    private View build(Alert a, Bitmap snap) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * density);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2202024);
        bg.setCornerRadius(22 * density);
        bg.setStroke((int) (2 * density), a.color);
        box.setBackground(bg);

        TextView title = new TextView(ctx);
        title.setText(a.title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText(a.subtitle);
        sub.setTextColor(0xB3FFFFFF);
        sub.setTextSize(15);
        box.addView(sub);

        if (snap != null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageBitmap(snap);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams((int) (360 * density), (int) (203 * density));
            ip.topMargin = (int) (12 * density);
            iv.setLayoutParams(ip);
            iv.setClipToOutline(true);
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 12 * density);
                }
            });
            box.addView(iv);
        }
        return box;
    }

    private void hide() {
        if (overlay == null) return;
        try {
            ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).removeView(overlay);
        } catch (Exception ignored) {
        }
        overlay = null;
    }
}
