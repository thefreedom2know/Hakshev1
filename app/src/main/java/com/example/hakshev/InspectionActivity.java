package com.example.hakshev;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Inspections: exception-based. Everyone starts as OK; a short tap records the failed
 * items for a soldier, a long press resets him to OK.
 */
public class InspectionActivity extends BaseActivity {
    public static final String EXTRA_AUTOSTART = "autostart";

    private Spinner spTemplate;
    private TextView tvHint;
    private View rowRun, rowIdle;
    private TextAdapter adapter;

    private List<ChecklistTemplate> templates = new ArrayList<>();
    private List<Soldier> roster = new ArrayList<>();
    private List<InspectionRun> history = new ArrayList<>();

    private boolean running = false;
    private ChecklistTemplate current;
    private List<Soldier> runSoldiers = new ArrayList<>();
    /** soldierId -> failed items (comma separated). Missing = OK. */
    private final Map<Long, String> failures = new HashMap<>();
    private boolean autoStartPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inspection);
        spTemplate = findViewById(R.id.spTemplate);
        tvHint = findViewById(R.id.tvHint);
        rowRun = findViewById(R.id.rowRun);
        rowIdle = findViewById(R.id.rowIdle);
        autoStartPending = getIntent().getBooleanExtra(EXTRA_AUTOSTART, false);

        adapter = new TextAdapter();
        adapter.setListener(new TextAdapter.Listener() {
            @Override
            public void onClick(int position) {
                if (running && position >= 0 && position < runSoldiers.size()) {
                    editFailures(runSoldiers.get(position));
                }
            }

            @Override
            public void onLongClick(int position) {
                if (running && position >= 0 && position < runSoldiers.size()) {
                    failures.remove(runSoldiers.get(position).id);
                    renderRun();
                }
            }
        });
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.btnStart).setOnClickListener(v -> startRun());
        findViewById(R.id.btnAllOk).setOnClickListener(v -> {
            failures.clear();
            renderRun();
        });
        findViewById(R.id.btnFinish).setOnClickListener(v -> finishRun());
        findViewById(R.id.btnNewTemplate).setOnClickListener(v -> editTemplate(null));
        findViewById(R.id.btnCloneTemplate).setOnClickListener(v -> {
            int i = spTemplate.getSelectedItemPosition();
            if (i >= 0 && i < templates.size()) editTemplate(templates.get(i));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Db.IO.execute(this::reload);
    }

    /** IO thread. */
    private void reload() {
        Db.ensureSeed(this);
        List<ChecklistTemplate> t = Db.dao(this).templates();
        List<Soldier> s = Db.dao(this).allSoldiers();
        List<InspectionRun> h = Db.dao(this).recentRuns();
        runOnUiThread(() -> {
            int keep = spTemplate.getSelectedItemPosition();
            templates = t;
            roster = s;
            history = h;
            List<String> names = new ArrayList<>();
            for (ChecklistTemplate c : t) names.add(c.name);
            Ui.setSpinner(spTemplate, this, names);
            if (keep >= 0 && keep < names.size()) spTemplate.setSelection(keep);
            if (autoStartPending) {
                autoStartPending = false;
                startRun();
            } else if (!running) {
                renderHistory();
            }
        });
    }

    private void renderHistory() {
        rowRun.setVisibility(View.GONE);
        rowIdle.setVisibility(View.VISIBLE);
        spTemplate.setEnabled(true);
        tvHint.setText(R.string.inspection_history);
        List<String> rows = new ArrayList<>();
        for (InspectionRun r : history) {
            StringBuilder sb = new StringBuilder();
            sb.append(r.templateName).append(" — ").append(Ui.dateTime(r.runAt)).append("\n");
            sb.append(getString(R.string.inspection_ok_count, r.okCount, r.total));
            if (!r.details.isEmpty()) sb.append("\n").append(r.details);
            rows.add(sb.toString());
        }
        if (rows.isEmpty()) rows.add(getString(R.string.no_inspections));
        adapter.setItems(rows);
    }

    private void startRun() {
        int i = spTemplate.getSelectedItemPosition();
        if (i < 0 || i >= templates.size()) return;
        current = templates.get(i);
        runSoldiers = new ArrayList<>();
        for (Soldier s : roster) if (!Status.LEAVE.equals(s.status)) runSoldiers.add(s);
        if (runSoldiers.isEmpty()) {
            Ui.toast(this, R.string.no_soldiers);
            return;
        }
        failures.clear();
        running = true;
        rowIdle.setVisibility(View.GONE);
        rowRun.setVisibility(View.VISIBLE);
        spTemplate.setEnabled(false);
        tvHint.setText(R.string.inspection_hint);
        renderRun();
    }

    private void renderRun() {
        List<String> rows = new ArrayList<>();
        for (Soldier s : runSoldiers) {
            String f = failures.get(s.id);
            rows.add(s.fullName + " — " + (f == null ? "✓ " + getString(R.string.ok) : "✗ " + f));
        }
        adapter.setItems(rows);
    }

    private void editFailures(Soldier s) {
        String[] items = current.itemList();
        boolean[] checked = new boolean[items.length];
        String existing = failures.get(s.id);
        if (existing != null) {
            for (int i = 0; i < items.length; i++) checked[i] = existing.contains(items[i]);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(s.fullName)
                .setMultiChoiceItems(items, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.save, (d, w) -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < items.length; i++) {
                        if (checked[i]) {
                            if (sb.length() > 0) sb.append(", ");
                            sb.append(items[i]);
                        }
                    }
                    if (sb.length() == 0) failures.remove(s.id);
                    else failures.put(s.id, sb.toString());
                    renderRun();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void finishRun() {
        InspectionRun run = new InspectionRun();
        run.templateName = current.name;
        run.runAt = System.currentTimeMillis();
        run.total = runSoldiers.size();
        run.okCount = run.total - failures.size();
        StringBuilder sb = new StringBuilder();
        for (Soldier s : runSoldiers) {
            String f = failures.get(s.id);
            if (f != null) sb.append("• ").append(s.fullName).append(": ").append(f).append("\n");
        }
        run.details = sb.toString().trim();
        running = false;
        failures.clear();
        Db.IO.execute(() -> {
            Db.dao(this).insertRun(run);
            reload();
        });
    }

    /** Create a new template, or clone {@code base} when it is not null. */
    private void editTemplate(ChecklistTemplate base) {
        View v = getLayoutInflater().inflate(R.layout.dialog_form, null);
        ViewGroup form = v.findViewById(R.id.form);
        EditText etName = Ui.addField(this, form, R.string.label_template_name,
                base == null ? "" : base.name + " " + getString(R.string.copy_suffix), InputType.TYPE_CLASS_TEXT);
        EditText etItems = Ui.addField(this, form, R.string.label_template_items,
                base == null ? "" : base.items,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etItems.setMinLines(5);

        new MaterialAlertDialogBuilder(this)
                .setTitle(base == null ? R.string.new_template : R.string.clone_template)
                .setView(v)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) return;
                    ChecklistTemplate t = new ChecklistTemplate();
                    t.name = name;
                    t.items = etItems.getText().toString().trim();
                    Db.IO.execute(() -> {
                        Db.dao(this).insertTemplate(t);
                        reload();
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
