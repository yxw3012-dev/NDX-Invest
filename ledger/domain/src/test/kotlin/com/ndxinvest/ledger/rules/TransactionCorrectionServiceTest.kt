package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.model.CorrectionId
import com.ndxinvest.ledger.model.CorrectionSource
import com.ndxinvest.ledger.model.CorrectionValue
import com.ndxinvest.ledger.model.DataAuthority
import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.LedgerTransaction
import com.ndxinvest.ledger.model.RuleVersionId
import com.ndxinvest.ledger.model.TransactionCorrectionField
import com.ndxinvest.ledger.model.TransactionId
import com.ndxinvest.ledger.model.TransactionOrigin
import com.ndxinvest.ledger.model.TransactionStatus
import com.ndxinvest.ledger.model.TransactionType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransactionCorrectionServiceTest {
    @Test
    fun `manual correction changes the fact and emits its original value as an audit record`() {
        val transaction = actualTransaction()
        val correctedAt = Instant.parse("2026-09-12T08:00:00Z")

        val result = TransactionCorrectionService.applyManualCorrection(
            transaction = transaction,
            correctionId = CorrectionId("correction-1"),
            field = TransactionCorrectionField.UNIT_NAV,
            correctedValue = CorrectionValue.Decimal(BigDecimal("1.3000")),
            correctedAt = correctedAt,
        )

        assertEquals(BigDecimal("1.3000"), result.transaction.unitNav)
        assertEquals(DataAuthority.USER_OR_PLATFORM_FINAL, result.transaction.authority)
        assertTrue(result.transaction.isManualCorrected)
        assertEquals(correctedAt, result.transaction.updatedAt)
        assertEquals(CorrectionValue.Decimal(BigDecimal("1.2000")), result.audit.originalValue)
        assertEquals(CorrectionSource.USER, result.audit.source)
    }

    @Test
    fun `correction rejects a no-op and a value of the wrong kind`() {
        val transaction = actualTransaction()
        val correctedAt = Instant.parse("2026-09-12T08:00:00Z")

        assertFailsWith<IllegalArgumentException> {
            TransactionCorrectionService.applyManualCorrection(
                transaction,
                CorrectionId("same"),
                TransactionCorrectionField.UNIT_NAV,
                CorrectionValue.Decimal(BigDecimal("1.20")),
                correctedAt,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TransactionCorrectionService.applyManualCorrection(
                transaction,
                CorrectionId("wrong-kind"),
                TransactionCorrectionField.UNIT_NAV,
                CorrectionValue.Date(LocalDate.parse("2026-09-12")),
                correctedAt,
            )
        }
    }

    @Test
    fun `sales platform final correction preserves the app value and records final source`() {
        val transaction = actualTransaction().copy(
            status = TransactionStatus.CALCULATED,
            authority = DataAuthority.APP_RULE_CALCULATION,
        )
        val correctedAt = Instant.parse("2026-09-12T09:00:00Z")

        val result = TransactionCorrectionService.applySalesPlatformFinalCorrection(
            transaction = transaction,
            correctionId = CorrectionId("platform-final-1"),
            field = TransactionCorrectionField.SHARES,
            correctedValue = CorrectionValue.Decimal(BigDecimal("88.1234")),
            correctedAt = correctedAt,
        )

        assertEquals(BigDecimal("88.1234"), result.transaction.shares)
        assertEquals(DataAuthority.USER_OR_PLATFORM_FINAL, result.transaction.authority)
        assertEquals(CorrectionValue.Missing, result.audit.originalValue)
        assertEquals(CorrectionSource.SALES_PLATFORM_FINAL, result.audit.source)
    }

    @Test
    fun `actual cash date audit preserves other transaction dates and requires actual cash`() {
        val original = actualTransaction().copy(actualCashFlow = BigDecimal("-1000.00"))
        val paidDate = original.tradeDate.minusDays(1)
        val corrected = TransactionCorrectionService.applySalesPlatformFinalCorrection(
            original, CorrectionId("actual-payment-date"), TransactionCorrectionField.CASH_FLOW_DATE,
            CorrectionValue.Date(paidDate), Instant.parse("2026-09-12T09:00:00Z"),
        )
        assertEquals(paidDate, corrected.transaction.cashFlowDate)
        assertEquals(original.settlementDate, corrected.transaction.settlementDate)
        assertEquals(original.tradeDate, corrected.transaction.tradeDate)
        assertEquals(original.navDate, corrected.transaction.navDate)
        assertEquals(original.confirmationDate, corrected.transaction.confirmationDate)
        assertEquals(CorrectionValue.Missing, corrected.audit.originalValue)
        assertEquals(CorrectionSource.SALES_PLATFORM_FINAL, corrected.audit.source)
        assertFailsWith<IllegalArgumentException> { original.copy(actualCashFlow = null, cashFlowDate = paidDate) }
        assertFailsWith<IllegalArgumentException> {
            TransactionCorrectionService.applyManualCorrection(
                original, CorrectionId("wrong-payment-kind"), TransactionCorrectionField.CASH_FLOW_DATE,
                CorrectionValue.Decimal(BigDecimal("1000.00")), Instant.parse("2026-09-12T09:00:00Z"),
            )
        }
    }

    private fun actualTransaction(): LedgerTransaction = LedgerTransaction(
        id = TransactionId("tx-actual"),
        fundId = FundId("fund-a"),
        type = TransactionType.PURCHASE,
        status = TransactionStatus.CONFIRMED_ACTUAL,
        origin = TransactionOrigin.USER_ENTERED,
        authority = DataAuthority.USER_OR_PLATFORM_FINAL,
        applicationTime = Instant.parse("2026-09-11T08:00:00Z"),
        tradeDate = LocalDate.parse("2026-09-11"),
        navDate = LocalDate.parse("2026-09-11"),
        confirmationDate = LocalDate.parse("2026-09-11"),
        settlementDate = null,
        ruleVersionId = RuleVersionId("rule-a"),
        unitNav = BigDecimal("1.2000"),
    )
}
