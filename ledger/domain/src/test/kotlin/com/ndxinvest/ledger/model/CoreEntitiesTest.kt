package com.ndxinvest.ledger.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoreEntitiesTest {
    @Test
    fun `NAV keeps unit accumulated source and fetch time as auditable facts`() {
        val nav = NavRecord(
            fundId = FundId("fund-a"),
            navDate = LocalDate.parse("2026-09-11"),
            unitNav = BigDecimal("1.2345"),
            accumulatedNav = BigDecimal("2.3456"),
            authority = DataAuthority.FUND_OFFICIAL,
            source = DataSourceId("fund-company"),
            fetchedAt = Instant.parse("2026-09-11T10:00:00Z"),
        )

        assertEquals(BigDecimal("2.3456"), nav.accumulatedNav)
        assertEquals(DataSourceId("fund-company"), nav.source)
    }

    @Test
    fun `NAV rejects an authority that is not published data`() {
        assertFailsWith<IllegalArgumentException> {
            NavRecord(
                fundId = FundId("fund-a"),
                navDate = LocalDate.parse("2026-09-11"),
                unitNav = BigDecimal.ONE,
                accumulatedNav = null,
                authority = DataAuthority.APP_RULE_CALCULATION,
                source = DataSourceId("calculation"),
                fetchedAt = Instant.parse("2026-09-11T10:00:00Z"),
            )
        }
    }

    @Test
    fun `recurring plan requires a positive amount`() {
        assertFailsWith<IllegalArgumentException> {
            RecurringPlan(
                id = RecurringPlanId("plan-a"),
                fundId = FundId("fund-a"),
                amount = BigDecimal.ZERO,
                frequency = "MONTHLY",
                scheduleRule = "DAY_1",
                nonOpenDayPolicy = "NEXT_OPEN_DAY",
                enabled = true,
                lastGeneratedDate = null,
            )
        }
    }

    @Test
    fun `manual rule fields require final user or platform authority`() {
        assertFailsWith<IllegalArgumentException> {
            FundRuleVersion(
                id = RuleVersionId("rule-a"),
                fundId = FundId("fund-a"),
                effectiveFrom = LocalDate.parse("2026-01-01"),
                effectiveTo = null,
                cutoffTime = java.time.LocalTime.parse("15:00"),
                purchaseConfirmationRule = RuleExpression("T_PLUS_1"),
                redemptionConfirmationRule = RuleExpression("T_PLUS_2"),
                openDayRule = RuleExpression("FUND_OPEN_DAY"),
                purchaseFeeRule = PurchaseFeeRule.None,
                redemptionFeeSchedule = RedemptionFeeSchedule.noFee(),
                allocationPolicy = LotAllocationPolicy.FIFO,
                authority = DataAuthority.FUND_OFFICIAL,
                source = DataSourceId("fund-company"),
                fetchedAt = Instant.parse("2026-01-01T00:00:00Z"),
                manualOverrideFields = setOf(FundRuleField.CUTOFF_TIME),
            )
        }
    }
}
