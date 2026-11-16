package com.example.hakshev;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** All counters the commander defined. */
public class CountersActivity extends BaseActivity {
    private CounterListAdapter adapter;
    private List<CounterDef> counters = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_counters);
        Nav.attach(this, R.id.nav_counters);

        adapter = new CounterListAdapter(position -> {
            if (position >= 0 && position < counters.size()) open(counters.get(position).id);
        });
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.btnNew).setOnClickListener(v -> Dialogs.newCounter(this, this::open));
    }

    private void open(long counterId) {
        startActivity(new Intent(this, CounterActivity.class)
                .putExtra(CounterActivity.EXTRA_ID, counterId));
    }

    @Override
    protected void onRemoteChange() {
        onResume();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Db.IO.execute(() -> {
            long now = System.currentTimeMillis();
            List<CounterDef> list = Db.dao(this).counters();
            List<CounterListAdapter.Item> rows = new ArrayList<>();
            for (CounterDef c : list) {
                CounterListAdapter.Item it = new CounterListAdapter.Item();
                it.name = c.name;
                it.period = Periods.label(this, c.period);
                it.members = getString(R.string.counter_members_n, Db.dao(this).members(c.id).size());
                if (c.kind == CounterDef.KIND_CHECK) {
                    it.target = getString(R.string.counter_kind_check);
                } else if (c.target > 0) {
                    it.target = getString(R.string.counter_target_n,
                            c.unit.isEmpty() ? String.valueOf(c.target) : c.target + " " + c.unit);
                }
                it.total = Counters.valueText(unitOnly(c), Counters.groupTotal(this, c, now));
                rows.add(it);
            }
            runOnUiThread(() -> {
                counters = list;
                adapter.setItems(rows);
                findViewById(R.id.tvEmpty).setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    /** Group totals are not compared to the per-soldier target, so show them without it. */
    private static CounterDef unitOnly(CounterDef c) {
        CounterDef copy = new CounterDef();
        copy.unit = c.unit;
        return copy;
    }
}
