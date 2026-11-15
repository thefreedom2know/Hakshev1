package com.example.hakshev;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** A completed inspection: exceptions only, everyone else counts as OK. */
@Entity(tableName = "inspection_runs")
public class InspectionRun {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String templateName = "";
    public long runAt;
    public int total;
    public int okCount;
    public String details = "";
}
