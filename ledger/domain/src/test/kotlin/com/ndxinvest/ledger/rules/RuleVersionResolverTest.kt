package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.model.DataAuthority
import com.ndxinvest.ledger.model.DataSourceId
import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.FundRuleField
import com.ndxinvest.ledger.model.FundRuleVersion
import com.ndxinvest.ledger.model.LedgerTransaction
import com.ndxinvest.ledger.model.LotAllocationPolicy
import com.ndxinvest.ledger.model.PurchaseFeeRule
import com.ndxinvest.ledger.model.RedemptionFeeSchedule
import com.ndxinvest.ledger.model.RuleExpression
import com.ndxinvest.ledger.model.RuleVersionId
import com.ndxinvest.ledger.model.TransactionId
import com.ndxinvest.ledger.model.TransactionOrigin
import com.ndxinvest.ledger.model.TransactionStatus
import com.ndxinvest.ledger.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleVersionResolverTest {
    private val fundId = FundId("fund-a")

    @Test
    fun `Test F new rule only selects non-final transactions on or after effective date`() {
        val newVersion = rule("v2", "2026-08-01", null)
        val transactions = listOf(
            transaction("before", "2026-07-31", TransactionStatus.PENDING_CALC),
            transaction("pending", "2026-08-01", TransactionStatus.PENDING_CALC),
            transaction("calculated", "2026-08-02", TransactionStatus.CALCULATED),
            transaction("actual", "2026-08-03", TransactionStatus.CONFIRMED_ACTUAL),
            transaction("cancelled", "2026-08-04", TransactionStatus.CANCELLED),
        )

        val affected = RuleVersionResolver.transactionsEligibleForRecalculation(newVersion, transactions)

        assertEquals(listOf(TransactionId("pending"), TransactionId("calculated")), affected.map { it.id })
    }

    @Test
    fun `resolver uses the version effective on trade date and preserves prior version identity`() {
        val versions = listOf(
            rule("v1", "2026-01-01", "2026-07-31"),
            rule("v2", "2026-08-01", null),
        )

        assertEquals(RuleVersionId("v1"), RuleVersionResolver.resolve(fundId, LocalDate.parse("2026-07-31"), versions).id)
        assertEquals(RuleVersionId("v2"), RuleVersionResolver.resolve(fundId, LocalDate.parse("2026-08-01"), versions).id)
    }

    @Test
    fun `lower authority automation cannot overwrite platform final confirmation`() {
        val actual = transaction("actual", "2026-08-03", TransactionStatus.CONFIRMED_ACTUAL)

        assertFalse(
            LedgerRevisionPolicy.canApply(
                existing = actual,
                incomingAuthority = DataAuthority.APP_RULE_CALCULATION,
            ),
        )
        assertFalse(
            LedgerRevisionPolicy.canApply(
                existing = actual,
                incomingAuthority = DataAuthority.USER_OR_PLATFORM_FINAL,
            ),
        )
    }

    @Test
    fun `automatic rule refresh preserves manually overridden fields`() {
        val existing = rule("manual", "2026-08-01", null).copy(
            cutoffTime = LocalTime.parse("14:30"),
            authority = DataAuthority.USER_OR_PLATFORM_FINAL,
            manualOverrideFields = setOf(FundRuleField.CUTOFF_TIME),
        )
        val incoming = rule("provider-v2", "2026-09-01", null).copy(
            cutoffTime = LocalTime.parse("15:30"),
            purchaseConfirmationRule = RuleExpression("T_PLUS_2"),
        )

        val merged = FundRuleUpdateMerger.mergeAutomatic(existing, incoming)

        assertEquals(LocalTime.parse("14:30"), merged.cutoffTime)
        assertEquals(RuleExpression("T_PLUS_2"), merged.purchaseConfirmationRule)
        assertEquals(DataAuthority.USER_OR_PLATFORM_FINAL, merged.authority)
        assertEquals(setOf(FundRuleField.CUTOFF_TIME), merged.manualOverrideFields)
    }

    @Test
    fun `non-final calculated transaction accepts equal-or-higher authority only`() {
        val calculated = transaction("calculated", "2026-08-03", TransactionStatus.CALCULATED)

        assertTrue(LedgerRevisionPolicy.canApply(calculated, DataAuthority.FUND_OFFICIAL))
        assertFalse(LedgerRevisionPolicy.canApply(calculated, DataAuthority.TYPE_DEFAULT))
    }

    private fun rule(id: String, from: String, to: String?): FundRuleVersion = FundRuleVersion(
        id = RuleVersionId(id),
        fundId = fundId,
        effectiveFrom = LocalDate.parse(from),
        effectiveTo = to?.let(LocalDate::parse),
        cutoffTime = LocalTime.parse("15:00"),
        purchaseConfirmationRule = RuleExpression("T_PLUS_1"),
        redemptionConfirmationRule = RuleExpression("T_PLUS_2"),
        openDayRule = RuleExpression("FUND_OPEN_DAY"),
        purchaseFeeRule = PurchaseFeeRule.None,
        redemptionFeeSchedule = RedemptionFeeSchedule.noFee(),
        allocationPolicy = LotAllocationPolicy.FIFO,
        authority = DataAuthority.FUND_OFFICIAL,
        source = DataSourceId("fund-company"),
        fetchedAt = Instant.parse("2026-07-31T08:00:00Z"),
        manualOverrideFields = emptySet(),
    )

    private fun transaction(id: String, tradeDate: String, status: TransactionStatus): LedgerTransaction =
        LedgerTransaction(
            id = TransactionId(id),
            fundId = fundId,
            type = TransactionType.PURCHASE,
            status = status,
            origin = TransactionOrigin.USER_ENTERED,
            authority = if (status == TransactionStatus.CONFIRMED_ACTUAL) {
                DataAuthority.USER_OR_PLATFORM_FINAL
            } else {
                DataAuthority.APP_RULE_CALCULATION
            },
            applicationTime = Instant.parse("2026-07-31T12:00:00Z"),
            tradeDate = LocalDate.parse(tradeDate),
            navDate = null,
            confirmationDate = null,
            settlementDate = null,
            ruleVersionId = RuleVersionId("v1"),
        )
}
