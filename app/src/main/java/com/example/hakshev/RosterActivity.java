package com.example.hakshev;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Main screen: the soldiers. Tapping one opens the counters screen for that soldier.
 * With EXTRA_EDIT (opened from settings) it is the place to add and edit soldier data.
 */
public class RosterActivity extends BaseActivity {
    public static final String EXTRA_EDIT = "edit";

    private boolean edit;
    private TextView tvGreeting, tvPresent;
    private Spinner spSquad, spStatus;
    private TextView tvCount;
    private SoldierAdapter adapter;

    private List<Soldier> all = new ArrayList<>();
    private List<Soldier> filtered = new ArrayList<>();
    private List<String> squadFilter = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_roster);
        edit = getIntent().getBooleanExtra(EXTRA_EDIT, false);
        tvGreeting = findViewById(R.id.tvGreeting);
        tvPresent = findViewById(R.id.tvPresent);
        if (!edit) Nav.attach(this, R.id.nav_soldiers);
        findViewById(R.id.headerMain).setVisibility(edit ? View.GONE : View.VISIBLE);
        findViewById(R.id.titleEdit).setVisibility(edit ? View.VISIBLE : View.GONE);
        findViewById(R.id.headerMain).setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        spSquad = findViewById(R.id.spSquad);
        spStatus = findViewById(R.id.spStatus);
        tvCount = findViewById(R.id.tvCount);

        adapter = new SoldierAdapter(new SoldierAdapter.Listener() {
            @Override
            public void onOpen(Soldier s) {
                startActivity(new Intent(RosterActivity.this, SoldierActivity.class)
                        .putExtra(SoldierActivity.EXTRA_ID, s.id)
                        .putExtra(SoldierActivity.EXTRA_EDIT, edit));
            }

            @Override
            public void onStatus(Soldier s) {
                new MaterialAlertDialogBuilder(RosterActivity.this)
                        .setTitle(s.fullName)
                        .setItems(Status.labels(RosterActivity.this), (d, which) -> {
                            List<Long> ids = new ArrayList<>();
                            ids.add(s.id);
                            Db.IO.execute(() -> {
                                Db.dao(RosterActivity.this).setStatusBulk(ids, Status.ALL[which], System.currentTimeMillis());
                                reload();
                            });
                        })
                        .show();
            }
        });
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        // status filter: "all" + each status
        String[] statusItems = new String[Status.ALL.length + 1];
        statusItems[0] = getString(R.string.filter_all);
        System.arraycopy(Status.labels(this), 0, statusItems, 1, Status.ALL.length);
        Ui.setSpinner(spStatus, this, statusItems);

        AdapterView.OnItemSelectedListener onFilter = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                applyFilter();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        spSquad.setOnItemSelectedListener(onFilter);
        spStatus.setOnItemSelectedListener(onFilter);

        findViewById(R.id.btnBulkPresent).setOnClickListener(v -> confirmBulkPresent());
        findViewById(R.id.btnAdd).setOnClickListener(v ->
                startActivity(new Intent(this, SoldierActivity.class).putExtra(SoldierActivity.EXTRA_EDIT, true)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!edit) {
            updateSyncLine();
            SyncManager.syncIfDue(this, (ok, msg) -> updateSyncLine());
        }
        Db.IO.execute(() -> {
            if (!edit) Db.housekeeping(this);
            reload();
        });
    }

    private void updateSyncLine() {
        TextView t = findViewById(R.id.tvSyncLine);
        if (t == null || edit) return;
        long last = SyncConfig.lastSyncAt(this);
        if (!SyncConfig.hasUnit(this)) {
            t.setText(R.string.sync_line_off);
        } else if (!SyncConfig.lastError(this).isEmpty()) {
            t.setText(R.string.sync_line_fail);
        } else if (last == 0) {
            t.setText(R.string.sync_line_wait);
        } else {
            t.setText(getString(R.string.sync_line_ok,
                    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date(last))));
        }
    }

    /** Runs on the IO thread. */
    private void reload() {
        Counters.ensureDefaults(this);
        List<Soldier> list = Db.dao(this).allSoldiers();
        runOnUiThread(() -> {
            all = list;
            if (!edit) {
                tvGreeting.setText(getString(R.string.greeting, Prefs.commander(this)));
                tvPresent.setText(getString(R.string.present_of, Alerts.headcount(list), list.size()));
            }
            rebuildSquadFilter();
            applyFilter();
        });
    }

    private void rebuildSquadFilter() {
        int selected = spSquad.getSelectedItemPosition();
        String previous = selected > 0 && selected < squadFilter.size() ? squadFilter.get(selected) : null;

        List<String> squads = new ArrayList<>(Prefs.squads(this));
        for (Soldier s : all) {
            if (!s.squad.isEmpty() && !squads.contains(s.squad)) squads.add(s.squad);
        }
        squadFilter = new ArrayList<>();
        squadFilter.add(getString(R.string.filter_all));
        squadFilter.addAll(squads);
        Ui.setSpinner(spSquad, this, squadFilter);
        if (previous != null && squadFilter.contains(previous)) {
            spSquad.setSelection(squadFilter.indexOf(previous), false);
        }
    }

    private void applyFilter() {
        int sq = spSquad.getSelectedItemPosition();
        int st = spStatus.getSelectedItemPosition();
        filtered = new ArrayList<>();
        for (Soldier s : all) {
            if (sq > 0 && sq < squadFilter.size() && !squadFilter.get(sq).equals(s.squad)) continue;
            if (st > 0 && !Status.ALL[st - 1].equals(s.status)) continue;
            filtered.add(s);
        }
        adapter.setItems(filtered);
        tvCount.setText(all.isEmpty() && !edit
                ? getString(R.string.roster_empty_hint)
                : getString(R.string.roster_count, filtered.size(), all.size()));
    }

    private void confirmBulkPresent() {
        if (filtered.isEmpty()) {
            Ui.toast(this, R.string.no_soldiers);
            return;
        }
        final List<Long> ids = new ArrayList<>();
        for (Soldier s : filtered) ids.add(s.id);
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.confirm_bulk_present, ids.size()))
                .setPositiveButton(R.string.yes, (d, w) -> Db.IO.execute(() -> {
                    Db.dao(this).setStatusBulk(ids, Status.PRESENT, System.currentTimeMillis());
                    reload();
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
