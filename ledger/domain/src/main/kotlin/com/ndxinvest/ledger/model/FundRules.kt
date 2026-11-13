package com.ndxinvest.ledger.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@JvmInline
public value class RuleExpression public constructor(public val value: String) {
    init {
        require(value.isNotBlank()) { "Rule expression must not be blank" }
    }
}

public enum class FundRuleField {
    CUTOFF_TIME,
    PURCHASE_CONFIRMATION_RULE,
    REDEMPTION_CONFIRMATION_RULE,
    OPEN_DAY_RULE,
    PURCHASE_FEE_RULE,
    REDEMPTION_FEE_RULE,
    LOT_ALLOCATION_RULE,
}

public data class FundRuleVersion public constructor(
    public val id: RuleVersionId,
    public val fundId: FundId,
    public val effectiveFrom: LocalDate,
    public val effectiveTo: LocalDate?,
    public val cutoffTime: LocalTime,
    public val purchaseConfirmationRule: RuleExpression,
    public val redemptionConfirmationRule: RuleExpression,
    public val openDayRule: RuleExpression,
    public val purchaseFeeRule: PurchaseFeeRule,
    public val redemptionFeeSchedule: RedemptionFeeSchedule,
    public val allocationPolicy: LotAllocationPolicy,
    public val authority: DataAuthority,
    public val source: DataSourceId,
    public val fetchedAt: Instant,
    public val manualOverrideFields: Set<FundRuleField>,
) {
    init {
        require(effectiveTo == null || !effectiveTo.isBefore(effectiveFrom)) {
            "Rule version end date must not precede its start date"
        }
        require(manualOverrideFields.isEmpty() || authority == DataAuthority.USER_OR_PLATFORM_FINAL) {
            "A rule version with manual fields requires user authority"
        }
    }

    public fun isEffectiveOn(date: LocalDate): Boolean =
        !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo))
}
