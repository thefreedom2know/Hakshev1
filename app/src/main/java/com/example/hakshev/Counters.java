package com.example.hakshev;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds the rows and text reports for counters. Everything here runs on {@link Db#IO}. */
public final class Counters {
    private Counters() {}

    /** One line on screen: a soldier inside a counter. */
    public static class Row {
        public long counterId;
        public long soldierId;
        public String title = "";
        public String sub = "";
        public String valueText = "";
        public boolean reached;
    }

    public static String valueText(CounterDef def, int value) {
        String s = def.target > 0 ? value + "/" + def.target : String.valueOf(value);
        return def.unit.isEmpty() ? s : s + " " + def.unit;
    }

    private static String previousText(Context c, CounterDef def, int prevValue) {
        return c.getString(R.string.previous_period, valueText(def, prevValue));
    }

    /** Rows for every member of a counter (counter screen). */
    public static List<Row> memberRows(Context c, CounterDef def, List<Soldier> members, long now) {
        AppDao dao = Db.dao(c);
        long[] cur = Periods.range(def.period, def.createdAt, now, 0);
        Map<Long, Integer> curMap = toMap(dao.totals(def.id, cur[0], cur[1]));
        Map<Long, Integer> prevMap = null;
        if (!Periods.TOTAL.equals(def.period)) {
            long[] prev = Periods.range(def.period, def.createdAt, now, -1);
            prevMap = toMap(dao.totals(def.id, prev[0], prev[1]));
        }
        List<Row> rows = new ArrayList<>();
        for (Soldier s : members) {
            int v = get(curMap, s.id);
            Row r = new Row();
            r.counterId = def.id;
            r.soldierId = s.id;
            r.title = s.fullName;
            r.valueText = valueText(def, v);
            r.reached = def.target > 0 && v >= def.target;
            if (prevMap != null) r.sub = previousText(c, def, get(prevMap, s.id));
            rows.add(r);
        }
        return rows;
    }

    /** Rows for every counter a soldier takes part in (soldier screen). */
    public static List<Row> soldierRows(Context c, Soldier s, List<CounterDef> counters, long now) {
        AppDao dao = Db.dao(c);
        List<Row> rows = new ArrayList<>();
        for (CounterDef def : counters) {
            long[] cur = Periods.range(def.period, def.createdAt, now, 0);
            int v = dao.total(def.id, s.id, cur[0], cur[1]);
            Row r = new Row();
            r.counterId = def.id;
            r.soldierId = s.id;
            r.title = def.name + " (" + Periods.label(c, def.period) + ")";
            r.valueText = valueText(def, v);
            r.reached = def.target > 0 && v >= def.target;
            if (!Periods.TOTAL.equals(def.period)) {
                long[] prev = Periods.range(def.period, def.createdAt, now, -1);
                r.sub = previousText(c, def, dao.total(def.id, s.id, prev[0], prev[1]));
            }
            rows.add(r);
        }
        return rows;
    }

    /** Sum over the members of the counter in the current period. */
    public static int groupTotal(Context c, CounterDef def, long now) {
        AppDao dao = Db.dao(c);
        long[] cur = Periods.range(def.period, def.createdAt, now, 0);
        Map<Long, Integer> map = toMap(dao.totals(def.id, cur[0], cur[1]));
        int sum = 0;
        for (Soldier s : dao.members(def.id)) sum += get(map, s.id);
        return sum;
    }

    /** WhatsApp text for one counter. */
    public static String report(Context c, CounterDef def, long now) {
        List<Soldier> members = Db.dao(c).members(def.id);
        List<Row> rows = memberRows(c, def, members, now);
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(def.name).append("* (")
                .append(Periods.label(c, def.period)).append(")\n");
        sb.append(Periods.rangeText(c, def, now)).append("\n");
        if (rows.isEmpty()) sb.append(c.getString(R.string.no_members));
        for (Row r : rows) sb.append("- ").append(r.title).append(": ").append(r.valueText).append("\n");
        return sb.toString().trim();
    }

    public static String reportAll(Context c, long now) {
        StringBuilder sb = new StringBuilder();
        for (CounterDef def : Db.dao(c).counters()) {
            sb.append(report(c, def, now)).append("\n\n");
        }
        return sb.toString().trim();
    }

    private static Map<Long, Integer> toMap(List<SoldierTotal> list) {
        Map<Long, Integer> m = new HashMap<>();
        for (SoldierTotal t : list) m.put(t.soldierId, t.total);
        return m;
    }

    private static int get(Map<Long, Integer> m, long id) {
        Integer v = m.get(id);
        return v == null ? 0 : v;
    }
}
