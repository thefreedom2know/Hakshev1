package com.example.hakshev;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
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

    public static int parseInt(CharSequence s, int fallback) {
        try {
            return Integer.parseInt(s.toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
