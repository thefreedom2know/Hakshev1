package com.example.hakshev;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One change to a soldier's count. The current value is the sum of the deltas inside the
 * counter's period, so history is never lost when a period ends.
 */
@Entity(
        tableName = "counter_entries",
        foreignKeys = {
                @ForeignKey(entity = CounterDef.class, parentColumns = "id",
                        childColumns = "counterId", onDelete = ForeignKey.CASCADE),
                @ForeignKey(entity = Soldier.class, parentColumns = "id",
                        childColumns = "soldierId", onDelete = ForeignKey.CASCADE)},
        indices = {@Index("counterId"), @Index("soldierId")})
public class CounterEntry {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long counterId;
    public long soldierId;
    public int delta;
    public String note = "";
    public String recordedBy = "";
    public long createdAt;
}
