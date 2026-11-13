package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*
import kotlin.test.*

class ReviewedAmountRedemptionTest {
    private val id = FundId("amount-fund")
    private val date = LocalDate.parse("2026-09-14")
    private val now = Instant.parse("2026-09-15T08:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val fund = Fund(id, "FUND", "基金", "基金", "INDEX", null, emptyMap(), null, FundPrecision(2, 2, 4, RoundingMode.HALF_UP))
    private val rule = FundRuleVersion(RuleVersionId("rule"), id, date.minusYears(1), null, LocalTime.of(15, 0),
        RuleExpression("T_PLUS_1"), RuleExpression("T_PLUS_2"), RuleExpression("FUND_OPEN_DAY"), PurchaseFeeRule.Rate(BigDecimal.ZERO),
        RedemptionFeeSchedule.noFee(), LotAllocationPolicy.FIFO, DataAuthority.FUND_OFFICIAL, DataSourceId("official"), now.minusSeconds(60), emptySet())
    private val lot = Lot(LotId("buy-lot"), id, TransactionId("buy"), LotType.REAL, date, BigDecimal("100.00"), BigDecimal("100.00"),
        BigDecimal("1000.00"), BigDecimal("1000.00"), BigDecimal("0.00"), BigDecimal("1000.00"), BigDecimal("10.0000"))
    private val buy = LedgerTransaction(lot.sourceTransactionId, id, TransactionType.PURCHASE, TransactionStatus.CALCULATED,
        TransactionOrigin.USER_ENTERED, DataAuthority.APP_RULE_CALCULATION, now.minusSeconds(86400), date, date, date, null, rule.id,
        grossAmount = lot.grossCashOut, netAmount = lot.netSubscriptionAmount, shares = lot.originalShares, unitNav = lot.unitNav,
        purchaseFee = lot.purchaseFee)
    private val tx = LedgerTransaction(TransactionId("amount-request"), id, TransactionType.REDEMPTION, TransactionStatus.PLANNED,
        TransactionOrigin.USER_ENTERED, DataAuthority.APP_RULE_CALCULATION, date.atTime(9, 0).atZone(zone).toInstant(), date, date,
        date.plusDays(2), null, rule.id, requestedAmount = BigDecimal("400.00"), createdAt = now, updatedAt = now)
    private val original = ReviewedAmountRedemption(fund, LedgerHistorySnapshot(id, listOf(buy), listOf(lot), emptyList(),
        listOf(RealLotOpened(LedgerEntryId("open"), 1, lot))), listOf(rule), tx, null,
        ReviewedFundOpenCalendar(date.minusDays(2), date.plusDays(5), (0L..5L).map { date.plusDays(it) }.toSet()), null)
    private fun activated(): ReviewedAmountRedemption {
        val shares = BigDecimal("40.00")
        return original.copy(history = original.history.copy(transactions = original.history.transactions + tx),
            transaction = tx.copy(status = TransactionStatus.PENDING_CALC, shares = shares), expected = tx, calendar = null,
            shareAudit = TransactionCorrection(CorrectionId("platform-redemption-shares:${tx.id.value}"), tx.id,
                TransactionCorrectionField.SHARES, CorrectionValue.Missing, CorrectionValue.Decimal(shares), CorrectionSource.SALES_PLATFORM_FINAL, now))
    }
    @Test fun `amount request retains native dates with no shares cash fees or consumption`() {
        original.validate(now, zone); assertNull(tx.shares); assertNull(tx.actualCashFlow)
        assertEquals(1, original.history.entries.size); assertEquals(lot, original.history.realLots.single())
        val calendar = requireNotNull(original.calendar).calendar()
        assertEquals(original.calendar.openDates, calendar.knownOpenDates())
    }
    @Test fun `forged estimates cash calendar dates rules precision and future application are rejected`() {
        for (bad in listOf(original.copy(transaction = tx.copy(shares = BigDecimal("40.00"))),
            original.copy(transaction = tx.copy(actualCashFlow = BigDecimal("400.00"))),
            original.copy(transaction = tx.copy(requestedAmount = BigDecimal("400.001"))),
            original.copy(transaction = tx.copy(navDate = date.plusDays(1))), original.copy(calendar = null),
            original.copy(transaction = tx.copy(applicationTime = now.plusSeconds(1))),
            original.copy(rules = listOf(rule.copy(redemptionConfirmationRule = RuleExpression("T_PLUS_1"))))))
            assertFailsWith<IllegalArgumentException> { bad.validate(now, zone) }
    }
    @Test fun `only final explicit available shares and exact audit can activate unchanged request`() {
        val proof = activated(); proof.validate(now, zone)
        for (bad in listOf(proof.copy(shareAudit = null), proof.copy(shareAudit = proof.shareAudit?.copy(source = CorrectionSource.USER)),
            proof.copy(transaction = proof.transaction.copy(shares = BigDecimal("100.01"))),
            proof.copy(transaction = proof.transaction.copy(requestedAmount = BigDecimal("401.00"))),
            proof.copy(transaction = proof.transaction.copy(actualCashFlow = BigDecimal("400.00"))), proof.copy(calendar = original.calendar)))
            assertFailsWith<IllegalArgumentException> { bad.validate(now, zone) }
    }
    @Test fun `pending reservations future T Legacy and abnormal sales block unsafe activation`() {
        val proof = activated()
        val reserved = tx.copy(id = TransactionId("pending"), status = TransactionStatus.PENDING_CALC, shares = BigDecimal("70.00"))
        val abnormal = reserved.copy(status = TransactionStatus.ABNORMAL)
        for (bad in listOf(proof.copy(history = proof.history.copy(transactions = proof.history.transactions + reserved)),
            proof.copy(history = proof.history.copy(transactions = proof.history.transactions + abnormal)),
            proof.copy(history = proof.history.copy(transactions = proof.history.transactions + reserved.copy(shares = null)))))
            assertFailsWith<IllegalArgumentException> { bad.validate(now, zone) }
        assertFailsWith<IllegalArgumentException> { proof.validate(now.minusSeconds(2 * 86400), zone) }
        assertFails { proof.copy(history = proof.history.copy(legacyLots = listOf(LegacyLot(LotId("legacy"), id,
            BigDecimal.ONE, BigDecimal.ONE, null)))).validate(now, zone) }
    }
    @Test fun `durable request identity survives calculation but not changed original facts or cancellation`() {
        val calculated = tx.copy(status = TransactionStatus.CALCULATED, shares = BigDecimal("40.00"),
            grossAmount = BigDecimal("400.00"), netAmount = BigDecimal("398.00"), redemptionFee = BigDecimal("2.00"), unitNav = BigDecimal("10.0000"))
        assertTrue(ReviewedAmountRedemption.sameRequest(tx, calculated))
        assertFalse(ReviewedAmountRedemption.sameRequest(tx, calculated.copy(requestedAmount = BigDecimal("399.00"))))
        assertFalse(ReviewedAmountRedemption.sameRequest(tx, calculated.copy(status = TransactionStatus.CANCELLED)))
    }
}
