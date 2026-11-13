package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.calculation.*
import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class PurchasePlatformAmountConfirmationTest {
    private val date = LocalDate.parse("2026-09-14")
    private val now = Instant.parse("2026-09-18T08:00:00Z")
    private val precision = FundPrecision(2, 2, 4, RoundingMode.UNNECESSARY)
    private val lot = Lot(LotId("lot"), FundId("fund"), TransactionId("purchase"), LotType.REAL, date,
        BigDecimal("100.00"), BigDecimal("100.00"), BigDecimal("1000.00"), BigDecimal("990.00"),
        BigDecimal("10.00"), BigDecimal("1000.00"), BigDecimal("9.9000"))
    private val transaction = LedgerTransaction(
        lot.sourceTransactionId, lot.fundId, TransactionType.RECURRING_PURCHASE, TransactionStatus.CALCULATED,
        TransactionOrigin.AUTO_PLAN_ASSUMED, DataAuthority.APP_RULE_CALCULATION, now.minusSeconds(86400 * 7),
        date.minusDays(3), date.minusDays(3), date, null, RuleVersionId("rule"),
        requestedAmount = lot.grossCashOut, grossAmount = lot.grossCashOut, netAmount = lot.netSubscriptionAmount,
        purchaseFee = lot.purchaseFee, shares = lot.originalShares, unitNav = lot.unitNav,
    )
    private fun prepare(
        cash: BigDecimal = BigDecimal("900.00"), net: BigDecimal = BigDecimal("891.00"),
        fee: BigDecimal = BigDecimal("9.00"), nav: BigDecimal = lot.unitNav,
        shares: BigDecimal = BigDecimal("90.00"), paid: LocalDate = date.minusDays(4),
        confirmed: LocalDate = date, expected: LedgerTransaction = transaction, sourceLot: Lot = lot,
    ) = PurchasePlatformAmountConfirmation.prepare(
        expected, sourceLot, precision, cash, net, fee, nav, shares, paid, confirmed, now, date.plusDays(4),
    )

    @Test
    fun `explicit actual money fee and shares replace computed cost with six original value audits`() {
        val result = prepare()
        assertEquals(BigDecimal("900.00"), result.entry.correctedLot.costBasis)
        assertEquals(BigDecimal("891.00"), result.entry.correctedLot.netSubscriptionAmount)
        assertEquals(BigDecimal("9.00"), result.entry.correctedLot.purchaseFee)
        assertEquals(BigDecimal("-900.00"), result.confirmed.actualCashFlow)
        assertEquals(transaction.requestedAmount, result.confirmed.requestedAmount)
        assertEquals(transaction.origin, result.confirmed.origin)
        assertEquals(transaction.navDate, result.confirmed.navDate)
        assertEquals(transaction.unitNav, result.confirmed.unitNav)
        assertNull(result.confirmed.settlementDate)
        assertEquals(6, result.audits.size)
        assertEquals(CorrectionValue.Decimal(BigDecimal("1000.00")), result.audits.single { it.field == TransactionCorrectionField.GROSS_AMOUNT }.originalValue)
        assertEquals(CorrectionValue.Decimal(BigDecimal("990.00")), result.audits.single { it.field == TransactionCorrectionField.NET_AMOUNT }.originalValue)
        assertEquals(CorrectionValue.Decimal(BigDecimal("10.00")), result.audits.single { it.field == TransactionCorrectionField.PURCHASE_FEE }.originalValue)
        assertTrue(result.audits.all { it.source == CorrectionSource.SALES_PLATFORM_FINAL })
    }

    @Test
    fun `fee only discrepancy and explicit zero fee retain gross cost without deducting NAV expenses`() {
        val result = prepare(cash = BigDecimal("1000.00"), net = BigDecimal("1000.00"), fee = BigDecimal("0.00"), shares = BigDecimal("101.01"))
        assertEquals(lot.grossCashOut, result.entry.correctedLot.costBasis)
        assertEquals(BigDecimal("0.00"), result.confirmed.purchaseFee)
        assertFalse(result.audits.any { it.field == TransactionCorrectionField.GROSS_AMOUNT })
        assertEquals(5, result.audits.size)
        assertFailsWith<IllegalArgumentException> { prepare(cash = lot.grossCashOut, net = lot.netSubscriptionAmount, fee = lot.purchaseFee) }
    }

    @Test
    fun `missing source facts NAV drift invalid identities precision and dates fail without invented values`() {
        assertFailsWith<IllegalArgumentException> { prepare(expected = transaction.copy(purchaseFee = null)) }
        assertFailsWith<IllegalArgumentException> { prepare(expected = transaction.copy(netAmount = null)) }
        assertFailsWith<IllegalArgumentException> { prepare(nav = BigDecimal("9.8000")) }
        assertFailsWith<IllegalArgumentException> { prepare(nav = BigDecimal("9.90001")) }
        assertFailsWith<IllegalArgumentException> { prepare(cash = BigDecimal("900.001")) }
        assertFailsWith<IllegalArgumentException> { prepare(net = BigDecimal("891.001")) }
        assertFailsWith<IllegalArgumentException> { prepare(fee = BigDecimal("9.001")) }
        assertFailsWith<IllegalArgumentException> { prepare(net = BigDecimal("890.00")) }
        assertFailsWith<IllegalArgumentException> { prepare(fee = BigDecimal("-1.00")) }
        assertFailsWith<IllegalArgumentException> { prepare(cash = BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { prepare(net = BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { prepare(shares = BigDecimal("90.001")) }
        assertFailsWith<IllegalArgumentException> { prepare(shares = BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { prepare(paid = date.plusDays(1)) }
        assertFailsWith<IllegalArgumentException> { prepare(confirmed = date.plusDays(5)) }
        assertFailsWith<IllegalArgumentException> { prepare(confirmed = date.minusDays(4)) }
        assertFailsWith<IllegalArgumentException> { prepare(expected = transaction.copy(origin = TransactionOrigin.IMPORTED)) }
        assertFailsWith<IllegalArgumentException> { prepare(expected = transaction.copy(status = TransactionStatus.PENDING_CALC)) }
        assertFailsWith<IllegalArgumentException> { prepare(expected = transaction.copy(updatedAt = now.plusSeconds(1))) }
    }

    @Test
    fun `amount correction replay and future sale agree while consumed or altered lots are rejected`() {
        val correction = prepare().entry.copy(sequence = 2)
        val opening = RealLotOpened(LedgerEntryId("open"), 1, lot)
        val sale = LotsRedeemed(LedgerEntryId("sale"), 3, lot.fundId,
            listOf(LotConsumption(lot.id, BigDecimal("10.00"), BigDecimal("100.00"))))
        val entries = listOf(opening, correction, sale)
        val replay = LedgerRebuilder.rebuild(entries.reversed())
        assertEquals(entries.fold(RebuiltLedger.empty(), LedgerRebuilder::apply), replay)
        assertEquals(BigDecimal("80.00"), replay.realLots.single().remainingShares)
        assertEquals(BigDecimal("800.00"), replay.realLots.single().costBasis)
        assertEquals(lot, opening.lot)
        assertFailsWith<IllegalArgumentException> { LedgerRebuilder.apply(replay, correction) }
        assertFailsWith<IllegalArgumentException> { prepare(sourceLot = lot.copy(remainingShares = BigDecimal("90.00"), costBasis = BigDecimal("900.00"))) }
        assertFailsWith<IllegalArgumentException> { correction.copy(correctedLot = correction.correctedLot.copy(costBasis = BigDecimal("899.00"))) }
        assertFailsWith<IllegalArgumentException> { correction.copy(correctedLot = correction.correctedLot.copy(unitNav = BigDecimal("9.8000"))) }
        assertFailsWith<IllegalArgumentException> { correction.copy(correctedLot = correction.correctedLot.copy(sourceTransactionId = TransactionId("other"))) }
    }

    @Test
    fun `new gross and net audit fields require decimals and preserve original facts`() {
        listOf(TransactionCorrectionField.GROSS_AMOUNT, TransactionCorrectionField.NET_AMOUNT).forEach { field ->
            val corrected = TransactionCorrectionService.applySalesPlatformFinalCorrection(transaction, CorrectionId("audit-${field.name}"),
                field, CorrectionValue.Decimal(BigDecimal("900.00")), now)
            assertEquals(field, corrected.audit.field)
            assertFailsWith<IllegalArgumentException> {
                TransactionCorrectionService.applyManualCorrection(transaction, CorrectionId("invalid-${field.name}"),
                    field, CorrectionValue.Date(date), now)
            }
        }
    }
}
