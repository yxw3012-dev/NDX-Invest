package com.ndxinvest.database

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

@Dao
public abstract class LedgerDao {
    @Transaction
    public open suspend fun wasConsumedPurchaseSharesCorrectedAtomically(proof: com.ndxinvest.ledger.rules.ConsumedPurchaseShareCorrection): Boolean {
        val confirmed = proof.confirmed.toLedgerEntity()
        val entries = loadLedgerEntries(confirmed.fundId)
        val saved = entries.singleOrNull { it.id == proof.entry.id.value } ?: return false
        val encoded = LedgerEventStorageCodec.encode(proof.entry)
        if (entries.any { it.sequence > saved.sequence && when (val revision = LedgerEventStorageCodec.decode(it)) {
            is com.ndxinvest.ledger.calculation.ConsumedPurchaseSharesCorrected -> revision.expectedLot.sourceTransactionId == proof.confirmed.id
            is com.ndxinvest.ledger.calculation.ConsumedPurchaseCostCorrected -> revision.expectedLot.sourceTransactionId == proof.confirmed.id
            else -> false
        } }) return false
        return findTransaction(confirmed.id) == confirmed && saved.sequence > proof.preview.history.entries.maxOf { it.sequence } &&
            saved.copy(sequence = encoded.sequence) == encoded && proof.audits.all { findCorrection(it.id.value) == it.toLedgerEntity() }
    }

    @Transaction
    public open suspend fun correctConsumedPurchaseSharesAtomically(proof: com.ndxinvest.ledger.rules.ConsumedPurchaseShareCorrection,
        expectedRows: LedgerHistoryRows, now: Instant, today: LocalDate): Boolean {
        proof.validate(now, today)
        if (wasConsumedPurchaseSharesCorrectedAtomically(proof)) return true
        val preview = proof.preview
        val fundId = preview.fund.id.value
        if (findFund(fundId) != preview.fund.toLedgerEntity() || loadHistorySnapshot(fundId) != expectedRows) return false
        val history = com.ndxinvest.ledger.calculation.LedgerHistorySnapshot(preview.fund.id,
            expectedRows.transactions.map { it.toLedgerDomain() }, expectedRows.realLots.map { it.toLedgerDomain() },
            expectedRows.legacyLots.map { it.toLedgerDomain() }, expectedRows.entries.map(LedgerEventStorageCodec::decode))
        if (history != preview.history || findTransaction(preview.originalPurchase.id.value) != preview.originalPurchase.toLedgerEntity() ||
            findLot(preview.originalLot.id.value) != preview.originalLot.toLedgerEntity()) return false
        check(updateTransaction(proof.confirmed.toLedgerEntity()) == 1)
        check(updateLot(preview.proposedLot.toLedgerEntity()) == 1)
        insertLedgerEntry(LedgerEventStorageCodec.encode(proof.entry.copy(sequence = nextLedgerSequence())))
        proof.audits.forEach { insertCorrection(it.toLedgerEntity()) }
        return true
    }

    @Transaction
    public open suspend fun wasConsumedPurchaseCostCorrectedAtomically(proof: com.ndxinvest.ledger.rules.ConsumedPurchaseCostCorrection): Boolean {
        val confirmed = proof.confirmed.toLedgerEntity()
        val entries = loadLedgerEntries(confirmed.fundId)
        val saved = entries.singleOrNull { it.id == proof.entry.id.value } ?: return false
        val encoded = LedgerEventStorageCodec.encode(proof.entry)
        // Even an A → B → A value cycle at the same clock instant cannot revive an older review handle.
        if (entries.any { it.sequence > saved.sequence && when (val revision = LedgerEventStorageCodec.decode(it)) {
            is com.ndxinvest.ledger.calculation.ConsumedPurchaseCostCorrected -> revision.expectedLot.sourceTransactionId == proof.confirmed.id
            is com.ndxinvest.ledger.calculation.ConsumedPurchaseSharesCorrected -> revision.expectedLot.sourceTransactionId == proof.confirmed.id
            else -> false
        } }) return false
        return findTransaction(confirmed.id) == confirmed && saved.sequence > proof.preview.history.entries.maxOf { it.sequence } &&
            saved.copy(sequence = encoded.sequence) == encoded && proof.audits.all { findCorrection(it.id.value) == it.toLedgerEntity() }
    }

    /** One compare-and-save transaction: exact reviewed history, append-only event, projection and all audits. */
    @Transaction
    public open suspend fun correctConsumedPurchaseCostAtomically(proof: com.ndxinvest.ledger.rules.ConsumedPurchaseCostCorrection,
        expectedRows: LedgerHistoryRows, now: Instant, today: LocalDate): Boolean {
        proof.validate(now, today)
        if (wasConsumedPurchaseCostCorrectedAtomically(proof)) return true
        val preview = proof.preview
        val fundId = preview.fund.id.value
        if (findFund(fundId) != preview.fund.toLedgerEntity() || loadHistorySnapshot(fundId) != expectedRows) return false
        // Decode inside this transaction, including immutable payloads, so direct DAO calls cannot swap witnesses.
        val history = com.ndxinvest.ledger.calculation.LedgerHistorySnapshot(preview.fund.id,
            expectedRows.transactions.map { it.toLedgerDomain() }, expectedRows.realLots.map { it.toLedgerDomain() },
            expectedRows.legacyLots.map { it.toLedgerDomain() }, expectedRows.entries.map(LedgerEventStorageCodec::decode))
        if (history != preview.history || findTransaction(preview.originalPurchase.id.value) != preview.originalPurchase.toLedgerEntity() ||
            findLot(preview.originalLot.id.value) != preview.originalLot.toLedgerEntity()) return false
        val entry = LedgerEventStorageCodec.encode(proof.entry.copy(sequence = nextLedgerSequence()))
        check(updateTransaction(proof.confirmed.toLedgerEntity()) == 1)
        check(updateLot(preview.proposedLot.toLedgerEntity()) == 1)
        insertLedgerEntry(entry)
        proof.audits.forEach { insertCorrection(it.toLedgerEntity()) } // Any final collision rolls back all writes.
        return true
    }

    @Transaction
    public open suspend fun wasFinalRedemptionReceiptCorrectedAtomically(confirmed: LedgerTransactionEntity,
        audits: List<TransactionCorrectionEntity>): Boolean = audits.isNotEmpty() && findTransaction(confirmed.id) == confirmed &&
        audits.all { findCorrection(it.id) == it }

    @Transaction
    public open suspend fun correctFinalRedemptionReceiptAtomically(fund: FundEntity, history: LedgerHistoryRows,
        expected: LedgerTransactionEntity, confirmed: LedgerTransactionEntity, audits: List<TransactionCorrectionEntity>): Boolean {
        if (wasFinalRedemptionReceiptCorrectedAtomically(confirmed, audits)) return true
        if (findFund(fund.id) != fund || loadHistorySnapshot(fund.id) != history || findTransaction(expected.id) != expected) return false
        require(expected.fundId == fund.id && expected.type == "REDEMPTION" && expected.status == "CONFIRMED_ACTUAL" &&
            expected.authority == "USER_OR_PLATFORM_FINAL" && expected.origin != "IMPORTED")
        val cash = BigDecimal(requireNotNull(confirmed.actualCashFlowDecimal))
        require(cash.signum() >= 0 && BigDecimal(requireNotNull(confirmed.netAmountDecimal)).compareTo(cash) == 0)
        cash.setScale(requireNotNull(fund.moneyScale), RoundingMode.UNNECESSARY)
        val cashDate = LocalDate.parse(requireNotNull(confirmed.cashFlowDate))
        require(!cashDate.isBefore(LocalDate.parse(expected.tradeDate)) && !Instant.parse(confirmed.updatedAt).isBefore(Instant.parse(expected.updatedAt)))
        require(confirmed == expected.copy(actualCashFlowDecimal = confirmed.actualCashFlowDecimal, netAmountDecimal = confirmed.netAmountDecimal,
            cashFlowDate = confirmed.cashFlowDate, confirmationDate = confirmed.confirmationDate, isManualCorrected = true, updatedAt = confirmed.updatedAt)) {
            "Receipt correction cannot change shares, cost, NAV, rule, trade or settlement facts" }
        require(confirmed.confirmationDate != null || expected.confirmationDate == null)
        require(confirmed.confirmationDate == null || confirmed.confirmationDate >= (expected.navDate ?: expected.tradeDate))
        val changes = linkedMapOf<String, Triple<String, String?, String?>>()
        fun decimal(field: String, old: String?, value: String?) {
            if (old == null || value == null || BigDecimal(old).compareTo(BigDecimal(value)) != 0) changes[field] = Triple("DECIMAL", old, value)
            else require(old == value) { "Unchanged decimal facts must keep their original representation" }
        }
        decimal("ACTUAL_CASH_FLOW", expected.actualCashFlowDecimal, confirmed.actualCashFlowDecimal)
        decimal("NET_AMOUNT", expected.netAmountDecimal, confirmed.netAmountDecimal)
        if (expected.cashFlowDate != confirmed.cashFlowDate) changes["CASH_FLOW_DATE"] = Triple("DATE", expected.cashFlowDate, confirmed.cashFlowDate)
        if (expected.confirmationDate != confirmed.confirmationDate) changes["CONFIRMATION_DATE"] = Triple("DATE", expected.confirmationDate, confirmed.confirmationDate)
        require(changes.isNotEmpty() && audits.size == changes.size && audits.map { it.field }.toSet() == changes.keys &&
            audits.map { it.id }.distinct().size == audits.size)
        require(audits.all { audit ->
            val (kind, old, value) = changes.getValue(audit.field)
            audit.transactionId == expected.id && audit.source == "SALES_PLATFORM_FINAL" && audit.correctedAt == confirmed.updatedAt &&
                audit.originalValueType == (if (old == null) "MISSING" else kind) && audit.originalValue == old &&
                audit.correctedValueType == kind && audit.correctedValue == value
        }) { "Receipt correction requires complete immutable before and after audits" }
        check(updateTransaction(confirmed) == 1)
        audits.forEach { insertCorrection(it) }
        return true
    }

    @Query("SELECT openDate FROM fund_calendar_open_dates WHERE fundId = :fundId ORDER BY openDate")
    protected abstract suspend fun reviewedCalendarOpenDates(fundId: String): List<String>

    @Transaction
    public open suspend fun recordReviewedAmountRedemptionAtomically(fund: FundEntity, history: LedgerHistoryRows,
        rules: List<FundRuleVersionEntity>, transaction: LedgerTransactionEntity, expected: LedgerTransactionEntity?,
        coverageStart: String?, coverageEnd: String?, openDates: List<String>?, audit: TransactionCorrectionEntity?): Boolean {
        require(transaction.fundId == fund.id && transaction.type == "REDEMPTION")
        if (findFund(fund.id) != fund || loadHistorySnapshot(fund.id) != history || loadRuleVersions(fund.id) != rules) return false
        if (expected == null) {
            if (findTransaction(transaction.id) != null) return false
            val calendar = legacySaleCalendar(fund.id) ?: return false
            if (calendar.authority != "FUND_OFFICIAL" || calendar.coverageStart != coverageStart || calendar.coverageEnd != coverageEnd ||
                reviewedCalendarOpenDates(fund.id) != openDates || Instant.parse(calendar.importedAt).isAfter(Instant.parse(transaction.createdAt)) ||
                Instant.parse(calendar.publishedAt).isAfter(Instant.parse(transaction.createdAt))) return false
            require(transaction.status == "PLANNED" && transaction.sharesDecimal == null && audit == null)
            insertTransaction(transaction)
        } else {
            if (findTransaction(expected.id) != expected) return false
            require(audit != null && coverageStart == null && coverageEnd == null && openDates == null)
            val shares = requireNotNull(transaction.sharesDecimal)
            require(transaction == expected.copy(status = "PENDING_CALC", sharesDecimal = shares, updatedAt = transaction.updatedAt))
            require(audit.transactionId == expected.id && audit.field == "SHARES" && audit.source == "SALES_PLATFORM_FINAL" &&
                audit.originalValueType == "MISSING" && audit.originalValue == null && audit.correctedValueType == "DECIMAL" &&
                audit.correctedValue == shares && audit.correctedAt == transaction.updatedAt)
            if (!activatePlannedAmountRedemptionIfAvailable(expected.id, shares, transaction.updatedAt)) return false
            insertCorrection(audit) // ABORT rolls back the activation too if the immutable proof collides.
        }
        return true
    }

    @Query("SELECT * FROM fund_calendar_snapshots WHERE fundId = :fundId")
    protected abstract suspend fun legacySaleCalendar(fundId: String): FundCalendarSnapshotEntity?

    @Query("SELECT COUNT(*) FROM fund_calendar_open_dates WHERE fundId = :fundId AND openDate = :date")
    protected abstract suspend fun legacySaleOpenDate(fundId: String, date: String): Int

    @Transaction
    public open suspend fun recordReviewedLegacyAtomically(fund: FundEntity, expected: LedgerHistoryRows,
        rules: List<FundRuleVersionEntity>, transaction: LedgerTransactionEntity,
        expectedLot: LegacyLotEntity?, updatedLot: LegacyLotEntity, entry: LedgerEntryEntity): Boolean {
        require(transaction.fundId == fund.id && updatedLot.fundId == fund.id && entry.fundId == fund.id)
        if (findTransaction(transaction.id) != null || findFund(fund.id) != fund ||
            loadHistorySnapshot(fund.id) != expected || loadRuleVersions(fund.id) != rules) return false
        if (expectedLot == null) {
            insertLegacyLotAtomically(transaction, updatedLot, entry)
        } else {
            val calendar = legacySaleCalendar(fund.id) ?: return false
            if (calendar.authority != "FUND_OFFICIAL" || transaction.tradeDate !in calendar.coverageStart..calendar.coverageEnd ||
                legacySaleOpenDate(fund.id, transaction.tradeDate) != 1 ||
                java.time.Instant.parse(calendar.importedAt).isAfter(java.time.Instant.parse(transaction.createdAt)) ||
                java.time.Instant.parse(calendar.publishedAt).isAfter(java.time.Instant.parse(transaction.createdAt))) return false
            insertConfirmedLegacyRedemptionAtomically(transaction, expectedLot, updatedLot, entry)
        }
        return true
    }

    @Transaction
    public open suspend fun recordReviewedDividendAtomically(fund: FundEntity, expected: LedgerHistoryRows,
        rules: List<FundRuleVersionEntity>, nav: NavRecordEntity?, transaction: LedgerTransactionEntity,
        lot: LotEntity?, entry: LedgerEntryEntity?): Boolean {
        require(transaction.fundId == fund.id && transaction.type in setOf("CASH_DIVIDEND", "DIVIDEND_REINVESTMENT"))
        if (findTransaction(transaction.id) != null || findFund(fund.id) != fund || loadHistorySnapshot(fund.id) != expected ||
            loadRuleVersions(fund.id) != rules || (nav != null && findNav(fund.id, nav.navDate) != nav)) return false
        if (lot == null) {
            require(nav == null && entry == null && transaction.type == "CASH_DIVIDEND")
            insertTransaction(transaction)
        } else {
            require(nav != null && entry != null && transaction.type == "DIVIDEND_REINVESTMENT")
            recordConfirmedReinvestmentAtomically(transaction, lot, entry)
        }
        return true
    }

    @Query("SELECT * FROM tracking_baselines WHERE fundId = :fundId")
    public abstract suspend fun findTrackingBaseline(fundId: String): TrackingBaselineEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertTrackingBaseline(entity: TrackingBaselineEntity)

    @Transaction
    public open suspend fun establishTrackingBaselineAtomically(entity: TrackingBaselineEntity, expected: LedgerHistoryRows,
        nav: NavRecordEntity, fund: FundEntity): Boolean {
        val existing = findTrackingBaseline(entity.fundId)
        if (existing != null) return existing == entity
        require(entity.fundId == fund.id && entity.fundId == nav.fundId) { "Baseline evidence must belong to one fund" }
        if (findFund(entity.fundId) != fund || findNav(nav.fundId, nav.navDate) != nav || loadHistorySnapshot(entity.fundId) != expected) return false
        insertTrackingBaseline(entity)
        return true
    }

    @Transaction
    public open suspend fun loadFundHistorySnapshot(fundId: String): LedgerFundHistoryRows =
        LedgerFundHistoryRows(findFund(fundId), loadHistorySnapshot(fundId))

    @Transaction
    public open suspend fun loadHistorySnapshot(fundId: String): LedgerHistoryRows = LedgerHistoryRows(
        loadTransactions(fundId), loadLots(fundId), loadLegacyLots(fundId), loadLedgerEntries(fundId),
    )
    @Upsert
    public abstract suspend fun upsertFund(entity: FundEntity)

    @Query("SELECT * FROM funds WHERE id = :id")
    public abstract suspend fun findFund(id: String): FundEntity?

    @Query("SELECT * FROM funds ORDER BY code, id")
    public abstract suspend fun loadFunds(): List<FundEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public abstract suspend fun insertRuleVersion(entity: FundRuleVersionEntity)

    @Query("SELECT * FROM fund_rule_versions WHERE id = :id")
    public abstract suspend fun findRuleVersion(id: String): FundRuleVersionEntity?

    @Query("SELECT * FROM fund_rule_versions WHERE fundId = :fundId ORDER BY effectiveFrom, id")
    public abstract suspend fun loadRuleVersions(fundId: String): List<FundRuleVersionEntity>

    @Upsert
    public abstract suspend fun upsertNav(entity: NavRecordEntity)

    @Query("SELECT * FROM nav_records WHERE fundId = :fundId AND navDate = :navDate")
    public abstract suspend fun findNav(fundId: String, navDate: String): NavRecordEntity?

    @Query("SELECT * FROM nav_records WHERE fundId = :fundId ORDER BY navDate DESC LIMIT 1")
    public abstract suspend fun findLatestNav(fundId: String): NavRecordEntity?

    @Transaction
    public open suspend fun upsertNavRespectingAuthority(entity: NavRecordEntity): Boolean {
        val existing = findNav(entity.fundId, entity.navDate)
        val mayReplace = existing == null ||
            authorityPriority(entity.authority) > authorityPriority(existing.authority) ||
            (
                authorityPriority(entity.authority) == authorityPriority(existing.authority) &&
                    Instant.parse(entity.fetchedAt).isAfter(Instant.parse(existing.fetchedAt))
                )
        if (mayReplace) upsertNav(entity)
        return mayReplace
    }

    @Upsert
    public abstract suspend fun upsertRecurringPlan(entity: RecurringPlanEntity)

    @Query("SELECT * FROM recurring_plans WHERE id = :id")
    public abstract suspend fun findRecurringPlan(id: String): RecurringPlanEntity?

    @Query("SELECT * FROM recurring_plans WHERE enabled = 1 ORDER BY id")
    public abstract suspend fun loadEnabledRecurringPlans(): List<RecurringPlanEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public abstract suspend fun insertTransaction(entity: LedgerTransactionEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    public abstract suspend fun updateTransaction(entity: LedgerTransactionEntity): Int

    @Query("SELECT * FROM ledger_transactions WHERE id = :id")
    public abstract suspend fun findTransaction(id: String): LedgerTransactionEntity?

    @Query("SELECT * FROM ledger_transactions WHERE fundId = :fundId ORDER BY tradeDate, applicationTime, id")
    public abstract suspend fun loadTransactions(fundId: String): List<LedgerTransactionEntity>

    @Query(
        "SELECT * FROM ledger_transactions " +
            "WHERE fundId = :fundId AND status = 'PENDING_CALC' " +
            "AND type IN ('PURCHASE', 'RECURRING_PURCHASE') " +
            "ORDER BY navDate, applicationTime, id",
    )
    public abstract suspend fun loadPendingPurchaseTransactions(fundId: String): List<LedgerTransactionEntity>

    @Query(
        "SELECT * FROM ledger_transactions " +
            "WHERE fundId = :fundId AND status = 'PENDING_CALC' AND type = 'REDEMPTION' " +
            "ORDER BY navDate, applicationTime, id",
    )
    public abstract suspend fun loadPendingRedemptionTransactions(fundId: String): List<LedgerTransactionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public abstract suspend fun insertLot(entity: LotEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    public abstract suspend fun updateLot(entity: LotEntity): Int

    @Delete
    public abstract suspend fun deleteLot(entity: LotEntity): Int

    @Query("SELECT * FROM lots WHERE id = :id")
    public abstract suspend fun findLot(id: String): LotEntity?

    @Query("SELECT * FROM lots WHERE sourceTransactionId = :transactionId")
    public abstract suspend fun findLotBySourceTransaction(transactionId: String): LotEntity?

    @Query("SELECT * FROM lots WHERE fundId = :fundId ORDER BY confirmationDate, id")
    public abstract suspend fun loadLots(fundId: String): List<LotEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public abstract suspend fun insertLegacyLot(entity: LegacyLotEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    public abstract suspend fun updateLegacyLot(entity: LegacyLotEntity): Int

    @Query("SELECT * FROM legacy_lots WHERE id = :id")
    public abstract suspend fun findLegacyLot(id: String): LegacyLotEntity?

    @Query("SELECT * FROM legacy_lots WHERE fundId = :fundId ORDER BY trackingStartDate, id")
    public abstract suspend fun loadLegacyLots(fundId: String): List<LegacyLotEntity>

    @Transaction
    public open suspend fun insertPendingRedemptionIfAvailable(
        transaction: LedgerTransactionEntity,
    ): Boolean {
        require(transaction.type == "REDEMPTION") { "Share reservation requires a redemption" }
        require(transaction.status == "PENDING_CALC") { "Share reservation requires a pending transaction" }
        val requestedShares = transaction.sharesDecimal?.let(::BigDecimal)
            ?: error("Share reservation requires explicit shares")
        require(requestedShares.signum() > 0) { "Share reservation requires positive shares" }

        val hasLegacyHolding = loadLegacyLots(transaction.fundId).any {
            BigDecimal(it.remainingSharesDecimal).signum() > 0
        }
        if (hasLegacyHolding) return false

        val realShares = loadLots(transaction.fundId).fold(BigDecimal.ZERO) { total, lot ->
            total.add(BigDecimal(lot.remainingSharesDecimal))
        }
        val pending = loadPendingRedemptionTransactions(transaction.fundId)
        if (pending.any { it.sharesDecimal == null || BigDecimal(it.sharesDecimal).signum() <= 0 }) return false
        val reservedShares = pending.fold(BigDecimal.ZERO) { total, redemption ->
            total.add(BigDecimal(requireNotNull(redemption.sharesDecimal)))
        }
        if (requestedShares.compareTo(realShares.subtract(reservedShares)) > 0) return false

        insertTransaction(transaction)
        return true
    }

    @Transaction
    public open suspend fun activatePlannedAmountRedemptionIfAvailable(
        transactionId: String,
        confirmedSharesDecimal: String,
        updatedAt: String,
    ): Boolean {
        val planned = findTransaction(transactionId) ?: return false
        if (planned.type != "REDEMPTION" || planned.status != "PLANNED" || planned.sharesDecimal != null) return false
        if (planned.requestedAmountDecimal?.let(::BigDecimal)?.signum() != 1) return false
        if (planned.navDate == null || planned.confirmationDate == null) return false
        if (Instant.parse(updatedAt).isBefore(Instant.parse(planned.updatedAt))) return false
        val confirmedShares = BigDecimal(confirmedSharesDecimal)
        require(confirmedShares.signum() > 0) { "Confirmed shares must be positive" }
        val shareScale = findFund(planned.fundId)?.shareScale ?: return false
        try {
            confirmedShares.setScale(shareScale, RoundingMode.UNNECESSARY)
        } catch (_: ArithmeticException) {
            return false
        }

        val hasLegacyHolding = loadLegacyLots(planned.fundId).any {
            BigDecimal(it.remainingSharesDecimal).signum() > 0
        }
        if (hasLegacyHolding) return false
        val realShares = loadLots(planned.fundId).fold(BigDecimal.ZERO) { total, lot ->
            total.add(BigDecimal(lot.remainingSharesDecimal))
        }
        val pending = loadPendingRedemptionTransactions(planned.fundId)
        if (pending.any { it.sharesDecimal == null || BigDecimal(it.sharesDecimal).signum() <= 0 }) return false
        val reservedShares = pending.fold(BigDecimal.ZERO) { total, redemption ->
            total.add(BigDecimal(requireNotNull(redemption.sharesDecimal)))
        }
        if (confirmedShares.compareTo(realShares.subtract(reservedShares)) > 0) return false

        return updateTransaction(
            planned.copy(
                status = "PENDING_CALC",
                sharesDecimal = confirmedSharesDecimal,
                updatedAt = updatedAt,
            ),
        ) == 1
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public abstract suspend fun insertRecurringExecution(entity: RecurringExecutionEntity)

    @Query(
        "SELECT COUNT(*) FROM recurring_executions " +
            "WHERE planId = :planId AND scheduledTradeDate = :scheduledTradeDate",
    )
    public abstract suspend fun countRecurringExecution(planId: String, scheduledTradeDate: String): Long

    @Query(
        "SELECT e.planId, e.scheduledTradeDate, e.transactionId, e.deliveredAt, " +
            "t.fundId, t.requestedAmountDecimal AS amountDecimal " +
            "FROM recurring_executions e JOIN ledger_transactions t ON t.id = e.transactionId " +
            "WHERE e.deliveredAt IS NULL ORDER BY e.scheduledTradeDate, e.planId",
    )
    public abstract suspend fun loadPendingRecurringExecutionNotifications(): List<RecurringExecutionNotificationRow>

    @Query(
        "UPDATE recurring_executions SET deliveredAt = :deliveredAt " +
            "WHERE planId = :planId AND scheduledTradeDate = :scheduledTradeDate AND deliveredAt IS NULL",
    )
    public abstract suspend fun markRecurringExecutionNotificationDelivered(
        planId: String,
        scheduledTradeDate: String,
        deliveredAt: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public abstract suspend fun insertLedgerEntry(entity: LedgerEntryEntity)

    @Query("SELECT * FROM ledger_entries WHERE fundId = :fundId ORDER BY sequence")
    public abstract suspend fun loadLedgerEntries(fundId: String): List<LedgerEntryEntity>

    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM ledger_entries")
    protected abstract suspend fun nextLedgerSequence(): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public abstract suspend fun insertCorrection(entity: TransactionCorrectionEntity)

    @Query("SELECT * FROM transaction_corrections WHERE id = :id")
    public abstract suspend fun findCorrection(id: String): TransactionCorrectionEntity?

    @Transaction
    public open suspend fun confirmPurchaseAtomically(
        transaction: LedgerTransactionEntity,
        lot: LotEntity,
        entry: LedgerEntryEntity,
    ) {
        val existing = requireTransaction(transaction.id)
        assertConfirmationTransition(existing, transaction)
        require(lot.sourceTransactionId == transaction.id && lot.fundId == transaction.fundId) {
            "Purchase lot must belong to its transaction"
        }
        require(entry.fundId == transaction.fundId) { "Purchase ledger entry fund mismatch" }
        check(updateTransaction(transaction) == 1) { "Purchase transaction update failed" }
        insertLedgerEntry(entry)
        insertLot(lot)
    }

    @Transaction
    public open suspend fun recordConfirmedReinvestmentAtomically(
        transaction: LedgerTransactionEntity,
        lot: LotEntity,
        entry: LedgerEntryEntity,
    ) {
        require(transaction.type == "DIVIDEND_REINVESTMENT" && transaction.status == "CONFIRMED_ACTUAL") {
            "A confirmed reinvestment needs final reinvestment facts"
        }
        require(lot.sourceTransactionId == transaction.id && lot.fundId == transaction.fundId) {
            "Reinvestment lot must belong to its transaction"
        }
        require(entry.fundId == transaction.fundId) { "Reinvestment ledger entry fund mismatch" }
        val navDate = requireNotNull(transaction.navDate) { "Reinvestment needs an exact NAV date" }
        val unitNav = requireNotNull(transaction.unitNavDecimal) { "Reinvestment needs an exact NAV" }
        val publishedNav = findNav(transaction.fundId, navDate)
            ?: error("Reinvestment needs a published NAV for $navDate")
        require(BigDecimal(publishedNav.unitNavDecimal).compareTo(BigDecimal(unitNav)) == 0) {
            "Reinvestment NAV differs from the persisted published NAV"
        }
        val sequencedEntry = entry.copy(sequence = nextLedgerSequence())
        insertTransaction(transaction)
        insertLedgerEntry(sequencedEntry)
        insertLot(lot)
    }

    @Transaction
    public open suspend fun materializeCalculatedPurchaseAtomically(
        transaction: LedgerTransactionEntity,
        lot: LotEntity,
        entry: LedgerEntryEntity,
    ): Boolean {
        val existing = requireTransaction(transaction.id)
        if (existing.status != "PENDING_CALC" && existing.status != "ABNORMAL") return false
        assertConfirmationTransition(existing, transaction)
        require(transaction.status == "CALCULATED") {
            "Automatic purchase materialization must produce calculated facts"
        }
        require(transaction.type == "PURCHASE" || transaction.type == "RECURRING_PURCHASE") {
            "Automatic NAV completion supports purchase transactions only"
        }
        require(lot.sourceTransactionId == transaction.id && lot.fundId == transaction.fundId) {
            "Purchase lot must belong to its transaction"
        }
        require(entry.fundId == transaction.fundId) { "Purchase ledger entry fund mismatch" }
        val sequencedEntry = entry.copy(sequence = nextLedgerSequence())
        check(updateTransaction(transaction) == 1) { "Purchase transaction update failed" }
        insertLedgerEntry(sequencedEntry)
        insertLot(lot)
        return true
    }

    @Transaction
    public open suspend fun insertRecurringExecutionAtomically(
        transaction: LedgerTransactionEntity,
        execution: RecurringExecutionEntity,
    ) {
        val plan = findRecurringPlan(execution.planId) ?: error("Recurring plan does not exist")
        require(plan.fundId == transaction.fundId) { "Recurring plan and transaction fund mismatch" }
        require(execution.transactionId == transaction.id) { "Recurring execution transaction mismatch" }
        require(transaction.type == "RECURRING_PURCHASE") { "Recurring execution requires a recurring purchase" }
        require(transaction.origin == "AUTO_PLAN_ASSUMED") { "Generated recurring transaction must remain assumed" }
        insertTransaction(transaction)
        insertRecurringExecution(execution)
    }

    @Transaction
    public open suspend fun materializeRecurringExecutionAtomically(
        updatedPlan: RecurringPlanEntity,
        transaction: LedgerTransactionEntity,
        execution: RecurringExecutionEntity,
    ): Boolean {
        val existingPlan = findRecurringPlan(execution.planId) ?: error("Recurring plan does not exist")
        if (countRecurringExecution(execution.planId, execution.scheduledTradeDate) != 0L) return false
        require(existingPlan.id == updatedPlan.id && existingPlan.fundId == updatedPlan.fundId) {
            "Recurring plan identity cannot change"
        }
        require(updatedPlan.lastGeneratedDate == execution.scheduledTradeDate) {
            "Recurring plan generation date must match its execution key"
        }
        require(existingPlan.fundId == transaction.fundId) { "Recurring plan and transaction fund mismatch" }
        require(execution.transactionId == transaction.id) { "Recurring execution transaction mismatch" }
        require(transaction.type == "RECURRING_PURCHASE") { "Recurring execution requires a recurring purchase" }
        require(transaction.origin == "AUTO_PLAN_ASSUMED") { "Generated recurring transaction must remain assumed" }
        insertTransaction(transaction)
        insertRecurringExecution(execution)
        upsertRecurringPlan(updatedPlan)
        return true
    }

    @Transaction
    public open suspend fun insertLegacyLotAtomically(
        transaction: LedgerTransactionEntity,
        lot: LegacyLotEntity,
        entry: LedgerEntryEntity,
    ) {
        require(transaction.origin == "IMPORTED") { "Legacy lot transaction must be imported" }
        require(lot.sourceTransactionId == transaction.id && lot.fundId == transaction.fundId) {
            "Legacy lot must belong to its import transaction"
        }
        require(entry.fundId == transaction.fundId) { "Legacy lot ledger entry fund mismatch" }
        insertTransaction(transaction)
        insertLedgerEntry(entry)
        insertLegacyLot(lot)
    }

    @Transaction
    public open suspend fun recordRedemptionAtomically(
        transaction: LedgerTransactionEntity,
        expectedLots: List<LotEntity>,
        updatedLots: List<LotEntity>,
        entry: LedgerEntryEntity,
    ) {
        require(expectedLots.isNotEmpty()) { "A redemption needs at least one expected lot" }
        require(expectedLots.map { it.id }.toSet().size == expectedLots.size) { "Expected lot identities must be unique" }
        require(updatedLots.map { it.id }.toSet() == expectedLots.map { it.id }.toSet()) {
            "Updated lots must exactly match expected lots"
        }
        val existing = requireTransaction(transaction.id)
        assertConfirmationTransition(existing, transaction)
        require(transaction.type == "REDEMPTION") { "Real lot redemption requires a redemption transaction" }
        require(entry.fundId == transaction.fundId) { "Redemption ledger entry fund mismatch" }
        expectedLots.forEach { expected ->
            check(findLot(expected.id) == expected) { "Real lot projection changed before redemption: ${expected.id}" }
        }
        updatedLots.forEach { updated ->
            val expected = expectedLots.single { it.id == updated.id }
            assertOnlyProjectionChanged(expected, updated)
        }
        check(updateTransaction(transaction) == 1) { "Redemption transaction update failed" }
        insertLedgerEntry(entry)
        updatedLots.forEach { check(updateLot(it) == 1) { "Real lot update failed: ${it.id}" } }
    }

    @Transaction
    public open suspend fun materializeCalculatedRedemptionAtomically(
        transaction: LedgerTransactionEntity,
        expectedLots: List<LotEntity>,
        updatedLots: List<LotEntity>,
        entry: LedgerEntryEntity,
    ): Boolean {
        val existing = requireTransaction(transaction.id)
        if (existing.status != "PENDING_CALC" && existing.status != "ABNORMAL") return false
        require(expectedLots.isNotEmpty()) { "A redemption needs at least one expected lot" }
        require(expectedLots.map { it.id }.toSet().size == expectedLots.size) {
            "Expected lot identities must be unique"
        }
        require(updatedLots.map { it.id }.toSet() == expectedLots.map { it.id }.toSet()) {
            "Updated lots must exactly match expected lots"
        }
        assertConfirmationTransition(existing, transaction)
        require(transaction.type == "REDEMPTION" && transaction.status == "CALCULATED") {
            "Automatic redemption materialization requires calculated redemption facts"
        }
        require(entry.fundId == transaction.fundId) { "Redemption ledger entry fund mismatch" }
        expectedLots.forEach { expected ->
            check(findLot(expected.id) == expected) {
                "Real lot projection changed before redemption: ${expected.id}"
            }
        }
        updatedLots.forEach { updated ->
            val expected = expectedLots.single { it.id == updated.id }
            assertOnlyProjectionChanged(expected, updated)
        }
        val sequencedEntry = entry.copy(sequence = nextLedgerSequence())
        check(updateTransaction(transaction) == 1) { "Redemption transaction update failed" }
        insertLedgerEntry(sequencedEntry)
        updatedLots.forEach { check(updateLot(it) == 1) { "Real lot update failed: ${it.id}" } }
        return true
    }

    @Transaction
    public open suspend fun recordLegacyRedemptionAtomically(
        transaction: LedgerTransactionEntity,
        expectedLot: LegacyLotEntity,
        updatedLot: LegacyLotEntity,
        entry: LedgerEntryEntity,
    ) {
        val existing = requireTransaction(transaction.id)
        assertConfirmationTransition(existing, transaction)
        require(transaction.type == "REDEMPTION") { "Legacy lot redemption requires a redemption transaction" }
        check(findLegacyLot(expectedLot.id) == expectedLot) { "Legacy lot projection changed before redemption" }
        require(updatedLot.copy(
            remainingSharesDecimal = expectedLot.remainingSharesDecimal,
            costBasisDecimal = expectedLot.costBasisDecimal,
        ) == expectedLot) { "Only Legacy lot remaining shares and cost may change" }
        require(entry.fundId == transaction.fundId && updatedLot.fundId == transaction.fundId) {
            "Legacy redemption fund mismatch"
        }
        check(updateTransaction(transaction) == 1) { "Legacy redemption transaction update failed" }
        insertLedgerEntry(entry)
        check(updateLegacyLot(updatedLot) == 1) { "Legacy lot update failed" }
    }

    @Transaction
    public open suspend fun insertConfirmedLegacyRedemptionAtomically(
        transaction: LedgerTransactionEntity,
        expectedLot: LegacyLotEntity,
        updatedLot: LegacyLotEntity,
        entry: LedgerEntryEntity,
    ) {
        require(transaction.type == "REDEMPTION" && transaction.status == "CONFIRMED_ACTUAL") {
            "Confirmed Legacy redemption needs final redemption facts"
        }
        check(findLegacyLot(expectedLot.id) == expectedLot) { "Legacy lot changed before redemption" }
        require(updatedLot.copy(
            remainingSharesDecimal = expectedLot.remainingSharesDecimal,
            costBasisDecimal = expectedLot.costBasisDecimal,
        ) == expectedLot) { "Only Legacy lot remaining shares and cost may change" }
        require(entry.fundId == transaction.fundId && expectedLot.fundId == transaction.fundId) {
            "Legacy redemption fund mismatch"
        }
        val sequencedEntry = entry.copy(sequence = nextLedgerSequence())
        insertTransaction(transaction)
        insertLedgerEntry(sequencedEntry)
        check(updateLegacyLot(updatedLot) == 1) { "Legacy lot update failed" }
    }

    @Transaction
    public open suspend fun reverseAssumedPurchaseAtomically(
        transaction: LedgerTransactionEntity,
        expectedLot: LotEntity,
        entry: LedgerEntryEntity,
    ) {
        val existing = requireTransaction(transaction.id)
        assertSameIdentity(existing, transaction)
        require(existing.origin == "AUTO_PLAN_ASSUMED") { "Only an assumed purchase may be reversed" }
        require(existing.status == "CALCULATED" && transaction.status == "NOT_EXECUTED") {
            "Assumed purchase reversal must change CALCULATED to NOT_EXECUTED"
        }
        check(findLot(expectedLot.id) == expectedLot) { "Assumed purchase lot changed before reversal" }
        require(expectedLot.sourceTransactionId == transaction.id) { "Reversal lot transaction mismatch" }
        require(expectedLot.remainingSharesDecimal == expectedLot.originalSharesDecimal) {
            "An assumed purchase cannot be reversed after shares were consumed"
        }
        require(
            expectedLot.grossCashOutDecimal != null &&
                expectedLot.costBasisDecimal == expectedLot.grossCashOutDecimal,
        ) { "An assumed purchase cannot be reversed after cost changed" }
        require(entry.fundId == transaction.fundId) { "Reversal ledger entry fund mismatch" }
        check(updateTransaction(transaction) == 1) { "Assumed purchase transaction update failed" }
        insertLedgerEntry(entry)
        check(deleteLot(expectedLot) == 1) { "Assumed purchase lot removal failed" }
    }

    @Transaction
    public open suspend fun recordCorrectionAtomically(
        transaction: LedgerTransactionEntity,
        correction: TransactionCorrectionEntity,
    ) {
        val existing = requireTransaction(transaction.id)
        assertSameIdentity(existing, transaction)
        require(existing.type != "REDEMPTION" || existing.status != "CONFIRMED_ACTUAL") {
            "Final redemption facts require a dedicated reviewed correction; consumed shares and costs cannot be overwritten" }
        require(correction.field !in setOf("GROSS_AMOUNT", "NET_AMOUNT") &&
            transaction.grossAmountDecimal == existing.grossAmountDecimal && transaction.netAmountDecimal == existing.netAmountDecimal
        ) { "Gross/net amount changes require dedicated atomic Lot reconciliation" }
        if (findLotBySourceTransaction(transaction.id) != null) {
            require(transaction.unitNavDecimal == existing.unitNavDecimal && transaction.sharesDecimal == existing.sharesDecimal &&
                transaction.purchaseFeeDecimal == existing.purchaseFeeDecimal && transaction.confirmationDate == existing.confirmationDate &&
                transaction.navDate == existing.navDate
            ) { "Materialized purchase Lot facts require dedicated atomic reconciliation" }
        }
        require(correction.transactionId == transaction.id) { "Correction transaction mismatch" }
        require(transaction.isManualCorrected) { "Corrected transaction must carry its manual marker" }
        require(transaction.authority == "USER_OR_PLATFORM_FINAL") {
            "Corrected transaction must carry final authority"
        }
        require(!Instant.parse(transaction.updatedAt).isBefore(Instant.parse(existing.updatedAt))) {
            "Correction cannot move transaction time backwards"
        }
        check(updateTransaction(transaction) == 1) { "Corrected transaction update failed" }
        insertCorrection(correction)
    }

    @Transaction
    public open suspend fun confirmCalculatedRedemptionActualCashAtomically(
        expected: LedgerTransactionEntity,
        confirmed: LedgerTransactionEntity,
        cashAudit: TransactionCorrectionEntity,
        cashFlowDateAudit: TransactionCorrectionEntity,
    ): Boolean {
        val current = findTransaction(expected.id) ?: return false
        if (current != expected || current.type != "REDEMPTION" || current.status != "CALCULATED") return false
        if (current.actualCashFlowDecimal != null || current.cashFlowDate != null) return false
        if (current.sharesDecimal?.let(::BigDecimal)?.signum() != 1) return false
        val actualCash = confirmed.actualCashFlowDecimal?.let(::BigDecimal) ?: return false
        if (actualCash.signum() < 0 || confirmed.netAmountDecimal != confirmed.actualCashFlowDecimal) return false
        val moneyScale = findFund(current.fundId)?.moneyScale ?: return false
        try {
            actualCash.setScale(moneyScale, RoundingMode.UNNECESSARY)
        } catch (_: ArithmeticException) {
            return false
        }
        val cashFlowDate = confirmed.cashFlowDate?.let(LocalDate::parse) ?: return false
        if (cashFlowDate.isBefore(LocalDate.parse(current.tradeDate))) return false
        if (Instant.parse(confirmed.updatedAt).isBefore(Instant.parse(current.updatedAt))) return false
        if (confirmed != current.copy(
                status = "CONFIRMED_ACTUAL",
                authority = "USER_OR_PLATFORM_FINAL",
                cashFlowDate = cashFlowDate.toString(),
                netAmountDecimal = confirmed.actualCashFlowDecimal,
                actualCashFlowDecimal = confirmed.actualCashFlowDecimal,
                isManualCorrected = true,
                updatedAt = confirmed.updatedAt,
            )
        ) return false
        if (cashAudit.id == cashFlowDateAudit.id ||
            cashAudit.transactionId != current.id || cashFlowDateAudit.transactionId != current.id ||
            cashAudit.field != "ACTUAL_CASH_FLOW" || cashFlowDateAudit.field != "CASH_FLOW_DATE" ||
            cashAudit.originalValueType != "MISSING" || cashAudit.originalValue != null ||
            cashFlowDateAudit.originalValueType != "MISSING" || cashFlowDateAudit.originalValue != null ||
            cashAudit.correctedValueType != "DECIMAL" ||
            cashAudit.correctedValue?.let(::BigDecimal)?.compareTo(actualCash) != 0 ||
            cashFlowDateAudit.correctedValueType != "DATE" || cashFlowDateAudit.correctedValue != cashFlowDate.toString() ||
            cashAudit.source != "SALES_PLATFORM_FINAL" || cashFlowDateAudit.source != "SALES_PLATFORM_FINAL" ||
            cashAudit.correctedAt != confirmed.updatedAt || cashFlowDateAudit.correctedAt != confirmed.updatedAt
        ) return false

        check(updateTransaction(confirmed) == 1) { "Actual redemption cash update failed" }
        insertCorrection(cashAudit)
        insertCorrection(cashFlowDateAudit)
        return true
    }

    @Transaction
    public open suspend fun confirmMatchingCalculatedPurchaseActualCashAtomically(
        expected: LedgerTransactionEntity,
        expectedLot: LotEntity,
        confirmed: LedgerTransactionEntity,
        cashAudit: TransactionCorrectionEntity,
        paymentAudit: TransactionCorrectionEntity,
    ): Boolean {
        val current = findTransaction(expected.id) ?: return false
        if (current != expected || current.type !in setOf("PURCHASE", "RECURRING_PURCHASE") ||
            current.status != "CALCULATED" || current.authority != "APP_RULE_CALCULATION" ||
            current.actualCashFlowDecimal != null || current.cashFlowDate != null || current.settlementDate != null ||
            current.origin == "IMPORTED"
        ) return false
        val lot = findLotBySourceTransaction(current.id) ?: return false
        if (lot != expectedLot || lot.type != "REAL" || lot.fundId != current.fundId ||
            lot.confirmationDate != current.confirmationDate || current.navDate == null ||
            lot.originalSharesDecimal != current.sharesDecimal || lot.unitNavDecimal != current.unitNavDecimal ||
            lot.grossCashOutDecimal != current.grossAmountDecimal ||
            lot.netSubscriptionAmountDecimal != current.netAmountDecimal ||
            lot.purchaseFeeDecimal != current.purchaseFeeDecimal
        ) return false
        val gross = current.grossAmountDecimal?.let(::BigDecimal) ?: return false
        val net = current.netAmountDecimal?.let(::BigDecimal) ?: return false
        val fee = BigDecimal(current.purchaseFeeDecimal)
        val actualCash = confirmed.actualCashFlowDecimal?.let(::BigDecimal) ?: return false
        if (gross.signum() <= 0 || net.signum() <= 0 || fee.signum() < 0 ||
            net.add(fee).compareTo(gross) != 0 || actualCash.compareTo(gross.negate()) != 0 ||
            BigDecimal(current.sharesDecimal).signum() != 1
        ) return false
        val fund = findFund(current.fundId) ?: return false
        val moneyScale = fund.moneyScale ?: return false
        val shareScale = fund.shareScale ?: return false
        try {
            actualCash.setScale(moneyScale, RoundingMode.UNNECESSARY)
            BigDecimal(current.sharesDecimal).setScale(shareScale, RoundingMode.UNNECESSARY)
        } catch (_: ArithmeticException) {
            return false
        }
        val paymentDate = confirmed.cashFlowDate?.let(LocalDate::parse) ?: return false
        if (paymentDate.isAfter(LocalDate.parse(requireNotNull(current.confirmationDate))) ||
            Instant.parse(confirmed.updatedAt).isBefore(Instant.parse(current.updatedAt))
        ) return false
        if (confirmed != current.copy(
                status = "CONFIRMED_ACTUAL", authority = "USER_OR_PLATFORM_FINAL",
                actualCashFlowDecimal = confirmed.actualCashFlowDecimal,
                cashFlowDate = paymentDate.toString(), isManualCorrected = true, updatedAt = confirmed.updatedAt,
            )
        ) return false
        if (cashAudit.id == paymentAudit.id || cashAudit.transactionId != current.id ||
            paymentAudit.transactionId != current.id || cashAudit.field != "ACTUAL_CASH_FLOW" ||
            paymentAudit.field != "CASH_FLOW_DATE" ||
            cashAudit.originalValueType != "MISSING" || cashAudit.originalValue != null ||
            paymentAudit.originalValueType != "MISSING" || paymentAudit.originalValue != null ||
            cashAudit.correctedValueType != "DECIMAL" ||
            cashAudit.correctedValue?.let(::BigDecimal)?.compareTo(actualCash) != 0 ||
            paymentAudit.correctedValueType != "DATE" || paymentAudit.correctedValue != paymentDate.toString() ||
            cashAudit.source != "SALES_PLATFORM_FINAL" || paymentAudit.source != "SALES_PLATFORM_FINAL" ||
            cashAudit.correctedAt != confirmed.updatedAt || paymentAudit.correctedAt != confirmed.updatedAt
        ) return false
        check(updateTransaction(confirmed) == 1) { "Actual purchase cash update failed" }
        insertCorrection(cashAudit)
        insertCorrection(paymentAudit)
        return true
    }

    @Transaction
    public open suspend fun confirmUnconsumedPurchasePlatformSharesAtomically(
        expected: LedgerTransactionEntity,
        expectedLot: LotEntity,
        confirmed: LedgerTransactionEntity,
        correctedLot: LotEntity,
        entry: LedgerEntryEntity,
        audits: List<TransactionCorrectionEntity>,
    ): Boolean = confirmUnconsumedPurchasePlatformFacts(
        expected, expectedLot, confirmed, correctedLot, entry, audits, allowAmountChanges = false,
    )

    @Transaction
    public open suspend fun confirmUnconsumedPurchasePlatformAmountsAtomically(
        expected: LedgerTransactionEntity,
        expectedLot: LotEntity,
        confirmed: LedgerTransactionEntity,
        correctedLot: LotEntity,
        entry: LedgerEntryEntity,
        audits: List<TransactionCorrectionEntity>,
    ): Boolean = confirmUnconsumedPurchasePlatformFacts(
        expected, expectedLot, confirmed, correctedLot, entry, audits, allowAmountChanges = true,
    )

    @Transaction
    public open suspend fun confirmUnconsumedPurchasePlatformNavAtomically(
        expected: LedgerTransactionEntity,
        expectedLot: LotEntity,
        confirmed: LedgerTransactionEntity,
        correctedLot: LotEntity,
        officialNav: NavRecordEntity,
        entry: LedgerEntryEntity,
        audits: List<TransactionCorrectionEntity>,
    ): Boolean = confirmUnconsumedPurchasePlatformFacts(
        expected, expectedLot, confirmed, correctedLot, entry, audits, allowAmountChanges = true, officialNav = officialNav,
    )

    private suspend fun confirmUnconsumedPurchasePlatformFacts(
        expected: LedgerTransactionEntity,
        expectedLot: LotEntity,
        confirmed: LedgerTransactionEntity,
        correctedLot: LotEntity,
        entry: LedgerEntryEntity,
        audits: List<TransactionCorrectionEntity>,
        allowAmountChanges: Boolean,
        officialNav: NavRecordEntity? = null,
    ): Boolean {
        if (findTransaction(expected.id) != expected || findLotBySourceTransaction(expected.id) != expectedLot) return false
        if (expected.type !in setOf("PURCHASE", "RECURRING_PURCHASE") || expected.status != "CALCULATED" ||
            expected.authority != "APP_RULE_CALCULATION" || expected.origin == "IMPORTED" ||
            expected.actualCashFlowDecimal != null || expected.cashFlowDate != null || expected.settlementDate != null
        ) return false
        // Reservation is fund-wide. Block all unfinished redemptions rather than guess a future FIFO allocation.
        val fundTransactions = loadTransactions(expected.fundId)
        if (fundTransactions.any {
                it.type == "REDEMPTION" && it.status in setOf("PLANNED", "PENDING_CALC", "ABNORMAL")
            }
        ) return false
        if (expectedLot.type != "REAL" || expectedLot.fundId != expected.fundId || expectedLot.sourceTransactionId != expected.id ||
            expectedLot.originalSharesDecimal != expected.sharesDecimal || expectedLot.confirmationDate != expected.confirmationDate ||
            expectedLot.grossCashOutDecimal != expected.grossAmountDecimal || expectedLot.netSubscriptionAmountDecimal != expected.netAmountDecimal ||
            expectedLot.purchaseFeeDecimal != expected.purchaseFeeDecimal || expectedLot.unitNavDecimal != expected.unitNavDecimal ||
            BigDecimal(expectedLot.remainingSharesDecimal).compareTo(BigDecimal(expectedLot.originalSharesDecimal)) != 0 ||
            BigDecimal(expectedLot.costBasisDecimal).compareTo(BigDecimal(requireNotNull(expectedLot.grossCashOutDecimal))) != 0
        ) return false
        val shares = confirmed.sharesDecimal?.let(::BigDecimal) ?: return false
        val oldGross = expected.grossAmountDecimal?.let(::BigDecimal) ?: return false
        val oldNet = expected.netAmountDecimal?.let(::BigDecimal) ?: return false
        val oldFee = expected.purchaseFeeDecimal?.let(::BigDecimal) ?: return false
        if (oldGross.signum() <= 0 || oldNet.signum() <= 0 || oldFee.signum() < 0 || oldNet.add(oldFee).compareTo(oldGross) != 0) return false
        val gross = confirmed.grossAmountDecimal?.let(::BigDecimal) ?: return false
        val net = confirmed.netAmountDecimal?.let(::BigDecimal) ?: return false
        val fee = confirmed.purchaseFeeDecimal?.let(::BigDecimal) ?: return false
        val cash = confirmed.actualCashFlowDecimal?.let(::BigDecimal) ?: return false
        if (shares.signum() <= 0 || gross.signum() <= 0 || net.signum() <= 0 || fee.signum() < 0 ||
            net.add(fee).compareTo(gross) != 0 || cash.compareTo(gross.negate()) != 0
        ) return false
        val moneyChanged = gross.compareTo(oldGross) != 0 || net.compareTo(oldNet) != 0 || fee.compareTo(oldFee) != 0
        if (officialNav == null && moneyChanged != allowAmountChanges) return false
        if (officialNav != null) {
            if (officialNav.authority != "FUND_OFFICIAL" || officialNav.fundId != expected.fundId || officialNav.navDate != expected.navDate ||
                findNav(officialNav.fundId, officialNav.navDate) != officialNav || confirmed.unitNavDecimal == null || expected.unitNavDecimal == null ||
                BigDecimal(officialNav.unitNavDecimal).compareTo(BigDecimal(confirmed.unitNavDecimal)) != 0 ||
                BigDecimal(expected.unitNavDecimal).compareTo(BigDecimal(confirmed.unitNavDecimal)) == 0 ||
                Instant.parse(officialNav.fetchedAt).isAfter(Instant.parse(confirmed.updatedAt))
            ) return false
        }
        val fund = findFund(expected.fundId) ?: return false
        try {
            shares.setScale(fund.shareScale ?: return false, RoundingMode.UNNECESSARY)
            cash.setScale(fund.moneyScale ?: return false, RoundingMode.UNNECESSARY)
            net.setScale(fund.moneyScale, RoundingMode.UNNECESSARY)
            fee.setScale(fund.moneyScale, RoundingMode.UNNECESSARY)
            if (officialNav != null) BigDecimal(requireNotNull(confirmed.unitNavDecimal)).setScale(fund.navScale ?: return false, RoundingMode.UNNECESSARY)
        } catch (_: ArithmeticException) { return false }
        val confirmationDate = confirmed.confirmationDate?.let(LocalDate::parse) ?: return false
        val paymentDate = confirmed.cashFlowDate?.let(LocalDate::parse) ?: return false
        val firstAffectedDate = minOf(confirmationDate, LocalDate.parse(expectedLot.confirmationDate))
        // Even a different Lot's later sale can depend on FIFO ordering. Historical allocations are not silently rewritten.
        if (fundTransactions.any {
                it.type == "REDEMPTION" && it.status in setOf("CALCULATED", "CONFIRMED_ACTUAL") &&
                    !LocalDate.parse(it.tradeDate).isBefore(firstAffectedDate)
            }
        ) return false
        if (expected.navDate == null || confirmationDate.isBefore(LocalDate.parse(expected.navDate)) ||
            confirmationDate.isBefore(LocalDate.parse(expected.tradeDate)) || paymentDate.isAfter(confirmationDate) ||
            Instant.parse(confirmed.updatedAt).isBefore(Instant.parse(expected.updatedAt))
        ) return false
        if (confirmed != expected.copy(
                sharesDecimal = confirmed.sharesDecimal, confirmationDate = confirmed.confirmationDate,
                actualCashFlowDecimal = confirmed.actualCashFlowDecimal, cashFlowDate = confirmed.cashFlowDate,
                grossAmountDecimal = if (allowAmountChanges) confirmed.grossAmountDecimal else expected.grossAmountDecimal,
                netAmountDecimal = if (allowAmountChanges) confirmed.netAmountDecimal else expected.netAmountDecimal,
                purchaseFeeDecimal = if (allowAmountChanges) confirmed.purchaseFeeDecimal else expected.purchaseFeeDecimal,
                unitNavDecimal = if (officialNav != null) confirmed.unitNavDecimal else expected.unitNavDecimal,
                status = "CONFIRMED_ACTUAL", authority = "USER_OR_PLATFORM_FINAL", isManualCorrected = true, updatedAt = confirmed.updatedAt,
            ) || correctedLot != expectedLot.copy(
                originalSharesDecimal = confirmed.sharesDecimal, remainingSharesDecimal = confirmed.sharesDecimal,
                confirmationDate = confirmed.confirmationDate,
                grossCashOutDecimal = if (allowAmountChanges) confirmed.grossAmountDecimal else expectedLot.grossCashOutDecimal,
                netSubscriptionAmountDecimal = if (allowAmountChanges) confirmed.netAmountDecimal else expectedLot.netSubscriptionAmountDecimal,
                purchaseFeeDecimal = if (allowAmountChanges) confirmed.purchaseFeeDecimal else expectedLot.purchaseFeeDecimal,
                costBasisDecimal = if (allowAmountChanges) confirmed.grossAmountDecimal else expectedLot.costBasisDecimal,
                unitNavDecimal = if (officialNav != null) requireNotNull(confirmed.unitNavDecimal) else expectedLot.unitNavDecimal,
            )
        ) return false
        val candidateChanges = linkedMapOf(
            "SHARES" to Triple("DECIMAL", expected.sharesDecimal, confirmed.sharesDecimal),
            "CONFIRMATION_DATE" to Triple("DATE", expected.confirmationDate, confirmed.confirmationDate),
            "ACTUAL_CASH_FLOW" to Triple("DECIMAL", null, confirmed.actualCashFlowDecimal),
            "CASH_FLOW_DATE" to Triple("DATE", null, confirmed.cashFlowDate),
        )
        if (allowAmountChanges) {
            candidateChanges["GROSS_AMOUNT"] = Triple("DECIMAL", expected.grossAmountDecimal, confirmed.grossAmountDecimal)
            candidateChanges["NET_AMOUNT"] = Triple("DECIMAL", expected.netAmountDecimal, confirmed.netAmountDecimal)
            candidateChanges["PURCHASE_FEE"] = Triple("DECIMAL", expected.purchaseFeeDecimal, confirmed.purchaseFeeDecimal)
        }
        if (officialNav != null) candidateChanges["UNIT_NAV"] = Triple("DECIMAL", expected.unitNavDecimal, requireNotNull(confirmed.unitNavDecimal))
        val changes = candidateChanges.filterValues { (type, old, new) ->
            if (type == "DECIMAL" && old != null) BigDecimal(old).compareTo(BigDecimal(new)) != 0 else old != new
        }
        if ((!allowAmountChanges && changes.keys.none { it in setOf("SHARES", "CONFIRMATION_DATE") }) ||
            audits.size != changes.size || audits.map { it.field }.toSet() != changes.keys ||
            audits.map { it.id }.distinct().size != audits.size
        ) return false
        if (audits.any { audit ->
                val (type, old, new) = changes.getValue(audit.field)
                audit.transactionId != expected.id || audit.source != "SALES_PLATFORM_FINAL" || audit.correctedAt != confirmed.updatedAt ||
                    audit.originalValueType != (if (old == null) "MISSING" else type) || audit.originalValue != old ||
                    audit.correctedValueType != type || audit.correctedValue != new
            }
        ) return false
        val requiredEntryType = when {
            officialNav != null -> "UNCONSUMED_PURCHASE_NAV_CORRECTED"
            allowAmountChanges -> "UNCONSUMED_PURCHASE_AMOUNTS_CORRECTED"
            else -> "UNCONSUMED_PURCHASE_LOT_CORRECTED"
        }
        if (entry.fundId != expected.fundId || entry.entryType != requiredEntryType) return false
        check(updateTransaction(confirmed) == 1)
        check(updateLot(correctedLot) == 1)
        audits.forEach { insertCorrection(it) }
        insertLedgerEntry(entry.copy(sequence = nextLedgerSequence()))
        return true
    }

    private fun authorityPriority(authority: String): Int = when (authority) {
        "TYPE_DEFAULT" -> 0
        "APP_RULE_CALCULATION" -> 1
        "RELIABLE_AGGREGATOR" -> 2
        "FUND_OFFICIAL" -> 3
        "USER_OR_PLATFORM_FINAL" -> 4
        else -> error("Unknown data authority: $authority")
    }

    private suspend fun requireTransaction(id: String): LedgerTransactionEntity =
        findTransaction(id) ?: error("Transaction does not exist: $id")

    private fun assertConfirmationTransition(
        existing: LedgerTransactionEntity,
        incoming: LedgerTransactionEntity,
    ) {
        assertSameIdentity(existing, incoming)
        require(existing.status == "PENDING_CALC" || existing.status == "ABNORMAL") {
            "Only pending or abnormal transactions may be confirmed"
        }
        require(incoming.status == "CALCULATED" || incoming.status == "CONFIRMED_ACTUAL") {
            "Confirmation must produce calculated or actual facts"
        }
        require(!Instant.parse(incoming.updatedAt).isBefore(Instant.parse(existing.updatedAt))) {
            "Confirmation cannot move transaction time backwards"
        }
    }

    private fun assertSameIdentity(
        existing: LedgerTransactionEntity,
        incoming: LedgerTransactionEntity,
    ) {
        require(existing.id == incoming.id) { "Transaction identity cannot change" }
        require(existing.fundId == incoming.fundId) { "Transaction fund cannot change" }
        require(existing.type == incoming.type) { "Transaction type cannot change" }
        require(existing.origin == incoming.origin) { "Transaction origin cannot change" }
        require(existing.applicationTime == incoming.applicationTime) { "Application time cannot change" }
        require(existing.tradeDate == incoming.tradeDate) { "Trade date cannot change" }
        require(existing.ruleVersionId == incoming.ruleVersionId) { "Rule version identity cannot change" }
        require(existing.createdAt == incoming.createdAt) { "Transaction creation time cannot change" }
    }

    private fun assertOnlyProjectionChanged(expected: LotEntity, updated: LotEntity) {
        require(updated.copy(
            remainingSharesDecimal = expected.remainingSharesDecimal,
            costBasisDecimal = expected.costBasisDecimal,
        ) == expected) { "Only lot remaining shares and cost may change" }
    }
}
