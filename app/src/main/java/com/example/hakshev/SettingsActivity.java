package com.example.hakshev;

import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RadioGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.InputStream;
import java.util.List;

/** Unit structure, CSV import, display mode, PIN lock and data wipe. */
public class SettingsActivity extends BaseActivity {
    private EditText etCommander, etSquads, etWipeDays;
    private android.widget.TextView tvPinState;

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importFrom);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ViewGroup unit = findViewById(R.id.formUnit);
        etCommander = Ui.addField(this, unit, R.string.label_commander, Prefs.commander(this), InputType.TYPE_CLASS_TEXT);
        etSquads = Ui.addField(this, unit, R.string.label_squads, String.join(", ", Prefs.squads(this)), InputType.TYPE_CLASS_TEXT);
        findViewById(R.id.btnSaveUnit).setOnClickListener(v -> {
            Prefs.setCommander(this, etCommander.getText().toString().trim());
            Prefs.setSquads(this, etSquads.getText().toString().replace("،", ","));
            Ui.toast(this, R.string.saved);
        });

        findViewById(R.id.btnImport).setOnClickListener(v ->
                picker.launch(new String[]{"text/*", "application/csv", "application/vnd.ms-excel"}));

        RadioGroup rg = findViewById(R.id.rgTheme);
        int theme = Prefs.theme(this);
        rg.check(theme == Prefs.THEME_DAY ? R.id.rbDay : theme == Prefs.THEME_NIGHT ? R.id.rbNight : R.id.rbSystem);
        rg.setOnCheckedChangeListener((group, id) -> {
            int t = id == R.id.rbDay ? Prefs.THEME_DAY : id == R.id.rbNight ? Prefs.THEME_NIGHT : Prefs.THEME_SYSTEM;
            Prefs.setTheme(this, t);
        });

        tvPinState = findViewById(R.id.tvPinState);
        findViewById(R.id.btnSetPin).setOnClickListener(v -> setPin());
        findViewById(R.id.btnClearPin).setOnClickListener(v -> {
            Prefs.clearPin(this);
            updatePinState();
        });
        ViewGroup sec = findViewById(R.id.formSecurity);
        etWipeDays = Ui.addField(this, sec, R.string.label_wipe_days,
                String.valueOf(Prefs.wipeDays(this)), InputType.TYPE_CLASS_NUMBER);
        findViewById(R.id.btnSaveWipe).setOnClickListener(v -> {
            Prefs.setWipeDays(this, Ui.parseInt(etWipeDays.getText(), 0));
            Ui.toast(this, R.string.saved);
        });
        findViewById(R.id.btnWipeAll).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.confirm_wipe_all)
                .setPositiveButton(R.string.yes, (d, w) -> Db.IO.execute(() -> {
                    Db.wipeAll(this);
                    runOnUiThread(() -> Ui.toast(this, R.string.wiped));
                }))
                .setNegativeButton(R.string.cancel, null)
                .show());
        updatePinState();
    }

    private void updatePinState() {
        tvPinState.setText(Prefs.hasPin(this) ? R.string.pin_on : R.string.pin_off);
    }

    private void setPin() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        et.setTextSize(24);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.set_pin)
                .setMessage(R.string.pin_rules)
                .setView(et)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String pin = et.getText().toString().trim();
                    if (pin.length() < 4) {
                        Ui.toast(this, R.string.pin_too_short);
                        return;
                    }
                    Prefs.setPin(this, pin);
                    BaseActivity.markUnlocked();
                    updatePinState();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void importFrom(Uri uri) {
        if (uri == null) return;
        Db.IO.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                List<Soldier> parsed = CsvImporter.parse(in);
                Db.dao(this).insertSoldiers(parsed);
                runOnUiThread(() -> Ui.toast(this, getString(R.string.imported, parsed.size())));
            } catch (Exception e) {
                runOnUiThread(() -> Ui.toast(this, R.string.import_failed));
            }
        });
    }
}
