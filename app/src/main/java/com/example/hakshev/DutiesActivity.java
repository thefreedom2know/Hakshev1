package com.example.hakshev;

import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Guard shifts and chores, with a rest-time warning and a fairness count. */
public class DutiesActivity extends BaseActivity {
    /** Required contiguous rest between two shifts of the same soldier (sleep rule). */
    private static final long REST_MS = 6L * 3600 * 1000;

    private TextAdapter adapter;
    private List<Soldier> soldiers = new ArrayList<>();
    private List<Duty> duties = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_duties);

        adapter = new TextAdapter();
        adapter.setListener(new TextAdapter.Listener() {
            @Override
            public void onClick(int position) {
            }

            @Override
            public void onLongClick(int position) {
                confirmDeleteDuty(position);
            }
        });
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.btnAdd).setOnClickListener(v -> addDuty());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Db.IO.execute(this::reload);
    }

    /** IO thread. */
    private void reload() {
        AppDao dao = Db.dao(this);
        List<Soldier> sl = dao.allSoldiers();
        long now = System.currentTimeMillis();
        List<Duty> ds = dao.dutiesSince(now - 12L * 3600 * 1000);
        List<Duty> week = dao.dutiesStartedSince(now - 7L * 86_400_000L);
        runOnUiThread(() -> {
            soldiers = sl;
            duties = ds;
            render(week);
        });
    }

    private Soldier byId(long id) {
        for (Soldier s : soldiers) if (s.id == id) return s;
        return null;
    }

    private void render(List<Duty> week) {
        List<String> rows = new ArrayList<>();

        // Fairness: number of shifts per soldier in the last 7 days.
        Map<Long, Integer> counts = new HashMap<>();
        for (Duty d : week) counts.merge(d.soldierId, 1, Integer::sum);
        List<Soldier> sorted = new ArrayList<>(soldiers);
        sorted.sort((a, b) -> {
            int ca = counts.containsKey(a.id) ? counts.get(a.id) : 0;
            int cb = counts.containsKey(b.id) ? counts.get(b.id) : 0;
            return ca - cb;
        });
        StringBuilder fair = new StringBuilder(getString(R.string.fairness_title)).append("\n");
        for (Soldier s : sorted) {
            int c = counts.containsKey(s.id) ? counts.get(s.id) : 0;
            fair.append(s.fullName).append(": ").append(c).append("   ");
        }
        rows.add(fair.toString().trim());

        for (Duty d : duties) {
            Soldier s = byId(d.soldierId);
            rows.add(d.name + " — " + (s == null ? "?" : s.fullName) + "\n"
                    + Ui.dateTime(d.startTime) + " → " + Ui.dateTime(d.endTime));
        }
        if (duties.isEmpty()) rows.add(getString(R.string.no_duties));
        adapter.setItems(rows);
    }

    private void confirmDeleteDuty(int position) {
        int idx = position - 1; // first row is the fairness summary
        if (idx < 0 || idx >= duties.size()) return;
        Duty d = duties.get(idx);
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.confirm_delete_duty)
                .setPositiveButton(R.string.yes, (dlg, w) -> Db.IO.execute(() -> {
                    Db.dao(this).deleteDuty(d);
                    reload();
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void addDuty() {
        if (soldiers.isEmpty()) {
            Ui.toast(this, R.string.no_soldiers);
            return;
        }
        View v = getLayoutInflater().inflate(R.layout.dialog_duty, null);
        Spinner spSoldier = v.findViewById(R.id.spSoldier);
        Spinner spDuty = v.findViewById(R.id.spDuty);
        MaterialButton btnTime = v.findViewById(R.id.btnTime);
        CheckBox cbTomorrow = v.findViewById(R.id.cbTomorrow);
        ViewGroup form = v.findViewById(R.id.formDuty);
        EditText etHours = Ui.addField(this, form, R.string.label_duration_hours, "4", InputType.TYPE_CLASS_NUMBER);

        String[] names = new String[soldiers.size()];
        for (int i = 0; i < names.length; i++) names[i] = soldiers.get(i).fullName;
        Ui.setSpinner(spSoldier, this, names);
        String[] dutyNames = getResources().getStringArray(R.array.duty_names);
        Ui.setSpinner(spDuty, this, dutyNames);

        final int[] hm = {Calendar.getInstance().get(Calendar.HOUR_OF_DAY), 0};
        hm[0] = (hm[0] + 1) % 24;
        btnTime.setText(getString(R.string.start_time, String.format("%02d:%02d", hm[0], hm[1])));
        btnTime.setOnClickListener(b -> new TimePickerDialog(this, (tp, h, m) -> {
            hm[0] = h;
            hm[1] = m;
            btnTime.setText(getString(R.string.start_time, String.format("%02d:%02d", h, m)));
        }, hm[0], hm[1], true).show());

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_duty)
                .setView(v)
                .setPositiveButton(R.string.save, (d, w) -> {
                    Soldier s = soldiers.get(spSoldier.getSelectedItemPosition());
                    Calendar start = Calendar.getInstance();
                    start.set(Calendar.HOUR_OF_DAY, hm[0]);
                    start.set(Calendar.MINUTE, hm[1]);
                    start.set(Calendar.SECOND, 0);
                    start.set(Calendar.MILLISECOND, 0);
                    if (cbTomorrow.isChecked()) start.add(Calendar.DAY_OF_YEAR, 1);
                    int hours = Math.max(1, Ui.parseInt(etHours.getText(), 4));

                    Duty duty = new Duty();
                    duty.soldierId = s.id;
                    duty.name = dutyNames[spDuty.getSelectedItemPosition()];
                    duty.startTime = start.getTimeInMillis();
                    duty.endTime = duty.startTime + hours * 3600_000L;
                    checkAndInsert(duty, s);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Warns when the soldier would get back-to-back shifts or less than 6h rest. */
    private void checkAndInsert(Duty duty, Soldier s) {
        Db.IO.execute(() -> {
            boolean conflict = false;
            for (Duty other : Db.dao(this).dutiesFor(s.id)) {
                if (duty.startTime < other.endTime + REST_MS && other.startTime < duty.endTime + REST_MS) {
                    conflict = true;
                    break;
                }
            }
            if (!conflict) {
                Db.dao(this).insertDuty(duty);
                reload();
                return;
            }
            runOnUiThread(() -> new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.rest_warning_title)
                    .setMessage(getString(R.string.rest_warning, s.fullName))
                    .setPositiveButton(R.string.assign_anyway, (d, w) -> Db.IO.execute(() -> {
                        Db.dao(this).insertDuty(duty);
                        reload();
                    }))
                    .setNegativeButton(R.string.cancel, null)
                    .show());
        });
    }
}
