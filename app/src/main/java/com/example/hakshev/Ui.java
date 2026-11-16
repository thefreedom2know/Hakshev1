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
            0xFF4F8A5B, 0xFF3F7CAC, 0xFFB07A2A, 0xFF8A5FA8,
            0xFFC25B4E, 0xFF2F8F8A, 0xFF7A8A3A, 0xFF6A7BB5};

    /** Soft tinted tile showing the first character of {@code text}. */
    public static void avatar(TextView tv, String text, String colorKey) {
        String t = text == null ? "" : text.trim();
        String first = t.isEmpty() ? "?" : new String(Character.toChars(t.codePointAt(0)));
        int color = PALETTE[Math.abs(colorKey.hashCode()) % PALETTE.length];
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(14f * tv.getResources().getDisplayMetrics().density);
        g.setColor((color & 0x00FFFFFF) | 0x33000000);
        tv.setBackground(g);
        tv.setTextColor(color);
        tv.setText(first);
    }

    /** Soft tinted pill behind a status text. */
    public static void chip(TextView tv, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(10f * tv.getResources().getDisplayMetrics().density);
        g.setColor((color & 0x00FFFFFF) | 0x2E000000);
        tv.setBackground(g);
        tv.setTextColor(color);
    }

    public static int statusColor(String status) {
        if (Status.SICK.equals(status)) return 0xFFC77D0A;
        if (Status.DUTY.equals(status)) return 0xFF3F7CAC;
        if (Status.LEAVE.equals(status)) return 0xFF7A8378;
        if (Status.PROBLEM.equals(status)) return 0xFFD9453A;
        return 0xFF3E9150;
    }

    public static int parseInt(CharSequence s, int fallback) {
        try {
            return Integer.parseInt(s.toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
