package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

public sealed interface LedgerTrackingCashFlowAssembly {
    public data class Complete public constructor(public val date: LocalDate, public val value: BigDecimal,
        public val nav: NavRecord?, public val cash: LedgerCashFlowAssembly.Complete) : LedgerTrackingCashFlowAssembly
    public data class Incomplete public constructor(public val detail: LedgerAnnualizedReturnAssembly.Incomplete) : LedgerTrackingCashFlowAssembly
}

/** A persisted dated market-value start plus only subsequent actual cash, never lifetime investment history. */
public object LedgerTrackingReturnAssembler {
    public fun assemble(snapshot: LedgerHistorySnapshot, baseline: TrackingBaseline, latestNav: NavRecord?,
        asOf: LocalDate, precision: FundPrecision): LedgerAnnualizedReturnAssembly {
        val prepared = prepareCashFlows(snapshot, baseline, latestNav, asOf, precision)
        if (prepared is LedgerTrackingCashFlowAssembly.Incomplete) return prepared.detail
        prepared as LedgerTrackingCashFlowAssembly.Complete
        val cash = prepared.cash
        if (cash.cashFlows.none { it.amount.signum() > 0 }) return LedgerAnnualizedReturnAssembly.Incomplete(AnnualizedReturnGapReason.NO_POSITIVE_RETURN)
        if (ChronoUnit.DAYS.between(cash.cashFlows.first().date, cash.cashFlows.last().date) > 999_999_999L) {
            return LedgerAnnualizedReturnAssembly.Incomplete(AnnualizedReturnGapReason.UNSUPPORTED_DATE_RANGE)
        }
        return LedgerAnnualizedReturnAssembly.Complete(prepared.date, prepared.value, prepared.nav, cash.cashFlows,
            XirrCalculator.calculate(cash.cashFlows, HistoryScope.TRACKING_PERIOD), HistoryScope.TRACKING_PERIOD, baseline.date)
    }

    /** Shared fact guards for period profit and XIRR. Known zero cash/loss need not have an XIRR root. */
    public fun prepareCashFlows(snapshot: LedgerHistorySnapshot, baseline: TrackingBaseline, latestNav: NavRecord?,
        asOf: LocalDate, precision: FundPrecision): LedgerTrackingCashFlowAssembly {
        fun gap(reason: AnnualizedReturnGapReason) = LedgerTrackingCashFlowAssembly.Incomplete(LedgerAnnualizedReturnAssembly.Incomplete(reason))
        val valid = try {
            baseline.fundId == snapshot.fundId && baseline.precision == precision &&
                baseline == TrackingBaseline.prepare(baseline.history, baseline.officialNav, precision, baseline.recordedAt, asOf)
        } catch (_: IllegalArgumentException) { false } catch (_: ArithmeticException) { false }
        if (!valid) return gap(AnnualizedReturnGapReason.INVALID_TRACKING_BASELINE)

        val oldTransactions = baseline.history.transactions.associateBy { it.id }
        val transactions = snapshot.transactions.associateBy { it.id }
        val oldEntries = baseline.history.entries.sortedBy { it.sequence }
        val entries = snapshot.entries.sortedBy { it.sequence }
        if (oldTransactions.any { (id, fact) -> transactions[id] != fact } || entries.take(oldEntries.size) != oldEntries) {
            return gap(AnnualizedReturnGapReason.BASELINE_HISTORY_CHANGED)
        }
        val added = snapshot.transactions.filter { it.id !in oldTransactions }
        val addedIds = added.map { it.id }.toSet()
        // An imported holding has no verified external payment, so it cannot be injected after the start.
        // Same-date late entries and payments spanning the boundary need explicit review, not double counting.
        if (added.filter { it.status !in setOf(TransactionStatus.CANCELLED, TransactionStatus.NOT_EXECUTED) }.any {
                it.origin == TransactionOrigin.IMPORTED || !it.tradeDate.isAfter(baseline.date) ||
                    it.navDate?.let { date -> !date.isAfter(baseline.date) } == true ||
                    it.confirmationDate?.let { date -> !date.isAfter(baseline.date) } == true ||
                    (it.cashFlowDate ?: it.settlementDate)?.let { date -> !date.isAfter(baseline.date) } == true ||
                    it.createdAt.isBefore(baseline.recordedAt)
            }) return gap(AnnualizedReturnGapReason.FACTS_CROSS_TRACKING_BOUNDARY)
        if (entries.drop(oldEntries.size).any { entry ->
                val source = when (entry) {
                    is RealLotOpened -> entry.lot.sourceTransactionId
                    is LotsRedeemed -> entry.sourceTransactionId
                    is LegacyLotRedeemed -> entry.sourceTransactionId
                    is AssumedPurchaseReversed -> entry.sourceTransactionId
                    is UnconsumedPurchaseLotCorrected -> entry.expectedLot.sourceTransactionId
                    is UnconsumedPurchaseAmountsCorrected -> entry.expectedLot.sourceTransactionId
                    is UnconsumedPurchaseNavCorrected -> entry.expectedLot.sourceTransactionId
                    is ConsumedPurchaseCostCorrected -> entry.expectedLot.sourceTransactionId
                    is ConsumedPurchaseSharesCorrected -> entry.expectedLot.sourceTransactionId
                    is LegacyLotOpened -> null
                }
                source == null || source !in addedIds
            }) return gap(AnnualizedReturnGapReason.UNSUPPORTED_TRACKING_EVENT)

        // Reuse the full-snapshot structural, allocation, cost/payment and final-cash guards.
        // A Legacy-only snapshot has no external recorded cash yet, but its explicit baseline supplies the start.
        val recorded = LedgerRealizedReturnAssembler.assemble(snapshot, asOf, precision)
        if (recorded is LedgerRealizedReturnAssembly.Incomplete &&
            recorded.gaps.any { it.reason != RealizedReturnGapReason.MISSING_RECORDED_HISTORY }) {
            return LedgerTrackingCashFlowAssembly.Incomplete(LedgerAnnualizedReturnAssembly.Incomplete(AnnualizedReturnGapReason.RECORDED_FACTS_REQUIRE_REVIEW, recorded))
        }
        val shares = (snapshot.realLots.map { it.remainingShares } + snapshot.legacyLots.map { it.remainingShares })
            .fold(BigDecimal.ZERO, BigDecimal::add)
        val nav = latestNav.takeIf { shares.signum() > 0 }
        if (shares.signum() > 0 && nav == null) return gap(AnnualizedReturnGapReason.MISSING_PUBLISHED_NAV)
        if (nav != null && (nav.fundId != snapshot.fundId || nav.navDate.isAfter(asOf) || nav.navDate.isBefore(baseline.date) ||
                nav.authority !in setOf(DataAuthority.FUND_OFFICIAL, DataAuthority.RELIABLE_AGGREGATOR) ||
                nav.unitNav.stripTrailingZeros().scale() > precision.navScale)) {
            return gap(AnnualizedReturnGapReason.INVALID_PUBLISHED_NAV)
        }
        val date = nav?.navDate ?: asOf
        val value = try {
            if (nav == null) precision.zeroMoney() else precision.roundMoney(
                precision.roundShares(shares).multiply(precision.roundNav(nav.unitNav), FundPrecision.CALCULATION_CONTEXT))
        } catch (_: ArithmeticException) { return gap(AnnualizedReturnGapReason.INVALID_VALUATION_PRECISION) }
        val cash = LedgerCashFlowAssembler.assemble(snapshot.fundId, added, value, date,
            HistoryScope.TRACKING_PERIOD, hasLegacyHistory = true,
            trackingBaseline = DatedCashFlow(baseline.date, baseline.startingValue.negate(), CashFlowKind.TRACKING_BASELINE),
            precision = precision)
        if (cash is LedgerCashFlowAssembly.Incomplete) return LedgerTrackingCashFlowAssembly.Incomplete(LedgerAnnualizedReturnAssembly.Incomplete(
            AnnualizedReturnGapReason.INCOMPLETE_CASH_FLOWS, cashFlowGaps = cash.gaps))
        cash as LedgerCashFlowAssembly.Complete
        return LedgerTrackingCashFlowAssembly.Complete(date, value, nav, cash)
    }
}
