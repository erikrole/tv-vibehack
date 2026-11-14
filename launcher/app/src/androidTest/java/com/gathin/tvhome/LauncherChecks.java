package com.gathin.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import androidx.recyclerview.widget.RecyclerView;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Device checks with the platform instrumentation runner; no additional runtime/test libraries. */
public final class LauncherChecks extends Instrumentation {
    private MainActivity activity;
    private int passed;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(!Ha.configured(), "Tests require -PwithoutHomeAssistant=true");
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> ui(() -> adapter().getItemCount() > 0 && focusedTile()), "Initial app focus");
            navigation();
            cacheAndFocus();
            returnFromSettings();
            longInputNames();
            snapshotsAndOverlays();
            result.putString("result", "PASS: " + passed + " launcher checks");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error);
            android.util.Log.e("LauncherChecks", "Check failed", error);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void navigation() {
        int count = ui(() -> adapter().getItemCount());
        check(count >= 8, "Install at least eight emulator fixture apps for row checks");
        focus(0);
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        check(position() == 0, "Left edge stays in its row");
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        check(position() == MainActivity.COLUMNS, "Down moves exactly one row");
        key(KeyEvent.KEYCODE_DPAD_UP);
        check(position() == 0, "Up returns to the same column");
        for (int i = 0; i < 16; i++) {
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            key(KeyEvent.KEYCODE_DPAD_LEFT);
        }
        focus(count - 1);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        check(ui(() -> activity.getCurrentFocus().getId() != R.id.card), "Last row reaches room scenes");
        key(KeyEvent.KEYCODE_DPAD_UP);
        check(position() == count - 1, "Scenes return to the last app");
        key(KeyEvent.KEYCODE_MENU);
        await(() -> ui(() -> (boolean) field(activity, "ccOpen")), "Room panel opens");
        key(KeyEvent.KEYCODE_BACK);
        await(() -> ui(() -> activity.getCurrentFocus().getId() == R.id.btnControl), "Panel returns focus to its button");
        passed++;
    }

    private void cacheAndFocus() {
        AtomicInteger changes = new AtomicInteger();
        List<?> before = ui(() -> new ArrayList<>((List<?>) field(adapter(), "items")));
        ui(() -> {
            adapter().registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override public void onChanged() { changes.incrementAndGet(); }
            });
            adapter().reload();
            return null;
        });
        await(() -> ui(() -> !(boolean) field(adapter(), "loading")), "Cached app reload completes");
        check(changes.get() == 0, "Unchanged apps do not rebind all tiles");
        List<?> after = ui(() -> new ArrayList<>((List<?>) field(adapter(), "items")));
        for (int i = 0; i < before.size(); i++) check(before.get(i) == after.get(i), "Catalogue cache retains app identity");
        ui(() -> { adapter().invalidatePackage("com.gathin.tvhome.fixture"); return null; });
        await(() -> changes.get() > 0, "Package update is applied");
        SystemClock.sleep(300);
        check(ui(() -> activity.getCurrentFocus().getId() == R.id.btnControl), "App update preserves top-bar focus");
        passed++;
    }

    private void longInputNames() {
        key(KeyEvent.KEYCODE_MENU);
        ui(() -> {
            LinearLayout row = (LinearLayout) field(activity, "ccInputs");
            row.removeAllViews();
            for (int i = 1; i <= 6; i++) {
                View chip = (View) invoke(activity, "ccChip", new Class[]{String.class, Runnable.class},
                        "HDMI " + i + " — Long console name", (Runnable) () -> {});
                row.addView(chip);
            }
            return null;
        });
        SystemClock.sleep(200);
        ui(() -> {
            LinearLayout row = (LinearLayout) field(activity, "ccInputs");
            row.getChildAt(row.getChildCount() - 1).requestFocus();
            return null;
        });
        await(() -> ui(() -> ((HorizontalScrollView) ((LinearLayout) field(activity, "ccInputs")).getParent()).getScrollX() > 0),
                "Long input names scroll into view");
        key(KeyEvent.KEYCODE_BACK);
        passed++;
    }

    private void returnFromSettings() {
        focus(1);
        ui(() -> { activity.startActivity(new Intent(Settings.ACTION_SETTINGS)); return null; });
        await(() -> ui(() -> !(boolean) field(activity, "resumed")), "Launcher pauses");
        check(ui(() -> field(activity, "pendingFocus") == null), "Paused launcher has no deferred focus listener");
        ui(() -> { activity.startActivity(new Intent(activity, MainActivity.class)); return null; });
        await(() -> ui(() -> (boolean) field(activity, "resumed") && focusedTile()), "Launcher resumes");
        check(position() == 1, "Return restores the selected app");
        passed++;
    }

    private void snapshotsAndOverlays() throws Exception {
        AlertWatcher watcher = new AlertWatcher(getTargetContext());
        Bitmap source = Bitmap.createBitmap(3840, 2160, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        source.compress(Bitmap.CompressFormat.JPEG, 90, bytes);
        source.recycle();
        Bitmap decoded = (Bitmap) invoke(watcher, "decodeSnapshot", new Class[]{byte[].class}, bytes.toByteArray());
        float density = getTargetContext().getResources().getDisplayMetrics().density;
        check(decoded.getWidth() <= 720 * density && decoded.getHeight() <= 406 * density, "4K snapshot decode is bounded by card size");
        decoded.recycle();
        passed++;
        set(watcher, "running", true);
        Class<?> alert = Class.forName("com.gathin.tvhome.AlertWatcher$Alert");
        Constructor<?> constructor = alert.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object first = constructor.newInstance("first", "Test alert", "Emulator only", null, false, 0xFF80D8AC, 900L);
        Object second = constructor.newInstance("second", "Second alert", "Emulator only", null, false, 0xFF80D8AC, 2500L);
        invoke(watcher, "show", new Class[]{alert}, first);
        await(() -> "first".equals(field(watcher, "visibleKey")), "First test overlay appears");
        SystemClock.sleep(250);
        invoke(watcher, "show", new Class[]{alert}, second);
        await(() -> "second".equals(field(watcher, "visibleKey")), "Replacement overlay appears");
        SystemClock.sleep(1000);
        check("second".equals(field(watcher, "visibleKey")), "Old dismissal does not remove a replacement alert");
        passed++;
        // Model a camera request still in flight while the old overlay expires. The pending slot must survive hide().
        AtomicInteger pending = (AtomicInteger) field(watcher, "pendingShows");
        pending.incrementAndGet();
        ui(() -> { invoke(watcher, "hide", new Class[]{}); return null; });
        set(watcher, "lastNotifId", 0L);
        getTargetContext().getSharedPreferences("sports", 0).edit().putString("games", "[]").commit();
        String notification = "{\"i\":1,\"l\":\"high\",\"t\":\"Queued\",\"m\":\"Emulator only\",\"s\":3}";
        invoke(watcher, "handleNotifications", new Class[]{String.class, boolean.class, boolean.class, boolean.class},
                notification, false, false, false);
        SystemClock.sleep(200);
        check(field(watcher, "visibleKey") == null, "Old dismissal cannot release a pending camera slot");
        pending.decrementAndGet();
        invoke(watcher, "handleNotifications", new Class[]{String.class, boolean.class, boolean.class, boolean.class},
                notification, false, false, false);
        await(() -> "n1".equals(field(watcher, "visibleKey")), "Notification displays when the pending slot clears");
        check((long) field(watcher, "busyUntil") > System.currentTimeMillis() + 2000, "Display duration starts at attachment");
        Object sticky = constructor.newInstance("sticky", "Sticky test", "Emulator only", null, true, 0xFF80D8AC, 0L);
        invoke(watcher, "show", new Class[]{alert}, sticky);
        await(() -> "sticky".equals(field(watcher, "visibleKey")), "Sticky alert appears");
        for (int i = 2; i <= 42; i++) {
            invoke(watcher, "handleNotifications", new Class[]{String.class, boolean.class, boolean.class, boolean.class},
                    notification.replace("\"i\":1", "\"i\":" + i), false, false, true);
        }
        check(((List<?>) field(watcher, "queue")).size() <= 32, "Notification backlog stays bounded");
        check("sticky".equals(field(watcher, "visibleKey")), "Queued notifications preserve a sticky alert");
        ui(() -> { watcher.stop(); return null; });
        check(field(watcher, "overlay") == null, "Stopping alerts removes its window");
        passed++;
    }

    private AppAdapter adapter() { return (AppAdapter) field(activity, "adapter"); }
    private boolean focusedTile() { View view = activity.getCurrentFocus(); return view != null && view.getId() == R.id.card; }
    private int position() { return ui(() -> (int) field(activity, "lastFocused")); }
    private void focus(int position) {
        ui(() -> { invoke(activity, "focusTile", new Class[]{int.class}, position); return null; });
        await(() -> ui(this::focusedTile) && position() == position, "Focus app " + position);
    }
    private void key(int code) { sendKeyDownUpSync(code); waitForIdleSync(); SystemClock.sleep(40); }
    private <T> T ui(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        runOnMainSync(() -> result.set(action.get()));
        return result.get();
    }
    private static void await(BooleanSupplier ready, String label) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        while (!ready.getAsBoolean() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
        check(ready.getAsBoolean(), label);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static Object invoke(Object object, String name, Class<?>[] types, Object... args) {
        try { Method method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(object, args); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
