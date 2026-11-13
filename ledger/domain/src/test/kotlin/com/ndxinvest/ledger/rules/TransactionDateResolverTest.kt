package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.model.DataAuthority
import com.ndxinvest.ledger.model.DataSourceId
import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.FundRuleVersion
import com.ndxinvest.ledger.model.RecurringPlan
import com.ndxinvest.ledger.model.RecurringPlanId
import com.ndxinvest.ledger.model.LotAllocationPolicy
import com.ndxinvest.ledger.model.PurchaseFeeRule
import com.ndxinvest.ledger.model.RedemptionFeeSchedule
import com.ndxinvest.ledger.model.RuleExpression
import com.ndxinvest.ledger.model.RuleVersionId
import com.ndxinvest.ledger.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransactionDateResolverTest {
    private val calendar = FundOpenDayCalendar(
        setOf(
            LocalDate.parse("2026-09-11"),
            LocalDate.parse("2026-09-14"),
            LocalDate.parse("2026-09-15"),
            LocalDate.parse("2026-09-16"),
        ),
    )

    @Test
    fun `application at cutoff uses the same open T date and configured purchase confirmation offset`() {
        val result = TransactionDateResolver.resolve(
            applicationTime = Instant.parse("2026-09-11T07:00:00Z"),
            zoneId = ZoneId.of("Asia/Shanghai"),
            transactionType = TransactionType.PURCHASE,
            ruleVersion = rule(),
            calendar = calendar,
        )

        assertEquals(LocalDate.parse("2026-09-11"), result.tradeDate)
        assertEquals(LocalDate.parse("2026-09-11"), result.navDate)
        assertEquals(LocalDate.parse("2026-09-14"), result.confirmationDate)
        assertEquals(RuleVersionId("rule-a"), result.ruleVersionId)
    }

    @Test
    fun `application after cutoff rolls T to next fund open day`() {
        val result = TransactionDateResolver.resolve(
            applicationTime = Instant.parse("2026-09-11T07:00:01Z"),
            zoneId = ZoneId.of("Asia/Shanghai"),
            transactionType = TransactionType.REDEMPTION,
            ruleVersion = rule(),
            calendar = calendar,
        )

        assertEquals(LocalDate.parse("2026-09-14"), result.tradeDate)
        assertEquals(LocalDate.parse("2026-09-16"), result.confirmationDate)
    }

    @Test
    fun `recurring schedule moves to the next known open day and selects the rule effective on actual T`() {
        val result = RecurringPlanDateResolver.resolve(
            plan = recurringPlan(),
            scheduledDate = LocalDate.parse("2026-09-13"),
            ruleVersions = listOf(
                rule(
                    id = "rule-old",
                    effectiveFrom = "2026-01-01",
                    effectiveTo = "2026-09-13",
                ),
                rule(
                    id = "rule-new",
                    effectiveFrom = "2026-09-14",
                    purchaseConfirmationRule = "T_PLUS_2",
                ),
            ),
            calendar = calendar,
        )

        assertEquals(LocalDate.parse("2026-09-14"), result.tradeDate)
        assertEquals(LocalDate.parse("2026-09-14"), result.navDate)
        assertEquals(LocalDate.parse("2026-09-16"), result.confirmationDate)
        assertEquals(RuleVersionId("rule-new"), result.ruleVersionId)
    }

    @Test
    fun `recurring schedule rejects an unsupported non open day policy instead of guessing`() {
        assertFailsWith<IllegalArgumentException> {
            RecurringPlanDateResolver.resolve(
                plan = recurringPlan().copy(nonOpenDayPolicy = "PREVIOUS_OPEN_DAY"),
                scheduledDate = LocalDate.parse("2026-09-13"),
                ruleVersions = listOf(rule()),
                calendar = calendar,
            )
        }
    }

    @Test
    fun `calendar rejects dates outside its declared official coverage instead of guessing closed days`() {
        val boundedCalendar = FundOpenDayCalendar(
            coverageStart = LocalDate.parse("2026-09-01"),
            coverageEnd = LocalDate.parse("2026-09-30"),
            openDates = setOf(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30")),
        )

        assertFailsWith<IllegalArgumentException> {
            boundedCalendar.isOpen(LocalDate.parse("2026-08-31"))
        }
        assertFailsWith<IllegalArgumentException> {
            boundedCalendar.nextOpenOnOrAfter(LocalDate.parse("2026-10-01"))
        }
    }

    @Test
    fun `calendar rejects an open date outside the source declared coverage`() {
        assertFailsWith<IllegalArgumentException> {
            FundOpenDayCalendar(
                coverageStart = LocalDate.parse("2026-09-01"),
                coverageEnd = LocalDate.parse("2026-09-30"),
                openDates = setOf(LocalDate.parse("2026-10-01")),
            )
        }
    }

    private fun rule(
        id: String = "rule-a",
        effectiveFrom: String = "2026-01-01",
        effectiveTo: String? = null,
        purchaseConfirmationRule: String = "T_PLUS_1",
    ): FundRuleVersion = FundRuleVersion(
        id = RuleVersionId(id),
        fundId = FundId("fund-a"),
        effectiveFrom = LocalDate.parse(effectiveFrom),
        effectiveTo = effectiveTo?.let(LocalDate::parse),
        cutoffTime = LocalTime.parse("15:00"),
        purchaseConfirmationRule = RuleExpression(purchaseConfirmationRule),
        redemptionConfirmationRule = RuleExpression("T_PLUS_2"),
        openDayRule = RuleExpression("FUND_OPEN_DAY"),
        purchaseFeeRule = PurchaseFeeRule.None,
        redemptionFeeSchedule = RedemptionFeeSchedule.noFee(),
        allocationPolicy = LotAllocationPolicy.FIFO,
        authority = DataAuthority.FUND_OFFICIAL,
        source = DataSourceId("fund-company"),
        fetchedAt = Instant.parse("2026-01-01T00:00:00Z"),
        manualOverrideFields = emptySet(),
    )

    private fun recurringPlan(): RecurringPlan = RecurringPlan(
        id = RecurringPlanId("plan-a"),
        fundId = FundId("fund-a"),
        amount = BigDecimal("1000.00"),
        frequency = "MONTHLY",
        scheduleRule = "DAY_13",
        nonOpenDayPolicy = "NEXT_OPEN_DAY",
        enabled = true,
        lastGeneratedDate = null,
    )
}
