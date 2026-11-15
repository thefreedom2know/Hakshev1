package com.example.hakshev;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Dashboard: headcount, counters overview, exceptions and quick actions. */
public class MainActivity extends BaseActivity {
    private TextView tvDate, tvHeadcount, tvCounterCount, tvAlertCount, tvAlerts, tvCounters;
    private List<Soldier> soldiers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvDate = findViewById(R.id.tvDate);
        tvHeadcount = findViewById(R.id.tvHeadcount);
        tvCounterCount = findViewById(R.id.tvCounterCount);
        tvAlertCount = findViewById(R.id.tvAlertCount);
        tvAlerts = findViewById(R.id.tvAlerts);
        tvCounters = findViewById(R.id.tvCounters);

        findViewById(R.id.btnNewInspection).setOnClickListener(v ->
                startActivity(new Intent(this, InspectionActivity.class)
                        .putExtra(InspectionActivity.EXTRA_AUTOSTART, true)));
        findViewById(R.id.btnReport).setOnClickListener(v -> chooseReport());
        findViewById(R.id.btnCounters).setOnClickListener(v -> startActivity(new Intent(this, CountersActivity.class)));
        findViewById(R.id.btnRoster).setOnClickListener(v -> startActivity(new Intent(this, RosterActivity.class)));
        findViewById(R.id.btnDuties).setOnClickListener(v -> startActivity(new Intent(this, DutiesActivity.class)));
        findViewById(R.id.btnInspections).setOnClickListener(v -> startActivity(new Intent(this, InspectionActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        final Context c = getApplicationContext();
        Db.IO.execute(() -> {
            // Auto-wipe after a long period of inactivity (optional, off by default).
            int wipeDays = Prefs.wipeDays(c);
            long last = Prefs.lastActive(c);
            if (wipeDays > 0 && last > 0
                    && System.currentTimeMillis() - last > wipeDays * 86_400_000L) {
                Db.wipeAll(c);
            }
            AppDao dao = Db.dao(c);
            Db.ensureSeed(c);
            List<Soldier> list = dao.allSoldiers();
            long now = System.currentTimeMillis();
            List<CounterDef> counters = dao.counters();
            StringBuilder sb = new StringBuilder();
            for (CounterDef def : counters) {
                int members = dao.members(def.id).size();
                int total = Counters.groupTotal(c, def, now);
                CounterDef unitOnly = new CounterDef();
                unitOnly.unit = def.unit;
                sb.append("• ").append(def.name).append(" (").append(Periods.label(c, def.period))
                        .append("): ").append(Counters.valueText(unitOnly, total))
                        .append(" · ").append(c.getString(R.string.n_soldiers, members)).append("\n");
            }
            String counterText = sb.length() == 0 ? c.getString(R.string.counters_empty) : sb.toString().trim();
            runOnUiThread(() -> {
                soldiers = list;
                render(counters.size(), counterText);
            });
        });
    }

    private void render(int counterCount, String counterText) {
        tvDate.setText(DateFormat.getDateInstance(DateFormat.FULL).format(new Date()));
        tvHeadcount.setText(Alerts.headcount(soldiers) + "/" + soldiers.size());
        tvCounterCount.setText(String.valueOf(counterCount));
        tvCounters.setText(counterText);
        List<String> alerts = Alerts.compute(this, soldiers);
        tvAlertCount.setText(String.valueOf(alerts.size()));
        if (alerts.isEmpty()) {
            tvAlerts.setText(R.string.no_alerts);
        } else {
            StringBuilder sb = new StringBuilder();
            for (String a : alerts) sb.append("• ").append(a).append("\n");
            tvAlerts.setText(sb.toString().trim());
        }
    }

    private void chooseReport() {
        String[] names = {
                getString(R.string.report_full), getString(R.string.report_headcount_title),
                getString(R.string.report_counters_title)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.copy_report)
                .setItems(names, (d, which) -> Db.IO.execute(() -> {
                    Context c = getApplicationContext();
                    List<Soldier> list = Db.dao(c).allSoldiers();
                    String text;
                    if (which == 0) text = ReportBuilder.full(c, list);
                    else if (which == 1) text = ReportBuilder.headcount(c, list);
                    else text = Counters.reportAll(c, System.currentTimeMillis());
                    if (text.isEmpty()) text = c.getString(R.string.counters_empty);
                    final String result = text;
                    runOnUiThread(() -> showReport(result));
                }))
                .show();
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
