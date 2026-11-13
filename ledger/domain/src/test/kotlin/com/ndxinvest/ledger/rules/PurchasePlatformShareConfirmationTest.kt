package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.calculation.*
import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class PurchasePlatformShareConfirmationTest {
    private val date = LocalDate.parse("2026-09-14")
    private val now = Instant.parse("2026-09-18T08:00:00Z")
    private val precision = FundPrecision(2, 2, 4, RoundingMode.UNNECESSARY)
    private val lot = Lot(
        LotId("lot"), FundId("fund"), TransactionId("purchase"), LotType.REAL, date,
        BigDecimal("100.00"), BigDecimal("100.00"), BigDecimal("1000.00"), BigDecimal("990.00"),
        BigDecimal("10.00"), BigDecimal("1000.00"), BigDecimal("9.9000"),
    )
    private val transaction = LedgerTransaction(
        lot.sourceTransactionId, lot.fundId, TransactionType.RECURRING_PURCHASE, TransactionStatus.CALCULATED,
        TransactionOrigin.AUTO_PLAN_ASSUMED, DataAuthority.APP_RULE_CALCULATION, now.minusSeconds(86400 * 7),
        date.minusDays(3), date.minusDays(3), date, null, RuleVersionId("rule"),
        grossAmount = lot.grossCashOut, netAmount = lot.netSubscriptionAmount, purchaseFee = lot.purchaseFee,
        shares = lot.originalShares, unitNav = lot.unitNav,
    )
    private fun prepare(
        expected: LedgerTransaction = transaction,
        expectedLot: Lot = lot,
        shares: BigDecimal = BigDecimal("99.99"),
        cash: BigDecimal = BigDecimal("1000.00"),
        confirmedDate: LocalDate = date.plusDays(1),
        paidDate: LocalDate = date.minusDays(4),
    ) = PurchasePlatformShareConfirmation.prepare(
        expected, expectedLot, precision, cash, paidDate, shares, confirmedDate, now, date.plusDays(4),
    )

    @Test
    fun `final shares and date append a correction and preserve money NAV origin and independent dates`() {
        val result = prepare()
        assertEquals(TransactionStatus.CONFIRMED_ACTUAL, result.confirmed.status)
        assertEquals(DataAuthority.USER_OR_PLATFORM_FINAL, result.confirmed.authority)
        assertEquals(transaction.origin, result.confirmed.origin)
        assertEquals(transaction.tradeDate, result.confirmed.tradeDate)
        assertEquals(transaction.navDate, result.confirmed.navDate)
        assertNull(result.confirmed.settlementDate)
        assertEquals(BigDecimal("-1000.00"), result.confirmed.actualCashFlow)
        assertEquals(date.minusDays(4), result.confirmed.cashFlowDate)
        assertEquals(lot.costBasis, result.entry.correctedLot.costBasis)
        assertEquals(lot.purchaseFee, result.confirmed.purchaseFee)
        assertEquals(lot.unitNav, result.confirmed.unitNav)
        assertEquals(4, result.audits.size)
        assertTrue(result.audits.all { it.source == CorrectionSource.SALES_PLATFORM_FINAL })
        assertEquals(CorrectionValue.Decimal(BigDecimal("100.00")), result.audits.first().originalValue)
    }

    @Test
    fun `date only and shares only corrections audit exactly the changed facts`() {
        assertEquals(3, prepare(shares = lot.originalShares).audits.size)
        assertEquals(3, prepare(confirmedDate = date).audits.size)
        assertFailsWith<IllegalArgumentException> { prepare(shares = lot.originalShares, confirmedDate = date) }
    }

    @Test
    fun `full replay and incremental replay produce the same corrected lot and future redemption`() {
        val result = prepare()
        val opening = RealLotOpened(LedgerEntryId("open"), 1, lot)
        val correction = result.entry.copy(sequence = 2)
        val sale = LotsRedeemed(LedgerEntryId("sale"), 3, lot.fundId,
            listOf(LotConsumption(lot.id, BigDecimal("9.99"), BigDecimal("99.91"))))
        val entries = listOf(opening, correction, sale)
        val rebuilt = LedgerRebuilder.rebuild(entries.reversed())
        assertEquals(entries.fold(RebuiltLedger.empty(), LedgerRebuilder::apply), rebuilt)
        assertEquals(BigDecimal("90.00"), rebuilt.realLots.single().remainingShares)
        assertEquals(BigDecimal("900.09"), rebuilt.realLots.single().costBasis)
        assertEquals(lot, opening.lot)
        assertFailsWith<IllegalArgumentException> { LedgerRebuilder.apply(rebuilt, correction) }
    }

    @Test
    fun `consumed stale altered cost or identity cannot be replaced in replay`() {
        assertFailsWith<IllegalArgumentException> { prepare(expectedLot = lot.copy(remainingShares = BigDecimal("90.00"))) }
        assertFailsWith<IllegalArgumentException> { prepare(expectedLot = lot.copy(costBasis = BigDecimal("900.00"))) }
        val correction = prepare().entry.copy(sequence = 3)
        val sold = LedgerRebuilder.rebuild(listOf(
            RealLotOpened(LedgerEntryId("open"), 1, lot),
            LotsRedeemed(LedgerEntryId("sale"), 2, lot.fundId,
                listOf(LotConsumption(lot.id, BigDecimal("10.00"), BigDecimal("100.00")))),
        ))
        assertFailsWith<IllegalArgumentException> { LedgerRebuilder.apply(sold, correction) }
        assertFailsWith<IllegalArgumentException> { correction.copy(correctedLot = correction.correctedLot.copy(id = LotId("other"))) }
        assertFailsWith<IllegalArgumentException> { correction.copy(correctedLot = correction.correctedLot.copy(unitNav = BigDecimal("9.8000"))) }
    }

    @Test
    fun `amount precision dates imported status and inconsistent source facts are refused`() {
        assertFailsWith<IllegalArgumentException> { prepare(cash = BigDecimal("999.00")) }
        assertFailsWith<IllegalArgumentException> { prepare(cash = BigDecimal("1000.001")) }
        assertFailsWith<IllegalArgumentException> { prepare(shares = BigDecimal("99.991")) }
        assertFailsWith<IllegalArgumentException> { prepare(shares = BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { prepare(confirmedDate = transaction.tradeDate.minusDays(1)) }
        assertFailsWith<IllegalArgumentException> { prepare(confirmedDate = date.plusDays(5)) }
        assertFailsWith<IllegalArgumentException> { prepare(paidDate = date.plusDays(2)) }
        assertFailsWith<IllegalArgumentException> { prepare(expected = transaction.copy(origin = TransactionOrigin.IMPORTED)) }
        assertFailsWith<IllegalArgumentException> { prepare(expected = transaction.copy(status = TransactionStatus.CONFIRMED_ACTUAL)) }
        assertFailsWith<IllegalArgumentException> { prepare(expected = transaction.copy(unitNav = BigDecimal("9.8000"))) }
        assertFailsWith<IllegalArgumentException> { prepare(expected = transaction.copy(updatedAt = now.plusSeconds(1))) }
        // A missing fee is not an explicitly known zero fee, even if the Lot stores zero.
        assertFailsWith<IllegalArgumentException> {
            prepare(
                expected = transaction.copy(netAmount = lot.grossCashOut, purchaseFee = null, unitNav = BigDecimal("10.0000")),
                expectedLot = lot.copy(netSubscriptionAmount = lot.grossCashOut, purchaseFee = BigDecimal("0.00"), unitNav = BigDecimal("10.0000")),
            )
        }
    }
}
