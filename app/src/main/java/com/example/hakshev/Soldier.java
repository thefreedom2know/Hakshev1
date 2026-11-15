package com.example.hakshev;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** A soldier / recruit in the commander's platoon. Stored locally only. */
@Entity(tableName = "soldiers")
public class Soldier {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String fullName = "";
    public String personalNumber = "";
    public String phone = "";
    public String parentPhone = "";
    public String squad = "";
    /** One of the Status.* codes. */
    public String status = Status.PRESENT;

    public String shoeSize = "";
    public String uniformSize = "";

    /** Short descriptions only (e.g. "פטור עמידה"), never full diagnoses. */
    public String allergies = "";
    public String exemptions = "";
    /** Millis of the exemption expiry date, 0 = none. */
    public long exemptionExpiry;
    public String medTimes = "";

    public String weaponNumber = "";
    public String equipment = "";
    public String notes = "";

    public long updatedAt;
}
