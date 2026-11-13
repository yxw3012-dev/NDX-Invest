package com.ndxinvest.ledger.persistence

import com.ndxinvest.database.LedgerDao
import com.ndxinvest.ledger.calculation.AssumedPurchaseReversed
import com.ndxinvest.ledger.calculation.LedgerEntry
import com.ndxinvest.ledger.calculation.LegacyLotOpened
import com.ndxinvest.ledger.calculation.LegacyLotRedeemed
import com.ndxinvest.ledger.calculation.LotConsumption
import com.ndxinvest.ledger.calculation.LotsRedeemed
import com.ndxinvest.ledger.calculation.RealLotOpened
import com.ndxinvest.ledger.model.CorrectedTransaction
import com.ndxinvest.ledger.model.CorrectionId
import com.ndxinvest.ledger.model.Fund
import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.FundRuleVersion
import com.ndxinvest.ledger.model.LedgerTransaction
import com.ndxinvest.ledger.model.LegacyLot
import com.ndxinvest.ledger.model.LedgerEntryId
import com.ndxinvest.ledger.model.Lot
import com.ndxinvest.ledger.model.LotId
import com.ndxinvest.ledger.model.NavRecord
import com.ndxinvest.ledger.model.RecurringExecutionKey
import com.ndxinvest.ledger.model.RecurringExecutionNotification
import com.ndxinvest.ledger.model.RecurringPlan
import com.ndxinvest.ledger.model.RecurringPlanId
import com.ndxinvest.ledger.model.RuleVersionId
import com.ndxinvest.ledger.model.TransactionCorrection
import com.ndxinvest.ledger.model.TransactionId
import com.ndxinvest.ledger.model.TransactionOrigin
import com.ndxinvest.ledger.model.TransactionStatus
import com.ndxinvest.ledger.model.TransactionType
import com.ndxinvest.ledger.repository.LedgerRepository
import java.math.BigDecimal
import java.time.LocalDate
import com.ndxinvest.ledger.calculation.LedgerRebuilder
import com.ndxinvest.ledger.rules.PurchasePlatformShareConfirmation
import com.ndxinvest.ledger.rules.PurchasePlatformAmountConfirmation
import com.ndxinvest.ledger.rules.PurchasePlatformNavConfirmation
import com.ndxinvest.ledger.calculation.LedgerHistorySnapshot
import com.ndxinvest.ledger.calculation.LedgerHistoryInspection
import com.ndxinvest.ledger.calculation.LedgerHistoryInspector
import com.ndxinvest.ledger.calculation.HistoryIssue
import com.ndxinvest.ledger.calculation.HistoryIssueReason

public class RoomLedgerRepository public constructor(
    private val dao: LedgerDao,
    private val clock: java.time.Clock = java.time.Clock.systemDefaultZone(),
) : LedgerRepository {
    override suspend fun previewConsumedPurchaseShareImpact(fundId: FundId, purchaseId: TransactionId,
        facts: com.ndxinvest.ledger.rules.ConsumedPurchaseShareFacts): com.ndxinvest.ledger.rules.ConsumedPurchaseShareImpactPreview {
        val snapshot = dao.loadFundHistorySnapshot(fundId.value)
        val fund = requireNotNull(snapshot.fund) { "账本中未找到基金" }.toDomain()
        val rows = snapshot.history
        val history = LedgerHistorySnapshot(fundId, rows.transactions.map { it.toDomain() }, rows.realLots.map { it.toDomain() },
            rows.legacyLots.map { it.toDomain() }, rows.entries.map(LedgerEntryCodec::decode))
        val purchase = requireNotNull(history.transactions.singleOrNull { it.id == purchaseId }) { "原买入不属于本基金" }
        val now = clock.instant(); val today = now.atZone(clock.zone).toLocalDate()
        val preview = com.ndxinvest.ledger.rules.ConsumedPurchaseShareImpactPreview.prepare(fund, history, purchase, facts, now, today)
        preview.validate(now, today)
        return preview
    }
    override suspend fun correctConsumedPurchaseShares(correction: com.ndxinvest.ledger.rules.ConsumedPurchaseShareCorrection): Boolean {
        val now = clock.instant(); val today = LocalDate.ofInstant(now, clock.zone)
        correction.validate(now, today)
        val rows = dao.loadHistorySnapshot(correction.preview.fund.id.value)
        return dao.correctConsumedPurchaseSharesAtomically(correction, rows, now, today)
    }

    override suspend fun correctConsumedPurchaseCost(correction: com.ndxinvest.ledger.rules.ConsumedPurchaseCostCorrection): Boolean {
        val now = clock.instant(); val today = LocalDate.now(clock)
        correction.validate(now, today)
        val rows = dao.loadHistorySnapshot(correction.preview.fund.id.value)
        // The DAO re-decodes and compares this snapshot atomically before saving or acknowledging an exact retry.
        return dao.correctConsumedPurchaseCostAtomically(correction, rows, now, today)
    }

    override suspend fun previewConsumedPurchaseCostImpact(fundId: FundId, purchaseId: TransactionId,
        facts: com.ndxinvest.ledger.rules.ConsumedPurchaseCostFacts): com.ndxinvest.ledger.rules.ConsumedPurchaseCostImpactPreview {
        val snapshot = dao.loadFundHistorySnapshot(fundId.value)
        val fund = requireNotNull(snapshot.fund) { "账本中未找到基金" }.toDomain()
        val rows = snapshot.history
        val history = LedgerHistorySnapshot(fundId, rows.transactions.map { it.toDomain() }, rows.realLots.map { it.toDomain() },
            rows.legacyLots.map { it.toDomain() }, rows.entries.map(LedgerEntryCodec::decode))
        val purchase = requireNotNull(history.transactions.singleOrNull { it.id == purchaseId }) { "原买入不属于本基金" }
        val preview = com.ndxinvest.ledger.rules.ConsumedPurchaseCostImpactPreview.prepare(fund, history, purchase, facts,
            clock.instant(), LocalDate.now(clock))
        preview.validate(clock.instant(), LocalDate.now(clock))
        return preview
    }

    override suspend fun correctFinalRedemptionReceipt(correction: com.ndxinvest.ledger.rules.FinalRedemptionReceiptCorrection): Boolean {
        correction.validate(clock.instant(), LocalDate.now(clock))
        val rows = dao.loadHistorySnapshot(correction.fund.id.value)
        val current = LedgerHistorySnapshot(correction.fund.id, rows.transactions.map { it.toDomain() }, rows.realLots.map { it.toDomain() },
            rows.legacyLots.map { it.toDomain() }, rows.entries.map(LedgerEntryCodec::decode))
        val confirmed = correction.confirmed.toEntity(); val audits = correction.audits.map { it.toEntity() }
        if (current != correction.history) return dao.wasFinalRedemptionReceiptCorrectedAtomically(confirmed, audits)
        return dao.correctFinalRedemptionReceiptAtomically(correction.fund.toEntity(), rows, correction.expected.toEntity(), confirmed, audits)
    }

    override suspend fun recordReviewedAmountRedemption(review: com.ndxinvest.ledger.calculation.ReviewedAmountRedemption): Boolean {
        review.validate(clock.instant(), clock.zone)
        val rows = dao.loadHistorySnapshot(review.fund.id.value)
        val current = LedgerHistorySnapshot(review.fund.id, rows.transactions.map { it.toDomain() }, rows.realLots.map { it.toDomain() },
            rows.legacyLots.map { it.toDomain() }, rows.entries.map(LedgerEntryCodec::decode))
        if (current != review.history) return false
        return dao.recordReviewedAmountRedemptionAtomically(review.fund.toEntity(), rows, review.rules.map { it.toEntity() },
            review.transaction.toEntity(), review.expected?.toEntity(), review.calendar?.coverageStart?.toString(),
            review.calendar?.coverageEnd?.toString(), review.calendar?.openDates?.sorted()?.map { it.toString() }, review.shareAudit?.toEntity())
    }

    override suspend fun recordReviewedLegacy(review: com.ndxinvest.ledger.calculation.ReviewedLegacy): Boolean {
        review.validate(clock.instant(), clock.zone)
        val rows = dao.loadHistorySnapshot(review.fund.id.value)
        val current = LedgerHistorySnapshot(review.fund.id, rows.transactions.map { it.toDomain() }, rows.realLots.map { it.toDomain() },
            rows.legacyLots.map { it.toDomain() }, rows.entries.map(LedgerEntryCodec::decode))
        if (current != review.history) return false
        val original = review.expectedLot?.let { lot -> rows.legacyLots.singleOrNull { it.id == lot.id.value } ?: return false }
        val sourceId = original?.sourceTransactionId?.let(::TransactionId) ?: review.transaction.id
        return dao.recordReviewedLegacyAtomically(review.fund.toEntity(), rows, review.rules.map { it.toEntity() },
            review.transaction.toEntity(), review.expectedLot?.toEntity(sourceId), review.updatedLot.toEntity(sourceId), LedgerEntryCodec.encode(review.entry))
    }

    override suspend fun recordReviewedDividend(review: com.ndxinvest.ledger.calculation.ReviewedDividend): Boolean {
        review.validate(clock.instant(), LocalDate.now(clock))
        val rows = dao.loadHistorySnapshot(review.fund.id.value)
        val current = LedgerHistorySnapshot(review.fund.id, rows.transactions.map { it.toDomain() }, rows.realLots.map { it.toDomain() },
            rows.legacyLots.map { it.toDomain() }, rows.entries.map(LedgerEntryCodec::decode))
        if (current != review.history) return false
        return dao.recordReviewedDividendAtomically(review.fund.toEntity(), rows, review.rules.map { it.toEntity() },
            review.nav?.toEntity(), review.transaction.toEntity(), review.lot?.toEntity(), review.entry?.let(LedgerEntryCodec::encode))
    }

    override suspend fun establishTrackingBaseline(baseline: com.ndxinvest.ledger.calculation.TrackingBaseline): Boolean {
        require(!baseline.recordedAt.isAfter(clock.instant())) { "Baseline cannot be recorded in the future" }
        require(baseline == com.ndxinvest.ledger.calculation.TrackingBaseline.prepare(baseline.history, baseline.officialNav,
            baseline.precision, baseline.recordedAt, baseline.recordedAt.atZone(clock.zone).toLocalDate())) {
            "Baseline facts or starting valuation were altered"
        }
        val entity = TrackingBaselineCodec.encode(baseline)
        val existing = dao.findTrackingBaseline(baseline.fundId.value)
        if (existing != null) return existing == entity
        val fund = dao.findFund(baseline.fundId.value) ?: return false
        require(fund.toDomain().precision == baseline.precision) { "Baseline must use saved fund precision" }
        val rows = dao.loadHistorySnapshot(baseline.fundId.value)
        val current = LedgerHistorySnapshot(baseline.fundId, rows.transactions.map { it.toDomain() }, rows.realLots.map { it.toDomain() },
            rows.legacyLots.map { it.toDomain() }, rows.entries.map(LedgerEntryCodec::decode))
        if (current != baseline.history) return false
        return dao.establishTrackingBaselineAtomically(entity, rows, baseline.officialNav.toEntity(), fund)
    }

    override suspend fun findTrackingBaseline(fundId: FundId): com.ndxinvest.ledger.calculation.TrackingBaseline? =
        dao.findTrackingBaseline(fundId.value)?.let(TrackingBaselineCodec::decode)

    override suspend fun loadHistorySnapshot(fundId: FundId): LedgerHistorySnapshot {
        val rows = dao.loadHistorySnapshot(fundId.value)
        return LedgerHistorySnapshot(fundId, rows.transactions.map { it.toDomain() }, rows.realLots.map { it.toDomain() },
            rows.legacyLots.map { it.toDomain() }, rows.entries.map(LedgerEntryCodec::decode))
    }

    override suspend fun inspectHistory(fundId: FundId): LedgerHistoryInspection {
        val rows = dao.loadHistorySnapshot(fundId.value)
        val snapshot = try {
            LedgerHistorySnapshot(fundId, rows.transactions.map { it.toDomain() }, rows.realLots.map { it.toDomain() },
                rows.legacyLots.map { it.toDomain() }, rows.entries.map(LedgerEntryCodec::decode))
        } catch (_: IllegalArgumentException) {
            return LedgerHistoryInspection.NeedsReview(listOf(HistoryIssue(HistoryIssueReason.INVALID_EVENT_HISTORY)))
        } catch (_: IllegalStateException) {
            return LedgerHistoryInspection.NeedsReview(listOf(HistoryIssue(HistoryIssueReason.INVALID_EVENT_HISTORY)))
        }
        return LedgerHistoryInspector.inspect(snapshot)
    }
    override suspend fun saveFund(fund: Fund) {
        dao.upsertFund(fund.toEntity())
    }

    override suspend fun findFund(fundId: FundId): Fund? = dao.findFund(fundId.value)?.toDomain()

    override suspend fun loadFunds(): List<Fund> = dao.loadFunds().map { it.toDomain() }

    override suspend fun saveRuleVersion(ruleVersion: FundRuleVersion) {
        dao.insertRuleVersion(ruleVersion.toEntity())
    }

    override suspend fun findRuleVersion(ruleVersionId: RuleVersionId): FundRuleVersion? =
        dao.findRuleVersion(ruleVersionId.value)?.toDomain()

    override suspend fun loadRuleVersions(fundId: FundId): List<FundRuleVersion> =
        dao.loadRuleVersions(fundId.value).map { it.toDomain() }

    override suspend fun saveNav(nav: NavRecord) {
        dao.upsertNavRespectingAuthority(nav.toEntity())
    }

    override suspend fun findNav(fundId: FundId, navDate: LocalDate): NavRecord? =
        dao.findNav(fundId.value, navDate.toString())?.toDomain()

    override suspend fun findLatestNav(fundId: FundId): NavRecord? =
        dao.findLatestNav(fundId.value)?.toDomain()

    override suspend fun saveRecurringPlan(plan: RecurringPlan) {
        dao.upsertRecurringPlan(plan.toEntity())
    }

    override suspend fun findRecurringPlan(planId: RecurringPlanId): RecurringPlan? =
        dao.findRecurringPlan(planId.value)?.toDomain()

    override suspend fun loadEnabledRecurringPlans(): List<RecurringPlan> =
        dao.loadEnabledRecurringPlans().map { it.toDomain() }

    override suspend fun createTransaction(transaction: LedgerTransaction) {
        dao.insertTransaction(transaction.toEntity())
    }

    override suspend fun createPendingRedemptionIfAvailable(transaction: LedgerTransaction): Boolean {
        require(transaction.type == TransactionType.REDEMPTION) {
            "Pending share reservation requires redemption semantics"
        }
        require(transaction.status == TransactionStatus.PENDING_CALC) {
            "Pending share reservation requires PENDING_CALC status"
        }
        require(transaction.shares?.signum() == 1) {
            "Pending share reservation requires explicit positive shares"
        }
        return dao.insertPendingRedemptionIfAvailable(transaction.toEntity())
    }

    override suspend fun activatePlannedAmountRedemptionIfAvailable(
        transactionId: TransactionId,
        confirmedShares: BigDecimal,
        updatedAt: java.time.Instant,
    ): Boolean {
        require(confirmedShares.signum() > 0) { "Platform-confirmed redemption shares must be positive" }
        return dao.activatePlannedAmountRedemptionIfAvailable(
            transactionId.value,
            confirmedShares.toPlainString(),
            updatedAt.toString(),
        )
    }

    override suspend fun confirmPurchase(
        transaction: LedgerTransaction,
        lot: Lot,
        entry: RealLotOpened,
    ) {
        require(transaction.type in setOf(TransactionType.PURCHASE, TransactionType.RECURRING_PURCHASE)) {
            "Purchase confirmation requires purchase semantics"
        }
        require(entry.lot == lot) { "Purchase ledger event and lot projection differ" }
        dao.confirmPurchaseAtomically(transaction.toEntity(), lot.toEntity(), LedgerEntryCodec.encode(entry))
    }

    override suspend fun confirmDividendReinvestment(
        transaction: LedgerTransaction,
        lot: Lot,
        entry: RealLotOpened,
    ) {
        require(transaction.type == TransactionType.DIVIDEND_REINVESTMENT) {
            "Dividend reinvestment confirmation requires reinvestment semantics"
        }
        require(entry.lot == lot) { "Reinvestment ledger event and lot projection differ" }
        dao.confirmPurchaseAtomically(transaction.toEntity(), lot.toEntity(), LedgerEntryCodec.encode(entry))
    }

    override suspend fun recordConfirmedDividendReinvestment(
        transaction: LedgerTransaction,
        lot: Lot,
        entry: RealLotOpened,
    ) {
        require(transaction.type == TransactionType.DIVIDEND_REINVESTMENT) {
            "Confirmed reinvestment requires reinvestment semantics"
        }
        require(transaction.status == TransactionStatus.CONFIRMED_ACTUAL) {
            "Confirmed reinvestment requires final status"
        }
        require(transaction.actualCashFlow == null) { "Reinvestment is not an external cash flow" }
        require(transaction.fundId == lot.fundId && lot.sourceTransactionId == transaction.id) {
            "Reinvestment lot must belong to its transaction"
        }
        require(entry.lot == lot) { "Reinvestment ledger event and lot projection differ" }
        require(lot.grossCashOut.signum() == 0 && lot.purchaseFee.signum() == 0) {
            "Reinvestment must not add external cash or purchase fees"
        }
        require(transaction.grossAmount?.compareTo(lot.costBasis) == 0) {
            "Reinvestment amount and new Lot cost must agree"
        }
        require(transaction.shares?.compareTo(lot.originalShares) == 0) {
            "Reinvestment shares and new Lot shares must agree"
        }
        require(lot.netSubscriptionAmount.compareTo(lot.costBasis) == 0) {
            "Reinvestment net amount and Lot cost must agree"
        }
        require(transaction.unitNav?.compareTo(lot.unitNav) == 0) {
            "Reinvestment NAV and new Lot NAV must agree"
        }
        require(transaction.confirmationDate == lot.confirmationDate) {
            "Reinvestment confirmation date and new Lot date must agree"
        }
        dao.recordConfirmedReinvestmentAtomically(
            transaction.toEntity(),
            lot.toEntity(),
            LedgerEntryCodec.encode(entry),
        )
    }

    override suspend fun scheduleRecurringPurchase(
        transaction: LedgerTransaction,
        executionKey: RecurringExecutionKey,
    ) {
        require(transaction.type == TransactionType.RECURRING_PURCHASE) { "Recurring schedule requires a recurring purchase" }
        require(transaction.origin == TransactionOrigin.AUTO_PLAN_ASSUMED) {
            "Recurring schedule must retain assumed origin"
        }
        dao.insertRecurringExecutionAtomically(
            transaction.toEntity(),
            executionKey.toEntity(transaction.id),
        )
    }

    override suspend fun materializeRecurringPurchase(
        updatedPlan: RecurringPlan,
        transaction: LedgerTransaction,
        executionKey: RecurringExecutionKey,
    ): Boolean {
        require(transaction.type == TransactionType.RECURRING_PURCHASE) {
            "Recurring materialization requires a recurring purchase"
        }
        require(transaction.origin == TransactionOrigin.AUTO_PLAN_ASSUMED) {
            "Recurring materialization must retain assumed origin"
        }
        return dao.materializeRecurringExecutionAtomically(
            updatedPlan.toEntity(),
            transaction.toEntity(),
            executionKey.toEntity(transaction.id),
        )
    }

    override suspend fun loadPendingRecurringExecutionNotifications(): List<RecurringExecutionNotification> =
        dao.loadPendingRecurringExecutionNotifications().map { row ->
            RecurringExecutionNotification(
                id = recurringNoticeId(row.planId, row.scheduledTradeDate),
                planId = RecurringPlanId(row.planId),
                transactionId = TransactionId(row.transactionId),
                scheduledTradeDate = LocalDate.parse(row.scheduledTradeDate),
                fundId = FundId(row.fundId),
                amount = BigDecimal(row.amountDecimal),
                deliveredAt = row.deliveredAt?.let(java.time.Instant::parse),
            )
        }

    override suspend fun markRecurringExecutionNotificationDelivered(
        notification: RecurringExecutionNotification,
        deliveredAt: java.time.Instant,
    ) {
        dao.markRecurringExecutionNotificationDelivered(
            notification.planId.value,
            notification.scheduledTradeDate.toString(),
            deliveredAt.toString(),
        )
    }

    override suspend fun confirmRedemption(
        transaction: LedgerTransaction,
        updatedLots: List<Lot>,
        entry: LotsRedeemed,
    ) {
        require(transaction.type == TransactionType.REDEMPTION) { "Redemption confirmation requires redemption semantics" }
        require(entry.fundId == transaction.fundId && (entry.sourceTransactionId == null || entry.sourceTransactionId == transaction.id)) {
            "Redemption event must reference its own fund and transaction"
        }
        require(updatedLots.map { it.id }.distinct().size == updatedLots.size) { "Updated lot identities must be unique" }
        val expected = updatedLots.map { lot ->
            dao.findLot(lot.id.value)?.toDomain() ?: error("Real lot does not exist: ${lot.id.value}")
        }
        require(expected.all { it.fundId == transaction.fundId } &&
            transaction.shares?.sameAs(entry.consumptions.fold(BigDecimal.ZERO) { total, value -> total.add(value.shares) }) == true) {
            "Redemption transaction and consumed fund/shares differ"
        }
        validateRealRedemptionEvent(expected, updatedLots, entry)
        dao.recordRedemptionAtomically(
            transaction.toEntity(),
            expected.map { it.toEntity() },
            updatedLots.map { it.toEntity() },
            LedgerEntryCodec.encode(entry.copy(sourceTransactionId = transaction.id)),
        )
    }

    override suspend fun recordLegacyLot(
        transaction: LedgerTransaction,
        lot: LegacyLot,
        entry: LegacyLotOpened,
    ) {
        require(transaction.origin == TransactionOrigin.IMPORTED) { "Legacy lot must be an imported starting fact" }
        require(entry.lot == lot) { "Legacy ledger event and projection differ" }
        dao.insertLegacyLotAtomically(
            transaction.toEntity(),
            lot.toEntity(transaction.id),
            LedgerEntryCodec.encode(entry),
        )
    }

    override suspend fun confirmLegacyRedemption(
        transaction: LedgerTransaction,
        updatedLot: LegacyLot,
        entry: LegacyLotRedeemed,
    ) {
        require(transaction.type == TransactionType.REDEMPTION) { "Legacy redemption requires redemption semantics" }
        require(entry.fundId == transaction.fundId && (entry.sourceTransactionId == null || entry.sourceTransactionId == transaction.id)) {
            "Legacy redemption event must reference its own fund and transaction"
        }
        val expectedEntity = dao.findLegacyLot(updatedLot.id.value)
            ?: error("Legacy lot does not exist: ${updatedLot.id.value}")
        val expected = expectedEntity.toDomain()
        require(expected.fundId == transaction.fundId && transaction.shares?.sameAs(entry.consumption.shares) == true) {
            "Legacy redemption transaction and consumed fund/shares differ"
        }
        require(entry.consumption.lotId == expected.id) { "Legacy redemption event references another lot" }
        require(expected.remainingShares.subtract(updatedLot.remainingShares).sameAs(entry.consumption.shares)) {
            "Legacy redemption share projection and event differ"
        }
        require(expected.costBasis.subtract(updatedLot.costBasis).sameAs(entry.consumption.allocatedCostBasis)) {
            "Legacy redemption cost projection and event differ"
        }
        dao.recordLegacyRedemptionAtomically(
            transaction.toEntity(),
            expectedEntity,
            updatedLot.toEntity(sourceTransactionId = TransactionId(expectedEntity.sourceTransactionId)),
            LedgerEntryCodec.encode(entry.copy(sourceTransactionId = transaction.id)),
        )
    }

    override suspend fun recordConfirmedLegacyRedemption(
        transaction: LedgerTransaction,
        expectedLot: LegacyLot,
        updatedLot: LegacyLot,
        entry: LegacyLotRedeemed,
    ) {
        require(transaction.type == TransactionType.REDEMPTION && transaction.status == TransactionStatus.CONFIRMED_ACTUAL) {
            "Confirmed Legacy redemption requires final redemption semantics"
        }
        require(entry.sourceTransactionId == null || entry.sourceTransactionId == transaction.id) {
            "Confirmed Legacy redemption event references another transaction"
        }
        require(transaction.fundId == expectedLot.fundId && updatedLot.fundId == expectedLot.fundId) {
            "Legacy redemption fund mismatch"
        }
        require(transaction.shares?.sameAs(entry.consumption.shares) == true) {
            "Legacy redemption transaction and event shares differ"
        }
        require(entry.consumption.lotId == expectedLot.id && entry.fundId == expectedLot.fundId) {
            "Legacy redemption event references another lot"
        }
        require(expectedLot.remainingShares.subtract(updatedLot.remainingShares).sameAs(entry.consumption.shares)) {
            "Legacy redemption share projection and event differ"
        }
        require(expectedLot.costBasis.subtract(updatedLot.costBasis).sameAs(entry.consumption.allocatedCostBasis)) {
            "Legacy redemption cost projection and event differ"
        }
        val actualCash = transaction.actualCashFlow
        require(actualCash != null && actualCash.signum() >= 0 && transaction.netAmount?.sameAs(actualCash) == true) {
            "Legacy redemption needs actual platform cash received"
        }
        val currentEntity = dao.findLegacyLot(expectedLot.id.value)
            ?: error("Legacy lot does not exist: ${expectedLot.id.value}")
        dao.insertConfirmedLegacyRedemptionAtomically(
            transaction.toEntity(),
            expectedLot.toEntity(sourceTransactionId = TransactionId(currentEntity.sourceTransactionId)),
            updatedLot.toEntity(sourceTransactionId = TransactionId(currentEntity.sourceTransactionId)),
            LedgerEntryCodec.encode(entry.copy(sourceTransactionId = transaction.id)),
        )
    }

    override suspend fun findTransaction(transactionId: TransactionId): LedgerTransaction? =
        dao.findTransaction(transactionId.value)?.toDomain()

    override suspend fun loadTransactions(fundId: FundId): List<LedgerTransaction> =
        dao.loadTransactions(fundId.value).map { it.toDomain() }

    override suspend fun loadPendingPurchaseTransactions(fundId: FundId): List<LedgerTransaction> =
        dao.loadPendingPurchaseTransactions(fundId.value).map { it.toDomain() }

    override suspend fun loadPendingRedemptionTransactions(fundId: FundId): List<LedgerTransaction> =
        dao.loadPendingRedemptionTransactions(fundId.value).map { it.toDomain() }

    override suspend fun loadLots(fundId: FundId): List<Lot> =
        dao.loadLots(fundId.value).map { it.toDomain() }

    override suspend fun materializeCalculatedPurchase(
        transaction: LedgerTransaction,
        lot: Lot,
        entryId: LedgerEntryId,
    ): Boolean {
        require(transaction.status == TransactionStatus.CALCULATED) {
            "Automatic purchase materialization requires calculated facts"
        }
        require(transaction.type in setOf(TransactionType.PURCHASE, TransactionType.RECURRING_PURCHASE)) {
            "Automatic NAV completion supports purchase transactions only"
        }
        require(lot.sourceTransactionId == transaction.id && lot.fundId == transaction.fundId) {
            "Automatic purchase lot must belong to its transaction"
        }
        val event = RealLotOpened(entryId, 1, lot)
        return dao.materializeCalculatedPurchaseAtomically(
            transaction.toEntity(),
            lot.toEntity(),
            LedgerEntryCodec.encode(event),
        )
    }

    override suspend fun materializeCalculatedRedemption(
        transaction: LedgerTransaction,
        expectedLots: List<Lot>,
        updatedLots: List<Lot>,
        entryId: LedgerEntryId,
        consumptions: List<LotConsumption>,
    ): Boolean {
        require(transaction.type == TransactionType.REDEMPTION) {
            "Automatic NAV completion supports redemption transactions only"
        }
        require(transaction.status == TransactionStatus.CALCULATED) {
            "Automatic redemption materialization requires calculated facts"
        }
        val entry = LotsRedeemed(entryId, 1, transaction.fundId, consumptions, transaction.id)
        require(expectedLots.all { it.fundId == transaction.fundId } &&
            transaction.shares?.sameAs(consumptions.fold(BigDecimal.ZERO) { total, value -> total.add(value.shares) }) == true) {
            "Automatic redemption transaction and consumed fund/shares differ"
        }
        validateRealRedemptionEvent(expectedLots, updatedLots, entry)
        return dao.materializeCalculatedRedemptionAtomically(
            transaction.toEntity(),
            expectedLots.map { it.toEntity() },
            updatedLots.map { it.toEntity() },
            LedgerEntryCodec.encode(entry),
        )
    }

    override suspend fun findLotBySourceTransaction(transactionId: TransactionId): Lot? =
        dao.findLotBySourceTransaction(transactionId.value)?.toDomain()

    override suspend fun findLegacyLot(lotId: LotId): LegacyLot? = dao.findLegacyLot(lotId.value)?.toDomain()

    override suspend fun loadLegacyLots(fundId: FundId): List<LegacyLot> =
        dao.loadLegacyLots(fundId.value).map { it.toDomain() }

    override suspend fun loadLedgerEntries(fundId: FundId): List<LedgerEntry> =
        dao.loadLedgerEntries(fundId.value).map(LedgerEntryCodec::decode)

    override suspend fun markAssumedPurchaseNotExecuted(
        transaction: LedgerTransaction,
        expectedLot: Lot,
        entry: AssumedPurchaseReversed,
    ) {
        require(transaction.status == TransactionStatus.NOT_EXECUTED) { "Assumed reversal needs NOT_EXECUTED status" }
        require(transaction.origin == TransactionOrigin.AUTO_PLAN_ASSUMED) { "Only assumed purchases may be reversed" }
        require(entry.sourceTransactionId == transaction.id) { "Reversal event references another transaction" }
        dao.reverseAssumedPurchaseAtomically(
            transaction.toEntity(),
            expectedLot.toEntity(),
            LedgerEntryCodec.encode(entry),
        )
    }

    override suspend fun recordCorrection(correctedTransaction: CorrectedTransaction) {
        require(correctedTransaction.audit.transactionId == correctedTransaction.transaction.id) {
            "Correction audit references another transaction"
        }
        dao.recordCorrectionAtomically(
            correctedTransaction.transaction.toEntity(),
            correctedTransaction.audit.toEntity(),
        )
    }

    override suspend fun confirmCalculatedRedemptionActualCash(
        expected: LedgerTransaction,
        confirmed: LedgerTransaction,
        cashAudit: TransactionCorrection,
        cashFlowDateAudit: TransactionCorrection,
    ): Boolean {
        require(expected.id == confirmed.id && cashAudit.transactionId == expected.id &&
            cashFlowDateAudit.transactionId == expected.id
        ) { "Actual cash confirmation must retain one transaction identity" }
        return dao.confirmCalculatedRedemptionActualCashAtomically(
            expected.toEntity(),
            confirmed.toEntity(),
            cashAudit.toEntity(),
            cashFlowDateAudit.toEntity(),
        )
    }

    override suspend fun confirmMatchingCalculatedPurchaseActualCash(
        expected: LedgerTransaction,
        expectedLot: Lot,
        confirmed: LedgerTransaction,
        cashAudit: TransactionCorrection,
        paymentAudit: TransactionCorrection,
    ): Boolean {
        require(expected.id == confirmed.id && expectedLot.sourceTransactionId == expected.id &&
            cashAudit.transactionId == expected.id && paymentAudit.transactionId == expected.id
        ) { "Actual purchase cash confirmation must retain one transaction identity" }
        return dao.confirmMatchingCalculatedPurchaseActualCashAtomically(
            expected.toEntity(), expectedLot.toEntity(), confirmed.toEntity(),
            cashAudit.toEntity(), paymentAudit.toEntity(),
        )
    }

    override suspend fun findCorrection(correctionId: CorrectionId): TransactionCorrection? =
        dao.findCorrection(correctionId.value)?.toDomain()

    override suspend fun confirmUnconsumedPurchasePlatformShares(confirmation: PurchasePlatformShareConfirmation): Boolean {
        val expected = confirmation.expected
        val fund = findFund(expected.fundId) ?: return false
        val confirmed = confirmation.confirmed
        // Revalidate the complete bundle; callers cannot smuggle unrelated edits or missing audits.
        val validated = PurchasePlatformShareConfirmation.prepare(
            expected, confirmation.expectedLot, fund.precision ?: return false,
            requireNotNull(confirmed.actualCashFlow).negate(), requireNotNull(confirmed.cashFlowDate),
            requireNotNull(confirmed.shares), requireNotNull(confirmed.confirmationDate), confirmed.updatedAt,
            LocalDate.now(clock),
        )
        require(validated == confirmation) { "Platform share confirmation bundle was altered" }
        val rebuilt = LedgerRebuilder.rebuild(loadLedgerEntries(expected.fundId))
        if (rebuilt.realLots.singleOrNull { it.id == confirmation.expectedLot.id } != confirmation.expectedLot) return false
        return dao.confirmUnconsumedPurchasePlatformSharesAtomically(
            expected.toEntity(), confirmation.expectedLot.toEntity(), confirmed.toEntity(),
            confirmation.entry.correctedLot.toEntity(), LedgerEntryCodec.encode(confirmation.entry),
            confirmation.audits.map { it.toEntity() },
        )
    }

    override suspend fun confirmUnconsumedPurchasePlatformAmounts(confirmation: PurchasePlatformAmountConfirmation): Boolean {
        val expected = confirmation.expected
        val fund = findFund(expected.fundId) ?: return false
        val confirmed = confirmation.confirmed
        val validated = PurchasePlatformAmountConfirmation.prepare(
            expected, confirmation.expectedLot, fund.precision ?: return false,
            requireNotNull(confirmed.actualCashFlow).negate(), requireNotNull(confirmed.netAmount),
            requireNotNull(confirmed.purchaseFee), requireNotNull(confirmed.unitNav), requireNotNull(confirmed.shares),
            requireNotNull(confirmed.cashFlowDate), requireNotNull(confirmed.confirmationDate), confirmed.updatedAt, LocalDate.now(clock),
        )
        require(validated == confirmation) { "Platform amount confirmation bundle was altered" }
        val rebuilt = LedgerRebuilder.rebuild(loadLedgerEntries(expected.fundId))
        if (rebuilt.realLots.singleOrNull { it.id == confirmation.expectedLot.id } != confirmation.expectedLot) return false
        return dao.confirmUnconsumedPurchasePlatformAmountsAtomically(
            expected.toEntity(), confirmation.expectedLot.toEntity(), confirmed.toEntity(),
            confirmation.entry.correctedLot.toEntity(), LedgerEntryCodec.encode(confirmation.entry),
            confirmation.audits.map { it.toEntity() },
        )
    }

    override suspend fun confirmUnconsumedPurchasePlatformNav(confirmation: PurchasePlatformNavConfirmation): Boolean {
        val expected = confirmation.expected
        val fund = findFund(expected.fundId) ?: return false
        val confirmed = confirmation.confirmed
        val validated = PurchasePlatformNavConfirmation.prepare(
            expected, confirmation.expectedLot, fund.precision ?: return false, confirmation.entry.officialNav,
            requireNotNull(confirmed.actualCashFlow).negate(), requireNotNull(confirmed.netAmount), requireNotNull(confirmed.purchaseFee),
            requireNotNull(confirmed.unitNav), requireNotNull(confirmed.shares), requireNotNull(confirmed.cashFlowDate),
            requireNotNull(confirmed.confirmationDate), confirmed.updatedAt, LocalDate.now(clock),
        )
        require(validated == confirmation) { "Platform NAV confirmation bundle was altered" }
        val rebuilt = LedgerRebuilder.rebuild(loadLedgerEntries(expected.fundId))
        if (rebuilt.realLots.singleOrNull { it.id == confirmation.expectedLot.id } != confirmation.expectedLot) return false
        return dao.confirmUnconsumedPurchasePlatformNavAtomically(
            expected.toEntity(), confirmation.expectedLot.toEntity(), confirmed.toEntity(), confirmation.entry.correctedLot.toEntity(),
            confirmation.entry.officialNav.toEntity(), LedgerEntryCodec.encode(confirmation.entry), confirmation.audits.map { it.toEntity() },
        )
    }

    private fun validateRealRedemptionEvent(
        expected: List<Lot>,
        updated: List<Lot>,
        entry: LotsRedeemed,
    ) {
        require(entry.consumptions.map { it.lotId }.toSet() == updated.map { it.id }.toSet()) {
            "Redemption event and projection must reference the same lots"
        }
        expected.zip(updated).forEach { (before, after) ->
            require(before.id == after.id) { "Lot projection order changed unexpectedly" }
            val consumption = entry.consumptions.single { it.lotId == before.id }
            require(before.remainingShares.subtract(after.remainingShares).sameAs(consumption.shares)) {
                "Redemption share projection and event differ for ${before.id.value}"
            }
            require(before.costBasis.subtract(after.costBasis).sameAs(consumption.allocatedCostBasis)) {
                "Redemption cost projection and event differ for ${before.id.value}"
            }
        }
    }

    private fun BigDecimal.sameAs(other: BigDecimal): Boolean = compareTo(other) == 0

    private fun recurringNoticeId(planId: String, scheduledTradeDate: String): String =
        "recurring-notice:$planId:$scheduledTradeDate"
}
