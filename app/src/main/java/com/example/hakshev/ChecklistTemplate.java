package com.example.hakshev;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Reusable inspection checklist. Items are stored one per line. */
@Entity(tableName = "checklist_templates")
public class ChecklistTemplate {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name = "";
    public String items = "";

    public String[] itemList() {
        String[] raw = items.split("\n");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : raw) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out.toArray(new String[0]);
    }
}
