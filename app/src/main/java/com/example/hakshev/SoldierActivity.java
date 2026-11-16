package com.example.hakshev;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Soldier profile plus the counters this soldier takes part in. */
public class SoldierActivity extends BaseActivity {
    public static final String EXTRA_ID = "soldier_id";
    /** True = edit the soldier's data. False = the screen where counters are added. */
    public static final String EXTRA_EDIT = "edit";

    private static final int TEXT = InputType.TYPE_CLASS_TEXT;
    private static final int PHONE = InputType.TYPE_CLASS_PHONE;
    private static final int MULTI = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;

    private long soldierId;
    private Soldier soldier = new Soldier();
    private long expiry;

    private EditText etName, etPersonal, etPhone, etParentPhone, etShoe, etUniform;
    private EditText etAllergies, etExemptions, etMeds, etWeapon, etEquipment, etNotes;
    private Spinner spSquad, spStatus;
    private MaterialButton btnExpiry;
    private TextView tvBanner, tvTitle, tvNoCounters, tvAvatar;
    private CountAdapter countAdapter;
    private List<CounterDef> myCounters = new ArrayList<>();
    private List<String> squads = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_soldier);
        soldierId = getIntent().getLongExtra(EXTRA_ID, 0);
        boolean edit = getIntent().getBooleanExtra(EXTRA_EDIT, false) || soldierId == 0;
        findViewById(R.id.viewArea).setVisibility(edit ? View.GONE : View.VISIBLE);
        findViewById(R.id.editArea).setVisibility(edit ? View.VISIBLE : View.GONE);

        tvTitle = findViewById(R.id.tvTitle);
        tvAvatar = findViewById(R.id.tvAvatar);
        tvBanner = findViewById(R.id.tvAlertBanner);
        tvNoCounters = findViewById(R.id.tvNoCounters);
        spSquad = findViewById(R.id.spSquad);
        spStatus = findViewById(R.id.spStatus);
        btnExpiry = findViewById(R.id.btnExpiry);

        ViewGroup personal = findViewById(R.id.formPersonal);
        etName = Ui.addField(this, personal, R.string.label_name, "", TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        etPersonal = Ui.addField(this, personal, R.string.label_personal_number, "", InputType.TYPE_CLASS_NUMBER);
        etPersonal.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(7)});
        etPhone = Ui.addField(this, personal, R.string.label_phone, "", PHONE);
        etParentPhone = Ui.addField(this, personal, R.string.label_parent_phone, "", PHONE);
        etShoe = Ui.addField(this, personal, R.string.label_shoe, "", TEXT);
        etUniform = Ui.addField(this, personal, R.string.label_uniform, "", TEXT);

        ViewGroup medical = findViewById(R.id.formMedical);
        etAllergies = Ui.addField(this, medical, R.string.label_allergies, "", MULTI);
        etExemptions = Ui.addField(this, medical, R.string.label_exemptions, "", MULTI);
        etMeds = Ui.addField(this, medical, R.string.label_meds, "", TEXT);

        ViewGroup equipment = findViewById(R.id.formEquipment);
        etWeapon = Ui.addField(this, equipment, R.string.label_weapon, "", TEXT);
        etEquipment = Ui.addField(this, equipment, R.string.label_equipment, "", MULTI);
        etNotes = Ui.addField(this, equipment, R.string.label_notes, "", MULTI);

        squads = new ArrayList<>(Prefs.squads(this));
        Ui.setSpinner(spStatus, this, Status.labels(this));

        countAdapter = new CountAdapter(new CountAdapter.Listener() {
            @Override
            public void onDelta(Counters.Row row, int delta) {
                CounterDef def = counterById(row.counterId);
                if (def != null) Dialogs.quickDelta(SoldierActivity.this, def, soldier, delta,
                        SoldierActivity.this::loadCounters);
            }

            @Override
            public void onClick(Counters.Row row) {
                CounterDef def = counterById(row.counterId);
                if (def != null) Dialogs.addAmount(SoldierActivity.this, def, soldier,
                        () -> Db.IO.execute(SoldierActivity.this::loadCounters));
            }

            @Override
            public void onLongClick(Counters.Row row) {
                CounterDef def = counterById(row.counterId);
                if (def != null) Dialogs.history(SoldierActivity.this, def, soldier,
                        () -> Db.IO.execute(SoldierActivity.this::loadCounters));
            }
        });
        RecyclerView countersList = findViewById(R.id.countersList);
        countersList.setLayoutManager(new LinearLayoutManager(this));
        countersList.setAdapter(countAdapter);

        btnExpiry.setOnClickListener(v -> pickExpiry());
        btnExpiry.setOnLongClickListener(v -> {
            expiry = 0;
            updateExpiryButton();
            return true;
        });
        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        findViewById(R.id.btnDelete).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.btnAddCounter).setOnClickListener(v -> addCounter());

        if (soldierId == 0) {
            tvTitle.setText(R.string.new_soldier);
            // Open the keyboard straight on the name field.
            getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            etName.requestFocus();
            etName.post(() -> {
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                        getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(etName, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            });
            Ui.avatar(tvAvatar, "+", "new");
            findViewById(R.id.btnDelete).setVisibility(View.GONE);
            Ui.setSpinner(spSquad, this, squads);
            updateExpiryButton();
        } else {
            Db.IO.execute(() -> {
                Soldier s = Db.dao(this).soldier(soldierId);
                runOnUiThread(() -> {
                    if (s == null) {
                        finish();
                        return;
                    }
                    soldier = s;
                    populate();
                });
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (soldierId != 0) Db.IO.execute(this::loadCounters);
    }

    private CounterDef counterById(long id) {
        for (CounterDef c : myCounters) if (c.id == id) return c;
        return null;
    }

    private void populate() {
        tvTitle.setText(soldier.fullName);
        Ui.avatar(tvAvatar, soldier.fullName, soldier.squad.isEmpty() ? soldier.fullName : soldier.squad);
        etName.setText(soldier.fullName);
        etPersonal.setText(soldier.personalNumber);
        etPhone.setText(soldier.phone);
        etParentPhone.setText(soldier.parentPhone);
        etShoe.setText(soldier.shoeSize);
        etUniform.setText(soldier.uniformSize);
        etAllergies.setText(soldier.allergies);
        etExemptions.setText(soldier.exemptions);
        etMeds.setText(soldier.medTimes);
        etWeapon.setText(soldier.weaponNumber);
        etEquipment.setText(soldier.equipment);
        etNotes.setText(soldier.notes);
        expiry = soldier.exemptionExpiry;

        if (!soldier.squad.isEmpty() && !squads.contains(soldier.squad)) squads.add(soldier.squad);
        Ui.setSpinner(spSquad, this, squads);
        int idx = squads.indexOf(soldier.squad);
        if (idx >= 0) spSquad.setSelection(idx);
        spStatus.setSelection(Status.indexOf(Status.ALL, soldier.status));

        updateExpiryButton();
        updateBanner();
    }

    private void updateBanner() {
        String allergies = etAllergies.getText().toString().trim();
        if (allergies.isEmpty()) {
            tvBanner.setVisibility(View.GONE);
        } else {
            tvBanner.setText(getString(R.string.allergy_banner, allergies));
            tvBanner.setVisibility(View.VISIBLE);
        }
    }

    private void updateExpiryButton() {
        btnExpiry.setText(expiry > 0
                ? getString(R.string.exemption_until, Ui.date(expiry))
                : getString(R.string.set_exemption_expiry));
    }

    private void pickExpiry() {
        Calendar cal = Calendar.getInstance();
        if (expiry > 0) cal.setTimeInMillis(expiry);
        new DatePickerDialog(this, (view, y, m, d) -> {
            Calendar picked = Calendar.getInstance();
            picked.set(y, m, d, 23, 59, 0);
            expiry = picked.getTimeInMillis();
            updateExpiryButton();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ---------------------------------------------------------------- counters

    /** IO thread. */
    private void loadCounters() {
        Counters.ensureDefaults(this);
        List<CounterDef> mine = Db.dao(this).countersOf(soldierId);
        List<Counters.Row> rows = Counters.soldierRows(this, soldier, mine, System.currentTimeMillis());
        runOnUiThread(() -> {
            myCounters = mine;
            countAdapter.setItems(rows);
            tvNoCounters.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            tvNoCounters.setText(R.string.soldier_no_counters);
        });
    }

    /** Adds this soldier to existing counters, or creates a new counter for him. */
    private void addCounter() {
        Db.IO.execute(() -> {
            List<CounterDef> all = Db.dao(this).counters();
            Set<Long> mine = new HashSet<>();
            for (CounterDef c : myCounters) mine.add(c.id);
            List<CounterDef> available = new ArrayList<>();
            for (CounterDef c : all) if (!mine.contains(c.id)) available.add(c);
            runOnUiThread(() -> showCounterChoice(available));
        });
    }

    private void showCounterChoice(List<CounterDef> available) {
        String[] labels = new String[available.size() + 1];
        for (int i = 0; i < available.size(); i++) {
            CounterDef c = available.get(i);
            labels[i] = c.name + " (" + Periods.label(this, c.period) + ")";
        }
        labels[available.size()] = getString(R.string.new_counter);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_counter_to_soldier)
                .setItems(labels, (d, which) -> {
                    if (which < available.size()) {
                        join(available.get(which).id);
                    } else {
                        Dialogs.newCounter(this, this::join);
                    }
                })
                .show();
    }

    private void join(long counterId) {
        List<CounterMember> one = new ArrayList<>();
        one.add(new CounterMember(counterId, soldierId));
        Db.IO.execute(() -> {
            Track.addMembers(Db.dao(this), one);
            loadCounters();
        });
    }

    // -------------------------------------------------------------------- save

    private void save() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            etName.setError(getString(R.string.name_required));
            return;
        }
        soldier.fullName = name;
        String personal = etPersonal.getText().toString().trim();
        if (!personal.isEmpty() && !personal.matches("\\d{7}")) {
            etPersonal.setError(getString(R.string.personal_number_7));
            return;
        }
        soldier.personalNumber = personal;
        soldier.phone = etPhone.getText().toString().trim();
        soldier.parentPhone = etParentPhone.getText().toString().trim();
        soldier.shoeSize = etShoe.getText().toString().trim();
        soldier.uniformSize = etUniform.getText().toString().trim();
        soldier.allergies = etAllergies.getText().toString().trim();
        soldier.exemptions = etExemptions.getText().toString().trim();
        soldier.exemptionExpiry = expiry;
        soldier.medTimes = etMeds.getText().toString().trim();
        soldier.weaponNumber = etWeapon.getText().toString().trim();
        soldier.equipment = etEquipment.getText().toString().trim();
        soldier.notes = etNotes.getText().toString().trim();
        int sq = spSquad.getSelectedItemPosition();
        soldier.squad = sq >= 0 && sq < squads.size() ? squads.get(sq) : "";
        soldier.status = Status.ALL[Math.max(0, spStatus.getSelectedItemPosition())];
        soldier.updatedAt = System.currentTimeMillis();

        Db.IO.execute(() -> {
            if (soldierId == 0) Db.dao(this).insertSoldier(soldier);
            else Db.dao(this).updateSoldier(soldier);
            runOnUiThread(this::finish);
        });
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.confirm_delete_soldier, soldier.fullName))
                .setPositiveButton(R.string.yes, (d, w) -> Db.IO.execute(() -> {
                    Track.soldierDeleted(Db.dao(this), soldier);
                    Db.dao(this).deleteSoldier(soldier);
                    runOnUiThread(this::finish);
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
