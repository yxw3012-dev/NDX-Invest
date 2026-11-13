package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.CalculationIssue
import com.ndxinvest.ledger.model.DataAuthority
import com.ndxinvest.ledger.model.DataSourceId
import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.FundPrecision
import com.ndxinvest.ledger.model.LedgerTransaction
import com.ndxinvest.ledger.model.NavRecord
import com.ndxinvest.ledger.model.PurchaseFeeRule
import com.ndxinvest.ledger.model.RuleVersionId
import com.ndxinvest.ledger.model.TransactionId
import com.ndxinvest.ledger.model.TransactionOrigin
import com.ndxinvest.ledger.model.TransactionStatus
import com.ndxinvest.ledger.model.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PurchaseConfirmationServiceTest {
    private val precision = FundPrecision(2, 2, 4, RoundingMode.HALF_UP)

    @Test
    fun `missing exact NAV date becomes explicit abnormal state instead of using a stale NAV`() {
        val transaction = pendingTransaction(navDate = LocalDate.parse("2026-09-11"))
        val calculatedAt = Instant.parse("2026-09-11T12:00:00Z")
        val staleNav = NavRecord(
            fundId = transaction.fundId,
            navDate = LocalDate.parse("2026-09-10"),
            unitNav = BigDecimal("1.5000"),
            accumulatedNav = null,
            authority = DataAuthority.FUND_OFFICIAL,
            source = DataSourceId("fund-company"),
            fetchedAt = Instant.parse("2026-09-11T11:00:00Z"),
        )

        val outcome = PurchaseConfirmationService.calculate(
            transaction = transaction,
            grossAmount = BigDecimal("1000.00"),
            feeRule = PurchaseFeeRule.None,
            navRecords = listOf(staleNav),
            precision = precision,
            calculatedAt = calculatedAt,
        )

        assertEquals(TransactionStatus.ABNORMAL, outcome.transaction.status)
        assertEquals(CalculationIssue.MISSING_OFFICIAL_NAV, outcome.issue)
        assertNull(outcome.calculation)
        assertNull(outcome.transaction.unitNav)
        assertEquals(calculatedAt, outcome.transaction.updatedAt)
    }

    @Test
    fun `exact fund and NAV date produces calculated facts without final-authority claim`() {
        val transaction = pendingTransaction(navDate = LocalDate.parse("2026-09-11"))
        val calculatedAt = Instant.parse("2026-09-11T12:00:00Z")
        val nav = NavRecord(
            fundId = transaction.fundId,
            navDate = LocalDate.parse("2026-09-11"),
            unitNav = BigDecimal("2.0000"),
            accumulatedNav = BigDecimal("3.1000"),
            authority = DataAuthority.FUND_OFFICIAL,
            source = DataSourceId("fund-company"),
            fetchedAt = Instant.parse("2026-09-11T11:00:00Z"),
        )

        val outcome = PurchaseConfirmationService.calculate(
            transaction = transaction,
            grossAmount = BigDecimal("1000.00"),
            feeRule = PurchaseFeeRule.None,
            navRecords = listOf(nav),
            precision = precision,
            calculatedAt = calculatedAt,
        )

        assertEquals(TransactionStatus.CALCULATED, outcome.transaction.status)
        assertEquals(DataAuthority.APP_RULE_CALCULATION, outcome.transaction.authority)
        assertEquals(BigDecimal("2.0000"), outcome.transaction.unitNav)
        assertEquals(BigDecimal("500.00"), outcome.transaction.shares)
        assertEquals(calculatedAt, outcome.transaction.updatedAt)
        assertNull(outcome.issue)
    }

    private fun pendingTransaction(navDate: LocalDate): LedgerTransaction = LedgerTransaction(
        id = TransactionId("tx-1"),
        fundId = FundId("fund-a"),
        type = TransactionType.PURCHASE,
        status = TransactionStatus.PENDING_CALC,
        origin = TransactionOrigin.USER_ENTERED,
        authority = DataAuthority.APP_RULE_CALCULATION,
        applicationTime = Instant.parse("2026-09-11T10:00:00Z"),
        tradeDate = LocalDate.parse("2026-09-11"),
        navDate = navDate,
        confirmationDate = LocalDate.parse("2026-09-14"),
        settlementDate = null,
        ruleVersionId = RuleVersionId("v1"),
    )
}
