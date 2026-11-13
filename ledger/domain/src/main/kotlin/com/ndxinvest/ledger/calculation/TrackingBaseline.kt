package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** A user-requested, dated valuation of a verified snapshot, not historical purchase cost or external cash. */
public data class TrackingBaseline public constructor(
    public val history: LedgerHistorySnapshot,
    public val officialNav: NavRecord,
    public val precision: FundPrecision,
    public val recordedAt: Instant,
    public val startingShares: BigDecimal,
    public val startingValue: BigDecimal,
) {
    public val fundId: FundId get() = history.fundId
    public val date: LocalDate get() = officialNav.navDate

    public companion object {
        public fun prepare(history: LedgerHistorySnapshot, officialNav: NavRecord, precision: FundPrecision,
            recordedAt: Instant, asOf: LocalDate): TrackingBaseline {
            require(LedgerHistoryInspector.inspect(history) is LedgerHistoryInspection.Consistent) {
                "Baseline needs a verified history snapshot"
            }
            require(officialNav.fundId == history.fundId && officialNav.authority == DataAuthority.FUND_OFFICIAL) {
                "Baseline needs exact fund-official NAV evidence"
            }
            val date = officialNav.navDate
            require(!date.isAfter(asOf) && !officialNav.fetchedAt.isAfter(recordedAt)) { "Baseline cannot use future evidence" }
            fun BigDecimal.money(): Boolean = stripTrailingZeros().scale() <= precision.moneyScale
            fun BigDecimal.shares(): Boolean = stripTrailingZeros().scale() <= precision.shareScale
            require(officialNav.unitNav.stripTrailingZeros().scale() <= precision.navScale) { "Baseline NAV precision is invalid" }
            require(history.realLots.all { lot ->
                !lot.confirmationDate.isAfter(date) && lot.originalShares.shares() && lot.remainingShares.shares() &&
                    listOf(lot.grossCashOut, lot.netSubscriptionAmount, lot.purchaseFee, lot.costBasis).all { it.money() } &&
                    lot.unitNav.stripTrailingZeros().scale() <= precision.navScale
            } && history.legacyLots.all {
                it.trackingStartDate?.isAfter(date) != true && it.remainingShares.shares() && it.costBasis.money()
            }) { "Baseline cannot guess earlier holdings or round imprecise cost/shares" }
            history.transactions.forEach { transaction ->
                require(!transaction.createdAt.isAfter(recordedAt) && !transaction.updatedAt.isAfter(recordedAt)) {
                    "Baseline recording cannot precede its transaction evidence"
                }
                if (transaction.status in setOf(TransactionStatus.CANCELLED, TransactionStatus.NOT_EXECUTED)) return@forEach
                require(!transaction.tradeDate.isAfter(date) && transaction.navDate?.isAfter(date) != true &&
                    transaction.confirmationDate?.isAfter(date) != true) { "Snapshot contains facts after the baseline date" }
                require(transaction.status == TransactionStatus.CONFIRMED_ACTUAL) { "Unfinished transactions cross the baseline boundary" }
                if (transaction.origin == TransactionOrigin.IMPORTED) return@forEach
                if (transaction.type == TransactionType.DIVIDEND_REINVESTMENT) {
                    require(transaction.actualCashFlow == null) { "Reinvestment cannot invent external cash" }
                    return@forEach
                }
                val cash = requireNotNull(transaction.actualCashFlow) { "Baseline boundary needs final actual cash" }
                val cashDate = requireNotNull(transaction.cashFlowDate ?: transaction.settlementDate) { "Baseline boundary needs an actual cash date" }
                val purchase = transaction.type in setOf(TransactionType.PURCHASE, TransactionType.RECURRING_PURCHASE)
                val valid = when (transaction.type) {
                    TransactionType.PURCHASE, TransactionType.RECURRING_PURCHASE -> cash.signum() < 0 && transaction.grossAmount?.compareTo(cash.negate()) == 0
                    TransactionType.REDEMPTION -> cash.signum() >= 0
                    TransactionType.CASH_DIVIDEND -> cash.signum() > 0
                    else -> false
                }
                require(valid && cash.money() && !cashDate.isAfter(date) && (purchase || !cashDate.isBefore(transaction.tradeDate))) {
                    "Invalid or unsettled cash cannot be hidden inside the baseline"
                }
            }
            val shares = history.realLots.fold(BigDecimal.ZERO) { total, lot -> total.add(lot.remainingShares) }
                .add(history.legacyLots.fold(BigDecimal.ZERO) { total, lot -> total.add(lot.remainingShares) })
            require(shares.signum() > 0) { "Baseline needs a positive actual holding" }
            val startingShares = precision.roundShares(shares)
            val value = precision.roundMoney(startingShares.multiply(precision.roundNav(officialNav.unitNav), FundPrecision.CALCULATION_CONTEXT))
            require(value.signum() > 0) { "Baseline value must remain positive at configured precision" }
            return TrackingBaseline(history, officialNav, precision, recordedAt, startingShares, value)
        }
    }
}
