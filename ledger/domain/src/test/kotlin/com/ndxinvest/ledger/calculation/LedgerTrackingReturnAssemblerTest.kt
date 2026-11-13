package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class LedgerTrackingReturnAssemblerTest {
    @Test fun `tracking cumulative profit shares verified market baseline guards without requiring an XIRR root`() {
        val prepared = assertIs<LedgerTrackingCashFlowAssembly.Complete>(LedgerTrackingReturnAssembler.prepareCashFlows(
            original, baseline, latest, end, precision))
        assertEquals(BigDecimal("100.00"), prepared.cash.performance.cumulativeReturn)
        assertEquals(BigDecimal("700.00"), legacy.costBasis)
        assertEquals(HistoryScope.TRACKING_PERIOD, prepared.cash.historyScope)
        assertIs<LedgerTrackingCashFlowAssembly.Incomplete>(LedgerTrackingReturnAssembler.prepareCashFlows(
            original.copy(legacyLots = listOf(legacy.copy(costBasis = BigDecimal("701.00")))), baseline, latest, end, precision))
    }
    @Test fun `tracking cumulative known total loss remains exact while annualized return is unavailable`() {
        val sale = finalTransaction("zero-sale", TransactionType.REDEMPTION, "0.00").copy(shares = BigDecimal("100.00"))
        val closed = original.copy(transactions = listOf(sale), legacyLots = listOf(legacy.copy(remainingShares = BigDecimal.ZERO, costBasis = BigDecimal.ZERO)),
            entries = original.entries + LegacyLotRedeemed(LedgerEntryId("zero-event"), 2, fund,
                LotConsumption(legacy.id, BigDecimal("100.00"), BigDecimal("700.00")), sale.id))
        val prepared = assertIs<LedgerTrackingCashFlowAssembly.Complete>(LedgerTrackingReturnAssembler.prepareCashFlows(closed, baseline, null, end, precision))
        assertEquals(BigDecimal("-1000.00"), prepared.cash.performance.cumulativeReturn)
        gap(AnnualizedReturnGapReason.NO_POSITIVE_RETURN, assemble(closed, nav = null))
    }
    @Test fun `tracking cumulative cannot include pre baseline cash or late imported holdings`() {
        val dividend = finalTransaction("late", TransactionType.CASH_DIVIDEND, "10.00", start)
        assertIs<LedgerTrackingCashFlowAssembly.Incomplete>(LedgerTrackingReturnAssembler.prepareCashFlows(
            original.copy(transactions = listOf(dividend)), baseline, latest, end, precision))
        val new = legacy.copy(id = LotId("new-old"))
        assertIs<LedgerTrackingCashFlowAssembly.Incomplete>(LedgerTrackingReturnAssembler.prepareCashFlows(
            original.copy(legacyLots = listOf(legacy, new), entries = original.entries + LegacyLotOpened(LedgerEntryId("late-open"), 2, new)),
            baseline, latest, end, precision))
    }
    private val fund = FundId("tracking-fund")
    private val start = LocalDate.parse("2025-01-01")
    private val end = LocalDate.parse("2026-01-01")
    private val recordedAt = Instant.parse("2025-01-02T08:00:00Z")
    private val precision = FundPrecision(2, 2, 4, RoundingMode.HALF_UP)
    private val legacy = LegacyLot(LotId("old-holding"), fund, BigDecimal("100.00"), BigDecimal("700.00"), null)
    private val original = LedgerHistorySnapshot(fund, emptyList(), emptyList(), listOf(legacy),
        listOf(LegacyLotOpened(LedgerEntryId("old-open"), 1, legacy)))
    private val official = NavRecord(fund, start, BigDecimal("10.0000"), null,
        DataAuthority.FUND_OFFICIAL, DataSourceId("official"), recordedAt)
    private val baseline = TrackingBaseline.prepare(original, official, precision, recordedAt, start.plusDays(1))
    private val latest = official.copy(navDate = end, unitNav = BigDecimal("11.0000"), fetchedAt = Instant.parse("2026-01-02T08:00:00Z"))
    private fun assemble(history: LedgerHistorySnapshot = original, nav: NavRecord? = latest,
        saved: TrackingBaseline = baseline, asOf: LocalDate = end, configured: FundPrecision = precision) =
        LedgerTrackingReturnAssembler.assemble(history, saved, nav, asOf, configured)
    private fun complete(history: LedgerHistorySnapshot = original, nav: NavRecord? = latest,
        saved: TrackingBaseline = baseline, asOf: LocalDate = end) =
        assertIs<LedgerAnnualizedReturnAssembly.Complete>(assemble(history, nav, saved, asOf))
    private fun gap(reason: AnnualizedReturnGapReason, result: LedgerAnnualizedReturnAssembly) =
        assertEquals(reason, assertIs<LedgerAnnualizedReturnAssembly.Incomplete>(result).reason)
    private fun finalTransaction(id: String, type: TransactionType, cash: String?, date: LocalDate = end) =
        LedgerTransaction(TransactionId(id), fund, type, TransactionStatus.CONFIRMED_ACTUAL,
            TransactionOrigin.USER_ENTERED, DataAuthority.USER_OR_PLATFORM_FINAL, latest.fetchedAt,
            date, date, date, null, RuleVersionId("rule"),
            actualCashFlow = cash?.let(::BigDecimal), cashFlowDate = cash?.let { date })

    @Test
    fun `Legacy only return begins at dated market value not unknown historical cash or old cost`() {
        val result = complete()
        assertEquals(BigDecimal("1000.00").negate(), result.cashFlows.first().amount)
        assertEquals(BigDecimal("1100.00"), result.currentPositionValue)
        assertEquals(HistoryScope.TRACKING_PERIOD, result.historyScope)
        assertEquals(start, result.trackingStartDate)
        assertEquals(XirrLabel.TRACKING_PERIOD_ANNUALIZED, result.xirr.label)
        assertEquals(0, assertIs<XirrCalculation>(result.xirr).annualRate.compareTo(BigDecimal("0.1")))
        assertEquals(BigDecimal("700.00"), original.legacyLots.single().costBasis)
        assertEquals(original, baseline.history)
    }

    @Test
    fun `partial Legacy sale and dividend use subsequent actual cash once without rebasing cost`() {
        val sale = finalTransaction("sale", TransactionType.REDEMPTION, "550.00").copy(shares = BigDecimal("50.00"))
        val dividend = finalTransaction("dividend", TransactionType.CASH_DIVIDEND, "100.00")
        val history = original.copy(transactions = listOf(sale, dividend),
            legacyLots = listOf(legacy.copy(remainingShares = BigDecimal("50.00"), costBasis = BigDecimal("350.00"))),
            entries = original.entries + LegacyLotRedeemed(LedgerEntryId("sale-event"), 2, fund,
                LotConsumption(legacy.id, BigDecimal("50.00"), BigDecimal("350.00")), sale.id))
        val result = complete(history)
        assertEquals(4, result.cashFlows.size)
        assertEquals(BigDecimal("550.00"), result.currentPositionValue)
        assertEquals(0, assertIs<XirrCalculation>(result.xirr).annualRate.compareTo(BigDecimal("0.2")))
        assertEquals(listOf("-1000.00", "550.00", "100.00", "550.00"), result.cashFlows.map { it.amount.toPlainString() })
    }

    @Test
    fun `closed Legacy position uses actual sale date without invented terminal NAV or a longer period`() {
        val sale = finalTransaction("closed-sale", TransactionType.REDEMPTION, "1100.00").copy(shares = BigDecimal("100.00"))
        val history = original.copy(transactions = listOf(sale),
            legacyLots = listOf(legacy.copy(remainingShares = BigDecimal.ZERO, costBasis = BigDecimal.ZERO)),
            entries = original.entries + LegacyLotRedeemed(LedgerEntryId("closed-event"), 2, fund,
                LotConsumption(legacy.id, BigDecimal("100.00"), BigDecimal("700.00")), sale.id))
        val result = complete(history, nav = null, asOf = end.plusYears(1))
        assertNull(result.valuationNav)
        assertEquals(2, result.cashFlows.size)
        assertEquals(end, result.cashFlows.last().date)
        assertEquals(0, assertIs<XirrCalculation>(result.xirr).annualRate.compareTo(BigDecimal("0.1")))
        gap(AnnualizedReturnGapReason.NO_POSITIVE_RETURN,
            assemble(history.copy(transactions = listOf(sale.copy(actualCashFlow = BigDecimal.ZERO))), nav = null))
    }

    @Test
    fun `pre baseline purchase cash is excluded even in mixed real and Legacy holdings`() {
        val lot = Lot(LotId("before-lot"), fund, TransactionId("before-buy"), LotType.REAL, start,
            BigDecimal("10.00"), BigDecimal("10.00"), BigDecimal("95.00"), BigDecimal("90.00"),
            BigDecimal("5.00"), BigDecimal("95.00"), BigDecimal("9.0000"))
        val buy = finalTransaction("before-buy", TransactionType.PURCHASE, "-95.00", start.minusDays(1)).copy(
            applicationTime = recordedAt.minusSeconds(86400), createdAt = recordedAt.minusSeconds(86400), updatedAt = recordedAt,
            confirmationDate = start, shares = lot.originalShares, grossAmount = lot.grossCashOut,
            netAmount = lot.netSubscriptionAmount, purchaseFee = lot.purchaseFee, unitNav = lot.unitNav)
        val history = original.copy(transactions = listOf(buy), realLots = listOf(lot),
            entries = original.entries + RealLotOpened(LedgerEntryId("before-open"), 2, lot))
        val saved = TrackingBaseline.prepare(history, official, precision, recordedAt, start.plusDays(1))
        val result = complete(history, saved = saved)
        assertEquals(listOf(BigDecimal("-1100.00"), BigDecimal("1210.00")), result.cashFlows.map { it.amount })
        assertEquals(0, assertIs<XirrCalculation>(result.xirr).annualRate.compareTo(BigDecimal("0.1")))
        gap(AnnualizedReturnGapReason.BASELINE_HISTORY_CHANGED,
            assemble(history.copy(transactions = listOf(buy.copy(updatedAt = latest.fetchedAt))), saved = saved))
    }

    @Test
    fun `new purchase and reinvestment keep distinct external cash semantics`() {
        val lot = Lot(LotId("new-lot"), fund, TransactionId("new-buy"), LotType.REAL, end,
            BigDecimal("10.00"), BigDecimal("10.00"), BigDecimal("110.00"), BigDecimal("109.00"),
            BigDecimal("1.00"), BigDecimal("110.00"), BigDecimal("10.9000"))
        val buy = finalTransaction("new-buy", TransactionType.PURCHASE, "-110.00").copy(shares = lot.originalShares,
            grossAmount = lot.grossCashOut, netAmount = lot.netSubscriptionAmount, purchaseFee = lot.purchaseFee, unitNav = lot.unitNav)
        fun history(transaction: LedgerTransaction, opened: Lot) = original.copy(transactions = listOf(transaction),
            realLots = listOf(opened), entries = original.entries + RealLotOpened(LedgerEntryId("new-open"), 2, opened))
        val purchased = complete(history(buy, lot))
        assertEquals(3, purchased.cashFlows.size)
        assertEquals(0, assertIs<XirrCalculation>(purchased.xirr).annualRate.compareTo(BigDecimal("0.1")))
        val reinvestedLot = lot.copy(grossCashOut = BigDecimal.ZERO, netSubscriptionAmount = BigDecimal("110.00"),
            purchaseFee = BigDecimal.ZERO, unitNav = BigDecimal("11.0000"))
        val reinvested = buy.copy(type = TransactionType.DIVIDEND_REINVESTMENT, actualCashFlow = null, cashFlowDate = null,
            grossAmount = reinvestedLot.grossCashOut, netAmount = reinvestedLot.netSubscriptionAmount,
            purchaseFee = reinvestedLot.purchaseFee, unitNav = reinvestedLot.unitNav)
        val result = complete(history(reinvested, reinvestedLot))
        assertEquals(2, result.cashFlows.size)
        assertEquals(0, assertIs<XirrCalculation>(result.xirr).annualRate.compareTo(BigDecimal("0.21")))
        gap(AnnualizedReturnGapReason.RECORDED_FACTS_REQUIRE_REVIEW,
            assemble(history(buy.copy(actualCashFlow = BigDecimal("-109.00")), lot)))
    }

    @Test
    fun `incomplete imprecise or future actual cash and projection drift never produce a partial return`() {
        val dividend = finalTransaction("dividend", TransactionType.CASH_DIVIDEND, "25.00")
        listOf(dividend.copy(actualCashFlow = null, cashFlowDate = null), dividend.copy(cashFlowDate = null),
            dividend.copy(status = TransactionStatus.CALCULATED), dividend.copy(actualCashFlow = BigDecimal("25.001")),
            dividend.copy(cashFlowDate = end.plusDays(1)), dividend.copy(tradeDate = end.plusDays(1), navDate = null, confirmationDate = null))
            .forEach { gap(AnnualizedReturnGapReason.RECORDED_FACTS_REQUIRE_REVIEW, assemble(original.copy(transactions = listOf(it)))) }
        gap(AnnualizedReturnGapReason.RECORDED_FACTS_REQUIRE_REVIEW,
            assemble(original.copy(legacyLots = listOf(legacy.copy(remainingShares = BigDecimal("90.00"))))))
    }

    @Test
    fun `late old same day prepaid or pre existing transactions cannot be hidden across the start boundary`() {
        val dividend = finalTransaction("new", TransactionType.CASH_DIVIDEND, "25.00")
        listOf(dividend.copy(tradeDate = start, navDate = start, confirmationDate = start, cashFlowDate = start),
            dividend.copy(cashFlowDate = start), dividend.copy(cashFlowDate = start.minusDays(1)),
            dividend.copy(createdAt = recordedAt.minusSeconds(1)))
            .forEach { gap(AnnualizedReturnGapReason.FACTS_CROSS_TRACKING_BOUNDARY, assemble(original.copy(transactions = listOf(it)))) }
        val cancelled = dividend.copy(status = TransactionStatus.CANCELLED, tradeDate = start,
            navDate = start, confirmationDate = start, cashFlowDate = start)
        assertIs<XirrCalculation>(complete(original.copy(transactions = listOf(cancelled))).xirr)
        gap(AnnualizedReturnGapReason.INCOMPLETE_CASH_FLOWS,
            assemble(original.copy(transactions = listOf(dividend)), nav = latest.copy(navDate = end.minusDays(1))))
    }

    @Test
    fun `new imported or anonymous Legacy opening never invents a post start cash investment`() {
        val imported = finalTransaction("import", TransactionType.ADJUSTMENT, null).copy(origin = TransactionOrigin.IMPORTED)
        gap(AnnualizedReturnGapReason.FACTS_CROSS_TRACKING_BOUNDARY, assemble(original.copy(transactions = listOf(imported))))
        val extra = legacy.copy(id = LotId("extra"))
        gap(AnnualizedReturnGapReason.UNSUPPORTED_TRACKING_EVENT, assemble(original.copy(legacyLots = listOf(legacy, extra),
            entries = original.entries + LegacyLotOpened(LedgerEntryId("extra-open"), 2, extra))))
    }

    @Test
    fun `tampered baseline wrong fund changed precision or missing immutable prefix needs review`() {
        listOf(baseline.copy(startingValue = BigDecimal("700.00")), baseline.copy(startingShares = BigDecimal("99.00")),
            baseline.copy(officialNav = official.copy(authority = DataAuthority.RELIABLE_AGGREGATOR)))
            .forEach { gap(AnnualizedReturnGapReason.INVALID_TRACKING_BASELINE, assemble(saved = it)) }
        gap(AnnualizedReturnGapReason.INVALID_TRACKING_BASELINE, assemble(original.copy(fundId = FundId("other"))))
        gap(AnnualizedReturnGapReason.INVALID_TRACKING_BASELINE, assemble(configured = precision.copy(moneyScale = 3)))
        gap(AnnualizedReturnGapReason.BASELINE_HISTORY_CHANGED, assemble(original.copy(entries = emptyList())))
    }

    @Test
    fun `missing wrong stale future or unpublished terminal NAV keeps tracking return unavailable`() {
        gap(AnnualizedReturnGapReason.MISSING_PUBLISHED_NAV, assemble(nav = null))
        listOf(latest.copy(fundId = FundId("other")), latest.copy(navDate = end.plusDays(1)),
            latest.copy(navDate = start.minusDays(1)), latest.copy(authority = DataAuthority.USER_OR_PLATFORM_FINAL),
            latest.copy(unitNav = BigDecimal("11.00001")))
            .forEach { gap(AnnualizedReturnGapReason.INVALID_PUBLISHED_NAV, assemble(nav = it)) }
        assertIs<XirrCalculation>(complete(nav = latest.copy(authority = DataAuthority.RELIABLE_AGGREGATOR)).xirr)
    }

    @Test
    fun `known zero loss short period and extreme spans retain solver semantics and explicit scope`() {
        assertEquals(0, assertIs<XirrCalculation>(complete(nav = latest.copy(unitNav = BigDecimal("10.0000"))).xirr).annualRate.signum())
        assertEquals(0, assertIs<XirrCalculation>(complete(nav = latest.copy(unitNav = BigDecimal("9.0000"))).xirr).annualRate.compareTo(BigDecimal("-0.1")))
        val short = complete(nav = official, asOf = start.plusDays(1))
        assertEquals(XirrUnavailableReason.TOO_SHORT_PERIOD, assertIs<XirrUnavailable>(short.xirr).reason)
        assertEquals(XirrLabel.TRACKING_PERIOD_ANNUALIZED, short.xirr.label)
        val far = start.plusDays(1_000_000_000L)
        gap(AnnualizedReturnGapReason.UNSUPPORTED_DATE_RANGE, assemble(nav = latest.copy(navDate = far), asOf = far))
    }
}
