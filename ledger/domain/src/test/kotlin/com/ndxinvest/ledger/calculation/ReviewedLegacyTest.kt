package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*
import kotlin.test.*

class ReviewedLegacyTest {
    private val id = FundId("old-fund")
    private val date = LocalDate.parse("2026-09-14")
    private val now = Instant.parse("2026-09-15T08:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val fund = Fund(id, "OLD", "旧持仓", "旧持仓", "INDEX", null, emptyMap(), null, FundPrecision(2, 2, 4, RoundingMode.HALF_UP))
    private val rule = FundRuleVersion(RuleVersionId("rule"), id, date.minusYears(1), null, LocalTime.of(15, 0),
        RuleExpression("T_PLUS_1"), RuleExpression("T_PLUS_2"), RuleExpression("FUND_OPEN_DAY"), PurchaseFeeRule.Rate(BigDecimal.ZERO),
        RedemptionFeeSchedule.noFee(), LotAllocationPolicy.FIFO, DataAuthority.FUND_OFFICIAL, DataSourceId("official"), now.minusSeconds(60), emptySet())
    private val lot = LegacyLot(LotId("old"), id, BigDecimal("100.00"), BigDecimal("700.00"), null)
    private val tx = LedgerTransaction(TransactionId("import"), id, TransactionType.ADJUSTMENT, TransactionStatus.CONFIRMED_ACTUAL,
        TransactionOrigin.IMPORTED, DataAuthority.USER_OR_PLATFORM_FINAL, now, date, null, null, null, rule.id, shares = lot.remainingShares)
    private val original = ReviewedLegacy(fund, LedgerHistorySnapshot(id, emptyList(), emptyList(), emptyList(), emptyList()), listOf(rule),
        tx, null, lot, LegacyLotOpened(LedgerEntryId("open"), 1, lot))
    private fun sale(shares: String = "40.00", cash: String = "300.00"): ReviewedLegacy {
        val history = original.history.copy(transactions = listOf(tx), legacyLots = listOf(lot), entries = listOf(original.entry))
        val amount = BigDecimal(cash); val units = BigDecimal(shares)
        val calculation = LegacyLotCalculator.redeem(lot, units, amount, requireNotNull(fund.precision))
        val sold = tx.copy(id = TransactionId("sale"), type = TransactionType.REDEMPTION, origin = TransactionOrigin.USER_ENTERED,
            applicationTime = date.atTime(9, 0).atZone(zone).toInstant(), settlementDate = date.plusDays(1), shares = units,
            actualCashFlow = amount, netAmount = amount)
        return original.copy(history = history, transaction = sold, expectedLot = lot, updatedLot = calculation.updatedLot,
            entry = LegacyLotRedeemed(LedgerEntryId("sold"), 2, id, LotConsumption(lot.id, units, calculation.allocatedCostBasis), sold.id))
    }
    @Test fun `explicit Legacy import has unknown start and no fabricated historical cash or NAV`() {
        original.validate(now, zone)
        assertNull(original.updatedLot.trackingStartDate); assertNull(original.transaction.actualCashFlow)
        val zero = lot.copy(costBasis = BigDecimal("0.00"))
        original.copy(updatedLot = zero, entry = LegacyLotOpened(LedgerEntryId("zero"), 1, zero)).validate(now, zone)
    }
    @Test fun `import altered cash precision future rule and invented original dates cannot validate`() {
        for (bad in listOf(original.copy(transaction = tx.copy(actualCashFlow = BigDecimal("-700.00"))),
            original.copy(updatedLot = lot.copy(costBasis = BigDecimal("700.001"))), original.copy(transaction = tx.copy(tradeDate = date.plusDays(2))),
            original.copy(rules = emptyList()), original.copy(transaction = tx.copy(applicationTime = now.minusSeconds(60)))))
            assertFailsWith<IllegalArgumentException> { bad.validate(now, zone) }
    }
    @Test fun `partial and complete sale allocate stored remaining cost without inferred fees`() {
        val part = sale(); part.validate(now, zone)
        assertEquals(BigDecimal("420.00"), part.updatedLot.costBasis)
        val closed = sale("100.00", "0.00"); closed.validate(now, zone)
        assertEquals(0, closed.updatedLot.costBasis.signum()); assertEquals(0, closed.updatedLot.remainingShares.signum())
    }
    @Test fun `tampered sale cost cash event and unavailable shares cannot validate`() {
        val proof = sale(); val event = proof.entry as LegacyLotRedeemed
        for (bad in listOf(proof.copy(updatedLot = proof.updatedLot.copy(costBasis = BigDecimal("419.00"))),
            proof.copy(entry = event.copy(consumption = event.consumption.copy(allocatedCostBasis = BigDecimal("279.00")))),
            proof.copy(transaction = proof.transaction.copy(netAmount = BigDecimal("299.00"))),
            proof.copy(transaction = proof.transaction.copy(shares = BigDecimal("100.01")))))
            assertFailsWith<IllegalArgumentException> { bad.validate(now, zone) }
    }
    @Test fun `pending sale and retroactive sale across recorded Legacy consumption stay blocked`() {
        val proof = sale()
        val pending = proof.transaction.copy(id = TransactionId("pending"), status = TransactionStatus.PENDING_CALC)
        assertFailsWith<IllegalArgumentException> { proof.copy(history = proof.history.copy(transactions = proof.history.transactions + pending)).validate(now, zone) }
        val prior = proof.transaction.copy(id = TransactionId("prior"), tradeDate = date.plusDays(1), settlementDate = date.plusDays(1))
        val after = proof.history.copy(transactions = proof.history.transactions + prior, legacyLots = listOf(proof.updatedLot),
            entries = proof.history.entries + (proof.entry as LegacyLotRedeemed).copy(sourceTransactionId = prior.id))
        val calculation = LegacyLotCalculator.redeem(proof.updatedLot, BigDecimal("10.00"), BigDecimal("80.00"), requireNotNull(fund.precision))
        val late = proof.copy(history = after, expectedLot = proof.updatedLot, updatedLot = calculation.updatedLot,
            transaction = proof.transaction.copy(shares = BigDecimal("10.00"), actualCashFlow = BigDecimal("80.00"), netAmount = BigDecimal("80.00")),
            entry = LegacyLotRedeemed(LedgerEntryId("retroactive"), 3, id,
                LotConsumption(lot.id, BigDecimal("10.00"), calculation.allocatedCostBasis), proof.transaction.id))
        assertFailsWith<IllegalArgumentException> { late.validate(now, zone) }
    }
}
