package com.example.hakshev;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RadioGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

/** Unit structure, CSV import, display mode, PIN lock and data wipe. */
public class SettingsActivity extends BaseActivity {
    private EditText etCommander, etSquads, etWipeDays;
    private android.widget.TextView tvPinState;

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importFrom);

    private final ActivityResultLauncher<String[]> jsonPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::createUnitFrom);

    private android.widget.TextView tvSyncState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Nav.attach(this, R.id.nav_settings);

        ViewGroup unit = findViewById(R.id.formUnit);
        etCommander = Ui.addField(this, unit, R.string.label_commander, Prefs.commander(this), InputType.TYPE_CLASS_TEXT);
        etSquads = Ui.addField(this, unit, R.string.label_squads, String.join(", ", Prefs.squads(this)), InputType.TYPE_CLASS_TEXT);
        findViewById(R.id.btnSaveUnit).setOnClickListener(v -> {
            Prefs.setCommander(this, etCommander.getText().toString().trim());
            Prefs.setSquads(this, etSquads.getText().toString().replace("،", ","));
            Ui.toast(this, R.string.saved);
        });

        findViewById(R.id.btnEditSoldiers).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, RosterActivity.class)
                        .putExtra(RosterActivity.EXTRA_EDIT, true)));
        findViewById(R.id.btnImport).setOnClickListener(v ->
                picker.launch(new String[]{"text/*", "application/csv", "application/vnd.ms-excel"}));

        RadioGroup rg = findViewById(R.id.rgTheme);
        int theme = Prefs.theme(this);
        rg.check(theme == Prefs.THEME_DAY ? R.id.rbDay : theme == Prefs.THEME_NIGHT ? R.id.rbNight
                : theme == Prefs.THEME_RED ? R.id.rbRed : R.id.rbSystem);
        rg.setOnCheckedChangeListener((group, id) -> {
            int t = id == R.id.rbDay ? Prefs.THEME_DAY : id == R.id.rbNight ? Prefs.THEME_NIGHT
                    : id == R.id.rbRed ? Prefs.THEME_RED : Prefs.THEME_SYSTEM;
            Prefs.setTheme(this, t);
            recreate();
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

        tvSyncState = findViewById(R.id.tvSyncState);
        findViewById(R.id.btnSyncCreate).setOnClickListener(v -> {
            if (SyncConfig.hasBundled(this)) {
                createRandomUnit();
                return;
            }
            Ui.toast(this, R.string.sync_pick_json_hint);
            jsonPicker.launch(new String[]{"application/json", "application/octet-stream", "text/plain"});
        });
        findViewById(R.id.btnSyncJoin).setOnClickListener(v -> joinDialog());
        findViewById(R.id.btnSyncNow).setOnClickListener(v -> syncNow());
        findViewById(R.id.btnSyncCode).setOnClickListener(v -> showCode());
        findViewById(R.id.btnSyncLeave).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.sync_confirm_leave)
                .setPositiveButton(R.string.yes, (d, w) -> {
                    SyncManager.leave(this);
                    Ui.toast(this, R.string.sync_left);
                    updateSyncState();
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
        updateSyncState();
    }

    private void updateSyncState() {
        boolean joined = SyncManager.isJoined(this);
        long last = SyncConfig.lastSyncAt(this);
        String state = joined
                ? getString(R.string.sync_on, last == 0 ? getString(R.string.sync_never) : Ui.dateTime(last))
                : getString(R.string.sync_off);
        String err = SyncConfig.lastError(this);
        if (joined && !err.isEmpty()) state += "\n" + err;
        tvSyncState.setText(state);
        int on = joined ? android.view.View.VISIBLE : android.view.View.GONE;
        int off = joined ? android.view.View.GONE : android.view.View.VISIBLE;
        findViewById(R.id.btnSyncCreate).setVisibility(off);
        findViewById(R.id.btnSyncJoin).setVisibility(android.view.View.VISIBLE);
        findViewById(R.id.btnSyncNow).setVisibility(on);
        findViewById(R.id.btnSyncCode).setVisibility(on);
        findViewById(R.id.btnSyncLeave).setVisibility(on);
    }

    private void createUnitFrom(Uri uri) {
        if (uri == null) return;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            SyncConfig cfg = SyncConfig.fromGoogleServices(bos.toString("UTF-8"));
            cfg.createUnit();
            cfg.save(this);
            updateSyncState();
            syncNow();
            showCode();
        } catch (Exception e) {
            Ui.toast(this, R.string.sync_bad_json);
        }
    }

    /** Join with a unit name and a password: the same two words on every commander's phone. */
    private void joinDialog() {
        if (!SyncConfig.hasBundled(this)) {
            codeDialog();
            return;
        }
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText etUnitName = Ui.addField(this, box, R.string.label_unit_name, "", InputType.TYPE_CLASS_TEXT);
        EditText etPass = Ui.addField(this, box, R.string.label_unit_password, "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        androidx.appcompat.app.AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sync_join)
                .setMessage(R.string.sync_join_name_hint)
                .setView(box)
                .setPositiveButton(R.string.sync_join_do, null)
                .setNeutralButton(R.string.sync_have_code, (d, w) -> codeDialog())
                .setNegativeButton(R.string.cancel, null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            String name = etUnitName.getText().toString().trim();
            String pass = etPass.getText().toString();
            if (name.isEmpty()) {
                etUnitName.setError(getString(R.string.name_required));
                return;
            }
            if (pass.length() < 6) {
                etPass.setError(getString(R.string.sync_short_password));
                return;
            }
            try {
                SyncConfig cfg = SyncConfig.fromNamePassword(this, name, pass);
                cfg.save(this);
                SyncConfig.setAutoDisabled(this, false);
                dlg.dismiss();
                updateSyncState();
                syncNow();
            } catch (Exception e) {
                Ui.toast(this, R.string.sync_bad_code);
            }
        }));
        dlg.show();
    }

    private void createRandomUnit() {
        try {
            SyncConfig cfg = SyncConfig.fromAssets(this);
            cfg.createUnit();
            cfg.save(this);
            SyncConfig.setAutoDisabled(this, false);
            updateSyncState();
            syncNow();
        } catch (Exception e) {
            Ui.toast(this, R.string.sync_bad_json);
        }
    }

    /** Join with a long code copied from another commander. */
    private void codeDialog() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(3);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sync_join)
                .setMessage(R.string.sync_join_hint)
                .setView(et)
                .setPositiveButton(R.string.save, (d, w) -> {
                    try {
                        SyncConfig cfg = SyncConfig.fromJoinCode(et.getText().toString());
                        cfg.save(this);
                        updateSyncState();
                        syncNow();
                    } catch (Exception e) {
                        Ui.toast(this, R.string.sync_bad_code);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showCode() {
        final String code;
        try {
            code = SyncConfig.load(this).joinCode();
        } catch (Exception e) {
            return;
        }
        EditText et = new EditText(this);
        et.setText(code);
        et.setTextIsSelectable(true);
        et.setKeyListener(null);
        et.setTextSize(12);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sync_code_title)
                .setMessage(R.string.sync_code_hint)
                .setView(et)
                .setPositiveButton(R.string.sync_copy, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("hakshev", code));
                    Ui.toast(this, R.string.sync_copied);
                })
                .setNeutralButton(R.string.sync_share, (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, code);
                    startActivity(Intent.createChooser(send, getString(R.string.sync_code_title)));
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void syncNow() {
        Ui.toast(this, R.string.sync_started);
        SyncManager.sync(this, (ok, message) -> {
            Ui.toast(this, message);
            updateSyncState();
        });
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
