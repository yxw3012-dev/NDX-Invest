package com.ndxinvest.database

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "fund_calendar_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = FundEntity::class,
            parentColumns = ["id"],
            childColumns = ["fundId"],
        ),
    ],
    indices = [Index(value = ["sourceId"])],
    primaryKeys = ["fundId"],
)
public data class FundCalendarSnapshotEntity public constructor(
    public val fundId: String,
    public val sourceId: String,
    public val authority: String,
    public val coverageStart: String,
    public val coverageEnd: String,
    public val publishedAt: String,
    public val importedAt: String,
    public val revision: String,
)

@Entity(
    tableName = "fund_calendar_open_dates",
    foreignKeys = [
        ForeignKey(
            entity = FundCalendarSnapshotEntity::class,
            parentColumns = ["fundId"],
            childColumns = ["fundId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["openDate"])],
    primaryKeys = ["fundId", "openDate"],
)
public data class FundCalendarOpenDateEntity public constructor(
    public val fundId: String,
    public val openDate: String,
)
