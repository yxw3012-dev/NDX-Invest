package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.time.LocalDate

public enum class RealizedReturnScope { RECORDED_TRANSACTIONS }

public enum class RealizedReturnGapReason {
    HISTORY_REVIEW_REQUIRED, MISSING_RECORDED_HISTORY, UNFINISHED_TRANSACTION, TRANSACTION_AFTER_AS_OF,
    MISSING_ACTUAL_CASH, MISSING_CASH_DATE, INVALID_CASH_SIGN_OR_PRECISION, CASH_DATE_OUT_OF_RANGE,
    PURCHASE_COST_CASH_MISMATCH, INVALID_ALLOCATION_PRECISION, UNSUPPORTED_ADJUSTMENT,
}

public data class RealizedReturnGap public constructor(
    public val transactionId: TransactionId?,
    public val reason: RealizedReturnGapReason,
)

public data class RealizedReturnContribution public constructor(
    public val transactionId: TransactionId,
    public val type: TransactionType,
    public val cashDate: LocalDate,
    public val actualCash: BigDecimal,
    public val allocatedCost: BigDecimal?,
    public val lotType: LotType?,
    public val income: BigDecimal,
)

public sealed interface LedgerRealizedReturnAssembly {
    /** Exact for the supplied recorded facts only, never a fabricated full-history or XIRR result. */
    public data class Complete public constructor(
        public val asOf: LocalDate,
        public val scope: RealizedReturnScope,
        public val hasLegacyHistory: Boolean,
        public val redemptionIncome: BigDecimal,
        public val cashDividendIncome: BigDecimal,
        public val realizedReturn: BigDecimal,
        public val contributions: List<RealizedReturnContribution>,
    ) : LedgerRealizedReturnAssembly

    public data class Incomplete public constructor(
        public val gaps: List<RealizedReturnGap>,
        public val historyIssues: List<HistoryIssue> = emptyList(),
    ) : LedgerRealizedReturnAssembly {
        init { require(gaps.isNotEmpty()) }
    }
}

/** Stored, verified cost plus final actual cash. No estimated proceeds, fee recalculation or repair writes. */
public object LedgerRealizedReturnAssembler {
    public fun assemble(snapshot: LedgerHistorySnapshot, asOf: LocalDate, precision: FundPrecision): LedgerRealizedReturnAssembly {
        val inspection = LedgerHistoryInspector.inspect(snapshot)
        if (inspection is LedgerHistoryInspection.NeedsReview) {
            return LedgerRealizedReturnAssembly.Incomplete(
                listOf(RealizedReturnGap(null, RealizedReturnGapReason.HISTORY_REVIEW_REQUIRED)), inspection.issues)
        }
        inspection as LedgerHistoryInspection.Consistent
        val gaps = mutableListOf<RealizedReturnGap>()
        fun gap(id: TransactionId?, reason: RealizedReturnGapReason) { gaps += RealizedReturnGap(id, reason) }
        fun BigDecimal.exactMoney(): Boolean = stripTrailingZeros().scale() <= precision.moneyScale
        fun BigDecimal.exactShares(): Boolean = stripTrailingZeros().scale() <= precision.shareScale

        // Check each allocation, not only the sum: opposing precision errors must not cancel each other out.
        snapshot.entries.forEach { entry ->
            val consumptions = when (entry) {
                is LotsRedeemed -> entry.consumptions + inspection.rebuiltLedger.effectiveConsumptions.getValue(entry.id)
                is LegacyLotRedeemed -> listOf(entry.consumption)
                else -> emptyList()
            }
            if (consumptions.any { !it.allocatedCostBasis.exactMoney() || !it.shares.exactShares() }) {
                gap(null, RealizedReturnGapReason.INVALID_ALLOCATION_PRECISION)
            }
        }
        val lotFacts = snapshot.realLots + snapshot.entries.filterIsInstance<RealLotOpened>().map { it.lot }
        if (lotFacts.any { lot ->
                listOf(lot.grossCashOut, lot.netSubscriptionAmount, lot.purchaseFee, lot.costBasis).any { !it.exactMoney() } ||
                    !lot.originalShares.exactShares() || !lot.remainingShares.exactShares() ||
                    lot.unitNav.stripTrailingZeros().scale() > precision.navScale
            } || snapshot.legacyLots.any { !it.costBasis.exactMoney() || !it.remainingShares.exactShares() } ||
            snapshot.entries.filterIsInstance<LegacyLotOpened>().any { !it.lot.costBasis.exactMoney() || !it.lot.remainingShares.exactShares() }) {
            gap(null, RealizedReturnGapReason.INVALID_ALLOCATION_PRECISION)
        }
        val costs = inspection.redemptionCosts.associateBy { it.transactionId }
        val contributions = mutableListOf<RealizedReturnContribution>()
        val effective = snapshot.transactions.filter {
            it.origin != TransactionOrigin.IMPORTED && it.status !in setOf(TransactionStatus.CANCELLED, TransactionStatus.NOT_EXECUTED)
        }
        if (effective.isEmpty()) gap(null, RealizedReturnGapReason.MISSING_RECORDED_HISTORY)
        effective.forEach { transaction ->
            if (transaction.tradeDate.isAfter(asOf) || transaction.navDate?.isAfter(asOf) == true || transaction.confirmationDate?.isAfter(asOf) == true) {
                gap(transaction.id, RealizedReturnGapReason.TRANSACTION_AFTER_AS_OF)
                return@forEach
            }
            if (transaction.status != TransactionStatus.CONFIRMED_ACTUAL) {
                gap(transaction.id, RealizedReturnGapReason.UNFINISHED_TRANSACTION)
                return@forEach
            }
            if (transaction.type == TransactionType.ADJUSTMENT) {
                gap(transaction.id, RealizedReturnGapReason.UNSUPPORTED_ADJUSTMENT)
                return@forEach
            }
            if (transaction.type == TransactionType.DIVIDEND_REINVESTMENT) {
                if (transaction.actualCashFlow != null) gap(transaction.id, RealizedReturnGapReason.INVALID_CASH_SIGN_OR_PRECISION)
                return@forEach
            }
            val cash = transaction.actualCashFlow
            if (cash == null) {
                gap(transaction.id, RealizedReturnGapReason.MISSING_ACTUAL_CASH)
                return@forEach
            }
            val cashDate = transaction.cashFlowDate ?: transaction.settlementDate
            if (cashDate == null) {
                gap(transaction.id, RealizedReturnGapReason.MISSING_CASH_DATE)
                return@forEach
            }
            val isPurchase = transaction.type in setOf(TransactionType.PURCHASE, TransactionType.RECURRING_PURCHASE)
            val signValid = when (transaction.type) {
                TransactionType.PURCHASE, TransactionType.RECURRING_PURCHASE -> cash.signum() < 0
                TransactionType.REDEMPTION -> cash.signum() >= 0
                TransactionType.CASH_DIVIDEND -> cash.signum() > 0
            }
            if (!signValid || !cash.exactMoney()) {
                gap(transaction.id, RealizedReturnGapReason.INVALID_CASH_SIGN_OR_PRECISION)
                return@forEach
            }
            if (cashDate.isAfter(asOf) || (!isPurchase && cashDate.isBefore(transaction.tradeDate))) {
                gap(transaction.id, RealizedReturnGapReason.CASH_DATE_OUT_OF_RANGE)
                return@forEach
            }
            when (transaction.type) {
                TransactionType.PURCHASE, TransactionType.RECURRING_PURCHASE -> {
                    if (transaction.grossAmount?.compareTo(cash.negate()) != 0) gap(transaction.id, RealizedReturnGapReason.PURCHASE_COST_CASH_MISMATCH)
                }
                TransactionType.REDEMPTION -> {
                    val cost = costs.getValue(transaction.id)
                    contributions += RealizedReturnContribution(transaction.id, transaction.type, cashDate, cash,
                        cost.allocatedCost, cost.lotType, cash.subtract(cost.allocatedCost))
                }
                TransactionType.CASH_DIVIDEND -> contributions += RealizedReturnContribution(transaction.id, transaction.type,
                    cashDate, cash, null, null, cash)
            }
        }
        if (gaps.isNotEmpty()) return LedgerRealizedReturnAssembly.Incomplete(gaps.distinct())
        val redemption = contributions.filter { it.type == TransactionType.REDEMPTION }.fold(BigDecimal.ZERO) { total, entry -> total.add(entry.income) }
        val dividends = contributions.filter { it.type == TransactionType.CASH_DIVIDEND }.fold(BigDecimal.ZERO) { total, entry -> total.add(entry.income) }
        val hasLegacy = snapshot.legacyLots.isNotEmpty() || snapshot.entries.any { it is LegacyLotOpened } ||
            snapshot.transactions.any { it.origin == TransactionOrigin.IMPORTED }
        return LedgerRealizedReturnAssembly.Complete(asOf, RealizedReturnScope.RECORDED_TRANSACTIONS, hasLegacy,
            precision.roundMoney(redemption), precision.roundMoney(dividends), precision.roundMoney(redemption.add(dividends)),
            contributions.sortedWith(compareBy({ it.cashDate }, { it.transactionId.value })))
    }
}
