package com.example.hakshev;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One counter: its soldiers, their values for the current period, and +/- buttons. */
public class CounterActivity extends BaseActivity {
    public static final String EXTRA_ID = "counter_id";

    private long counterId;
    private CounterDef def;
    private List<Soldier> members = new ArrayList<>();
    private CountAdapter adapter;
    private TextView tvTitle, tvRange;
    private List<Counters.Row> lastRows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_counter);
        counterId = getIntent().getLongExtra(EXTRA_ID, 0);
        tvTitle = findViewById(R.id.tvTitle);
        tvRange = findViewById(R.id.tvRange);

        adapter = new CountAdapter(new CountAdapter.Listener() {
            @Override
            public void onDelta(Counters.Row row, int delta) {
                Soldier s = soldierById(row.soldierId);
                if (s != null && def != null) Dialogs.quickDelta(CounterActivity.this, def, s, delta, CounterActivity.this::reload);
            }

            @Override
            public void onClick(Counters.Row row) {
                Soldier s = soldierById(row.soldierId);
                if (s != null) Dialogs.addAmount(CounterActivity.this, def, s, () -> Db.IO.execute(CounterActivity.this::reload));
            }

            @Override
            public void onLongClick(Counters.Row row) {
                Soldier s = soldierById(row.soldierId);
                if (s != null) Dialogs.history(CounterActivity.this, def, s, () -> Db.IO.execute(CounterActivity.this::reload));
            }
        });
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.btnPlusAll).setOnClickListener(v -> confirmPlusAll());
        findViewById(R.id.btnAddMembers).setOnClickListener(v -> addMembers());
        findViewById(R.id.btnReport).setOnClickListener(v -> copyReport());
        findViewById(R.id.btnEdit).setOnClickListener(v -> {
            if (def == null) return;
            Dialogs.editCounter(this, def, () -> Db.IO.execute(this::reload), this::finish);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Db.IO.execute(this::reload);
    }

    private Soldier soldierById(long id) {
        for (Soldier s : members) if (s.id == id) return s;
        return null;
    }

    /** IO thread. */
    private void reload() {
        Counters.ensureDefaults(this);
        CounterDef d = Db.dao(this).counter(counterId);
        if (d == null) {
            runOnUiThread(this::finish);
            return;
        }
        List<Soldier> m = Db.dao(this).members(counterId);
        List<Counters.Row> rows = Counters.memberRows(this, d, m, System.currentTimeMillis());
        CounterDef target = d.convertToId != 0 ? Db.dao(this).counter(d.convertToId) : null;
        runOnUiThread(() -> {
            def = d;
            members = m;
            tvTitle.setText(d.name + " (" + Periods.label(this, d.period) + ")");
            String range = getString(R.string.period_range, Periods.rangeText(this, d, System.currentTimeMillis()));
            if (target != null && d.convertAt > 0) {
                range += "\n" + getString(R.string.conversion_info, d.convertAt, target.name);
            }
            tvRange.setText(range);
            lastRows = rows;
            adapter.setItems(rows);
        });
    }

    private void confirmPlusAll() {
        if (members.isEmpty()) {
            Ui.toast(this, R.string.no_members);
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.confirm_plus_all, members.size()))
                .setPositiveButton(R.string.yes, (d, w) -> {
                    List<CounterEntry> entries = new ArrayList<>();
                    for (Soldier s : members) {
                        if (def != null && def.kind == CounterDef.KIND_CHECK && isDone(s.id)) continue;
                        entries.add(Dialogs.entry(this, counterId, s.id, 1));
                    }
                    Counters.addEntries(this, entries, this::reload);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    protected void onRemoteChange() {
        Db.IO.execute(this::reload);
    }

    private boolean isDone(long soldierId) {
        for (Counters.Row r : lastRows) if (r.soldierId == soldierId) return r.value > 0;
        return false;
    }

    private void addMembers() {
        Db.IO.execute(() -> {
            List<Soldier> all = Db.dao(this).allSoldiers();
            Set<Long> inside = new HashSet<>();
            for (Soldier s : members) inside.add(s.id);
            List<Soldier> candidates = new ArrayList<>();
            for (Soldier s : all) if (!inside.contains(s.id)) candidates.add(s);
            runOnUiThread(() -> showMemberOptions(candidates));
        });
    }

    private void showMemberOptions(List<Soldier> candidates) {
        if (candidates.isEmpty()) {
            Ui.toast(this, R.string.no_more_soldiers);
            return;
        }
        Map<String, List<Soldier>> bySquad = new LinkedHashMap<>();
        for (Soldier s : candidates) {
            if (s.squad.isEmpty()) continue;
            List<Soldier> l = bySquad.get(s.squad);
            if (l == null) {
                l = new ArrayList<>();
                bySquad.put(s.squad, l);
            }
            l.add(s);
        }
        List<String> labels = new ArrayList<>();
        List<List<Soldier>> groups = new ArrayList<>();
        labels.add(getString(R.string.members_all, candidates.size()));
        groups.add(candidates);
        for (Map.Entry<String, List<Soldier>> e : bySquad.entrySet()) {
            labels.add(getString(R.string.members_squad, e.getKey(), e.getValue().size()));
            groups.add(e.getValue());
        }
        labels.add(getString(R.string.members_manual));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_members)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which < groups.size()) insertMembers(groups.get(which));
                    else pickManually(candidates);
                })
                .show();
    }

    private void pickManually(List<Soldier> candidates) {
        String[] names = new String[candidates.size()];
        boolean[] checked = new boolean[names.length];
        for (int i = 0; i < names.length; i++) names[i] = candidates.get(i).fullName;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_members)
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.save, (d, w) -> {
                    List<Soldier> chosen = new ArrayList<>();
                    for (int i = 0; i < names.length; i++) if (checked[i]) chosen.add(candidates.get(i));
                    insertMembers(chosen);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void insertMembers(List<Soldier> chosen) {
        if (chosen.isEmpty()) return;
        List<CounterMember> list = new ArrayList<>();
        for (Soldier s : chosen) list.add(new CounterMember(counterId, s.id));
        Db.IO.execute(() -> {
            Track.addMembers(Db.dao(this), list);
            reload();
        });
    }

    private void copyReport() {
        Db.IO.execute(() -> {
            CounterDef d = Db.dao(this).counter(counterId);
            if (d == null) return;
            String text = Counters.report(this, d, System.currentTimeMillis());
            runOnUiThread(() -> showReport(text));
        });
    }

    private void showReport(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("report", text));
        Ui.toast(this, R.string.copied);
        new MaterialAlertDialogBuilder(this)
                .setMessage(text)
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.share, (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(send, getString(R.string.share)));
                })
                .show();
    }
}
