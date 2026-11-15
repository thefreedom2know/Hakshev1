package com.example.hakshev;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports soldiers from a CSV (export from Excel as "CSV UTF-8").
 * Columns: full name, personal number, phone, squad. A header row is detected and skipped.
 */
public final class CsvImporter {
    private CsvImporter() {}

    public static List<Soldier> parse(InputStream in) throws IOException {
        List<Soldier> out = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        boolean first = true;
        while ((line = r.readLine()) != null) {
            if (first && line.startsWith("﻿")) line = line.substring(1);
            if (line.trim().isEmpty()) {
                first = false;
                continue;
            }
            String[] cols = line.split("[,;\t]", -1);
            boolean isHeader = first && looksLikeHeader(col(cols, 0));
            first = false;
            if (isHeader) continue;

            Soldier s = new Soldier();
            s.fullName = col(cols, 0);
            if (s.fullName.isEmpty()) continue;
            s.personalNumber = col(cols, 1);
            s.phone = col(cols, 2);
            s.squad = col(cols, 3);
            s.updatedAt = System.currentTimeMillis();
            out.add(s);
        }
        return out;
    }

    /** Exact matches only, so a soldier named e.g. "שמעון" is never mistaken for a header. */
    private static boolean looksLikeHeader(String first) {
        String f = first.trim().toLowerCase();
        return f.equals("שם") || f.equals("שם מלא") || f.equals("שם פרטי")
                || f.equals("name") || f.equals("full name");
    }

    private static String col(String[] cols, int i) {
        if (i >= cols.length) return "";
        String v = cols[i].trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }
}
