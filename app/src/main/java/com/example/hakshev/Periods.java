package com.example.hakshev;

import android.content.Context;

import java.util.Calendar;

/** Counting periods and their date ranges. Weeks start on Sunday. */
public final class Periods {
    private Periods() {}

    public static final String DAY = "DAY";
    public static final String WEEK = "WEEK";
    public static final String TWO_WEEKS = "TWO_WEEKS";
    public static final String MONTH = "MONTH";
    public static final String TOTAL = "TOTAL";
    public static final String[] ALL = {DAY, WEEK, TWO_WEEKS, MONTH, TOTAL};

    public static String label(Context c, String period) {
        if (WEEK.equals(period)) return c.getString(R.string.period_week);
        if (TWO_WEEKS.equals(period)) return c.getString(R.string.period_two_weeks);
        if (MONTH.equals(period)) return c.getString(R.string.period_month);
        if (TOTAL.equals(period)) return c.getString(R.string.period_total);
        return c.getString(R.string.period_day);
    }

    public static String[] labels(Context c) {
        String[] out = new String[ALL.length];
        for (int i = 0; i < ALL.length; i++) out[i] = label(c, ALL[i]);
        return out;
    }

    private static void midnight(Calendar cal) {
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
    }

    private static void toSunday(Calendar cal) {
        cal.add(Calendar.DAY_OF_YEAR, -(cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY));
    }

    /**
     * Returns {from, to) of the period containing {@code now}, shifted by {@code offset}
     * periods (0 = current, -1 = previous). {@code anchor} (creation time) fixes the
     * two-week blocks.
     */
    public static long[] range(String period, long anchor, long now, int offset) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        midnight(cal);
        long from;
        long to;
        if (WEEK.equals(period)) {
            toSunday(cal);
            cal.add(Calendar.DAY_OF_YEAR, 7 * offset);
            from = cal.getTimeInMillis();
            cal.add(Calendar.DAY_OF_YEAR, 7);
            to = cal.getTimeInMillis();
        } else if (TWO_WEEKS.equals(period)) {
            Calendar a = Calendar.getInstance();
            a.setTimeInMillis(anchor > 0 ? anchor : now);
            midnight(a);
            toSunday(a);
            long days = Math.round((cal.getTimeInMillis() - a.getTimeInMillis()) / 86_400_000.0);
            long blocks = Math.floorDiv(days, 14L);
            a.add(Calendar.DAY_OF_YEAR, (int) ((blocks + offset) * 14));
            from = a.getTimeInMillis();
            a.add(Calendar.DAY_OF_YEAR, 14);
            to = a.getTimeInMillis();
        } else if (MONTH.equals(period)) {
            cal.set(Calendar.DAY_OF_MONTH, 1);
            cal.add(Calendar.MONTH, offset);
            from = cal.getTimeInMillis();
            cal.add(Calendar.MONTH, 1);
            to = cal.getTimeInMillis();
        } else if (TOTAL.equals(period)) {
            return new long[]{0, Long.MAX_VALUE};
        } else {
            cal.add(Calendar.DAY_OF_YEAR, offset);
            from = cal.getTimeInMillis();
            cal.add(Calendar.DAY_OF_YEAR, 1);
            to = cal.getTimeInMillis();
        }
        return new long[]{from, to};
    }

    /** Human readable range of the current period, e.g. "12/11/2026 – 18/11/2026". */
    public static String rangeText(Context c, CounterDef def, long now) {
        if (TOTAL.equals(def.period)) return c.getString(R.string.period_all_time);
        long[] r = range(def.period, def.createdAt, now, 0);
        if (DAY.equals(def.period)) return Ui.date(r[0]);
        return Ui.date(r[0]) + " – " + Ui.date(r[1] - 1);
    }
}
