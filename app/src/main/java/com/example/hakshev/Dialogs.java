package com.example.hakshev;

import android.app.Activity;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/** Dialogs for creating counters, adding amounts and reading history. */
public final class Dialogs {
    private Dialogs() {}

    private static final int TEXT = InputType.TYPE_CLASS_TEXT;
    private static final int NUMBER = InputType.TYPE_CLASS_NUMBER;
    private static final int SIGNED = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;

    // ---------------------------------------------------------------- counters

    /** Offers suggestions first, then opens the form. {@code onCreated} gets the new id. */
    public static void newCounter(Activity a, LongConsumer onCreated) {
        List<String> names = new ArrayList<>();
        String[] presets = a.getResources().getStringArray(R.array.counter_presets);
        for (String p : presets) names.add(p.split("\\|", -1)[0]);
        names.add(a.getString(R.string.counter_custom));
        new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.counter_suggestions_title)
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    CounterDef base = new CounterDef();
                    if (which < presets.length) {
                        String[] f = presets[which].split("\\|", -1);
                        base.name = f[0];
                        base.period = f[1];
                        base.target = Ui.parseInt(f[2], 0);
                        base.unit = f[3];
                    }
                    counterForm(a, base, true, id -> onCreated.accept(id), null);
                })
                .show();
    }

    /**
     * Form for name, unit, target and period. For an existing counter a delete button is
     * shown and {@code onDeleted} runs afterwards.
     */
    public static void editCounter(Activity a, CounterDef def, Runnable onSaved, Runnable onDeleted) {
        counterForm(a, def, false, id -> onSaved.run(), onDeleted);
    }

    private static void counterForm(Activity a, CounterDef def, boolean isNew,
                                    LongConsumer onSaved, Runnable onDeleted) {
        View v = a.getLayoutInflater().inflate(R.layout.dialog_counter, null);
        ViewGroup form = v.findViewById(R.id.form);
        Spinner spPeriod = v.findViewById(R.id.spPeriod);
        EditText etName = Ui.addField(a, form, R.string.label_counter_name, def.name, TEXT);
        EditText etUnit = Ui.addField(a, form, R.string.label_counter_unit, def.unit, TEXT);
        EditText etTarget = Ui.addField(a, form, R.string.label_counter_target,
                def.target > 0 ? String.valueOf(def.target) : "", NUMBER);
        Ui.setSpinner(spPeriod, a, Periods.labels(a));
        spPeriod.setSelection(Status.indexOf(Periods.ALL, def.period));

        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(a)
                .setTitle(isNew ? R.string.new_counter : R.string.edit_counter)
                .setView(v)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) {
                        Ui.toast(a, R.string.name_required);
                        return;
                    }
                    def.name = name;
                    def.unit = etUnit.getText().toString().trim();
                    def.target = Math.max(0, Ui.parseInt(etTarget.getText(), 0));
                    def.period = Periods.ALL[Math.max(0, spPeriod.getSelectedItemPosition())];
                    if (def.createdAt == 0) def.createdAt = System.currentTimeMillis();
                    Db.IO.execute(() -> {
                        long id = def.id;
                        if (isNew) id = Db.dao(a).insertCounter(def);
                        else Db.dao(a).updateCounter(def);
                        final long finalId = id;
                        a.runOnUiThread(() -> onSaved.accept(finalId));
                    });
                })
                .setNegativeButton(R.string.cancel, null);
        if (!isNew && onDeleted != null) {
            b.setNeutralButton(R.string.delete, (d, w) -> new MaterialAlertDialogBuilder(a)
                    .setMessage(a.getString(R.string.confirm_delete_counter, def.name))
                    .setPositiveButton(R.string.yes, (d2, w2) -> Db.IO.execute(() -> {
                        Db.dao(a).deleteCounter(def);
                        a.runOnUiThread(onDeleted);
                    }))
                    .setNegativeButton(R.string.cancel, null)
                    .show());
        }
        b.show();
    }

    // ----------------------------------------------------------------- entries

    /** Adds an amount (can be negative) with an optional note. */
    public static void addAmount(Activity a, CounterDef def, Soldier s, Runnable onDone) {
        View v = a.getLayoutInflater().inflate(R.layout.dialog_form, null);
        ViewGroup form = v.findViewById(R.id.form);
        EditText etAmount = Ui.addField(a, form, R.string.label_amount, "1", SIGNED);
        EditText etNote = Ui.addField(a, form, R.string.label_reason, "",
                TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new MaterialAlertDialogBuilder(a)
                .setTitle(def.name + " — " + s.fullName)
                .setView(v)
                .setPositiveButton(R.string.save, (d, w) -> {
                    int amount = Ui.parseInt(etAmount.getText(), 0);
                    if (amount == 0) return;
                    CounterEntry e = entry(a, def.id, s.id, amount);
                    e.note = etNote.getText().toString().trim();
                    Db.IO.execute(() -> {
                        Db.dao(a).insertEntry(e);
                        a.runOnUiThread(onDone);
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public static CounterEntry entry(Activity a, long counterId, long soldierId, int delta) {
        CounterEntry e = new CounterEntry();
        e.counterId = counterId;
        e.soldierId = soldierId;
        e.delta = delta;
        e.recordedBy = Prefs.commander(a);
        e.createdAt = System.currentTimeMillis();
        return e;
    }

    /** History of one soldier in one counter. Tapping an entry offers to delete it. */
    public static void history(Activity a, CounterDef def, Soldier s, Runnable onChanged) {
        Db.IO.execute(() -> {
            List<CounterEntry> entries = Db.dao(a).entries(def.id, s.id);
            a.runOnUiThread(() -> {
                if (entries.isEmpty()) {
                    Ui.toast(a, R.string.no_history);
                    return;
                }
                String[] rows = new String[entries.size()];
                for (int i = 0; i < rows.length; i++) {
                    CounterEntry e = entries.get(i);
                    StringBuilder sb = new StringBuilder();
                    sb.append(e.delta > 0 ? "+" : "").append(e.delta);
                    if (!e.note.isEmpty()) sb.append("  ").append(e.note);
                    sb.append("\n").append(e.recordedBy).append(" · ").append(Ui.dateTime(e.createdAt));
                    rows[i] = sb.toString();
                }
                new MaterialAlertDialogBuilder(a)
                        .setTitle(def.name + " — " + s.fullName)
                        .setItems(rows, (d, which) -> new MaterialAlertDialogBuilder(a)
                                .setMessage(R.string.confirm_delete_entry)
                                .setPositiveButton(R.string.yes, (d2, w2) -> Db.IO.execute(() -> {
                                    Db.dao(a).deleteEntry(entries.get(which));
                                    a.runOnUiThread(onChanged);
                                }))
                                .setNegativeButton(R.string.cancel, null)
                                .show())
                        .setPositiveButton(R.string.close, null)
                        .setNeutralButton(R.string.remove_from_counter, (d, w) -> Db.IO.execute(() -> {
                            Db.dao(a).removeMember(def.id, s.id);
                            a.runOnUiThread(onChanged);
                        }))
                        .show();
            });
        });
    }
}
