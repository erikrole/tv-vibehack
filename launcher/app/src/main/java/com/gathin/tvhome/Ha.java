package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal Home Assistant REST client: calls one service on one entity. Settings come from local.properties. */
final class Ha {
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Callback {
        void done(boolean ok, String message);
    }

    private Ha() {}

    static boolean configured() {
        return !BuildConfig.HA_URL.isEmpty() && !BuildConfig.HA_TOKEN.isEmpty();
    }

    /** POST /api/states/{entityId}: creates or updates a state (not persisted across Home Assistant restarts). */
    static void setState(String entityId, String state, String attributesJson, Callback cb) {
        if (!configured()) {
            if (cb != null) cb.done(false, "Home Assistant is not configured");
            return;
        }
        POOL.execute(() -> {
            boolean ok = false;
            HttpURLConnection c = null;
            try {
                String base = BuildConfig.HA_URL.replaceAll("/+$", "");
                c = (HttpURLConnection) new URL(base + "/api/states/" + entityId).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(6000);
                c.setRequestMethod("POST");
                c.setRequestProperty("Authorization", "Bearer " + BuildConfig.HA_TOKEN);
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                byte[] body = ("{\"state\":\"" + state + "\",\"attributes\":" + attributesJson + "}")
                        .getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
                int code = c.getResponseCode();
                ok = code >= 200 && code < 300;
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
            final boolean fOk = ok;
            if (cb != null) MAIN.post(() -> cb.done(fOk, fOk ? "Done" : "Home Assistant did not accept the update"));
        });
    }

    /** POST /api/services/{domain}/{service} with {"entity_id": entityId}. The callback runs on the main thread. */
    static void call(String domain, String service, String entityId, Callback cb) {
        if (!configured()) {
            cb.done(false, "Add ha.url and ha.token to launcher/local.properties");
            return;
        }
        POOL.execute(() -> {
            boolean ok = false;
            String msg;
            HttpURLConnection c = null;
            try {
                String base = BuildConfig.HA_URL.replaceAll("/+$", "");
                c = (HttpURLConnection) new URL(base + "/api/services/" + domain + "/" + service).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(6000);
                c.setRequestMethod("POST");
                c.setRequestProperty("Authorization", "Bearer " + BuildConfig.HA_TOKEN);
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                byte[] body = ("{\"entity_id\":\"" + entityId + "\"}").getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
                int code = c.getResponseCode();
                ok = code >= 200 && code < 300;
                msg = ok ? "Done" : "Home Assistant said " + code;
            } catch (Exception e) {
                msg = "Can't reach Home Assistant";
            } finally {
                if (c != null) c.disconnect();
            }
            final boolean fOk = ok;
            final String fMsg = msg;
            MAIN.post(() -> cb.done(fOk, fMsg));
        });
    }
}
