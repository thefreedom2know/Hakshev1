package com.example.hakshev;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import net.zetetic.database.sqlcipher.SupportOpenHelperFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local-only Room database (offline-first). All access runs on {@link #IO}. */
@Database(
        entities = {
                Soldier.class, CounterDef.class, CounterMember.class, CounterEntry.class,
                Duty.class, ChecklistTemplate.class, InspectionRun.class, Tombstone.class},
        version = 6,
        exportSchema = false)
public abstract class Db extends RoomDatabase {
    public abstract AppDao dao();

    public static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static volatile Db instance;

    /** Adds the conversion rule to counters. Existing data is kept. */
    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE counters ADD COLUMN convertAt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE counters ADD COLUMN convertToId INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** Adds the "default for every soldier" flag. */
    private static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE counters ADD COLUMN forAll INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** Adds the counter kind (count / done-list) and the reason mode. */
    private static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE counters ADD COLUMN kind INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE counters ADD COLUMN reasonMode INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** Adds the fields used to sync between commanders. Existing data is kept and gets ids. */
    private static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            String now = "CAST(strftime('%s','now') AS INTEGER) * 1000";
            String[] withUid = {"soldiers", "counters", "counter_entries", "duties"};
            for (String t : withUid) {
                db.execSQL("ALTER TABLE " + t + " ADD COLUMN uid TEXT");
                db.execSQL("UPDATE " + t + " SET uid = lower(hex(randomblob(16)))");
                db.execSQL("ALTER TABLE " + t + " ADD COLUMN syncedAt INTEGER NOT NULL DEFAULT 0");
            }
            db.execSQL("ALTER TABLE counters ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE counter_entries ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE duties ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE counter_members ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE counter_members ADD COLUMN syncedAt INTEGER NOT NULL DEFAULT 0");
            // Mark everything as changed so the first sync uploads it.
            db.execSQL("UPDATE soldiers SET updatedAt = " + now + " WHERE updatedAt = 0");
            db.execSQL("UPDATE counters SET updatedAt = " + now);
            db.execSQL("UPDATE counter_entries SET updatedAt = createdAt");
            db.execSQL("UPDATE duties SET updatedAt = " + now);
            db.execSQL("UPDATE counter_members SET updatedAt = " + now);
            db.execSQL("CREATE TABLE IF NOT EXISTS tombstones (`key` TEXT NOT NULL, `type` TEXT NOT NULL, "
                    + "`uid` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))");
        }
    };

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
                            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                            .build();
                    instance.getInvalidationTracker().addObserver(new androidx.room.InvalidationTracker.Observer(
                            "soldiers", "counters", "counter_members", "counter_entries", "duties", "tombstones") {
                        @Override
                        public void onInvalidated(java.util.Set<String> tables) {
                            SyncManager.requestSync(app);
                        }
                    });
                }
            }
        }
        return instance;
    }

    public static AppDao dao(Context c) {
        return get(c).dao();
    }

    /** Optional auto-wipe after long inactivity, then default data. Call on {@link #IO}. */
    public static void housekeeping(Context c) {
        int wipeDays = Prefs.wipeDays(c);
        long last = Prefs.lastActive(c);
        if (wipeDays > 0 && last > 0 && System.currentTimeMillis() - last > wipeDays * 86_400_000L) {
            wipeAll(c);
        }
        ensureSeed(c);
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
        SyncManager.leave(c, false);
    }
}
