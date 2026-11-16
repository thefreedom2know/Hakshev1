package com.example.hakshev;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Connection to the shared unit: the Firebase project (taken from google-services.json),
 * the unit id (a random Firestore path) and the secret that encrypts every record.
 * Everything together is the "join code" that other commanders paste into their app.
 */
public final class SyncConfig {
    public String projectId = "";
    public String appId = "";
    public String apiKey = "";
    public String unitId = "";
    public byte[] secret;

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("hakshev_sync", Context.MODE_PRIVATE);
    }

    public boolean joined() {
        return !unitId.isEmpty() && secret != null && !projectId.isEmpty();
    }

    public static SyncConfig load(Context c) {
        SyncConfig cfg = new SyncConfig();
        SharedPreferences p = sp(c);
        cfg.projectId = p.getString("projectId", "");
        cfg.appId = p.getString("appId", "");
        cfg.apiKey = p.getString("apiKey", "");
        cfg.unitId = p.getString("unitId", "");
        String sealed = p.getString("secret", null);
        if (sealed != null) {
            try {
                cfg.secret = DbKey.open(sealed);
            } catch (Exception e) {
                cfg.secret = null;
            }
        }
        return cfg;
    }

    public void save(Context c) {
        sp(c).edit().putString("projectId", projectId).putString("appId", appId)
                .putString("apiKey", apiKey).putString("unitId", unitId)
                .putString("secret", secret == null ? null : DbKey.seal(secret)).apply();
    }

    /** Forgets the unit and the pull position. */
    public static void clear(Context c) {
        sp(c).edit().clear().apply();
    }

    static long lastPullSeconds(Context c) {
        return sp(c).getLong("pullSec", 0);
    }

    static int lastPullNanos(Context c) {
        return sp(c).getInt("pullNano", 0);
    }

    static void setLastPull(Context c, long seconds, int nanos) {
        sp(c).edit().putLong("pullSec", seconds).putInt("pullNano", nanos).apply();
    }

    /** Cheap check (no Keystore access): is a unit stored on this phone? */
    static boolean hasUnit(Context c) {
        return !sp(c).getString("unitId", "").isEmpty();
    }

    private static SharedPreferences flags(Context c) {
        return c.getApplicationContext().getSharedPreferences("hakshev_sync_flags", Context.MODE_PRIVATE);
    }

    /** True once the user left the unit on purpose: do not create one automatically again. */
    static boolean autoDisabled(Context c) {
        return flags(c).getBoolean("autoDisabled", false);
    }

    static void setAutoDisabled(Context c, boolean v) {
        flags(c).edit().putBoolean("autoDisabled", v).apply();
    }

    static String lastError(Context c) {
        return sp(c).getString("lastError", "");
    }

    static void setLastError(Context c, String e) {
        sp(c).edit().putString("lastError", e).apply();
    }

    static long lastSyncAt(Context c) {
        return sp(c).getLong("lastSyncAt", 0);
    }

    static void setLastSyncAt(Context c, long t) {
        sp(c).edit().putLong("lastSyncAt", t).apply();
    }

    /** Fills the Firebase fields from the text of a google-services.json file. */
    public static SyncConfig fromGoogleServices(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONObject info = root.getJSONObject("project_info");
        JSONObject client = root.getJSONArray("client").getJSONObject(0);
        SyncConfig cfg = new SyncConfig();
        cfg.projectId = info.getString("project_id");
        cfg.appId = client.getJSONObject("client_info").getString("mobilesdk_app_id");
        cfg.apiKey = client.getJSONArray("api_key").getJSONObject(0).getString("current_key");
        return cfg;
    }

    /** A brand-new unit: random id and secret. */
    public void createUnit() {
        SecureRandom r = new SecureRandom();
        byte[] id = new byte[16];
        r.nextBytes(id);
        StringBuilder sb = new StringBuilder();
        for (byte b : id) sb.append(String.format("%02x", b));
        unitId = sb.toString();
        secret = new byte[32];
        r.nextBytes(secret);
    }

    public String joinCode() throws Exception {
        JSONObject o = new JSONObject();
        o.put("p", projectId);
        o.put("a", appId);
        o.put("k", apiKey);
        o.put("u", unitId);
        o.put("s", Base64.encodeToString(secret, Base64.NO_WRAP));
        return "HK1:" + Base64.encodeToString(o.toString().getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP | Base64.URL_SAFE);
    }

    public static SyncConfig fromJoinCode(String code) throws Exception {
        code = code.trim();
        if (!code.startsWith("HK1:")) throw new IllegalArgumentException("bad code");
        byte[] raw = Base64.decode(code.substring(4), Base64.NO_WRAP | Base64.URL_SAFE);
        JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        SyncConfig cfg = new SyncConfig();
        cfg.projectId = o.getString("p");
        cfg.appId = o.getString("a");
        cfg.apiKey = o.getString("k");
        cfg.unitId = o.getString("u");
        cfg.secret = Base64.decode(o.getString("s"), Base64.NO_WRAP);
        if (cfg.secret.length < 16 || cfg.unitId.length() < 16) {
            throw new IllegalArgumentException("bad code");
        }
        return cfg;
    }
}
