package com.example.hakshev;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Common behaviour: blocks screenshots / recents preview (FLAG_SECURE) and
 * re-locks with the PIN after the app was in the background for a while.
 */
public abstract class BaseActivity extends AppCompatActivity {
    private static final long RELOCK_AFTER_MS = 30_000;

    private static boolean unlocked = false;
    private static int startedCount = 0;
    private static long leftAt = 0;

    public static void markUnlocked() {
        unlocked = true;
        leftAt = 0;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (Prefs.theme(this) == Prefs.THEME_RED) setTheme(R.style.Theme_Hakshev_Red);
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (startedCount == 0 && leftAt > 0
                && System.currentTimeMillis() - leftAt > RELOCK_AFTER_MS) {
            unlocked = false;
        }
        startedCount++;
        if (Prefs.hasPin(this) && !unlocked) {
            startActivity(new Intent(this, LockActivity.class));
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        startedCount--;
        Prefs.touchActive(this);
        if (startedCount <= 0) {
            startedCount = 0;
            leftAt = System.currentTimeMillis();
        }
    }
}
