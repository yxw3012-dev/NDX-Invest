package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class LedgerAnnualizedReturnAssemblerTest {
    private val fund = FundId("annual-fund")
    private val start = LocalDate.parse("2025-01-01")
    private val end = LocalDate.parse("2026-01-01")
    private val precision = FundPrecision(2, 2, 4, RoundingMode.HALF_UP)
    private val now = Instant.parse("2026-01-02T08:00:00Z")
    private val lot = Lot(LotId("lot"), fund, TransactionId("buy"), LotType.REAL, start.plusDays(1),
        BigDecimal("100.00"), BigDecimal("100.00"), BigDecimal("1000.00"), BigDecimal("990.00"),
        BigDecimal("10.00"), BigDecimal("1000.00"), BigDecimal("9.9000"))
    private val buy = LedgerTransaction(lot.sourceTransactionId, fund, TransactionType.PURCHASE,
        TransactionStatus.CONFIRMED_ACTUAL, TransactionOrigin.USER_ENTERED, DataAuthority.USER_OR_PLATFORM_FINAL,
        now.minusSeconds(366 * 86400), start, start, lot.confirmationDate, null, RuleVersionId("rule"),
        grossAmount = lot.grossCashOut, netAmount = lot.netSubscriptionAmount, shares = lot.originalShares,
        unitNav = lot.unitNav, purchaseFee = lot.purchaseFee, actualCashFlow = BigDecimal("-1000.00"), cashFlowDate = start)
    private val opening = RealLotOpened(LedgerEntryId("opening"), 1, lot)
    private val nav = NavRecord(fund, end, BigDecimal("11.0000"), null, DataAuthority.FUND_OFFICIAL, DataSourceId("official"), now)
    private fun snapshot() = LedgerHistorySnapshot(fund, listOf(buy), listOf(lot), emptyList(), listOf(opening))
    private fun assemble(input: LedgerHistorySnapshot = snapshot(), value: NavRecord? = nav, date: LocalDate = end.plusDays(1),
        configured: FundPrecision = precision) = LedgerAnnualizedReturnAssembler.assemble(input, value, date, configured)
    private fun reason(input: LedgerHistorySnapshot = snapshot(), value: NavRecord? = nav, date: LocalDate = end.plusDays(1),
        configured: FundPrecision = precision) = assertIs<LedgerAnnualizedReturnAssembly.Incomplete>(assemble(input, value, date, configured)).reason
    private fun sale(shares: String = "40.00", cash: String = "440.00") = buy.copy(id = TransactionId("sale"),
        type = TransactionType.REDEMPTION, tradeDate = start.plusMonths(6), navDate = start.plusMonths(6),
        confirmationDate = start.plusMonths(6).plusDays(1), grossAmount = BigDecimal("450.00"), netAmount = BigDecimal(cash),
        shares = BigDecimal(shares), purchaseFee = null, redemptionFee = BigDecimal("10.00"),
        actualCashFlow = BigDecimal(cash), cashFlowDate = start.plusMonths(6).plusDays(2))
    private fun withSale(shares: String = "40.00", cash: String = "440.00"): LedgerHistorySnapshot {
        val transaction = sale(shares, cash)
        val cost = BigDecimal(shares).multiply(BigDecimal.TEN)
        return snapshot().copy(transactions = listOf(buy, transaction), realLots = listOf(lot.copy(
            remainingShares = lot.originalShares.subtract(transaction.shares), costBasis = lot.costBasis.subtract(cost))),
            entries = listOf(opening, LotsRedeemed(LedgerEntryId("sale-entry"), 2, fund,
                listOf(LotConsumption(lot.id, requireNotNull(transaction.shares), cost)), transaction.id)))
    }

    @Test
    fun `full year actual payment and matching formal valuation reuse BigDecimal XIRR without percent conversion`() {
        val input = snapshot()
        val result = assertIs<LedgerAnnualizedReturnAssembly.Complete>(assemble(input))
        assertEquals(BigDecimal("0.10000000"), assertIs<XirrCalculation>(result.xirr).annualRate)
        assertEquals(XirrLabel.ANNUALIZED_RETURN, result.xirr.label)
        assertEquals(nav, result.valuationNav)
        assertEquals(end, result.valuationDate)
        assertEquals(BigDecimal("1100.00"), result.currentPositionValue)
        assertEquals(listOf(DatedCashFlow(start, BigDecimal("-1000.00"), CashFlowKind.PURCHASE),
            DatedCashFlow(end, BigDecimal("1100.00"), CashFlowKind.TERMINAL_VALUE)), result.cashFlows)
        assertEquals(input, snapshot())
    }

    @Test
    fun `partial sale and dividend use actual dates and cash once without fee deductions`() {
        val sold = withSale()
        val dividend = sold.transactions.last().copy(id = TransactionId("dividend"), type = TransactionType.CASH_DIVIDEND,
            tradeDate = start.plusMonths(8), navDate = null, confirmationDate = null, grossAmount = null, netAmount = null,
            shares = null, unitNav = null, redemptionFee = null, actualCashFlow = BigDecimal("50.00"), cashFlowDate = start.plusMonths(8))
        val input = sold.copy(transactions = sold.transactions + dividend)
        val result = assertIs<LedgerAnnualizedReturnAssembly.Complete>(assemble(input))
        val expected = listOf(DatedCashFlow(start, BigDecimal("-1000.00"), CashFlowKind.PURCHASE),
            DatedCashFlow(requireNotNull(sold.transactions.last().cashFlowDate), BigDecimal("440.00"), CashFlowKind.REDEMPTION),
            DatedCashFlow(start.plusMonths(8), BigDecimal("50.00"), CashFlowKind.CASH_DIVIDEND),
            DatedCashFlow(end, BigDecimal("660.00"), CashFlowKind.TERMINAL_VALUE))
        assertEquals(expected, result.cashFlows)
        assertEquals(XirrCalculator.calculate(expected, HistoryScope.FULL_HISTORY), result.xirr)
    }

    @Test
    fun `closed position needs no NAV and later read dates never invent a terminal return`() {
        val initial = withSale("100.00", "1100.00")
        val sale = initial.transactions.last().copy(tradeDate = end.minusDays(2), navDate = end.minusDays(2),
            confirmationDate = end.minusDays(1), cashFlowDate = end)
        val input = initial.copy(transactions = listOf(buy, sale))
        val result = assertIs<LedgerAnnualizedReturnAssembly.Complete>(assemble(input, null))
        assertEquals(BigDecimal("0.10000000"), assertIs<XirrCalculation>(result.xirr).annualRate)
        assertNull(result.valuationNav)
        assertEquals(BigDecimal("0.00"), result.currentPositionValue)
        assertTrue(result.cashFlows.none { it.kind == CashFlowKind.TERMINAL_VALUE })
        assertEquals(result.xirr, assertIs<LedgerAnnualizedReturnAssembly.Complete>(assemble(input, null, end.plusYears(3))).xirr)
        assertEquals(AnnualizedReturnGapReason.NO_POSITIVE_RETURN, reason(withSale("100.00", "0.00"), null))
    }

    @Test
    fun `missing wrong fund future unofficial or over precise NAV cannot value a live position`() {
        assertEquals(AnnualizedReturnGapReason.MISSING_PUBLISHED_NAV, reason(value = null))
        listOf(nav.copy(fundId = FundId("other")), nav.copy(navDate = end.plusDays(2)),
            nav.copy(authority = DataAuthority.USER_OR_PLATFORM_FINAL), nav.copy(unitNav = BigDecimal("11.00001"))).forEach {
            assertEquals(AnnualizedReturnGapReason.INVALID_PUBLISHED_NAV, reason(value = it))
        }
        assertIs<LedgerAnnualizedReturnAssembly.Complete>(assemble(value = nav.copy(authority = DataAuthority.RELIABLE_AGGREGATOR)))
    }

    @Test
    fun `current transaction or cash after published valuation cannot be mixed into old NAV`() {
        val input = withSale()
        val stale = nav.copy(navDate = start.plusMonths(6))
        val result = assertIs<LedgerAnnualizedReturnAssembly.Incomplete>(assemble(input, stale))
        assertEquals(AnnualizedReturnGapReason.INCOMPLETE_CASH_FLOWS, result.reason)
        assertTrue(result.cashFlowGaps.any { it.reason == CashFlowGapReason.TRANSACTION_AFTER_VALUATION })
        val justBeforeCash = nav.copy(navDate = start.plusMonths(6).plusDays(1))
        assertTrue(assertIs<LedgerAnnualizedReturnAssembly.Incomplete>(assemble(input, justBeforeCash)).cashFlowGaps
            .any { it.reason == CashFlowGapReason.CASH_AFTER_VALUATION })
    }

    @Test
    fun `incomplete actual cash and inconsistent history are typed gaps not a fabricated annual rate`() {
        val estimated = buy.copy(status = TransactionStatus.CALCULATED, authority = DataAuthority.APP_RULE_CALCULATION,
            actualCashFlow = null, cashFlowDate = null)
        val missing = assemble(snapshot().copy(transactions = listOf(estimated)))
        assertTrue(assertIs<LedgerAnnualizedReturnAssembly.Incomplete>(missing).recordedFacts!!.gaps
            .any { it.reason == RealizedReturnGapReason.UNFINISHED_TRANSACTION })
        assertEquals(AnnualizedReturnGapReason.RECORDED_FACTS_REQUIRE_REVIEW, reason(snapshot().copy(realLots = emptyList())))
        assertEquals(AnnualizedReturnGapReason.RECORDED_FACTS_REQUIRE_REVIEW, reason(snapshot().copy(transactions =
            listOf(buy.copy(actualCashFlow = BigDecimal("-999.00"))))))
    }

    @Test
    fun `Legacy or imported history requires an explicit future baseline never its historical cost`() {
        val legacy = LegacyLot(LotId("legacy"), fund, BigDecimal("10.00"), BigDecimal("100.00"), start)
        val input = snapshot().copy(legacyLots = listOf(legacy), entries = listOf(opening,
            LegacyLotOpened(LedgerEntryId("legacy-open"), 2, legacy)))
        assertEquals(AnnualizedReturnGapReason.LEGACY_BASELINE_REQUIRED, reason(input))
        val imported = buy.copy(id = TransactionId("imported"), type = TransactionType.ADJUSTMENT,
            origin = TransactionOrigin.IMPORTED, actualCashFlow = null, cashFlowDate = null)
        assertEquals(AnnualizedReturnGapReason.LEGACY_BASELINE_REQUIRED, reason(snapshot().copy(transactions = listOf(buy, imported))))
    }

    @Test
    fun `same day history retains explicit too short result and extreme date spans fail before solving`() {
        val sameDayLot = lot.copy(confirmationDate = start)
        val sameDay = snapshot().copy(transactions = listOf(buy.copy(confirmationDate = start)), realLots = listOf(sameDayLot),
            entries = listOf(opening.copy(lot = sameDayLot)))
        val result = assertIs<LedgerAnnualizedReturnAssembly.Complete>(assemble(sameDay, nav.copy(navDate = start)))
        assertEquals(XirrUnavailableReason.TOO_SHORT_PERIOD, assertIs<XirrUnavailable>(result.xirr).reason)
        assertEquals(AnnualizedReturnGapReason.UNSUPPORTED_DATE_RANGE, reason(value = nav.copy(navDate = LocalDate.MAX), date = LocalDate.MAX))
    }

    @Test
    fun `unnecessary valuation rounding produces unknown instead of throwing or silently rounding`() {
        val input = withSale("99.99", "1099.89")
        assertEquals(AnnualizedReturnGapReason.INVALID_VALUATION_PRECISION, reason(input, nav.copy(unitNav = BigDecimal("11.1111")),
            configured = precision.copy(roundingMode = RoundingMode.UNNECESSARY)))
    }

    @Test
    fun `reinvestment contributes terminal shares but never becomes external dividend or investment cash`() {
        val reinvested = Lot(LotId("reinvest"), fund, TransactionId("reinvestment"), LotType.REAL, start.plusMonths(6),
            BigDecimal("10.00"), BigDecimal("10.00"), BigDecimal("0.00"), BigDecimal("100.00"), BigDecimal("0.00"),
            BigDecimal("100.00"), BigDecimal("10.0000"))
        val transaction = buy.copy(id = reinvested.sourceTransactionId, type = TransactionType.DIVIDEND_REINVESTMENT,
            confirmationDate = reinvested.confirmationDate, grossAmount = BigDecimal("100.00"), netAmount = BigDecimal("100.00"),
            shares = reinvested.originalShares, unitNav = reinvested.unitNav, purchaseFee = BigDecimal.ZERO,
            actualCashFlow = null, cashFlowDate = null)
        val input = snapshot().copy(transactions = listOf(buy, transaction), realLots = listOf(lot, reinvested),
            entries = listOf(opening, RealLotOpened(LedgerEntryId("reinvest-open"), 2, reinvested)))
        val result = assertIs<LedgerAnnualizedReturnAssembly.Complete>(assemble(input, nav.copy(unitNav = BigDecimal("10.0000"))))
        assertEquals(BigDecimal("1100.00"), result.currentPositionValue)
        assertEquals(2, result.cashFlows.size)
        assertEquals(BigDecimal("0.10000000"), assertIs<XirrCalculation>(result.xirr).annualRate)
    }

    @Test
    fun `no root or multiple supported roots remain explicit unavailable outcomes`() {
        listOf(Triple("230.00", "132.00", XirrUnavailableReason.MULTIPLE_ROOTS),
            Triple("200.00", "150.00", XirrUnavailableReason.NO_ROOT)).forEach { (proceeds, secondCost, expected) ->
            val first = lot.copy(confirmationDate = start, grossCashOut = BigDecimal("100.00"),
                netSubscriptionAmount = BigDecimal("100.00"), purchaseFee = BigDecimal.ZERO, costBasis = BigDecimal("100.00"),
                originalShares = BigDecimal("10.00"), remainingShares = BigDecimal("10.00"), unitNav = BigDecimal("10.0000"))
            val firstBuy = buy.copy(confirmationDate = start, grossAmount = first.grossCashOut, netAmount = first.netSubscriptionAmount,
                purchaseFee = first.purchaseFee, unitNav = first.unitNav, shares = first.originalShares, actualCashFlow = first.grossCashOut.negate())
            val second = first.copy(id = LotId("second"), sourceTransactionId = TransactionId("second-buy"), confirmationDate = start.plusDays(2),
                grossCashOut = BigDecimal(secondCost), netSubscriptionAmount = BigDecimal(secondCost), costBasis = BigDecimal(secondCost),
                unitNav = BigDecimal(secondCost).divide(BigDecimal.TEN))
            val secondBuy = firstBuy.copy(id = second.sourceTransactionId, tradeDate = second.confirmationDate, navDate = second.confirmationDate,
                confirmationDate = second.confirmationDate, grossAmount = second.grossCashOut, netAmount = second.netSubscriptionAmount,
                unitNav = second.unitNav, actualCashFlow = second.grossCashOut.negate(), cashFlowDate = second.confirmationDate)
            val firstSale = sale("10.00", proceeds).copy(tradeDate = start.plusDays(1), navDate = start.plusDays(1),
                confirmationDate = start.plusDays(1), cashFlowDate = start.plusDays(1))
            val secondSale = firstSale.copy(id = TransactionId("second-sale"), tradeDate = second.confirmationDate,
                navDate = second.confirmationDate, confirmationDate = second.confirmationDate, cashFlowDate = second.confirmationDate,
                actualCashFlow = BigDecimal.ZERO, netAmount = BigDecimal.ZERO)
            val input = LedgerHistorySnapshot(fund, listOf(firstBuy, firstSale, secondBuy, secondSale),
                listOf(first.copy(remainingShares = BigDecimal.ZERO, costBasis = BigDecimal.ZERO),
                    second.copy(remainingShares = BigDecimal.ZERO, costBasis = BigDecimal.ZERO)), emptyList(),
                listOf(RealLotOpened(LedgerEntryId("first-open"), 1, first),
                    LotsRedeemed(LedgerEntryId("first-sale"), 2, fund, listOf(LotConsumption(first.id, first.originalShares, first.costBasis)), firstSale.id),
                    RealLotOpened(LedgerEntryId("second-open"), 3, second),
                    LotsRedeemed(LedgerEntryId("second-sale"), 4, fund, listOf(LotConsumption(second.id, second.originalShares, second.costBasis)), secondSale.id)))
            val result = assertIs<LedgerAnnualizedReturnAssembly.Complete>(assemble(input, null))
            assertEquals(expected, assertIs<XirrUnavailable>(result.xirr).reason)
        }
    }
}
