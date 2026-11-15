package com.example.hakshev;

import android.content.Context;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Computes the "exceptions" list shown on the dashboard and in reports. */
public final class Alerts {
    private Alerts() {}

    /** Exemptions that expire within a day (or already expired). */
    public static List<String> compute(Context c, List<Soldier> soldiers) {
        List<String> out = new ArrayList<>();
        long tomorrow = System.currentTimeMillis() + 24L * 3600 * 1000;
        DateFormat df = DateFormat.getDateInstance(DateFormat.SHORT);
        for (Soldier s : soldiers) {
            if (s.exemptionExpiry > 0 && s.exemptionExpiry <= tomorrow) {
                out.add(c.getString(R.string.alert_exemption, s.fullName, df.format(new Date(s.exemptionExpiry))));
            }
        }
        return out;
    }

    public static int headcount(List<Soldier> soldiers) {
        int n = 0;
        for (Soldier s : soldiers) if (Status.onSite(s.status)) n++;
        return n;
    }
}
