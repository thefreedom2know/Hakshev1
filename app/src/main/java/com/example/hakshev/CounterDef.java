package com.example.hakshev;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** A commander-defined thing to count (water, exit hours, passed tests, ...). */
@Entity(tableName = "counters")
public class CounterDef {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name = "";
    /** One of the Periods.* codes: how often the count starts again from zero. */
    public String period = Periods.DAY;
    /** Optional goal per period, 0 = none. */
    public int target;
    /** Optional unit label, e.g. "שעות". */
    public String unit = "";
    public long createdAt;
}
