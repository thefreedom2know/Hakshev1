package com.example.hakshev;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;

/** A soldier taking part in a counter. */
@Entity(
        tableName = "counter_members",
        primaryKeys = {"counterId", "soldierId"},
        foreignKeys = {
                @ForeignKey(entity = CounterDef.class, parentColumns = "id",
                        childColumns = "counterId", onDelete = ForeignKey.CASCADE),
                @ForeignKey(entity = Soldier.class, parentColumns = "id",
                        childColumns = "soldierId", onDelete = ForeignKey.CASCADE)},
        indices = {@Index("soldierId")})
public class CounterMember {
    public long counterId;
    public long soldierId;

    public CounterMember() {}

    @Ignore
    public CounterMember(long counterId, long soldierId) {
        this.counterId = counterId;
        this.soldierId = soldierId;
    }
}
