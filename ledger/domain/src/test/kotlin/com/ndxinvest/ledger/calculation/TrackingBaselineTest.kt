package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class TrackingBaselineTest {
    private val fund = FundId("baseline-fund")
    private val date = LocalDate.parse("2026-09-14")
    private val now = Instant.parse("2026-09-15T08:00:00Z")
    private val precision = FundPrecision(2, 2, 4, RoundingMode.HALF_UP)
    private val legacy = LegacyLot(LotId("legacy"), fund, BigDecimal("100.00"), BigDecimal("700.00"), null)
    private val snapshot = LedgerHistorySnapshot(fund, emptyList(), emptyList(), listOf(legacy),
        listOf(LegacyLotOpened(LedgerEntryId("legacy-open"), 1, legacy)))
    private val nav = NavRecord(fund, date, BigDecimal("10.0000"), BigDecimal("13.1234"),
        DataAuthority.FUND_OFFICIAL, DataSourceId("fund-company"), now.minusSeconds(3600))
    private fun prepare(history: LedgerHistorySnapshot = snapshot, witness: NavRecord = nav, configured: FundPrecision = precision) =
        TrackingBaseline.prepare(history, witness, configured, now, date.plusDays(1))

    @Test
    fun `explicit Legacy baseline uses formal dated market value never historical cost or fake purchase cash`() {
        val baseline = prepare()
        assertEquals(date, baseline.date)
        assertEquals(BigDecimal("100.00"), baseline.startingShares)
        assertEquals(BigDecimal("1000.00"), baseline.startingValue)
        assertEquals(BigDecimal("700.00"), baseline.history.legacyLots.single().costBasis)
        assertNull(baseline.history.legacyLots.single().trackingStartDate)
        assertTrue(baseline.history.transactions.isEmpty())
        assertEquals(nav, baseline.officialNav)
        assertEquals(snapshot, baseline.history)
    }

    @Test
    fun `aggregator wrong fund future date future evidence or imprecise NAV cannot establish a baseline`() {
        listOf(nav.copy(authority = DataAuthority.RELIABLE_AGGREGATOR), nav.copy(fundId = FundId("other")),
            nav.copy(navDate = date.plusDays(2)), nav.copy(fetchedAt = now.plusSeconds(1)),
            nav.copy(unitNav = BigDecimal("10.00001"))).forEach { witness ->
            assertFailsWith<IllegalArgumentException> { prepare(witness = witness) }
        }
    }

    @Test
    fun `inconsistent replay empty holdings or over precise shares and cost cannot be silently rounded`() {
        assertFailsWith<IllegalArgumentException> { prepare(snapshot.copy(legacyLots = emptyList())) }
        assertFailsWith<IllegalArgumentException> { prepare(LedgerHistorySnapshot(fund, emptyList(), emptyList(), emptyList(), emptyList())) }
        listOf(legacy.copy(remainingShares = BigDecimal("100.001")), legacy.copy(costBasis = BigDecimal("700.001")),
            legacy.copy(trackingStartDate = date.plusDays(1))).forEach { lot ->
            val history = snapshot.copy(legacyLots = listOf(lot), entries = listOf(LegacyLotOpened(LedgerEntryId("opening"), 1, lot)))
            assertFailsWith<IllegalArgumentException> { prepare(history) }
        }
    }

    @Test
    fun `pending planned abnormal or calculated transactions cannot cross an explicit start boundary`() {
        val request = LedgerTransaction(TransactionId("pending"), fund, TransactionType.PURCHASE, TransactionStatus.PENDING_CALC,
            TransactionOrigin.USER_ENTERED, DataAuthority.APP_RULE_CALCULATION, now, date, date, null, null, RuleVersionId("rule"))
        listOf(TransactionStatus.PENDING_CALC, TransactionStatus.PLANNED, TransactionStatus.ABNORMAL).forEach { status ->
            assertFailsWith<IllegalArgumentException> { prepare(snapshot.copy(transactions = listOf(request.copy(status = status)))) }
        }
        assertEquals(BigDecimal("1000.00"), prepare(snapshot.copy(transactions = listOf(request.copy(status = TransactionStatus.CANCELLED)))).startingValue)
    }

    @Test
    fun `imported metadata is not investment while transactions and cash after the baseline date are blocked`() {
        val imported = LedgerTransaction(TransactionId("import"), fund, TransactionType.ADJUSTMENT, TransactionStatus.CONFIRMED_ACTUAL,
            TransactionOrigin.IMPORTED, DataAuthority.USER_OR_PLATFORM_FINAL, now, date, null, null, null, RuleVersionId("rule"))
        assertEquals(BigDecimal("1000.00"), prepare(snapshot.copy(transactions = listOf(imported))).startingValue)
        assertFailsWith<IllegalArgumentException> { prepare(snapshot.copy(transactions = listOf(imported.copy(tradeDate = date.plusDays(1))))) }
        val dividend = imported.copy(origin = TransactionOrigin.USER_ENTERED, type = TransactionType.CASH_DIVIDEND,
            actualCashFlow = BigDecimal("25.00"), cashFlowDate = date.plusDays(1))
        assertFailsWith<IllegalArgumentException> { prepare(snapshot.copy(transactions = listOf(dividend))) }
        assertEquals(BigDecimal("1000.00"), prepare(snapshot.copy(transactions = listOf(dividend.copy(cashFlowDate = date)))).startingValue)
        assertFailsWith<IllegalArgumentException> { prepare(snapshot.copy(transactions = listOf(dividend.copy(actualCashFlow = null, cashFlowDate = null)))) }
        assertFailsWith<IllegalArgumentException> { prepare(snapshot.copy(transactions = listOf(imported.copy(updatedAt = now.plusSeconds(1))))) }
        assertFailsWith<IllegalArgumentException> { prepare(snapshot.copy(transactions = listOf(imported.copy(status = TransactionStatus.PENDING_CALC)))) }
    }

    @Test
    fun `mixed real and Legacy facts retain actual purchase date and are valued together without using cost`() {
        val lot = Lot(LotId("real"), fund, TransactionId("buy"), LotType.REAL, date, BigDecimal("10.00"), BigDecimal("10.00"),
            BigDecimal("95.00"), BigDecimal("90.00"), BigDecimal("5.00"), BigDecimal("95.00"), BigDecimal("9.0000"))
        val buy = LedgerTransaction(lot.sourceTransactionId, fund, TransactionType.PURCHASE, TransactionStatus.CONFIRMED_ACTUAL,
            TransactionOrigin.USER_ENTERED, DataAuthority.USER_OR_PLATFORM_FINAL, now, date, date, date, null, RuleVersionId("rule"),
            grossAmount = lot.grossCashOut, netAmount = lot.netSubscriptionAmount, shares = lot.originalShares,
            unitNav = lot.unitNav, purchaseFee = lot.purchaseFee, actualCashFlow = BigDecimal("-95.00"), cashFlowDate = date.minusDays(1))
        val input = snapshot.copy(transactions = listOf(buy), realLots = listOf(lot),
            entries = snapshot.entries + RealLotOpened(LedgerEntryId("real-open"), 2, lot))
        val baseline = prepare(input)
        assertEquals(BigDecimal("1100.00"), baseline.startingValue)
        assertEquals(date.minusDays(1), baseline.history.transactions.single().cashFlowDate)
        assertFailsWith<IllegalArgumentException> { prepare(input.copy(transactions = listOf(buy.copy(actualCashFlow = BigDecimal("-94.00"))))) }
        assertFailsWith<IllegalArgumentException> { prepare(input.copy(transactions = listOf(buy.copy(status = TransactionStatus.CALCULATED)))) }
    }

    @Test
    fun `unnecessary valuation rounding rejects an unrepresentable starting value instead of changing money`() {
        val lot = legacy.copy(remainingShares = BigDecimal("0.01"))
        val input = snapshot.copy(legacyLots = listOf(lot), entries = listOf(LegacyLotOpened(LedgerEntryId("small"), 1, lot)))
        assertFailsWith<ArithmeticException> { prepare(input, nav.copy(unitNav = BigDecimal("10.1111")), precision.copy(roundingMode = RoundingMode.UNNECESSARY)) }
        assertFailsWith<IllegalArgumentException> { prepare(input, nav.copy(unitNav = BigDecimal("0.0100"))) }
    }
}
