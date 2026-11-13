package com.ndxinvest.database

import androidx.room3.Entity
import androidx.room3.Index

@Entity(
    tableName = "drawdown_bands",
    primaryKeys = ["id"],
)
public data class DrawdownBandEntity public constructor(
    public val id: String,
    public val thresholdRateDecimal: String,
    public val plannedAmountDecimal: String,
    public val sortOrder: Int,
)

@Entity(
    tableName = "drawdown_monitor_state",
    primaryKeys = ["monitorId"],
)
public data class DrawdownMonitorStateEntity public constructor(
    public val monitorId: String,
    public val activeBandIdsJson: String,
    public val lastTriggeredBandId: String?,
    public val lastDrawdownRateDecimal: String?,
    public val observedAt: String?,
    public val revision: Long,
)

@Entity(
    tableName = "drawdown_alert_events",
    indices = [Index(value = ["monitorId", "deliveredAt"])],
    primaryKeys = ["id"],
)
public data class DrawdownAlertEventEntity public constructor(
    public val id: String,
    public val monitorId: String,
    public val revision: Long,
    public val bandId: String,
    public val thresholdRateDecimal: String,
    public val plannedAmountDecimal: String,
    public val currentPointDecimal: String,
    public val allTimeHighPointDecimal: String,
    public val allTimeHighDate: String,
    public val drawdownRateDecimal: String,
    public val observedAt: String,
    public val deliveredAt: String?,
)

@Entity(
    tableName = "actionable_exception_states",
    primaryKeys = ["identity"],
)
public data class ActionableExceptionStateEntity public constructor(
    public val identity: String,
    public val category: String,
    public val subjectId: String,
    public val active: Boolean,
    public val revision: Long,
    public val firstObservedAt: String,
    public val lastObservedAt: String,
    public val resolvedAt: String?,
    public val requiredConsecutive: Int = 1,
    public val consecutiveCount: Int = 0,
    public val lastObservationKey: String? = null,
    public val lastObservationPresent: Boolean? = null,
    public val resolutionPending: Boolean = false,
)

@Entity(
    tableName = "actionable_exception_events",
    indices = [
        Index(value = ["identity", "revision"], unique = true),
        Index(value = ["deliveredAt"]),
    ],
    primaryKeys = ["id"],
)
public data class ActionableExceptionEventEntity public constructor(
    public val id: String,
    public val identity: String,
    public val revision: Long,
    public val category: String,
    public val subjectId: String,
    public val observedAt: String,
    public val deliveredAt: String?,
    public val closedAt: String?,
)
