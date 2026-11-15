package com.example.hakshev;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface AppDao {
    // ---- soldiers ----
    @Insert
    long insertSoldier(Soldier s);

    @Insert
    void insertSoldiers(List<Soldier> list);

    @Update
    void updateSoldier(Soldier s);

    @Delete
    void deleteSoldier(Soldier s);

    @Query("SELECT * FROM soldiers ORDER BY squad, fullName")
    List<Soldier> allSoldiers();

    @Query("SELECT * FROM soldiers WHERE id = :id")
    Soldier soldier(long id);

    @Query("UPDATE soldiers SET status = :status, updatedAt = :now WHERE id IN (:ids)")
    void setStatusBulk(List<Long> ids, String status, long now);

    // ---- counters ----
    @Insert
    long insertCounter(CounterDef c);

    @Update
    void updateCounter(CounterDef c);

    @Delete
    void deleteCounter(CounterDef c);

    @Query("SELECT * FROM counters ORDER BY name")
    List<CounterDef> counters();

    @Query("SELECT * FROM counters WHERE id = :id")
    CounterDef counter(long id);

    @Query("SELECT s.* FROM soldiers s INNER JOIN counter_members m ON m.soldierId = s.id "
            + "WHERE m.counterId = :counterId ORDER BY s.squad, s.fullName")
    List<Soldier> members(long counterId);

    @Query("SELECT c.* FROM counters c INNER JOIN counter_members m ON m.counterId = c.id "
            + "WHERE m.soldierId = :soldierId ORDER BY c.name")
    List<CounterDef> countersOf(long soldierId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void addMembers(List<CounterMember> list);

    @Query("DELETE FROM counter_members WHERE counterId = :counterId AND soldierId = :soldierId")
    void removeMember(long counterId, long soldierId);

    // ---- counter entries ----
    @Insert
    void insertEntry(CounterEntry e);

    @Insert
    void insertEntries(List<CounterEntry> list);

    @Delete
    void deleteEntry(CounterEntry e);

    @Query("SELECT soldierId, SUM(delta) AS total FROM counter_entries "
            + "WHERE counterId = :counterId AND createdAt >= :from AND createdAt < :to GROUP BY soldierId")
    List<SoldierTotal> totals(long counterId, long from, long to);

    @Query("SELECT COALESCE(SUM(delta), 0) FROM counter_entries "
            + "WHERE counterId = :counterId AND soldierId = :soldierId AND createdAt >= :from AND createdAt < :to")
    int total(long counterId, long soldierId, long from, long to);

    @Query("SELECT * FROM counter_entries WHERE counterId = :counterId AND soldierId = :soldierId "
            + "ORDER BY createdAt DESC LIMIT 100")
    List<CounterEntry> entries(long counterId, long soldierId);

    // ---- duties ----
    @Insert
    void insertDuty(Duty d);

    @Delete
    void deleteDuty(Duty d);

    @Query("SELECT * FROM duties WHERE endTime >= :since ORDER BY startTime")
    List<Duty> dutiesSince(long since);

    @Query("SELECT * FROM duties WHERE soldierId = :soldierId")
    List<Duty> dutiesFor(long soldierId);

    @Query("SELECT * FROM duties WHERE startTime >= :since")
    List<Duty> dutiesStartedSince(long since);

    // ---- checklists ----
    @Insert
    long insertTemplate(ChecklistTemplate t);

    @Update
    void updateTemplate(ChecklistTemplate t);

    @Delete
    void deleteTemplate(ChecklistTemplate t);

    @Query("SELECT * FROM checklist_templates ORDER BY name")
    List<ChecklistTemplate> templates();

    @Query("SELECT COUNT(*) FROM checklist_templates")
    int templateCount();

    @Insert
    void insertRun(InspectionRun r);

    @Query("SELECT * FROM inspection_runs ORDER BY runAt DESC LIMIT 30")
    List<InspectionRun> recentRuns();
}
