package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

public enum class AnnualizedReturnGapReason {
    RECORDED_FACTS_REQUIRE_REVIEW, LEGACY_BASELINE_REQUIRED, MISSING_PUBLISHED_NAV,
    INVALID_PUBLISHED_NAV, INVALID_VALUATION_PRECISION, INCOMPLETE_CASH_FLOWS,
    NO_POSITIVE_RETURN, UNSUPPORTED_DATE_RANGE,
    INVALID_TRACKING_BASELINE, BASELINE_HISTORY_CHANGED, FACTS_CROSS_TRACKING_BOUNDARY,
    UNSUPPORTED_TRACKING_EVENT,
}

public sealed interface LedgerAnnualizedReturnAssembly {
    public data class Complete public constructor(
        public val valuationDate: LocalDate,
        public val currentPositionValue: BigDecimal,
        public val valuationNav: NavRecord?,
        public val cashFlows: List<DatedCashFlow>,
        public val xirr: XirrResult,
        public val historyScope: HistoryScope = HistoryScope.FULL_HISTORY,
        public val trackingStartDate: LocalDate? = null,
    ) : LedgerAnnualizedReturnAssembly

    public data class Incomplete public constructor(
        public val reason: AnnualizedReturnGapReason,
        public val recordedFacts: LedgerRealizedReturnAssembly.Incomplete? = null,
        public val cashFlowGaps: List<CashFlowGap> = emptyList(),
    ) : LedgerAnnualizedReturnAssembly
}

/** Full recorded history only. A Legacy tracking baseline needs a separate explicit, persisted workflow. */
public object LedgerAnnualizedReturnAssembler {
    public fun assemble(snapshot: LedgerHistorySnapshot, latestNav: NavRecord?, asOf: LocalDate,
        precision: FundPrecision): LedgerAnnualizedReturnAssembly {
        val recorded = LedgerRealizedReturnAssembler.assemble(snapshot, asOf, precision)
        if (recorded is LedgerRealizedReturnAssembly.Incomplete) {
            return LedgerAnnualizedReturnAssembly.Incomplete(AnnualizedReturnGapReason.RECORDED_FACTS_REQUIRE_REVIEW, recorded)
        }
        recorded as LedgerRealizedReturnAssembly.Complete
        if (recorded.hasLegacyHistory) {
            return LedgerAnnualizedReturnAssembly.Incomplete(AnnualizedReturnGapReason.LEGACY_BASELINE_REQUIRED)
        }
        val shares = snapshot.realLots.fold(BigDecimal.ZERO) { total, lot -> total.add(lot.remainingShares) }
        val holdsShares = shares.signum() > 0
        if (holdsShares && latestNav == null) {
            return LedgerAnnualizedReturnAssembly.Incomplete(AnnualizedReturnGapReason.MISSING_PUBLISHED_NAV)
        }
        // A closed position needs no invented NAV or terminal cash. Its actual dated proceeds suffice.
        val nav = latestNav.takeIf { holdsShares }
        if (nav != null && (nav.fundId != snapshot.fundId || nav.navDate.isAfter(asOf) ||
                nav.authority !in setOf(DataAuthority.FUND_OFFICIAL, DataAuthority.RELIABLE_AGGREGATOR) ||
                nav.unitNav.stripTrailingZeros().scale() > precision.navScale)) {
            return LedgerAnnualizedReturnAssembly.Incomplete(AnnualizedReturnGapReason.INVALID_PUBLISHED_NAV)
        }
        val valuationDate = nav?.navDate ?: asOf
        // Match the existing position valuation formula without calculating unrelated ratios/average costs.
        val value = try {
            if (nav == null) precision.zeroMoney() else precision.roundMoney(
                precision.roundShares(shares).multiply(precision.roundNav(nav.unitNav), FundPrecision.CALCULATION_CONTEXT))
        } catch (_: ArithmeticException) {
            return LedgerAnnualizedReturnAssembly.Incomplete(AnnualizedReturnGapReason.INVALID_VALUATION_PRECISION)
        }
        val cash = LedgerCashFlowAssembler.assemble(snapshot.fundId, snapshot.transactions, value, valuationDate,
            HistoryScope.FULL_HISTORY, hasLegacyHistory = false, precision = precision)
        if (cash is LedgerCashFlowAssembly.Incomplete) {
            return LedgerAnnualizedReturnAssembly.Incomplete(AnnualizedReturnGapReason.INCOMPLETE_CASH_FLOWS, cashFlowGaps = cash.gaps)
        }
        cash as LedgerCashFlowAssembly.Complete
        if (cash.cashFlows.none { it.amount.signum() > 0 }) {
            return LedgerAnnualizedReturnAssembly.Incomplete(AnnualizedReturnGapReason.NO_POSITIVE_RETURN)
        }
        // BigDecimal.pow has a bounded exponent; extreme persisted dates must fail before numerical solving.
        if (ChronoUnit.DAYS.between(cash.cashFlows.first().date, cash.cashFlows.last().date) > 999_999_999L) {
            return LedgerAnnualizedReturnAssembly.Incomplete(AnnualizedReturnGapReason.UNSUPPORTED_DATE_RANGE)
        }
        return LedgerAnnualizedReturnAssembly.Complete(valuationDate, value, nav, cash.cashFlows,
            XirrCalculator.calculate(cash.cashFlows, HistoryScope.FULL_HISTORY))
    }
}
