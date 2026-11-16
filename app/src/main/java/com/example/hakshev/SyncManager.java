package com.example.hakshev;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;
import com.google.firebase.firestore.WriteBatch;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps this phone and the other commanders' phones in step through Firestore.
 *
 * <p>Offline-first: everything is written to the local database first. A sync pushes the
 * records changed here ({@code updatedAt > syncedAt}) and deletions (tombstones), then pulls
 * everything the server received after the last pull. Every record is AES-GCM encrypted with
 * the unit secret before it leaves the phone. Conflicts: the newest {@code updatedAt} wins;
 * counter entries are append-only, so counts from two commanders simply add up.
 */
public final class SyncManager {
    private SyncManager() {}

    public interface Listener {
        /** Called on the main thread. */
        void onDone(boolean ok, String message);
    }

    private static final ExecutorService SYNC = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile boolean again;
    private static final long DEBOUNCE_MS = 3_000;
    private static final Runnable[] PENDING = new Runnable[1];
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String APP_NAME = "unit";
    private static final long AUTO_INTERVAL_MS = 60_000;

    private static final String SOLDIER = "soldier";
    private static final String COUNTER = "counter";
    private static final String MEMBER = "member";
    private static final String ENTRY = "entry";
    private static final String DUTY = "duty";
    private static final String[] ORDER = {SOLDIER, COUNTER, MEMBER, ENTRY, DUTY};

    public static boolean isJoined(Context c) {
        return SyncConfig.load(c).joined();
    }

    /**
     * First start: when the app contains a Firebase config (assets/google-services.json) and no
     * unit exists yet, a private unit is created and syncing starts. Other commanders join it
     * with the join code from the settings screen.
     */
    public static void autoStart(Context context) {
        Context c = context.getApplicationContext();
        if (SyncConfig.hasUnit(c) || SyncConfig.autoDisabled(c)) return;
        SYNC.execute(() -> {
            try {
                SyncConfig cfg = SyncConfig.fromAssets(c);
                cfg.createUnit();
                cfg.save(c);
            } catch (Exception e) {
                return; // no bundled config: the user can still set it up in the settings
            }
            sync(c, null);
        });
    }

    /** Called after every change to the local data: syncs a few seconds later. */
    public static void requestSync(Context context) {
        Context c = context.getApplicationContext();
        if (!SyncConfig.hasUnit(c)) return;
        synchronized (PENDING) {
            if (PENDING[0] != null) MAIN.removeCallbacks(PENDING[0]);
            PENDING[0] = () -> sync(c, null);
            MAIN.postDelayed(PENDING[0], DEBOUNCE_MS);
        }
    }

    /** Same as {@link #syncIfDue(Context)}, and tells the caller when the sync finished. */
    public static void syncIfDue(Context c, Listener l) {
        if (!isJoined(c)) return;
        if (System.currentTimeMillis() - SyncConfig.lastSyncAt(c) < AUTO_INTERVAL_MS) return;
        sync(c, l);
    }

    /** Syncs at most once a minute; used when screens open. */
    public static void syncIfDue(Context c) {
        if (!isJoined(c)) return;
        if (System.currentTimeMillis() - SyncConfig.lastSyncAt(c) < AUTO_INTERVAL_MS) return;
        sync(c, null);
    }

    public static void sync(Context context, Listener listener) {
        Context c = context.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) {
            again = true;
            if (listener != null) MAIN.post(() -> listener.onDone(true, c.getString(R.string.sync_running)));
            return;
        }
        SYNC.execute(() -> {
            boolean ok;
            String msg;
            try {
                int[] n = run(c);
                SyncConfig.setLastSyncAt(c, System.currentTimeMillis());
                ok = true;
                SyncConfig.setLastError(c, "");
                msg = c.getString(R.string.sync_done, n[0], n[1]);
            } catch (WrongCodeException e) {
                ok = false;
                msg = c.getString(R.string.sync_wrong_code);
            } catch (Exception e) {
                ok = false;
                String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                msg = c.getString(R.string.sync_error, detail);
            } finally {
                RUNNING.set(false);
            }
            if (!ok) SyncConfig.setLastError(c, msg);
            if (listener != null) {
                boolean fOk = ok;
                String fMsg = msg;
                MAIN.post(() -> listener.onDone(fOk, fMsg));
            }
            if (again && ok) {
                again = false;
                sync(c, null);
            }
            again = false;
        });
    }

    /** Forgets the unit on this phone. Data stays on the phone and in the cloud. */
    public static void leave(Context c) {
        leave(c, true);
    }

    /** {@code disableAuto}: do not create a new unit by itself at the next start. */
    public static void leave(Context c, boolean disableAuto) {
        SyncConfig.clear(c);
        SyncConfig.setAutoDisabled(c, disableAuto);
        try {
            FirebaseApp app = FirebaseApp.getInstance(APP_NAME);
            FirebaseAuth.getInstance(app).signOut();
            app.delete();
        } catch (Exception ignored) {
            // not initialised
        }
    }

    // ------------------------------------------------------------------ engine

    private static class WrongCodeException extends Exception {}

    private static <T> T db(Callable<T> job) throws Exception {
        return Db.IO.submit(job).get();
    }

    private static FirebaseApp firebase(Context c, SyncConfig cfg) {
        try {
            FirebaseApp existing = FirebaseApp.getInstance(APP_NAME);
            FirebaseOptions o = existing.getOptions();
            if (cfg.projectId.equals(o.getProjectId()) && cfg.appId.equals(o.getApplicationId())) {
                return existing;
            }
            existing.delete();
        } catch (IllegalStateException notYet) {
            // first use
        }
        FirebaseOptions opts = new FirebaseOptions.Builder()
                .setProjectId(cfg.projectId)
                .setApplicationId(cfg.appId)
                .setApiKey(cfg.apiKey)
                .build();
        return FirebaseApp.initializeApp(c, opts, APP_NAME);
    }

    /** Returns {pushed, pulled}. */
    private static int[] run(Context c) throws Exception {
        SyncConfig cfg = SyncConfig.load(c);
        if (!cfg.joined()) throw new IllegalStateException(c.getString(R.string.sync_not_joined));
        FirebaseApp app = firebase(c, cfg);
        FirebaseAuth auth = FirebaseAuth.getInstance(app);
        if (auth.getCurrentUser() == null) Tasks.await(auth.signInAnonymously());
        CollectionReference col = FirebaseFirestore.getInstance(app)
                .collection("units").document(cfg.unitId).collection("records");
        SyncCrypto crypto = new SyncCrypto(cfg.secret);

        int pushed = push(c, col, crypto);
        int pulled = pull(c, col, crypto);
        return new int[]{pushed, pulled};
    }

    // ------------------------------------------------------------------ push

    private static final class Op {
        String docId;
        Map<String, Object> data;
        Runnable afterPush;
    }

    private static Op op(String type, String uid, long updatedAt, boolean deleted, String payload) {
        Op o = new Op();
        o.docId = type + "_" + uid;
        o.data = new HashMap<>();
        o.data.put("t", type);
        o.data.put("u", updatedAt);
        o.data.put("d", deleted);
        o.data.put("p", payload);
        o.data.put("ts", FieldValue.serverTimestamp());
        return o;
    }

    private static int push(Context c, CollectionReference col, SyncCrypto crypto) throws Exception {
        List<Op> ops = db(() -> {
            AppDao dao = Db.dao(c);
            List<Op> out = new ArrayList<>();
            for (Soldier s : dao.soldiersToPush()) {
                Op o = op(SOLDIER, s.uid, s.updatedAt, false, crypto.encrypt(soldierJson(s).toString()));
                o.afterPush = () -> dao.markSoldierSynced(s.id, s.updatedAt);
                out.add(o);
            }
            for (CounterDef d : dao.countersToPush()) {
                CounterDef target = d.convertToId == 0 ? null : dao.counter(d.convertToId);
                Op o = op(COUNTER, d.uid, d.updatedAt, false,
                        crypto.encrypt(counterJson(d, target == null ? "" : target.uid).toString()));
                o.afterPush = () -> dao.markCounterSynced(d.id, d.updatedAt);
                out.add(o);
            }
            for (CounterMember m : dao.membersToPush()) {
                CounterDef d = dao.counter(m.counterId);
                Soldier s = dao.soldier(m.soldierId);
                if (d == null || s == null) continue;
                JSONObject j = new JSONObject();
                j.put("c", d.uid);
                j.put("s", s.uid);
                Op o = op(MEMBER, Track.memberUid(d.uid, s.uid), m.updatedAt, false,
                        crypto.encrypt(j.toString()));
                o.afterPush = () -> dao.markMemberSynced(m.counterId, m.soldierId, m.updatedAt);
                out.add(o);
            }
            for (CounterEntry e : dao.entriesToPush()) {
                CounterDef d = dao.counter(e.counterId);
                Soldier s = dao.soldier(e.soldierId);
                if (d == null || s == null) continue;
                JSONObject j = new JSONObject();
                j.put("c", d.uid);
                j.put("s", s.uid);
                j.put("delta", e.delta);
                j.put("note", e.note);
                j.put("by", e.recordedBy);
                j.put("at", e.createdAt);
                Op o = op(ENTRY, e.uid, e.updatedAt, false, crypto.encrypt(j.toString()));
                o.afterPush = () -> dao.markEntrySynced(e.id, e.updatedAt);
                out.add(o);
            }
            for (Duty d : dao.dutiesToPush()) {
                Soldier s = dao.soldier(d.soldierId);
                if (s == null) continue;
                JSONObject j = new JSONObject();
                j.put("s", s.uid);
                j.put("name", d.name);
                j.put("start", d.startTime);
                j.put("end", d.endTime);
                Op o = op(DUTY, d.uid, d.updatedAt, false, crypto.encrypt(j.toString()));
                o.afterPush = () -> dao.markDutySynced(d.id, d.updatedAt);
                out.add(o);
            }
            for (Tombstone t : dao.tombstones()) {
                Op o = op(t.type, t.uid, t.deletedAt, true, "");
                o.afterPush = () -> dao.deleteTombstone(t.key);
                out.add(o);
            }
            return out;
        });

        for (int i = 0; i < ops.size(); i += 400) {
            List<Op> chunk = ops.subList(i, Math.min(ops.size(), i + 400));
            WriteBatch batch = col.getFirestore().batch();
            for (Op o : chunk) batch.set(col.document(o.docId), o.data);
            Tasks.await(batch.commit());
            db(() -> {
                for (Op o : chunk) o.afterPush.run();
                return null;
            });
        }
        return ops.size();
    }

    // ------------------------------------------------------------------ pull

    private static final class Remote {
        String type;
        String uid;
        long updatedAt;
        boolean deleted;
        JSONObject json;
    }

    private static int pull(Context c, CollectionReference col, SyncCrypto crypto) throws Exception {
        Timestamp last = new Timestamp(SyncConfig.lastPullSeconds(c), SyncConfig.lastPullNanos(c));
        Query q = col.whereGreaterThan("ts", last).orderBy("ts");
        QuerySnapshot snap = Tasks.await(q.get(Source.SERVER));

        List<Remote> remotes = new ArrayList<>();
        Timestamp max = last;
        for (DocumentSnapshot d : snap.getDocuments()) {
            Timestamp ts = d.getTimestamp("ts");
            String type = d.getString("t");
            Long u = d.getLong("u");
            if (ts == null || type == null || u == null) continue;
            if (ts.compareTo(max) > 0) max = ts;
            Remote r = new Remote();
            r.type = type;
            String id = d.getId();
            r.uid = id.startsWith(type + "_") ? id.substring(type.length() + 1) : id;
            r.updatedAt = u;
            r.deleted = Boolean.TRUE.equals(d.getBoolean("d"));
            if (!r.deleted) {
                String p = d.getString("p");
                try {
                    r.json = new JSONObject(crypto.decrypt(p == null ? "" : p));
                } catch (Exception e) {
                    throw new WrongCodeException();
                }
            }
            remotes.add(r);
        }
        if (remotes.isEmpty()) return 0;

        int applied = db(() -> apply(c, remotes));
        SyncConfig.setLastPull(c, max.getSeconds(), max.getNanoseconds());
        return applied;
    }

    private static boolean blockedByTombstone(AppDao dao, String type, String uid, long remoteUpdatedAt) {
        String key = type + "_" + uid;
        for (Tombstone t : dao.tombstones()) {
            if (t.key.equals(key)) {
                if (t.deletedAt >= remoteUpdatedAt) return true;
                dao.deleteTombstone(key);
                return false;
            }
        }
        return false;
    }

    /** IO thread. Applies the received records and returns how many changed this phone. */
    private static int apply(Context c, List<Remote> remotes) throws Exception {
        AppDao dao = Db.dao(c);
        int n = 0;
        List<String[]> convertLinks = new ArrayList<>();

        for (String type : ORDER) {
            for (Remote r : remotes) {
                if (r.deleted || !r.type.equals(type)) continue;
                if (blockedByTombstone(dao, type, r.uid, r.updatedAt)) continue;
                switch (type) {
                    case SOLDIER: {
                        Soldier cur = dao.soldierByUid(r.uid);
                        if (cur != null && cur.updatedAt >= r.updatedAt) break;
                        Soldier s = cur != null ? cur : new Soldier();
                        readSoldier(r.json, s);
                        s.uid = r.uid;
                        s.updatedAt = r.updatedAt;
                        s.syncedAt = r.updatedAt;
                        if (cur == null) dao.insertSoldier(s);
                        else dao.updateSoldier(s);
                        n++;
                        break;
                    }
                    case COUNTER: {
                        CounterDef cur = dao.counterByUid(r.uid);
                        if (cur != null && cur.updatedAt >= r.updatedAt) break;
                        CounterDef d = cur != null ? cur : new CounterDef();
                        long keepConvertTo = d.convertToId;
                        readCounter(r.json, d);
                        d.convertToId = keepConvertTo;
                        d.uid = r.uid;
                        d.updatedAt = r.updatedAt;
                        d.syncedAt = r.updatedAt;
                        long id;
                        if (cur == null) {
                            d.convertToId = 0;
                            id = dao.insertCounter(d);
                        } else {
                            dao.updateCounter(d);
                            id = d.id;
                        }
                        convertLinks.add(new String[]{String.valueOf(id), r.json.optString("to", "")});
                        n++;
                        break;
                    }
                    case MEMBER: {
                        CounterDef d = dao.counterByUid(r.json.getString("c"));
                        Soldier s = dao.soldierByUid(r.json.getString("s"));
                        if (d == null || s == null) break;
                        CounterMember cur = dao.member(d.id, s.id);
                        if (cur == null) {
                            CounterMember m = new CounterMember(d.id, s.id);
                            m.updatedAt = r.updatedAt;
                            m.syncedAt = r.updatedAt;
                            List<CounterMember> one = new ArrayList<>();
                            one.add(m);
                            dao.addMembers(one);
                            n++;
                        } else if (cur.updatedAt < r.updatedAt) {
                            dao.markMemberSynced(d.id, s.id, r.updatedAt);
                        }
                        break;
                    }
                    case ENTRY: {
                        if (dao.entryByUid(r.uid) != null) break;
                        CounterDef d = dao.counterByUid(r.json.getString("c"));
                        Soldier s = dao.soldierByUid(r.json.getString("s"));
                        if (d == null || s == null) break;
                        CounterEntry e = new CounterEntry();
                        e.uid = r.uid;
                        e.counterId = d.id;
                        e.soldierId = s.id;
                        e.delta = r.json.optInt("delta", 0);
                        e.note = r.json.optString("note", "");
                        e.recordedBy = r.json.optString("by", "");
                        e.createdAt = r.json.optLong("at", r.updatedAt);
                        e.updatedAt = r.updatedAt;
                        e.syncedAt = r.updatedAt;
                        dao.insertEntry(e);
                        n++;
                        break;
                    }
                    case DUTY: {
                        if (dao.dutyByUid(r.uid) != null) break;
                        Soldier s = dao.soldierByUid(r.json.getString("s"));
                        if (s == null) break;
                        Duty d = new Duty();
                        d.uid = r.uid;
                        d.soldierId = s.id;
                        d.name = r.json.optString("name", "");
                        d.startTime = r.json.optLong("start", 0);
                        d.endTime = r.json.optLong("end", 0);
                        d.updatedAt = r.updatedAt;
                        d.syncedAt = r.updatedAt;
                        dao.insertDuty(d);
                        n++;
                        break;
                    }
                    default:
                        break;
                }
            }
        }

        // Conversion targets can point at a counter that arrived later in the same batch.
        for (String[] link : convertLinks) {
            long id = Long.parseLong(link[0]);
            long target = 0;
            if (!link[1].isEmpty()) {
                CounterDef t = dao.counterByUid(link[1]);
                if (t != null) target = t.id;
            }
            dao.setConvertTarget(id, target);
        }

        // Deletions last, so a record and its parent's deletion in one batch end up deleted.
        for (Remote r : remotes) {
            if (!r.deleted) continue;
            switch (r.type) {
                case SOLDIER: {
                    Soldier cur = dao.soldierByUid(r.uid);
                    if (cur == null) break;
                    if (cur.updatedAt <= r.updatedAt) {
                        dao.deleteSoldier(cur);
                        n++;
                    } else {
                        cur.syncedAt = 0;
                        dao.updateSoldier(cur);
                    }
                    break;
                }
                case COUNTER: {
                    CounterDef cur = dao.counterByUid(r.uid);
                    if (cur == null) break;
                    if (cur.updatedAt <= r.updatedAt) {
                        dao.deleteCounter(cur);
                        n++;
                    } else {
                        cur.syncedAt = 0;
                        dao.updateCounter(cur);
                    }
                    break;
                }
                case ENTRY: {
                    CounterEntry cur = dao.entryByUid(r.uid);
                    if (cur != null) {
                        dao.deleteEntry(cur);
                        n++;
                    }
                    break;
                }
                case DUTY: {
                    Duty cur = dao.dutyByUid(r.uid);
                    if (cur != null) {
                        dao.deleteDuty(cur);
                        n++;
                    }
                    break;
                }
                case MEMBER: {
                    String[] parts = r.uid.split("_", 2);
                    if (parts.length != 2) break;
                    CounterDef d = dao.counterByUid(parts[0]);
                    Soldier s = dao.soldierByUid(parts[1]);
                    if (d == null || s == null) break;
                    CounterMember cur = dao.member(d.id, s.id);
                    if (cur != null && cur.updatedAt <= r.updatedAt) {
                        dao.removeMember(d.id, s.id);
                        n++;
                    }
                    break;
                }
                default:
                    break;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ JSON

    private static JSONObject soldierJson(Soldier s) throws Exception {
        JSONObject j = new JSONObject();
        j.put("name", s.fullName);
        j.put("num", s.personalNumber);
        j.put("phone", s.phone);
        j.put("pphone", s.parentPhone);
        j.put("squad", s.squad);
        j.put("status", s.status);
        j.put("shoe", s.shoeSize);
        j.put("uni", s.uniformSize);
        j.put("all", s.allergies);
        j.put("ex", s.exemptions);
        j.put("exExp", s.exemptionExpiry);
        j.put("med", s.medTimes);
        j.put("wpn", s.weaponNumber);
        j.put("eq", s.equipment);
        j.put("notes", s.notes);
        return j;
    }

    private static void readSoldier(JSONObject j, Soldier s) {
        s.fullName = j.optString("name", "");
        s.personalNumber = j.optString("num", "");
        s.phone = j.optString("phone", "");
        s.parentPhone = j.optString("pphone", "");
        s.squad = j.optString("squad", "");
        s.status = j.optString("status", Status.PRESENT);
        s.shoeSize = j.optString("shoe", "");
        s.uniformSize = j.optString("uni", "");
        s.allergies = j.optString("all", "");
        s.exemptions = j.optString("ex", "");
        s.exemptionExpiry = j.optLong("exExp", 0);
        s.medTimes = j.optString("med", "");
        s.weaponNumber = j.optString("wpn", "");
        s.equipment = j.optString("eq", "");
        s.notes = j.optString("notes", "");
    }

    private static JSONObject counterJson(CounterDef d, String convertToUid) throws Exception {
        JSONObject j = new JSONObject();
        j.put("name", d.name);
        j.put("period", d.period);
        j.put("target", d.target);
        j.put("unit", d.unit);
        j.put("created", d.createdAt);
        j.put("at", d.convertAt);
        j.put("to", convertToUid);
        j.put("kind", d.kind);
        j.put("rm", d.reasonMode);
        j.put("fa", d.forAll);
        return j;
    }

    private static void readCounter(JSONObject j, CounterDef d) {
        d.name = j.optString("name", "");
        d.period = j.optString("period", Periods.DAY);
        d.target = j.optInt("target", 0);
        d.unit = j.optString("unit", "");
        d.createdAt = j.optLong("created", 0);
        d.convertAt = j.optInt("at", 0);
        d.kind = j.optInt("kind", 0);
        d.reasonMode = j.optInt("rm", 0);
        d.forAll = j.optBoolean("fa", false);
    }
}
