package com.example.hakshev;

import android.content.Context;

/** Codes and Hebrew labels for soldier statuses. */
public final class Status {
    private Status() {}

    public static final String PRESENT = "PRESENT";
    public static final String SICK = "SICK";
    public static final String DUTY = "DUTY";
    public static final String LEAVE = "LEAVE";
    public static final String PROBLEM = "PROBLEM";
    public static final String[] ALL = {PRESENT, SICK, DUTY, LEAVE, PROBLEM};

    /** On site = counted in the headcount. */
    public static boolean onSite(String status) {
        return PRESENT.equals(status) || DUTY.equals(status);
    }

    public static String label(Context c, String status) {
        if (SICK.equals(status)) return c.getString(R.string.status_sick);
        if (DUTY.equals(status)) return c.getString(R.string.status_duty);
        if (LEAVE.equals(status)) return c.getString(R.string.status_leave);
        if (PROBLEM.equals(status)) return c.getString(R.string.status_problem);
        return c.getString(R.string.status_present);
    }

    public static String[] labels(Context c) {
        String[] out = new String[ALL.length];
        for (int i = 0; i < ALL.length; i++) out[i] = label(c, ALL[i]);
        return out;
    }

    public static int indexOf(String[] arr, String value) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(value)) return i;
        return 0;
    }
}
