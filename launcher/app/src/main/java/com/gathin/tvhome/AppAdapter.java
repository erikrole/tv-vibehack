package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Free-standing app marks and small labels. Configured favourites come first; large banners are never loaded.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        /** The app catalogue is ready; called on the main thread, after any adapter update. */
        void onAppsLoaded(boolean changed);

        void onTileFocused(int position, View card);

        /** Called for D-pad directions on a tile. Return true when handled. */
        boolean onTileDirectionalKey(int position, int keyCode);

        /** The listener runs {@code go} after the brief launch transition. */
        void onLaunch(Runnable go);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable mark;
        Entry(String label, ComponentName component, Drawable mark) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.mark = mark;
        }
    }

    private final Context ctx;
    private final TileListener listener;
    /** Dock order (res/values/config.xml, dock_apps). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Set<String> invalidated = new HashSet<>();
    private boolean loading, reloadAgain;
    private volatile boolean closed;

    AppAdapter(Context ctx, TileListener listener) {
        this.ctx = ctx;
        this.listener = listener;
        // Dock apps first, then the grid order from config; everything else follows alphabetically.
        List<String> order = new ArrayList<>(Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps)));
        order.addAll(Arrays.asList(ctx.getResources().getStringArray(R.array.grid_apps)));
        this.favourites = order;
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    void reload() {
        if (closed) return;
        if (loading) {
            reloadAgain = true;
            return;
        }
        loading = true;
        Map<String, Entry> previous = new HashMap<>();
        for (Entry e : items) {
            if (!invalidated.contains(e.pkg)) previous.put(e.component.flattenToShortString(), e);
        }
        invalidated.clear();
        Context app = ctx.getApplicationContext();
        loader.execute(() -> {
            List<Entry> fresh;
            try {
                fresh = loadEntries(app, previous);
            } catch (RuntimeException e) {
                android.util.Log.w("AppAdapter", "Could not refresh apps", e);
                fresh = null;
            }
            final List<Entry> result = fresh;
            if (closed) return;
            main.post(() -> {
                if (closed) return;
                loading = false;
                boolean changed = result != null && !items.equals(result);
                if (changed) {
                    items.clear();
                    items.addAll(result);
                    notifyDataSetChanged();
                }
                listener.onAppsLoaded(changed);
                if (reloadAgain) {
                    reloadAgain = false;
                    reload();
                }
            });
        });
    }

    void invalidatePackage(String pkg) {
        if (pkg != null) invalidated.add(pkg);
        reload();
    }

    void close() {
        closed = true;
        loader.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    private List<Entry> loadEntries(Context app, Map<String, Entry> previous) {
        PackageManager pm = app.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(app.getPackageName()) || hidden.contains(pkg)) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Entry existing = previous.get(component.flattenToShortString());
            if (existing != null) {
                fresh.add(existing);
                continue;
            }
            try {
                String label = String.valueOf(ri.loadLabel(pm));
                fresh.add(new Entry(label, component, BrandMarks.load(app, pkg, ri, pm)));
            } catch (RuntimeException e) {
                // An app may be removed or replaced while PackageManager is loading its resources.
                android.util.Log.w("AppAdapter", "Could not load app " + pkg, e);
            }
        }
        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        return fresh;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.animate().cancel();
        h.mark.animate().cancel();
        h.name.setText(e.label);
        h.mark.setImageDrawable(e.mark);
        h.card.setContentDescription(e.label);
        boolean focused = h.card.isFocused();
        h.name.setTextColor(focused ? 0xFFF4F4F2 : 0xFF8C919A);
        h.name.setTranslationX(0);
        h.mark.setAlpha(focused ? 1f : 0.62f);
        h.mark.setScaleX(focused ? 1.06f : 1f);
        h.mark.setScaleY(focused ? 1.06f : 1f);
        h.card.setOnClickListener(v -> listener.onLaunch(() -> launch(e)));
        h.card.setOnLongClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        });
    }

    @Override
    public void onViewRecycled(@NonNull Holder h) {
        h.name.animate().cancel();
        h.mark.animate().cancel();
        h.mark.setImageDrawable(null);
        super.onViewRecycled(h);
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final TextView name;
        final ImageView mark;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            name = v.findViewById(R.id.name);
            mark = v.findViewById(R.id.mark);
            final float density = v.getResources().getDisplayMetrics().density;

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileDirectionalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                name.setTextColor(focused ? 0xFFF4F4F2 : 0xFF8C919A);
                mark.animate().alpha(focused ? 1f : 0.62f).scaleX(focused ? 1.06f : 1f)
                        .scaleY(focused ? 1.06f : 1f).setDuration(160).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
