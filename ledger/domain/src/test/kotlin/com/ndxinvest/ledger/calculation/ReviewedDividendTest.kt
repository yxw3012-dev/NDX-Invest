package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*
import kotlin.test.*

class ReviewedDividendTest {
    private val id = FundId("reviewed-dividend")
    private val date = LocalDate.parse("2026-09-14")
    private val now = Instant.parse("2026-09-15T08:00:00Z")
    private val fund = Fund(id, "270042", "基金", "基金", "QDII", null, emptyMap(), null,
        FundPrecision(2, 2, 4, RoundingMode.HALF_UP))
    private val rule = FundRuleVersion(RuleVersionId("rule"), id, date.minusYears(1), null, LocalTime.of(15, 0),
        RuleExpression("T_PLUS_1"), RuleExpression("T_PLUS_2"), RuleExpression("FUND_OPEN_DAY"),
        PurchaseFeeRule.Rate(BigDecimal.ZERO), RedemptionFeeSchedule.noFee(), LotAllocationPolicy.FIFO,
        DataAuthority.FUND_OFFICIAL, DataSourceId("rule-source"), now.minusSeconds(60), emptySet())
    private val history = LedgerHistorySnapshot(id, emptyList(), emptyList(), emptyList(), emptyList())
    private val cash = LedgerTransaction(TransactionId("cash"), id, TransactionType.CASH_DIVIDEND,
        TransactionStatus.CONFIRMED_ACTUAL, TransactionOrigin.USER_ENTERED, DataAuthority.USER_OR_PLATFORM_FINAL,
        now.minusSeconds(86400), date, null, null, date, rule.id, grossAmount = BigDecimal("50.00"),
        actualCashFlow = BigDecimal("50.00"), createdAt = now, updatedAt = now)
    private fun cashProof() = ReviewedDividend(fund, history, listOf(rule), null, cash, null, null)
    private fun reinvestment(): ReviewedDividend {
        val nav = NavRecord(id, date, BigDecimal("10.0000"), null, DataAuthority.FUND_OFFICIAL, DataSourceId("official"), now.minusSeconds(60))
        val tx = cash.copy(id = TransactionId("reinvestment"), type = TransactionType.DIVIDEND_REINVESTMENT,
            navDate = date, confirmationDate = date.plusDays(1), settlementDate = null, actualCashFlow = null,
            netAmount = BigDecimal("50.00"), shares = BigDecimal("5.00"), unitNav = nav.unitNav, purchaseFee = BigDecimal("0.00"))
        val lot = Lot(LotId("reinvestment-lot"), id, tx.id, LotType.REAL, requireNotNull(tx.confirmationDate),
            BigDecimal("5.00"), BigDecimal("5.00"), BigDecimal("0.00"), BigDecimal("50.00"), BigDecimal("0.00"),
            BigDecimal("50.00"), nav.unitNav)
        return ReviewedDividend(fund, history, listOf(rule), nav, tx, lot, RealLotOpened(LedgerEntryId("reinvestment-entry"), 1, lot))
    }
    @Test fun `cash proof retains actual positive receipt and leaves holdings alone`() {
        cashProof().validate(now, date.plusDays(1))
        assertNull(cashProof().lot); assertEquals(BigDecimal("50.00"), cashProof().transaction.actualCashFlow)
    }
    @Test fun `reinvestment proof adds verified shares and cost but no external cash or fee`() {
        val proof = reinvestment(); proof.validate(now, date.plusDays(1))
        assertNull(proof.transaction.actualCashFlow); assertEquals(BigDecimal("50.00"), proof.lot?.costBasis)
    }
    @Test fun `cash cannot gain imaginary NAV shares or a different actual receipt`() {
        for (tx in listOf(cash.copy(actualCashFlow = BigDecimal("49.00")), cash.copy(shares = BigDecimal.ONE),
            cash.copy(unitNav = BigDecimal.ONE), cash.copy(settlementDate = date.plusDays(1)),
            cash.copy(grossAmount = BigDecimal("50.001")), cash.copy(status = TransactionStatus.CALCULATED)))
            assertFailsWith<IllegalArgumentException> { cashProof().copy(transaction = tx).validate(now, date.plusDays(1)) }
    }
    @Test fun `reinvestment cannot replace missing exact NAV invent cash or change cost`() {
        val proof = reinvestment()
        for (changed in listOf(proof.copy(nav = null), proof.copy(nav = proof.nav?.copy(navDate = date.minusDays(1))),
            proof.copy(nav = proof.nav?.copy(fetchedAt = now.plusSeconds(1))),
            proof.copy(transaction = proof.transaction.copy(actualCashFlow = BigDecimal("50.00"))),
            proof.copy(transaction = proof.transaction.copy(shares = BigDecimal("6.00"))),
            proof.copy(lot = proof.lot?.copy(costBasis = BigDecimal("49.00")))))
            assertFailsWith<IllegalArgumentException> { changed.validate(now, date.plusDays(1)) }
    }
    @Test fun `wrong history fund unknown precision ineffective rule and future proof fail closed`() {
        for (proof in listOf(cashProof().copy(history = history.copy(fundId = FundId("other"))),
            cashProof().copy(fund = fund.copy(precision = null)), cashProof().copy(rules = emptyList()),
            cashProof().copy(rules = listOf(rule.copy(fetchedAt = now.plusSeconds(1)))),
            cashProof().copy(transaction = cash.copy(createdAt = now.plusSeconds(1), updatedAt = now.plusSeconds(1)))))
            assertFailsWith<IllegalArgumentException> { proof.validate(now, date.plusDays(1)) }
    }
    @Test fun `pending redemption cannot be crossed by an unreviewed reinvestment insertion`() {
        val pending = cash.copy(id = TransactionId("pending-sale"), type = TransactionType.REDEMPTION,
            status = TransactionStatus.PENDING_CALC, authority = DataAuthority.APP_RULE_CALCULATION,
            actualCashFlow = null, settlementDate = null, grossAmount = null)
        assertFailsWith<IllegalArgumentException> {
            reinvestment().copy(history = history.copy(transactions = listOf(pending))).validate(now, date.plusDays(1))
        }
    }
}
