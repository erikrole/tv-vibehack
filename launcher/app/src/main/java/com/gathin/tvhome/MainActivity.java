package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * Theater-first home: apps, a compact sports strip, and room scenes with predictable remote navigation.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** App columns (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private TextView heroName;
    private boolean appsReady;
    private boolean resumed;
    private ViewTreeObserver.OnPreDrawListener pendingFocus;
    private TextView greeting;
    private TextView btnWifi;
    private String networkLabel = "";
    private View infoRow, controlCenter;
    private LinearLayout sceneRow, ccInputs, ccSleep, ccNotify;
    private TextView btnControl, ccStatus;
    private LinearLayout sportsRow;
    private boolean ccOpen;
    /** Sleep timer: when it fires the TV is turned off through Home Assistant. Lives as long as the process. */
    private static long sleepAtMs = 0L;
    private static final Handler SLEEP_HANDLER = new Handler(Looper.getMainLooper());
    private static Runnable sleepFire;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private View focusedCard;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            adapter.invalidatePackage(intent.getData() != null ? intent.getData().getSchemeSpecificPart() : null);
        }
    };
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        heroName = findViewById(R.id.heroName);
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);
        btnControl = findViewById(R.id.btnControl);
        infoRow = findViewById(R.id.infoRow);
        sceneRow = findViewById(R.id.sceneRow);
        sportsRow = findViewById(R.id.sportsRow);
        controlCenter = findViewById(R.id.controlCenter);
        ccInputs = findViewById(R.id.ccInputs);
        ccSleep = findViewById(R.id.ccSleep);
        ccNotify = findViewById(R.id.ccNotify);
        ccStatus = findViewById(R.id.ccStatus);
        buildScenes();
        controlCenter.setOnClickListener(v -> closeControlCenter());
        Sports.load(this, this::showGames);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this);
        grid.setAdapter(adapter);
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        registerReceiver(packageReceiver, packages);

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                // While the grid scrolls, pin the glass to its tile's real position every frame, so it can never
                // trail behind (a spring chasing a moving target looked like the outline sliding below the icon).
                if (focusedCard != null && focusedCard.isAttachedToWindow()) {
                    Rect r = new Rect(0, 0, focusedCard.getWidth(), focusedCard.getHeight());
                    root.offsetDescendantRectToMyCoords(focusedCard, r);
                    glassTargetY = r.bottom - 2 * density;
                } else {
                    glassTargetY -= dy;
                }
                springY.cancel();
                glass.setTranslationY(glassTargetY);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(700f).setDampingRatio(1f));
        }

        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnSettings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusTile(lastFocused);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnInputs) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnControl.setOnClickListener(v -> openControlCenter());
        for (TextView pill : new TextView[]{(TextView) btnInputs, btnWifi, btnControl, (TextView) btnSettings}) {
            pill.setOnFocusChangeListener((v, focused) -> {
                TextView t = (TextView) v;
                t.setText(focused ? pillLabel(t) : "");
                t.setCompoundDrawablePadding(focused ? (int) (9 * density) : 0);
                t.setPadding((int) (11 * density), 0, (int) ((focused ? 18 : 11) * density), 0);
                t.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(180).start();
            });
        }
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnControl.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        root.animate().cancel();
        root.setScaleX(1f);
        root.setScaleY(1f);
        root.setAlpha(1f);
        glassShown = false;
        glass.setAlpha(0f);
        infoRow.setAlpha(1f);
        adapter.reload();
        updateGreeting();
        updateWifiLabel();
        handler.postDelayed(minuteTick, MINUTE_MS);

        restoreTileFocus();
    }

    @Override
    public void onAppsLoaded(boolean changed) {
        boolean firstLoad = !appsReady;
        appsReady = true;
        heroName.setText(adapter.getItemCount() == 0 ? R.string.no_apps : R.string.app_options_hint);
        if (!resumed || ccOpen || !changed) return;
        if (firstLoad) grid.scheduleLayoutAnimation();
        View focus = getCurrentFocus();
        if (firstLoad || focus == null || focus.getId() == R.id.card) {
            // Keep the selected app when a package changes while the list owns focus.
            restoreTileFocus();
        }
    }

    private void restoreTileFocus() {
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        handler.post(() -> {
            if (!resumed || ccOpen) return;
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
        });
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
        handler.removeCallbacks(minuteTick);
        cancelPendingFocus();
        grid.stopScroll();
        root.animate().cancel();
        heroName.animate().withEndAction(null).cancel();
        infoRow.animate().cancel();
        glass.animate().cancel();
        springX.cancel();
        springY.cancel();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        handler.removeCallbacksAndMessages(null);
        cancelPendingFocus();
        adapter.close();
        grid.setAdapter(null);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
    }

    /** Brief launch fade; returning Home restores the canvas immediately. */
    @Override
    public void onLaunch(Runnable go) {
        root.animate().cancel();
        root.animate().alpha(0f).setDuration(140)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                .withEndAction(() -> {
                    go.run();
                    // A failed launch never pauses us: make the home screen usable again.
                    if (resumed) root.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start();
                }).start();
    }

    @Override
    public void onBackPressed() {
        if (ccOpen) closeControlCenter();
        // Home screen: Back goes nowhere.
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {   // Menu/Options key toggles the Control Center
            if (ccOpen) closeControlCenter();
            else openControlCenter();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- scenes, game card -----------------------------------------------------------------------

    private void buildScenes() {
        String[] ids = getResources().getStringArray(R.array.ha_scene_ids);
        String[] labels = getResources().getStringArray(R.array.ha_scene_labels);
        for (int i = 0; i < ids.length && i < labels.length; i++) {
            final String id = ids[i];
            TextView chip = new TextView(this, null, 0, R.style.Chip);
            chip.setText(labels[i]);
            chip.setOnClickListener(v -> Ha.call("scene", "turn_on", id, (ok, msg) ->
                    Toast.makeText(this, ok ? ((TextView) v).getText() + " on" : msg, Toast.LENGTH_SHORT).show()));
            chip.setId(View.generateViewId());
            chip.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {   // up from the scene row goes to the top-bar pills
                    focusTile(lastFocused);
                    return true;
                }
                return keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        || (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v == sceneRow.getChildAt(0))
                        || (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v == sceneRow.getChildAt(sceneRow.getChildCount() - 1));
            });
            chip.setLayoutParams(chipParams());
            chip.getLayoutParams().height = (int) (36 * density);
            chipFocus(chip);
            sceneRow.addView(chip);
        }
    }

    private void showGames(java.util.List<Sports.Game> games) {
        if (isDestroyed()) return;
        sportsRow.removeAllViews();
        if (games == null || games.isEmpty()) {
            sportsRow.setVisibility(View.GONE);
            return;
        }
        int shown = 0;
        for (Sports.Game g : games) {
            if (shown == 4) break;
            View card = getLayoutInflater().inflate(R.layout.item_sport, sportsRow, false);
            String net = g.network.isEmpty() ? "" : " · " + g.network;
            ((TextView) card.findViewById(R.id.team)).setText(
                    g.live ? "● LIVE · " + g.team + " " + g.sport : g.team + " " + g.sport + net);
            ((TextView) card.findViewById(R.id.matchup)).setText(
                    g.live && !g.score.isEmpty()
                            ? String.format(Locale.US, "%s %s %s", g.home ? "vs" : "at", g.opponent, g.score)
                            : String.format(Locale.US, "%s %s", g.home ? "vs" : "at", g.opponent));
            String when = g.live ? g.detail
                    : countdown(g.startMs) + " · " + new SimpleDateFormat("EEE h:mm a", Locale.US).format(new Date(g.startMs));
            ((TextView) card.findViewById(R.id.when)).setText(when);
            if (g.live) {
                ((TextView) card.findViewById(R.id.team)).setTextColor(0xFF80D8AC);
            }
            sportsRow.addView(card);
            shown++;
        }
        sportsRow.setVisibility(View.VISIBLE);
    }

    /** "Today", "Tomorrow" or "In 6 days", counted in calendar days. */
    private static String countdown(long startMs) {
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(startMs);
        for (Calendar c : new Calendar[]{a, b}) {
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
        }
        long days = Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / 86_400_000.0);
        if (days <= 0) return "Today";
        if (days == 1) return "Tomorrow";
        return "In " + days + " days";
    }

    // ---- Control Center --------------------------------------------------------------------------

    private void openControlCenter() {
        if (ccOpen) return;
        ccOpen = true;
        buildCcInputs();
        buildCcSleep();
        buildCcNotify();
        updateCcStatus();
        setUnderlayFocusable(false);
        controlCenter.setAlpha(0f);
        controlCenter.setVisibility(View.VISIBLE);
        View panel = findViewById(R.id.ccPanel);
        panel.setScaleX(0.94f);
        panel.setScaleY(0.94f);
        controlCenter.animate().alpha(1f).setDuration(200).start();
        panel.animate().scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f)).start();
        View first = ccInputs.getChildCount() > 0 && ccInputs.getChildAt(0).isFocusable()
                ? ccInputs.getChildAt(0) : ccSleep.getChildAt(0);
        if (first != null) first.requestFocus();
    }

    private void closeControlCenter() {
        if (!ccOpen) return;
        ccOpen = false;
        controlCenter.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            controlCenter.setVisibility(View.GONE);
            setUnderlayFocusable(true);
            btnControl.requestFocus();
        }).start();
    }

    private void setUnderlayFocusable(boolean on) {
        int mode = on ? ViewGroup.FOCUS_BEFORE_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS;
        ((ViewGroup) findViewById(R.id.mainContent)).setDescendantFocusability(mode);
        ((ViewGroup) infoRow).setDescendantFocusability(mode);
    }

    /** Same focus feel as the pills: a small scale-up. */
    private void chipFocus(View chip) {
        chip.setOnFocusChangeListener((v, focused) ->
                v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(180).start());
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) (44 * density));
        lp.setMarginEnd((int) (8 * density));
        return lp;
    }

    private TextView ccChip(String text, Runnable action) {
        TextView chip = new TextView(this, null, 0, R.style.Chip);
        chip.setLayoutParams(chipParams());
        chipFocus(chip);
        chip.setText(text);
        chip.setOnClickListener(v -> action.run());
        return chip;
    }

    /** One chip per HDMI/AV input; selecting it switches the TV straight to that input. */
    private void buildCcInputs() {
        ccInputs.removeAllViews();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            Collections.sort(inputs, (a, b) -> String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this))));
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                if (info.getType() == TvInputInfo.TYPE_HDMI && !label.toUpperCase(Locale.US).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                ccInputs.addView(ccChip(label, () -> {
                    closeControlCenter();
                    open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                            TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel"));
                }));
            }
        }
        if (ccInputs.getChildCount() == 0) {
            TextView none = new TextView(this);
            none.setText("No HDMI inputs found on this device");
            none.setTextColor(0x80FFFFFF);
            none.setTextSize(14);
            ccInputs.addView(none);
        }
    }

    private void buildCcSleep() {
        ccSleep.removeAllViews();
        int[] minutes = {15, 30, 60, 90};
        for (int m : minutes) {
            ccSleep.addView(ccChip(m + " min", () -> {
                cancelSleepTimer();
                sleepAtMs = System.currentTimeMillis() + m * MINUTE_MS;
                Context app = getApplicationContext();
                String entity = getString(R.string.ha_tv_entity);
                sleepFire = () -> {
                    sleepAtMs = 0L;
                    sleepFire = null;
                    Ha.call("media_player", "turn_off", entity, (ok, msg) ->
                            Toast.makeText(app, ok ? "Good night" : msg, Toast.LENGTH_LONG).show());
                };
                SLEEP_HANDLER.postDelayed(sleepFire, m * MINUTE_MS);
                updateCcStatus();
            }));
        }
        ccSleep.addView(ccChip("Off", () -> {
            cancelSleepTimer();
            updateCcStatus();
        }));
    }

    private static void cancelSleepTimer() {
        if (sleepFire != null) SLEEP_HANDLER.removeCallbacks(sleepFire);
        sleepFire = null;
        sleepAtMs = 0L;
    }

    /** Notification mode, read by the alert watcher: normal, quiet for an hour, or do-not-disturb until switched back. */
    private void buildCcNotify() {
        ccNotify.removeAllViews();
        android.content.SharedPreferences sp = getSharedPreferences("notify", MODE_PRIVATE);
        ccNotify.addView(ccChip("Normal", () -> {
            sp.edit().putString("mode", "normal").putLong("until", 0).apply();
            updateCcStatus();
        }));
        ccNotify.addView(ccChip("Quiet 1 h", () -> {
            sp.edit().putString("mode", "quiet").putLong("until", System.currentTimeMillis() + 60 * MINUTE_MS).apply();
            updateCcStatus();
        }));
        ccNotify.addView(ccChip("Do not disturb", () -> {
            sp.edit().putString("mode", "dnd").putLong("until", 0).apply();
            updateCcStatus();
        }));
    }

    private void updateCcStatus() {
        String s;
        if (sleepAtMs > System.currentTimeMillis()) {
            long min = Math.max(1, (sleepAtMs - System.currentTimeMillis() + MINUTE_MS - 1) / MINUTE_MS);
            s = "The TV turns off in " + min + " min";
        } else {
            s = Ha.configured() ? "Sleep timer is off" : "Sleep timer needs Home Assistant: add ha.url and ha.token to launcher/local.properties";
        }
        android.content.SharedPreferences np = getSharedPreferences("notify", MODE_PRIVATE);
        String nm = np.getString("mode", "normal");
        if (nm.equals("quiet") && np.getLong("until", 0) > System.currentTimeMillis()) {
            s += "\nNotifications are quiet for " + Math.max(1, (np.getLong("until", 0) - System.currentTimeMillis() + MINUTE_MS - 1) / MINUTE_MS) + " more min";
        } else if (nm.equals("dnd")) {
            s += "\nDo not disturb: only the doorbell ring, leaks and the alarm get through";
        }
        ccStatus.setText(s);
    }

    // ---- selection marker -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        focusedCard = card;
        lastFocusedKey = adapter.keyAt(position);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;
        float w = 28f * density, h = 2f * density;
        float x = r.exactCenterX() - w / 2f;
        float y = r.bottom - h;
        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(density);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        View item = (View) card.getParent();
        Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
        grid.requestChildRectangleOnScreen(item, want, false);
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileDirectionalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (position % COLUMNS == 0) return true;
            target = position - 1;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (position % COLUMNS == COLUMNS - 1 || position == count - 1) return true;
            target = position + 1;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) {
                findViewById(R.id.btnInputs).requestFocus();
                return true;
            }
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) {
                if (sceneRow.getChildCount() > 0) sceneRow.getChildAt(0).requestFocus();
                return true;
            }
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        cancelPendingFocus();
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null && !grid.hasPendingAdapterUpdates()) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // One pending target, resolved after layout. Repeated keys replace it instead of accumulating listeners.
        pendingFocus = () -> {
            cancelPendingFocus();
            if (!resumed || ccOpen) return true;
            RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(pos);
            if (holder != null) {
                View card = holder.itemView.findViewById(R.id.card);
                if (card.isFocused()) onTileFocused(pos, card);
                else card.requestFocus();
            }
            return true;
        };
        grid.getViewTreeObserver().addOnPreDrawListener(pendingFocus);
        grid.scrollToPosition(pos);
        grid.postInvalidateOnAnimation();
    }

    private void cancelPendingFocus() {
        if (pendingFocus == null) return;
        if (grid.getViewTreeObserver().isAlive()) grid.getViewTreeObserver().removeOnPreDrawListener(pendingFocus);
        pendingFocus = null;
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Label shown while a pill is focused. */
    private String pillLabel(TextView pill) {
        if (pill.getId() == R.id.btnInputs) return getString(R.string.inputs);
        if (pill.getId() == R.id.btnWifi) return networkLabel;
        if (pill.getId() == R.id.btnControl) return getString(R.string.room_controls);
        return getString(R.string.settings);
    }

    private void updateGreeting() {
        String room = getString(R.string.room_name);
        if (!room.isEmpty()) {   // a fixed room name replaces the time-of-day greeting
            greeting.setText(room);
            return;
        }
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        int netIcon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = getString(R.string.offline);
                netIcon = R.drawable.ic_wifi_off;
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
                netIcon = R.drawable.ic_ethernet;
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        networkLabel = text;
        btnWifi.setCompoundDrawablesWithIntrinsicBounds(netIcon, 0, 0, 0);
        if (btnWifi.isFocused()) btnWifi.setText(text);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (tunerId == null || info.getId().compareTo(tunerId) < 0)) {
                    tunerId = info.getId();
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), Gravity.START);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
