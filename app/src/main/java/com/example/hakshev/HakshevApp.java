package com.example.hakshev;

import android.app.Application;

public class HakshevApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.applyTheme(this);
        SyncManager.autoStart(this);
    }
}
