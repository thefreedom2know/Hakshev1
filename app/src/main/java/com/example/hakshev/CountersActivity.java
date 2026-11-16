package com.example.hakshev;

import android.content.Intent;
import android.os.Bundle;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** All counters the commander defined. */
public class CountersActivity extends BaseActivity {
    private TextAdapter adapter;
    private List<CounterDef> counters = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_counters);
        Nav.attach(this, R.id.nav_counters);

        adapter = new TextAdapter();
        adapter.setListener(new TextAdapter.Listener() {
            @Override
            public void onClick(int position) {
                if (position >= 0 && position < counters.size()) open(counters.get(position).id);
            }

            @Override
            public void onLongClick(int position) {
                onClick(position);
            }
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
            List<String> rows = new ArrayList<>();
            for (CounterDef c : list) {
                int members = Db.dao(this).members(c.id).size();
                int total = Counters.groupTotal(this, c, now);
                rows.add(c.name + " (" + Periods.label(this, c.period) + ")\n"
                        + getString(R.string.counter_summary, members,
                        Counters.valueText(unitOnly(c), total)));
            }
            if (rows.isEmpty()) rows.add(getString(R.string.counters_empty));
            runOnUiThread(() -> {
                counters = list;
                adapter.setItems(rows);
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
