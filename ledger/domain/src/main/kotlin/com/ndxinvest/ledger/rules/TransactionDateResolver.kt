package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.model.FundRuleVersion
import com.ndxinvest.ledger.model.RecurringPlan
import com.ndxinvest.ledger.model.RuleExpression
import com.ndxinvest.ledger.model.RuleVersionId
import com.ndxinvest.ledger.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

public class FundOpenDayCalendar public constructor(
    public val coverageStart: LocalDate,
    public val coverageEnd: LocalDate,
    openDates: Set<LocalDate>,
) {
    private val orderedOpenDates: List<LocalDate> = openDates.sorted()
        .also { require(it.isNotEmpty()) { "A fund calendar needs at least one known open day" } }

    init {
        require(!coverageStart.isAfter(coverageEnd)) { "Fund calendar coverage start must not be after its end" }
        require(orderedOpenDates.all { it in coverageStart..coverageEnd }) {
            "Every known fund open day must be inside the declared coverage"
        }
    }

    public constructor(openDates: Set<LocalDate>) : this(
        coverageStart = openDates.minOrNull() ?: error("A fund calendar needs at least one known open day"),
        coverageEnd = openDates.maxOrNull() ?: error("A fund calendar needs at least one known open day"),
        openDates = openDates,
    )

    public fun isOpen(date: LocalDate): Boolean {
        requireCovered(date)
        return orderedOpenDates.binarySearch(date) >= 0
    }

    /** A detached, read-only snapshot for review/commit comparison, never synthesized dates. */
    public fun knownOpenDates(): Set<LocalDate> = orderedOpenDates.toSet()

    public fun nextOpenOnOrAfter(date: LocalDate): LocalDate {
        requireCovered(date)
        return orderedOpenDates.firstOrNull { !it.isBefore(date) }
            ?: error("Fund calendar does not cover a known open day on or after $date")
    }

    public fun plusOpenDays(openDate: LocalDate, days: Int): LocalDate {
        require(days >= 0) { "Open-day offset must not be negative" }
        val startIndex = orderedOpenDates.binarySearch(openDate)
        require(startIndex >= 0) { "Offset must start on a known fund open day" }
        return orderedOpenDates.getOrNull(startIndex + days)
            ?: error("Fund calendar does not cover T+$days from $openDate")
    }

    private fun requireCovered(date: LocalDate) {
        require(date in coverageStart..coverageEnd) {
            "Fund calendar does not cover $date; known coverage is $coverageStart through $coverageEnd"
        }
    }
}

public data class ResolvedTransactionDates public constructor(
    public val tradeDate: LocalDate,
    public val navDate: LocalDate,
    public val confirmationDate: LocalDate,
    public val ruleVersionId: RuleVersionId,
)

public object TransactionDateResolver {
    public fun resolve(
        applicationTime: Instant,
        zoneId: ZoneId,
        transactionType: TransactionType,
        ruleVersion: FundRuleVersion,
        calendar: FundOpenDayCalendar,
    ): ResolvedTransactionDates {
        require(ruleVersion.openDayRule.value == FUND_OPEN_DAY) {
            "Unsupported open-day rule: ${ruleVersion.openDayRule.value}"
        }
        val localApplication = applicationTime.atZone(zoneId)
        val applicationDate = localApplication.toLocalDate()
        val earliestTradeDate = if (
            calendar.isOpen(applicationDate) && !localApplication.toLocalTime().isAfter(ruleVersion.cutoffTime)
        ) applicationDate else applicationDate.plusDays(1)
        val tradeDate = calendar.nextOpenOnOrAfter(earliestTradeDate)
        require(ruleVersion.isEffectiveOn(tradeDate)) {
            "Rule version ${ruleVersion.id.value} is not effective on resolved T date $tradeDate"
        }
        val confirmationRule = when (transactionType) {
            TransactionType.PURCHASE,
            TransactionType.RECURRING_PURCHASE,
            TransactionType.DIVIDEND_REINVESTMENT,
            -> ruleVersion.purchaseConfirmationRule
            TransactionType.REDEMPTION -> ruleVersion.redemptionConfirmationRule
            TransactionType.CASH_DIVIDEND,
            TransactionType.ADJUSTMENT,
            -> throw IllegalArgumentException("$transactionType does not use purchase/redemption confirmation rules")
        }
        return ResolvedTransactionDates(
            tradeDate = tradeDate,
            navDate = tradeDate,
            confirmationDate = calendar.plusOpenDays(tradeDate, confirmationRule.openDayOffset()),
            ruleVersionId = ruleVersion.id,
        )
    }

}

public object RecurringPlanDateResolver {
    public fun resolve(
        plan: RecurringPlan,
        scheduledDate: LocalDate,
        ruleVersions: List<FundRuleVersion>,
        calendar: FundOpenDayCalendar,
    ): ResolvedTransactionDates {
        require(plan.nonOpenDayPolicy == NEXT_OPEN_DAY) {
            "Unsupported recurring non-open-day policy: ${plan.nonOpenDayPolicy}"
        }
        val tradeDate = calendar.nextOpenOnOrAfter(scheduledDate)
        val ruleVersion = RuleVersionResolver.resolve(plan.fundId, tradeDate, ruleVersions)
        require(ruleVersion.openDayRule.value == FUND_OPEN_DAY) {
            "Unsupported open-day rule: ${ruleVersion.openDayRule.value}"
        }
        return ResolvedTransactionDates(
            tradeDate = tradeDate,
            navDate = tradeDate,
            confirmationDate = calendar.plusOpenDays(
                tradeDate,
                ruleVersion.purchaseConfirmationRule.openDayOffset(),
            ),
            ruleVersionId = ruleVersion.id,
        )
    }
}

private fun RuleExpression.openDayOffset(): Int {
    val match = T_PLUS_PATTERN.matchEntire(value)
        ?: throw IllegalArgumentException("Unsupported confirmation rule: $value")
    return match.groupValues[1].toInt()
}

private const val FUND_OPEN_DAY: String = "FUND_OPEN_DAY"
private const val NEXT_OPEN_DAY: String = "NEXT_OPEN_DAY"
private val T_PLUS_PATTERN: Regex = Regex("T_PLUS_([0-9]+)")
