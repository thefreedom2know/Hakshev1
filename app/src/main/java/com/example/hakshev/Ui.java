package com.example.hakshev;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Small UI helpers shared by the screens. */
public final class Ui {
    private Ui() {}

    /** Adds an outlined text field to {@code parent} and returns its EditText. */
    public static EditText addField(Context c, ViewGroup parent, int hintRes, String value, int inputType) {
        View v = LayoutInflater.from(c).inflate(R.layout.item_field, parent, false);
        TextInputLayout til = (TextInputLayout) v;
        til.setHint(c.getString(hintRes));
        EditText et = v.findViewById(R.id.etField);
        et.setInputType(inputType);
        et.setText(value);
        parent.addView(v);
        return et;
    }

    /** The whole labelled box (TextInputLayout) around a field made by {@link #addField}. */
    public static View fieldBox(EditText et) {
        View v = et;
        while (v.getParent() instanceof View && !(v instanceof TextInputLayout)) v = (View) v.getParent();
        return v;
    }

    public static void setSpinner(Spinner sp, Context c, String[] items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(c, R.layout.item_spinner, items);
        a.setDropDownViewResource(R.layout.item_spinner);
        sp.setAdapter(a);
    }

    public static void setSpinner(Spinner sp, Context c, List<String> items) {
        setSpinner(sp, c, items.toArray(new String[0]));
    }

    public static void toast(Context c, int res) {
        Toast.makeText(c, res, Toast.LENGTH_SHORT).show();
    }

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }

    public static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    public static String dateTime(long millis) {
        return new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    public static String time(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    public static String date(long millis) {
        return new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(new Date(millis));
    }

    private static final int[] PALETTE = {
            0xFF4B5320, 0xFF3C4A56, 0xFF5E5238, 0xFF454B40,
            0xFF2F4F3A, 0xFF4A4458, 0xFF5B4A3A, 0xFF384A5E};

    /** Colored round avatar showing the first character (or emoji) of {@code text}. */
    public static void avatar(TextView tv, String text, String colorKey) {
        String t = text == null ? "" : text.trim();
        String first = t.isEmpty() ? "?" : new String(Character.toChars(t.codePointAt(0)));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(6f * tv.getResources().getDisplayMetrics().density);
        g.setColor(PALETTE[Math.abs(colorKey.hashCode()) % PALETTE.length]);
        tv.setBackground(g);
        tv.setTextColor(0xFFFFFFFF);
        tv.setText(first);
    }

    /** Rounded colored chip behind a status text. */
    public static void chip(TextView tv, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(4f * tv.getResources().getDisplayMetrics().density);
        g.setColor(color);
        tv.setBackground(g);
        tv.setTextColor(0xFFFFFFFF);
    }

    public static int statusColor(String status) {
        if (Status.SICK.equals(status)) return 0xFFA86B12;
        if (Status.DUTY.equals(status)) return 0xFF3C5A78;
        if (Status.LEAVE.equals(status)) return 0xFF6B7066;
        if (Status.PROBLEM.equals(status)) return 0xFFA82A22;
        return 0xFF4B6B2E;
    }

    public static int parseInt(CharSequence s, int fallback) {
        try {
            return Integer.parseInt(s.toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
