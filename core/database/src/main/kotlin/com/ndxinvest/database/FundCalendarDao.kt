package com.ndxinvest.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import java.time.Instant

@Dao
public abstract class FundCalendarDao {
    @Query("SELECT * FROM fund_calendar_snapshots WHERE fundId = :fundId")
    public abstract suspend fun findSnapshot(fundId: String): FundCalendarSnapshotEntity?

    @Query("SELECT * FROM fund_calendar_open_dates WHERE fundId = :fundId ORDER BY openDate")
    public abstract suspend fun listOpenDates(fundId: String): List<FundCalendarOpenDateEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSnapshot(entity: FundCalendarSnapshotEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun updateSnapshot(entity: FundCalendarSnapshotEntity): Int

    @Query("DELETE FROM fund_calendar_open_dates WHERE fundId = :fundId")
    protected abstract suspend fun deleteOpenDates(fundId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOpenDates(entities: List<FundCalendarOpenDateEntity>)

    @Transaction
    public open suspend fun importSnapshotAtomically(
        snapshot: FundCalendarSnapshotEntity,
        openDates: List<FundCalendarOpenDateEntity>,
    ): Boolean {
        require(openDates.isNotEmpty()) { "A fund calendar snapshot needs at least one open day" }
        require(openDates.all { it.fundId == snapshot.fundId }) { "Fund calendar snapshot and dates must match" }
        require(openDates.map(FundCalendarOpenDateEntity::openDate).distinct().size == openDates.size) {
            "Fund calendar open dates must be unique"
        }
        val existing = findSnapshot(snapshot.fundId)
        if (existing == null) {
            insertSnapshot(snapshot)
            insertOpenDates(openDates)
            return true
        }
        val existingDates = listOpenDates(snapshot.fundId)
        if (existing.sourceId == snapshot.sourceId && existing.revision == snapshot.revision) {
            require(existing.copy(importedAt = snapshot.importedAt) == snapshot && existingDates == openDates) {
                "One official fund calendar revision cannot change facts"
            }
            return false
        }
        require(!Instant.parse(snapshot.publishedAt).isBefore(Instant.parse(existing.publishedAt))) {
            "A fund calendar snapshot must not predate the active official snapshot"
        }
        deleteOpenDates(snapshot.fundId)
        check(updateSnapshot(snapshot) == 1) { "Fund calendar snapshot update failed" }
        insertOpenDates(openDates)
        return true
    }
}
