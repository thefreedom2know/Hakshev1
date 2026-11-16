package com.example.hakshev;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.appcompat.app.AppCompatDelegate;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** App settings kept in private SharedPreferences. */
public final class Prefs {
    private Prefs() {}

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_DAY = 1;
    public static final int THEME_NIGHT = 2;
    /** Black and dim red for night vision. */
    public static final int THEME_RED = 3;

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("hakshev_prefs", Context.MODE_PRIVATE);
    }

    // ---- commander name (recorded on discipline entries) ----
    public static String commander(Context c) {
        return sp(c).getString("commander", c.getString(R.string.default_commander));
    }

    public static void setCommander(Context c, String v) {
        sp(c).edit().putString("commander", v).apply();
    }

    // ---- squads ----
    public static List<String> squads(Context c) {
        String raw = sp(c).getString("squads", c.getString(R.string.default_squads));
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    public static void setSquads(Context c, String commaSeparated) {
        sp(c).edit().putString("squads", commaSeparated).apply();
    }

    // ---- theme ----
    public static int theme(Context c) {
        return sp(c).getInt("theme", THEME_SYSTEM);
    }

    public static void setTheme(Context c, int v) {
        sp(c).edit().putInt("theme", v).apply();
        applyTheme(c);
    }

    public static void applyTheme(Context c) {
        int mode;
        switch (theme(c)) {
            case THEME_DAY:
                mode = AppCompatDelegate.MODE_NIGHT_NO;
                break;
            case THEME_NIGHT:
            case THEME_RED:
                mode = AppCompatDelegate.MODE_NIGHT_YES;
                break;
            default:
                mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    // ---- auto wipe ----
    /** Days of inactivity after which all data is deleted. 0 = disabled. */
    public static int wipeDays(Context c) {
        return sp(c).getInt("wipe_days", 0);
    }

    public static void setWipeDays(Context c, int v) {
        sp(c).edit().putInt("wipe_days", Math.max(0, v)).apply();
    }

    public static long lastActive(Context c) {
        return sp(c).getLong("last_active", 0);
    }

    public static void touchActive(Context c) {
        sp(c).edit().putLong("last_active", System.currentTimeMillis()).apply();
    }

    // ---- PIN ----
    public static boolean hasPin(Context c) {
        return sp(c).getString("pin_hash", null) != null;
    }

    public static void setPin(Context c, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP);
        sp(c).edit().putString("pin_salt", saltB64).putString("pin_hash", hash(saltB64, pin)).apply();
    }

    public static void clearPin(Context c) {
        sp(c).edit().remove("pin_salt").remove("pin_hash").apply();
    }

    public static boolean checkPin(Context c, String pin) {
        String salt = sp(c).getString("pin_salt", "");
        String expected = sp(c).getString("pin_hash", null);
        return expected != null && expected.equals(hash(salt, pin));
    }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes("UTF-8"));
            md.update(pin.getBytes("UTF-8"));
            return Base64.encodeToString(md.digest(), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
