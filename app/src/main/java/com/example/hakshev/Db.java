package com.example.hakshev;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import net.zetetic.database.sqlcipher.SupportOpenHelperFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local-only Room database (offline-first). All access runs on {@link #IO}. */
@Database(
        entities = {
                Soldier.class, CounterDef.class, CounterMember.class, CounterEntry.class,
                Duty.class, ChecklistTemplate.class, InspectionRun.class},
        version = 2,
        exportSchema = false)
public abstract class Db extends RoomDatabase {
    public abstract AppDao dao();

    public static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static volatile Db instance;

    public static Db get(Context c) {
        if (instance == null) {
            synchronized (Db.class) {
                if (instance == null) {
                    Context app = c.getApplicationContext();
                    // Remove the pre-encryption database (never shipped to users).
                    app.deleteDatabase("hakshev.db");
                    System.loadLibrary("sqlcipher");
                    instance = Room.databaseBuilder(app, Db.class, "hakshev_enc.db")
                            .openHelperFactory(new SupportOpenHelperFactory(DbKey.passphrase(app)))
                            // Version 1 was a development build that never shipped.
                            // From version 2 on, real migrations must be written.
                            .fallbackToDestructiveMigrationFrom(true, 1)
                            .build();
                }
            }
        }
        return instance;
    }

    public static AppDao dao(Context c) {
        return get(c).dao();
    }

    /** Seeds the default checklist templates when none exist. Call on {@link #IO}. */
    public static void ensureSeed(Context c) {
        AppDao dao = dao(c);
        if (dao.templateCount() > 0) return;
        String[][] defaults = {
                {c.getString(R.string.tpl_morning_name), c.getString(R.string.tpl_morning_items)},
                {c.getString(R.string.tpl_weapon_name), c.getString(R.string.tpl_weapon_items)},
                {c.getString(R.string.tpl_gear_name), c.getString(R.string.tpl_gear_items)},
                {c.getString(R.string.tpl_medical_name), c.getString(R.string.tpl_medical_items)},
        };
        for (String[] d : defaults) {
            ChecklistTemplate t = new ChecklistTemplate();
            t.name = d[0];
            t.items = d[1];
            dao.insertTemplate(t);
        }
    }

    /** Deletes everything stored locally. Call on {@link #IO}. */
    public static void wipeAll(Context c) {
        get(c).clearAllTables();
    }
}
