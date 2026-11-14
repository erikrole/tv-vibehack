package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
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
    private volatile String visibleKey;
    private volatile boolean running;
    private final Runnable dismissOverlay = this::hide;
    /** Notifications from Home Assistant (script.tv_notify) and local ones (game starts), waiting to be shown. */
    private final java.util.ArrayList<Pending> queue = new java.util.ArrayList<>();
    private long lastNotifId = -1;          // -1 until the first poll: what is already there is not replayed
    private volatile long busyUntil;        // shared by the worker and the UI that attaches the overlay
    private final java.util.concurrent.atomic.AtomicInteger pendingShows = new java.util.concurrent.atomic.AtomicInteger();
    private static final int MAX_PENDING = 32;
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
            io.execute(() -> {
                try {
                    if (running) pollOnce();
                } finally {
                    // Schedule after completion. A slow camera/server must never build a queue of stale polls.
                    main.post(() -> {
                        if (running) main.postDelayed(poll, POLL_MS);
                    });
                }
            });
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
            if (!running) return;
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

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // Only the pre-33 branch uses the legacy overload.
    void start() {
        if (running || !Ha.configured()) return;
        running = true;
        if (!receiverRegistered) {
            android.content.IntentFilter filter = new android.content.IntentFilter("com.gathin.tvhome.TEST_ALERT");
            // Exported for adb, still restricted to the shell's DUMP permission on every Android version.
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(testReceiver, filter, "android.permission.DUMP", main, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(testReceiver, filter, "android.permission.DUMP", main);
            }
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
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        hide();
    }

    private void pollOnce() {
        String json = Ha.renderTemplate(TEMPLATE);
        if (!running || json == null) return;
        try {
            JSONObject o = new JSONObject(json.trim());
            Alert sticky = null;
            java.util.List<Alert> triggered = new java.util.ArrayList<>();
            for (Alert a : ALERTS) {
                boolean now = o.optBoolean(a.key, false);
                boolean before = Boolean.TRUE.equals(last.get(a.key));
                last.put(a.key, now);
                if (!now) continue;
                if (a.sticky) {
                    if (sticky == null) sticky = a;   // the first (most severe) active sticky alert stays up
                } else if (!before) {
                    if (notifyMode().equals("dnd") && !a.key.equals("ring")) continue;   // do-not-disturb: ring only
                    triggered.add(a);
                }
            }
            if (sticky != null) show(sticky);
            else {
                main.post(() -> {
                    if (overlay != null && Boolean.TRUE.equals(overlay.getTag())) hide();
                });
                for (Alert a : triggered) show(a);
            }
            handleNotifications(o.optString("n", ""), "playing".equals(o.optString("play")),
                    "Sleep".equals(o.optString("mode")), sticky != null);
        } catch (Exception ignored) {
        }
    }

    /** Picks up a new script.tv_notify message, adds game-start reminders, and shows whatever is allowed right now. */
    private void handleNotifications(String raw, boolean playing, boolean sleeping, boolean stickyActive) {
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
                    enqueue(new Pending(new Alert("n" + id, n.optString("t"), n.optString("m"), null, false,
                            high ? 0xFFFF9F0A : 0xFF64D2FF, ms), high));
                }
            } else if (lastNotifId == -1) {
                lastNotifId = 0;
            }
        } catch (Exception ignored) {
        }
        queueGameStarts();
        queueScores();
        long now = System.currentTimeMillis();
        String mode = notifyMode();
        for (java.util.Iterator<Pending> it = queue.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (!p.high && (sleeping || !mode.equals("normal") || now - p.queuedAt > INFO_MAX_AGE_MS)) it.remove();   // stale or quiet
            else if (mode.equals("dnd")) it.remove();
        }
        if (!running || stickyActive || pendingShows.get() > 0 || now < busyUntil) return;
        for (java.util.Iterator<Pending> it = queue.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (!p.high && playing) continue;   // info waits until you pause or stop
            it.remove();
            busyUntil = now + p.alert.showMs + 800;
            show(p.alert);
            return;
        }
    }

    /** "normal", "quiet" (no info cards, no game reminders) or "dnd" (also no person alerts), set from the Control Center. */
    private String notifyMode() {
        android.content.SharedPreferences sp = ctx.getSharedPreferences("notify", Context.MODE_PRIVATE);
        long until = sp.getLong("until", 0);
        if (until > 0 && System.currentTimeMillis() > until) {
            sp.edit().putString("mode", "normal").putLong("until", 0).apply();
            return "normal";
        }
        return sp.getString("mode", "normal");
    }

    /** Scoring plays and finals for live games (info level: they wait for a pause). */
    private void queueScores() {
        try {
            for (String[] e : Sports.pollLive(ctx)) {
                enqueue(new Pending(new Alert("s" + System.nanoTime(), e[0], e[1], null, false, 0xFF30D158, 9000), false));
            }
        } catch (Exception ignored) {
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
                enqueue(new Pending(new Alert("g" + key, g.optString("team") + " kick off in 10 minutes",
                        (g.optBoolean("home", true) ? "vs " : "at ") + g.optString("opp") + (net.isEmpty() ? "" : " · " + net),
                        null, false, 0xFF30D158, 14000), true));
            }
        } catch (Exception ignored) {
        }
    }

    private void enqueue(Pending pending) {
        if (queue.size() >= MAX_PENDING) {
            int oldest = 0;
            for (int i = 0; i < queue.size(); i++) {
                if (!queue.get(i).high) {
                    oldest = i;
                    break;
                }
            }
            queue.remove(oldest);
        }
        queue.add(pending);
    }

    private void show(Alert a) {
        if (!running || !Settings.canDrawOverlays(ctx)) return;
        if (a.sticky && a.key.equals(visibleKey)) return;
        // Reserve the slot during camera IO too; the real duration begins when the card is attached.
        pendingShows.incrementAndGet();
        Bitmap snap = null;
        if (a.camera != null) {
            byte[] img = Ha.getBytes("/api/camera_proxy/" + a.camera);
            if (img != null && running) snap = decodeSnapshot(img);
        }
        final Bitmap bmp = snap;
        main.post(() -> {
            try {
                if (!running) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
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
                    visibleKey = a.key;
                    busyUntil = a.sticky ? Long.MAX_VALUE : System.currentTimeMillis() + a.showMs + 800;
                } catch (Exception e) {
                    overlay = null;
                    busyUntil = 0;
                    return;
                }
                overlay.setAlpha(0f);
                overlay.setTranslationX(24 * density);
                overlay.animate().alpha(1f).translationX(0f).setDuration(260).start();
                if (!a.sticky) main.postDelayed(dismissOverlay, a.showMs);
            } finally {
                pendingShows.decrementAndGet();
            }
        });
    }

    /** Decode close to the displayed card size, avoiding a full-resolution camera bitmap in native memory. */
    private Bitmap decodeSnapshot(byte[] bytes) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        int width = Math.max(1, (int) (360 * density)), height = Math.max(1, (int) (203 * density));
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > width * 2
                || options.outHeight / options.inSampleSize > height * 2) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
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
        main.removeCallbacks(dismissOverlay);
        visibleKey = null;
        busyUntil = 0;
        if (overlay == null) return;
        try {
            ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).removeView(overlay);
        } catch (Exception ignored) {
        }
        overlay = null;
    }
}
