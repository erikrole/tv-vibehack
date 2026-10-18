package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Next Wisconsin game from ESPN's public team schedules. One fetch per launch at most, cached for six hours in
 * SharedPreferences, so the TV never polls.
 */
final class Badgers {
    private static final long MAX_AGE_MS = 6 * 60 * 60 * 1000L;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static final class Game {
        final String sport;     // "Football"
        final String opponent;  // "USC"
        final boolean home;
        final long startMs;
        final String network;   // may be empty

        Game(String sport, String opponent, boolean home, long startMs, String network) {
            this.sport = sport;
            this.opponent = opponent;
            this.home = home;
            this.startMs = startMs;
            this.network = network;
        }
    }

    interface Listener {
        void onGame(Game game);   // null when nothing is scheduled
    }

    private Badgers() {}

    static void load(Context ctx, Listener l) {
        final Context app = ctx.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences("badgers", Context.MODE_PRIVATE);
        Game cached = parseCached(sp);
        boolean fresh = cached != null && cached.startMs > System.currentTimeMillis()
                && System.currentTimeMillis() - sp.getLong("fetched", 0) < MAX_AGE_MS;
        if (cached != null && cached.startMs > System.currentTimeMillis()) l.onGame(cached);
        if (fresh) return;
        POOL.execute(() -> {
            Game g = fetch(app);
            if (g != null) {
                sp.edit().putString("sport", g.sport).putString("opp", g.opponent).putBoolean("home", g.home)
                        .putLong("start", g.startMs).putString("net", g.network)
                        .putLong("fetched", System.currentTimeMillis()).apply();
                MAIN.post(() -> l.onGame(g));
            } else if (cached == null) {
                MAIN.post(() -> l.onGame(null));
            }
        });
    }

    private static Game parseCached(SharedPreferences sp) {
        long start = sp.getLong("start", 0);
        if (start == 0) return null;
        return new Game(sp.getString("sport", ""), sp.getString("opp", ""), sp.getBoolean("home", true), start,
                sp.getString("net", ""));
    }

    private static Game fetch(Context ctx) {
        String team = ctx.getString(R.string.badgers_team_id);
        Game best = null;
        for (String path : ctx.getResources().getStringArray(R.array.badgers_schedules)) {
            try {
                Game g = nextIn(path, team);
                if (g != null && (best == null || g.startMs < best.startMs)) best = g;
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    private static Game nextIn(String path, String team) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://site.api.espn.com/apis/site/v2/sports/" + path + "/teams/" + team + "/schedule").openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        } finally {
            c.disconnect();
        }
        JSONArray events = new JSONObject(sb.toString()).optJSONArray("events");
        if (events == null) return null;
        long now = System.currentTimeMillis();
        Game best = null;
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            JSONObject comp = e.getJSONArray("competitions").getJSONObject(0);
            if (!"pre".equals(comp.getJSONObject("status").getJSONObject("type").optString("state"))) continue;
            long start = parseDate(e.optString("date"));
            if (start < now - 3 * 60 * 60 * 1000L) continue;
            JSONArray teams = comp.getJSONArray("competitors");
            String opp = "";
            boolean home = true;
            for (int k = 0; k < teams.length(); k++) {
                JSONObject t = teams.getJSONObject(k);
                JSONObject info = t.getJSONObject("team");
                if (team.equals(info.optString("id"))) {
                    home = "home".equals(t.optString("homeAway"));
                } else {
                    opp = info.optString("shortDisplayName", info.optString("displayName"));
                }
            }
            String net = "";
            JSONArray b = comp.optJSONArray("broadcasts");
            if (b != null && b.length() > 0) {
                JSONObject first = b.getJSONObject(0);
                JSONObject media = first.optJSONObject("media");
                net = media != null ? media.optString("shortName") : first.optString("name");
                if ("null".equals(net)) net = "";
            }
            String sport = sportName(path);
            if (best == null || start < best.startMs) best = new Game(sport, opp, home, start, net);
        }
        return best;
    }

    private static String sportName(String path) {
        if (path.contains("football")) return "Football";
        if (path.contains("womens")) return "Women's Basketball";
        if (path.contains("basketball")) return "Basketball";
        if (path.contains("hockey")) return "Hockey";
        return "Badgers";
    }

    private static long parseDate(String iso) {
        // ESPN gives "2026-10-24T23:30Z".
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso).getTime();
        } catch (ParseException e) {
            return 0L;
        }
    }
}
