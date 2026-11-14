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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Next (or live) game for each team in res/values/config.xml (sports_teams), from ESPN's public team schedules.
 * Refreshed when the launcher opens if the cache is old: every 5 minutes while a game is live, otherwise every 3 hours.
 */
final class Sports {
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long LIVE_AGE_MS = 5 * 60 * 1000L, IDLE_AGE_MS = 3 * 60 * 60 * 1000L;

    static final class Game {
        String team = "";       // "Badgers", "Packers"
        String sport = "";      // "football"
        String opponent = "";
        boolean home = true;
        long startMs;
        String network = "";
        boolean live;
        String detail = "";     // "Q3 5:12" while live
        String score = "";      // "17-10" while live
    }

    interface Listener {
        void onGames(List<Game> games);
    }

    private Sports() {}

    static void load(Context ctx, Listener l) {
        final Context app = ctx.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences("sports", Context.MODE_PRIVATE);
        List<Game> cached = fromJson(sp.getString("games", "[]"));
        if (!cached.isEmpty()) l.onGames(cached);
        boolean anyLive = false;
        for (Game g : cached) anyLive |= g.live || Math.abs(g.startMs - System.currentTimeMillis()) < 4 * 3600_000L;
        long age = System.currentTimeMillis() - sp.getLong("fetched", 0);
        if (!cached.isEmpty() && age < (anyLive ? LIVE_AGE_MS : IDLE_AGE_MS)) return;
        POOL.execute(() -> {
            List<Game> fresh = fetchAll(app);
            if (!fresh.isEmpty()) MAIN.post(() -> l.onGames(fresh));
        });
    }

    /** Fetches every team, caches the result and returns it (blocking: call off the main thread). */
    static List<Game> fetchAll(Context app) {
        SharedPreferences sp = app.getSharedPreferences("sports", Context.MODE_PRIVATE);
        List<Game> fresh = new ArrayList<>();
        for (String spec : app.getResources().getStringArray(R.array.sports_teams)) {
            try {
                Game g = bestFor(spec);
                if (g != null) fresh.add(g);
            } catch (Exception ignored) {
            }
        }
        if (fresh.isEmpty()) return fresh;
        Collections.sort(fresh, (a, b) -> {
            if (a.live != b.live) return a.live ? -1 : 1;
            return Long.compare(a.startMs, b.startMs);
        });
        sp.edit().putString("games", toJson(fresh)).putLong("fetched", System.currentTimeMillis()).apply();
        return fresh;
    }

    /**
     * Scoring plays and finals for teams whose game is live. Polls at most once a minute, and only while a cached
     * game is live (or about to start). Returns {title, message} pairs for new events (blocking: off the main thread).
     */
    static List<String[]> pollLive(Context app) {
        List<String[]> events = new ArrayList<>();
        SharedPreferences sp = app.getSharedPreferences("sports", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        List<Game> cached = fromJson(sp.getString("games", "[]"));
        boolean watch = false;
        for (Game g : cached) watch |= g.live || (g.startMs - now < 5 * 60_000L && g.startMs - now > -3 * 3600_000L);
        if (!watch || now - sp.getLong("live_poll", 0) < 60_000L) return events;
        sp.edit().putLong("live_poll", now).apply();
        List<Game> fresh = fetchAll(app);
        SharedPreferences.Editor ed = sp.edit();
        for (Game g : fresh) {
            String key = "last_" + g.team;
            String before = sp.getString(key, "");     // "score|opponent" while live
            if (g.live && !g.score.isEmpty()) {
                String now1 = g.score + "|" + g.opponent;
                if (!before.isEmpty() && !before.equals(now1) && before.endsWith("|" + g.opponent)) {
                    events.add(new String[]{g.team + " " + g.score.replace("-", "–"), (g.home ? "vs " : "at ") + g.opponent
                            + (g.detail.isEmpty() ? "" : " · " + g.detail)});
                }
                ed.putString(key, now1);
            } else if (!before.isEmpty()) {
                String[] parts = before.split("\\|");
                events.add(new String[]{"Final: " + g.team + " " + parts[0].replace("-", "–"),
                        parts.length > 1 ? "vs " + parts[1] : ""});
                ed.remove(key);
            }
        }
        ed.apply();
        return events;
    }

    /** spec = "Label;sport/league[,sport/league...];espnTeamId" */
    private static Game bestFor(String spec) throws Exception {
        String[] p = spec.split(";");
        Game best = null;
        for (String path : p[1].split(",")) {
            Game g = nextIn(p[0], path.trim(), p[2].trim());
            if (g == null) continue;
            if (best == null || (g.live && !best.live) || (g.live == best.live && g.startMs < best.startMs)) best = g;
        }
        return best;
    }

    private static Game nextIn(String label, String path, String team) throws Exception {
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
            JSONObject type = comp.getJSONObject("status").getJSONObject("type");
            String state = type.optString("state");
            if ("post".equals(state)) continue;
            long start = parseDate(e.optString("date"));
            boolean live = "in".equals(state);
            if (!live && start < now - 3 * 3600_000L) continue;
            Game g = new Game();
            g.team = label;
            g.sport = sportName(path);
            g.live = live;
            g.startMs = start;
            g.detail = type.optString("shortDetail");
            JSONArray teams = comp.getJSONArray("competitors");
            String ours = "", theirs = "";
            for (int k = 0; k < teams.length(); k++) {
                JSONObject t = teams.getJSONObject(k);
                JSONObject info = t.getJSONObject("team");
                JSONObject sc = t.optJSONObject("score");
                String s = sc != null ? sc.optString("displayValue", "") : "";
                if (team.equals(info.optString("id"))) {
                    g.home = "home".equals(t.optString("homeAway"));
                    ours = s;
                } else {
                    g.opponent = info.optString("shortDisplayName", info.optString("displayName"));
                    theirs = s;
                }
            }
            if (live && !ours.isEmpty() && !theirs.isEmpty()) g.score = ours + "-" + theirs;
            JSONArray b = comp.optJSONArray("broadcasts");
            if (b != null && b.length() > 0) {
                JSONObject first = b.getJSONObject(0);
                JSONObject media = first.optJSONObject("media");
                String net = media != null ? media.optString("shortName") : first.optString("name");
                g.network = "null".equals(net) ? "" : net;
            }
            if (best == null || (g.live && !best.live) || (g.live == best.live && g.startMs < best.startMs)) best = g;
        }
        return best;
    }

    private static String sportName(String path) {
        if (path.contains("football")) return "football";
        if (path.contains("womens")) return "women's basketball";
        if (path.contains("basketball")) return "basketball";
        if (path.contains("hockey")) return "hockey";
        if (path.contains("baseball")) return "baseball";
        return "";
    }

    private static long parseDate(String iso) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso).getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String toJson(List<Game> games) {
        JSONArray a = new JSONArray();
        try {
            for (Game g : games) {
                a.put(new JSONObject().put("team", g.team).put("sport", g.sport).put("opp", g.opponent)
                        .put("home", g.home).put("start", g.startMs).put("net", g.network).put("live", g.live)
                        .put("detail", g.detail).put("score", g.score));
            }
        } catch (Exception ignored) {
        }
        return a.toString();
    }

    private static List<Game> fromJson(String s) {
        List<Game> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Game g = new Game();
                g.team = o.optString("team");
                g.sport = o.optString("sport");
                g.opponent = o.optString("opp");
                g.home = o.optBoolean("home", true);
                g.startMs = o.optLong("start");
                g.network = o.optString("net");
                g.live = o.optBoolean("live");
                g.detail = o.optString("detail");
                g.score = o.optString("score");
                if (g.startMs > System.currentTimeMillis() - 3 * 3600_000L || g.live) out.add(g);
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
