package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.time.LocalDate

public data class LedgerHistorySnapshot public constructor(
    public val fundId: FundId,
    public val transactions: List<LedgerTransaction>,
    public val realLots: List<Lot>,
    public val legacyLots: List<LegacyLot>,
    public val entries: List<LedgerEntry>,
)

public enum class HistoryIssueReason {
    WRONG_FUND, DUPLICATE_IDENTITY, INVALID_EVENT_HISTORY, PROJECTION_MISMATCH,
    UNLINKED_REDEMPTION, DUPLICATE_REDEMPTION_LINK, EVENT_TRANSACTION_MISMATCH,
    REDEMPTION_SHARES_MISMATCH, REDEMPTION_BEFORE_LOT, MISSING_REDEMPTION_EVENT,
    PURCHASE_FACTS_MISMATCH, MISSING_PURCHASE_EVENT, REVERSAL_TRANSACTION_MISMATCH,
}

public data class HistoryIssue public constructor(
    public val reason: HistoryIssueReason,
    public val transactionId: TransactionId? = null,
    public val entryId: LedgerEntryId? = null,
    public val lotId: LotId? = null,
)

/** A stored allocation, not a newly calculated fee/cost or a claim of complete investment history. */
public data class VerifiedRedemptionCost public constructor(
    public val transactionId: TransactionId,
    public val entryId: LedgerEntryId,
    public val lotType: LotType,
    public val tradeDate: LocalDate,
    public val shares: BigDecimal,
    public val allocatedCost: BigDecimal,
)

public sealed interface LedgerHistoryInspection {
    public data class Consistent public constructor(
        public val rebuiltLedger: RebuiltLedger,
        public val redemptionCosts: List<VerifiedRedemptionCost>,
    ) : LedgerHistoryInspection
    public data class NeedsReview public constructor(public val issues: List<HistoryIssue>) : LedgerHistoryInspection {
        init { require(issues.isNotEmpty()) }
    }
}

/** Read-only structural reconciliation. Never infers a transaction link from an event name, date or amount. */
public object LedgerHistoryInspector {
    public fun inspect(snapshot: LedgerHistorySnapshot): LedgerHistoryInspection {
        val issues = mutableListOf<HistoryIssue>()
        fun issue(reason: HistoryIssueReason, transaction: TransactionId? = null, entry: LedgerEntryId? = null, lot: LotId? = null) {
            issues += HistoryIssue(reason, transaction, entry, lot)
        }
        if (snapshot.transactions.any { it.fundId != snapshot.fundId } || snapshot.entries.any { it.fundId != snapshot.fundId } ||
            snapshot.realLots.any { it.fundId != snapshot.fundId } || snapshot.legacyLots.any { it.fundId != snapshot.fundId }) {
            return LedgerHistoryInspection.NeedsReview(listOf(HistoryIssue(HistoryIssueReason.WRONG_FUND)))
        }
        if (snapshot.transactions.map { it.id }.distinct().size != snapshot.transactions.size ||
            (snapshot.realLots.map { it.id } + snapshot.legacyLots.map { it.id }).let { it.distinct().size != it.size }) {
            return LedgerHistoryInspection.NeedsReview(listOf(HistoryIssue(HistoryIssueReason.DUPLICATE_IDENTITY)))
        }
        val rebuilt = try { LedgerRebuilder.rebuild(snapshot.entries) } catch (_: IllegalArgumentException) {
            return LedgerHistoryInspection.NeedsReview(listOf(HistoryIssue(HistoryIssueReason.INVALID_EVENT_HISTORY)))
        } catch (_: IllegalStateException) {
            return LedgerHistoryInspection.NeedsReview(listOf(HistoryIssue(HistoryIssueReason.INVALID_EVENT_HISTORY)))
        }
        if (snapshot.realLots.size != rebuilt.realLots.size || snapshot.legacyLots.size != rebuilt.legacyLots.size ||
            rebuilt.realLots.any { expected -> snapshot.realLots.singleOrNull { it.id == expected.id }?.sameFactsAs(expected) != true } ||
            rebuilt.legacyLots.any { expected -> snapshot.legacyLots.singleOrNull { it.id == expected.id }?.sameFactsAs(expected) != true }) {
            issue(HistoryIssueReason.PROJECTION_MISMATCH)
        }
        val transactions = snapshot.transactions.associateBy { it.id }
        val costs = mutableListOf<VerifiedRedemptionCost>()
        val linkedSales = mutableSetOf<TransactionId>()
        val openings = snapshot.entries.filterIsInstance<RealLotOpened>().groupBy { it.lot.sourceTransactionId }
        val purchaseTypes = setOf(TransactionType.PURCHASE, TransactionType.RECURRING_PURCHASE, TransactionType.DIVIDEND_REINVESTMENT)
        val materializedStatuses = setOf(TransactionStatus.CALCULATED, TransactionStatus.CONFIRMED_ACTUAL)
        openings.forEach { (id, entries) ->
            val transaction = transactions[id]
            if (entries.size != 1 || transaction == null || transaction.type !in purchaseTypes ||
                transaction.status !in materializedStatuses + TransactionStatus.NOT_EXECUTED) {
                issue(HistoryIssueReason.PURCHASE_FACTS_MISMATCH, id, entries.first().id)
            }
            entries.forEach { opening ->
                val lot = opening.lot
                val validCost = if (transaction?.type == TransactionType.DIVIDEND_REINVESTMENT) {
                    lot.grossCashOut.signum() == 0 && lot.purchaseFee.signum() == 0 &&
                        lot.netSubscriptionAmount.signum() > 0 && lot.costBasis.sameAmount(lot.netSubscriptionAmount)
                } else {
                    lot.grossCashOut.signum() > 0 && lot.netSubscriptionAmount.signum() > 0 &&
                        lot.netSubscriptionAmount.add(lot.purchaseFee).sameAmount(lot.grossCashOut) && lot.costBasis.sameAmount(lot.grossCashOut)
                }
                if (!validCost) issue(HistoryIssueReason.PURCHASE_FACTS_MISMATCH, id, opening.id, lot.id)
            }
        }
        rebuilt.realLots.forEach { lot ->
            val transaction = transactions[lot.sourceTransactionId]
            if (transaction == null || transaction.type !in purchaseTypes || transaction.status !in materializedStatuses ||
                !lot.originalShares.sameAmount(transaction.shares) || !lot.netSubscriptionAmount.sameAmount(transaction.netAmount) ||
                !lot.purchaseFee.sameAmount(transaction.purchaseFee) || !lot.unitNav.sameAmount(transaction.unitNav) ||
                lot.confirmationDate != transaction.confirmationDate ||
                (transaction.type != TransactionType.DIVIDEND_REINVESTMENT && !lot.grossCashOut.sameAmount(transaction.grossAmount))) {
                issue(HistoryIssueReason.PURCHASE_FACTS_MISMATCH, lot.sourceTransactionId, lot = lot.id)
            }
        }
        snapshot.transactions.filter { it.type in purchaseTypes && it.status in materializedStatuses }.forEach {
            if (openings[it.id]?.size != 1) issue(HistoryIssueReason.MISSING_PURCHASE_EVENT, it.id)
        }
        var state = RebuiltLedger.empty()
        snapshot.entries.sortedBy { it.sequence }.forEach { entry ->
            val source = when (entry) {
                is LotsRedeemed -> entry.sourceTransactionId
                is LegacyLotRedeemed -> entry.sourceTransactionId
                else -> null
            }
            if (entry is LotsRedeemed || entry is LegacyLotRedeemed) {
                val consumptions = if (entry is LotsRedeemed) entry.consumptions else listOf((entry as LegacyLotRedeemed).consumption)
                val transaction = source?.let(transactions::get)
                if (source == null) issue(HistoryIssueReason.UNLINKED_REDEMPTION, entry = entry.id)
                else if (!linkedSales.add(source)) issue(HistoryIssueReason.DUPLICATE_REDEMPTION_LINK, source, entry.id)
                if (source != null && (transaction == null || transaction.type != TransactionType.REDEMPTION || transaction.status !in materializedStatuses)) {
                    issue(HistoryIssueReason.EVENT_TRANSACTION_MISMATCH, source, entry.id)
                }
                if (transaction != null) {
                    val shares = consumptions.fold(BigDecimal.ZERO) { total, consumption -> total.add(consumption.shares) }
                    val effective = if (entry is LotsRedeemed) rebuilt.effectiveConsumptions.getValue(entry.id) else consumptions
                    val cost = effective.fold(BigDecimal.ZERO) { total, consumption -> total.add(consumption.allocatedCostBasis) }
                    if (!shares.sameAmount(transaction.shares)) issue(HistoryIssueReason.REDEMPTION_SHARES_MISMATCH, source, entry.id)
                    consumptions.forEach { consumption ->
                        val availableFrom = if (entry is LotsRedeemed) state.realLots.single { it.id == consumption.lotId }.confirmationDate
                            else state.legacyLots.single { it.id == consumption.lotId }.trackingStartDate
                        if (availableFrom != null && transaction.tradeDate.isBefore(availableFrom)) {
                            issue(HistoryIssueReason.REDEMPTION_BEFORE_LOT, source, entry.id, consumption.lotId)
                        }
                    }
                    costs += VerifiedRedemptionCost(transaction.id, entry.id,
                        if (entry is LotsRedeemed) LotType.REAL else LotType.LEGACY, transaction.tradeDate, shares, cost)
                }
            }
            if (entry is AssumedPurchaseReversed) {
                val transaction = transactions[entry.sourceTransactionId]
                if (transaction == null || transaction.type != TransactionType.RECURRING_PURCHASE ||
                    transaction.origin != TransactionOrigin.AUTO_PLAN_ASSUMED || transaction.status != TransactionStatus.NOT_EXECUTED) {
                    issue(HistoryIssueReason.REVERSAL_TRANSACTION_MISMATCH, entry.sourceTransactionId, entry.id)
                }
            }
            state = LedgerRebuilder.apply(state, entry)
        }
        snapshot.transactions.filter { it.type == TransactionType.REDEMPTION && it.status in materializedStatuses }.forEach {
            if (it.id !in linkedSales) issue(HistoryIssueReason.MISSING_REDEMPTION_EVENT, it.id)
        }
        return if (issues.isEmpty()) LedgerHistoryInspection.Consistent(rebuilt, costs)
            else LedgerHistoryInspection.NeedsReview(issues.distinct())
    }

    private fun BigDecimal.sameAmount(other: BigDecimal?): Boolean = other != null && compareTo(other) == 0
    private fun Lot.sameFactsAs(other: Lot): Boolean =
        copy(originalShares = other.originalShares, remainingShares = other.remainingShares, grossCashOut = other.grossCashOut,
            netSubscriptionAmount = other.netSubscriptionAmount, purchaseFee = other.purchaseFee, costBasis = other.costBasis, unitNav = other.unitNav) == other &&
            listOf(originalShares to other.originalShares, remainingShares to other.remainingShares, grossCashOut to other.grossCashOut,
                netSubscriptionAmount to other.netSubscriptionAmount, purchaseFee to other.purchaseFee, costBasis to other.costBasis,
                unitNav to other.unitNav).all { (first, second) -> first.sameAmount(second) }
    private fun LegacyLot.sameFactsAs(other: LegacyLot): Boolean =
        copy(remainingShares = other.remainingShares, costBasis = other.costBasis) == other &&
            remainingShares.sameAmount(other.remainingShares) && costBasis.sameAmount(other.costBasis)
}
