package com.ndxinvest.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update

@Dao
public abstract class AutomationDao {
    @Query("SELECT * FROM drawdown_bands ORDER BY sortOrder, id")
    public abstract suspend fun listDrawdownBands(): List<DrawdownBandEntity>

    @Query("DELETE FROM drawdown_bands")
    protected abstract suspend fun deleteDrawdownBands(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertDrawdownBands(entities: List<DrawdownBandEntity>)

    @Transaction
    public open suspend fun replaceDrawdownBandsAtomically(entities: List<DrawdownBandEntity>) {
        require(entities.map(DrawdownBandEntity::id).distinct().size == entities.size) {
            "Drawdown band ids must be unique"
        }
        require(entities.map(DrawdownBandEntity::sortOrder).distinct().size == entities.size) {
            "Drawdown band sort orders must be unique"
        }
        deleteDrawdownBands()
        if (entities.isNotEmpty()) insertDrawdownBands(entities)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public abstract suspend fun initializeDrawdownState(entity: DrawdownMonitorStateEntity): Long

    @Query("SELECT * FROM drawdown_monitor_state WHERE monitorId = :monitorId")
    public abstract suspend fun findDrawdownState(monitorId: String): DrawdownMonitorStateEntity?

    @Query(
        "UPDATE drawdown_monitor_state SET " +
            "activeBandIdsJson = :activeBandIdsJson, " +
            "lastTriggeredBandId = :lastTriggeredBandId, " +
            "lastDrawdownRateDecimal = :lastDrawdownRateDecimal, " +
            "observedAt = :observedAt, revision = :newRevision " +
            "WHERE monitorId = :monitorId AND revision = :expectedRevision",
    )
    protected abstract suspend fun updateDrawdownStateIfRevision(
        monitorId: String,
        expectedRevision: Long,
        newRevision: Long,
        activeBandIdsJson: String,
        lastTriggeredBandId: String?,
        lastDrawdownRateDecimal: String,
        observedAt: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertDrawdownAlertEvent(entity: DrawdownAlertEventEntity)

    @Transaction
    public open suspend fun commitDrawdownObservationAtomically(
        expectedRevision: Long,
        state: DrawdownMonitorStateEntity,
        event: DrawdownAlertEventEntity?,
    ): Boolean {
        require(state.revision == expectedRevision + 1) { "Drawdown state revision must advance exactly once" }
        val updated = updateDrawdownStateIfRevision(
            monitorId = state.monitorId,
            expectedRevision = expectedRevision,
            newRevision = state.revision,
            activeBandIdsJson = state.activeBandIdsJson,
            lastTriggeredBandId = state.lastTriggeredBandId,
            lastDrawdownRateDecimal = requireNotNull(state.lastDrawdownRateDecimal),
            observedAt = requireNotNull(state.observedAt),
        )
        if (updated != 1) return false
        if (event != null) {
            require(event.monitorId == state.monitorId && event.revision == state.revision) {
                "Drawdown alert event must belong to its committed state"
            }
            insertDrawdownAlertEvent(event)
        }
        return true
    }

    @Query(
        "SELECT * FROM drawdown_alert_events " +
            "WHERE monitorId = :monitorId AND deliveredAt IS NULL ORDER BY revision, id",
    )
    public abstract suspend fun listPendingDrawdownAlerts(monitorId: String): List<DrawdownAlertEventEntity>

    @Query(
        "UPDATE drawdown_alert_events SET deliveredAt = :deliveredAt " +
            "WHERE id = :id AND deliveredAt IS NULL",
    )
    public abstract suspend fun markDrawdownAlertDelivered(id: String, deliveredAt: String): Int

    @Query("SELECT * FROM actionable_exception_states WHERE identity = :identity")
    protected abstract suspend fun findActionableExceptionState(identity: String): ActionableExceptionStateEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertActionableExceptionState(entity: ActionableExceptionStateEntity)

    @Update
    protected abstract suspend fun updateActionableExceptionState(entity: ActionableExceptionStateEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertActionableExceptionEvent(entity: ActionableExceptionEventEntity)

    @Transaction
    public open suspend fun recordActionableExceptionQualificationAtomically(
        identity: String,
        category: String,
        subjectId: String,
        observationKey: String,
        conditionPresent: Boolean,
        requiredConsecutive: Int,
        observedAt: String,
    ) {
        require(identity.isNotBlank() && subjectId.isNotBlank() && observationKey.isNotBlank()) {
            "Actionable qualification identity, subject and observation key are required"
        }
        require(requiredConsecutive > 0) { "Required consecutive observations must be positive" }
        val existing = findActionableExceptionState(identity)
        if (existing != null) {
            require(existing.category == category && existing.subjectId == subjectId) {
                "Actionable exception identity cannot change meaning"
            }
            require(existing.requiredConsecutive == requiredConsecutive) {
                "Actionable exception threshold cannot change without an explicit migration"
            }
            if (existing.lastObservationKey == observationKey) {
                require(existing.lastObservationPresent == conditionPresent) {
                    "One actionable observation key cannot change outcome"
                }
                return
            }
        }
        val priorCount = existing?.consecutiveCount ?: 0
        val nextCount = if (conditionPresent) Math.addExact(priorCount, 1) else 0
        val firstObservedAt = if (conditionPresent && priorCount == 0) {
            observedAt
        } else {
            existing?.firstObservedAt ?: observedAt
        }
        val next = (existing ?: ActionableExceptionStateEntity(
            identity = identity,
            category = category,
            subjectId = subjectId,
            active = false,
            revision = 0,
            firstObservedAt = firstObservedAt,
            lastObservedAt = observedAt,
            resolvedAt = null,
            requiredConsecutive = requiredConsecutive,
        )).copy(
            firstObservedAt = firstObservedAt,
            lastObservedAt = observedAt,
            requiredConsecutive = requiredConsecutive,
            consecutiveCount = nextCount,
            lastObservationKey = observationKey,
            lastObservationPresent = conditionPresent,
            resolutionPending = if (conditionPresent) {
                existing?.resolutionPending ?: false
            } else {
                existing?.resolutionPending == true || existing?.active == true
            },
        )
        if (existing == null) insertActionableExceptionState(next) else updateActionableExceptionState(next)
    }

    @Query(
        "SELECT * FROM actionable_exception_states " +
            "WHERE consecutiveCount >= requiredConsecutive AND resolutionPending = 0 ORDER BY identity",
    )
    public abstract suspend fun listQualifiedActionableExceptionStates(): List<ActionableExceptionStateEntity>

    @Query(
        "SELECT identity FROM actionable_exception_states " +
            "WHERE resolutionPending = 1 ORDER BY identity",
    )
    public abstract suspend fun listPendingActionableExceptionResolutionIdentities(): List<String>

    @Query(
        "UPDATE actionable_exception_states SET resolutionPending = 0 " +
            "WHERE identity IN (:identities) AND resolutionPending = 1",
    )
    public abstract suspend fun markActionableExceptionResolutionsApplied(identities: List<String>): Int

    @Transaction
    public open suspend fun observeActionableExceptionAtomically(
        identity: String,
        category: String,
        subjectId: String,
        observedAt: String,
    ): ActionableExceptionEventEntity? {
        val existing = findActionableExceptionState(identity)
        if (existing != null) {
            require(existing.category == category && existing.subjectId == subjectId) {
                "Actionable exception identity cannot change meaning"
            }
            if (existing.active) {
                updateActionableExceptionState(existing.copy(lastObservedAt = observedAt))
                return null
            }
        }
        val revision = Math.addExact(existing?.revision ?: 0L, 1L)
        val state = if (existing == null) {
            ActionableExceptionStateEntity(
                identity = identity,
                category = category,
                subjectId = subjectId,
                active = true,
                revision = revision,
                firstObservedAt = observedAt,
                lastObservedAt = observedAt,
                resolvedAt = null,
                consecutiveCount = 1,
            )
        } else {
            existing.copy(
                active = true,
                revision = revision,
                lastObservedAt = observedAt,
                resolvedAt = null,
            )
        }
        if (existing == null) insertActionableExceptionState(state) else updateActionableExceptionState(state)
        val event = ActionableExceptionEventEntity(
            id = "action-required:$identity:$revision",
            identity = identity,
            revision = revision,
            category = category,
            subjectId = subjectId,
            observedAt = observedAt,
            deliveredAt = null,
            closedAt = null,
        )
        insertActionableExceptionEvent(event)
        return event
    }

    @Query(
        "UPDATE actionable_exception_states SET active = 0, resolvedAt = :resolvedAt " +
            "WHERE identity = :identity AND active = 1",
    )
    protected abstract suspend fun resolveActionableExceptionState(identity: String, resolvedAt: String): Int

    @Query(
        "UPDATE actionable_exception_events SET closedAt = :resolvedAt " +
            "WHERE identity = :identity AND deliveredAt IS NULL AND closedAt IS NULL",
    )
    protected abstract suspend fun closePendingActionableExceptionEvents(identity: String, resolvedAt: String): Int

    @Transaction
    public open suspend fun resolveActionableExceptionAtomically(identity: String, resolvedAt: String) {
        resolveActionableExceptionState(identity, resolvedAt)
        closePendingActionableExceptionEvents(identity, resolvedAt)
    }

    @Query(
        "SELECT * FROM actionable_exception_events " +
            "WHERE deliveredAt IS NULL AND closedAt IS NULL ORDER BY observedAt, id",
    )
    public abstract suspend fun listPendingActionableExceptionEvents(): List<ActionableExceptionEventEntity>

    @Query(
        "UPDATE actionable_exception_events SET deliveredAt = :deliveredAt " +
            "WHERE id = :id AND deliveredAt IS NULL",
    )
    public abstract suspend fun markActionableExceptionEventDelivered(id: String, deliveredAt: String): Int
}
