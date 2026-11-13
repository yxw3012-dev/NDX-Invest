package com.ndxinvest.database

import androidx.room3.Entity
import androidx.room3.ForeignKey

/** Versioned immutable evidence. Monetary values inside the payload are decimal TEXT, never SQL numeric affinity. */
@Entity(tableName = "tracking_baselines", primaryKeys = ["fundId"], foreignKeys = [
    ForeignKey(entity = FundEntity::class, parentColumns = ["id"], childColumns = ["fundId"]),
])
public data class TrackingBaselineEntity public constructor(
    public val fundId: String,
    public val payloadJson: String,
)
