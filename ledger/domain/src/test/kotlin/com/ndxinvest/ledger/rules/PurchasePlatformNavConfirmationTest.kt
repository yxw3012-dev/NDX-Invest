package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.calculation.*
import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class PurchasePlatformNavConfirmationTest {
    private val date = LocalDate.parse("2026-09-14")
    private val now = Instant.parse("2026-09-18T08:00:00Z")
    private val precision = FundPrecision(2, 2, 4, RoundingMode.UNNECESSARY)
    private val lot = Lot(LotId("lot"), FundId("fund"), TransactionId("purchase"), LotType.REAL, date,
        BigDecimal("100.00"), BigDecimal("100.00"), BigDecimal("1000.00"), BigDecimal("990.00"),
        BigDecimal("10.00"), BigDecimal("1000.00"), BigDecimal("9.9000"))
    private val transaction = LedgerTransaction(lot.sourceTransactionId, lot.fundId, TransactionType.RECURRING_PURCHASE,
        TransactionStatus.CALCULATED, TransactionOrigin.AUTO_PLAN_ASSUMED, DataAuthority.APP_RULE_CALCULATION,
        now.minusSeconds(86400 * 7), date.minusDays(3), date.minusDays(3), date, null, RuleVersionId("rule"),
        requestedAmount = lot.grossCashOut, grossAmount = lot.grossCashOut, netAmount = lot.netSubscriptionAmount,
        purchaseFee = lot.purchaseFee, shares = lot.originalShares, unitNav = lot.unitNav)
    private val proof = NavRecord(lot.fundId, requireNotNull(transaction.navDate), BigDecimal("9.8000"), null,
        DataAuthority.FUND_OFFICIAL, DataSourceId("official"), now.minusSeconds(3600))
    private fun prepare(
        official: NavRecord = proof, nav: BigDecimal = proof.unitNav, cash: BigDecimal = lot.grossCashOut,
        net: BigDecimal = lot.netSubscriptionAmount, fee: BigDecimal = lot.purchaseFee,
        shares: BigDecimal = BigDecimal("101.02"), paid: LocalDate = date.minusDays(4), confirmed: LocalDate = date,
        expected: LedgerTransaction = transaction, sourceLot: Lot = lot,
    ) = PurchasePlatformNavConfirmation.prepare(expected, sourceLot, precision, official, cash, net, fee, nav,
        shares, paid, confirmed, now, date.plusDays(4))

    @Test
    fun `original day official NAV and explicit platform shares preserve origin dates cost and evidence`() {
        val result = prepare()
        assertEquals(proof, result.entry.officialNav)
        assertEquals(proof.unitNav, result.confirmed.unitNav)
        assertEquals(lot.grossCashOut, result.entry.correctedLot.costBasis)
        assertEquals(transaction.navDate, result.confirmed.navDate)
        assertEquals(transaction.tradeDate, result.confirmed.tradeDate)
        assertEquals(transaction.origin, result.confirmed.origin)
        assertEquals(TransactionStatus.CONFIRMED_ACTUAL, result.confirmed.status)
        assertEquals(DataAuthority.USER_OR_PLATFORM_FINAL, result.confirmed.authority)
        assertEquals(date.minusDays(4), result.confirmed.cashFlowDate)
        assertNull(result.confirmed.settlementDate)
        assertEquals(4, result.audits.size)
        val audit = result.audits.single { it.field == TransactionCorrectionField.UNIT_NAV }
        assertEquals(CorrectionValue.Decimal(lot.unitNav), audit.originalValue)
        assertEquals(CorrectionValue.Decimal(proof.unitNav), audit.correctedValue)
    }

    @Test
    fun `NAV correction can reconcile explicit cash fee and confirmation date without inventing fee`() {
        val result = prepare(cash = BigDecimal("900.00"), net = BigDecimal("900.00"), fee = BigDecimal("0.00"),
            shares = BigDecimal("91.84"), confirmed = date.plusDays(1))
        assertEquals(BigDecimal("900.00"), result.entry.correctedLot.costBasis)
        assertEquals(BigDecimal("-900.00"), result.confirmed.actualCashFlow)
        assertEquals(BigDecimal("0.00"), result.confirmed.purchaseFee)
        assertEquals(transaction.requestedAmount, result.confirmed.requestedAmount)
        assertEquals(8, result.audits.size)
        assertFailsWith<IllegalArgumentException> { prepare(expected = transaction.copy(purchaseFee = null)) }
    }

    @Test
    fun `aggregator wrong fund wrong day future proof unmatched input and unchanged NAV cannot authorize override`() {
        listOf(proof.copy(authority = DataAuthority.RELIABLE_AGGREGATOR),
            proof.copy(authority = DataAuthority.USER_OR_PLATFORM_FINAL), proof.copy(fundId = FundId("other")),
            proof.copy(navDate = date), proof.copy(fetchedAt = now.plusSeconds(1))).forEach {
            assertFailsWith<IllegalArgumentException> { prepare(official = it) }
        }
        assertFailsWith<IllegalArgumentException> { prepare(nav = BigDecimal("9.7000")) }
        assertFailsWith<IllegalArgumentException> { prepare(official = proof.copy(unitNav = lot.unitNav), nav = lot.unitNav) }
        assertFailsWith<IllegalArgumentException> { prepare(official = proof.copy(unitNav = BigDecimal("9.80001")), nav = BigDecimal("9.80001")) }
    }

    @Test
    fun `invalid platform facts future dates finalized or imported purchases cannot enter NAV correction`() {
        assertFailsWith<IllegalArgumentException> { prepare(cash = BigDecimal("1000.001")) }
        assertFailsWith<IllegalArgumentException> { prepare(net = BigDecimal("989.00")) }
        assertFailsWith<IllegalArgumentException> { prepare(fee = BigDecimal("-1.00")) }
        assertFailsWith<IllegalArgumentException> { prepare(shares = BigDecimal("101.021")) }
        assertFailsWith<IllegalArgumentException> { prepare(shares = BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { prepare(paid = date.plusDays(1)) }
        assertFailsWith<IllegalArgumentException> { prepare(confirmed = date.plusDays(5)) }
        assertFailsWith<IllegalArgumentException> { prepare(confirmed = date.minusDays(4)) }
        assertFailsWith<IllegalArgumentException> { prepare(expected = transaction.copy(origin = TransactionOrigin.IMPORTED)) }
        assertFailsWith<IllegalArgumentException> { prepare(expected = transaction.copy(status = TransactionStatus.CONFIRMED_ACTUAL)) }
        assertFailsWith<IllegalArgumentException> { prepare(expected = transaction.copy(actualCashFlow = BigDecimal("-1000.00"))) }
        assertFailsWith<IllegalArgumentException> { prepare(expected = transaction.copy(updatedAt = now.plusSeconds(1))) }
    }

    @Test
    fun `immutable NAV correction rebuild agrees with incremental sale and rejects consumed or tampered lots`() {
        val correction = prepare().entry.copy(sequence = 2)
        val opening = RealLotOpened(LedgerEntryId("open"), 1, lot)
        val sale = LotsRedeemed(LedgerEntryId("sale"), 3, lot.fundId,
            listOf(LotConsumption(lot.id, BigDecimal("10.00"), BigDecimal("99.00"))))
        val entries = listOf(opening, correction, sale)
        val replay = LedgerRebuilder.rebuild(entries.reversed())
        assertEquals(entries.fold(RebuiltLedger.empty(), LedgerRebuilder::apply), replay)
        assertEquals(BigDecimal("91.02"), replay.realLots.single().remainingShares)
        assertEquals(BigDecimal("901.00"), replay.realLots.single().costBasis)
        assertEquals(lot, opening.lot)
        assertFailsWith<IllegalArgumentException> { LedgerRebuilder.apply(replay, correction) }
        assertFailsWith<IllegalArgumentException> { prepare(sourceLot = lot.copy(remainingShares = BigDecimal("90.00"), costBasis = BigDecimal("900.00"))) }
        assertFailsWith<IllegalArgumentException> { correction.copy(officialNav = proof.copy(authority = DataAuthority.RELIABLE_AGGREGATOR)) }
        assertFailsWith<IllegalArgumentException> { correction.copy(officialNav = proof.copy(unitNav = BigDecimal("9.7000"))) }
        assertFailsWith<IllegalArgumentException> { correction.copy(correctedLot = correction.correctedLot.copy(sourceTransactionId = TransactionId("other"))) }
        assertFailsWith<IllegalArgumentException> { correction.copy(correctedLot = correction.correctedLot.copy(costBasis = BigDecimal("999.00"))) }
    }
}
