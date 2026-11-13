package com.ndxinvest.ledger.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

public data class Fund public constructor(
    public val id: FundId,
    public val code: String,
    public val name: String,
    public val shortName: String,
    public val fundType: String,
    public val trackingIndex: String?,
    public val providerMetadata: Map<String, String>,
    public val cardStyle: String?,
    public val precision: FundPrecision? = null,
) {
    init {
        require(code.isNotBlank()) { "Fund code must not be blank" }
        require(name.isNotBlank()) { "Fund name must not be blank" }
        require(shortName.isNotBlank()) { "Fund short name must not be blank" }
        require(fundType.isNotBlank()) { "Fund type must not be blank" }
        require(trackingIndex == null || trackingIndex.isNotBlank()) { "Tracking index must not be blank" }
        require(providerMetadata.keys.all(String::isNotBlank)) { "Provider metadata keys must not be blank" }
        require(providerMetadata.values.all(String::isNotBlank)) { "Provider metadata values must not be blank" }
        require(cardStyle == null || cardStyle.isNotBlank()) { "Card style must not be blank" }
    }
}

public data class RecurringPlan public constructor(
    public val id: RecurringPlanId,
    public val fundId: FundId,
    public val amount: BigDecimal,
    public val frequency: String,
    public val scheduleRule: String,
    public val nonOpenDayPolicy: String,
    public val enabled: Boolean,
    public val lastGeneratedDate: LocalDate?,
) {
    init {
        require(amount.signum() > 0) { "Recurring amount must be positive" }
        require(frequency.isNotBlank()) { "Recurring frequency must not be blank" }
        require(scheduleRule.isNotBlank()) { "Recurring schedule rule must not be blank" }
        require(nonOpenDayPolicy.isNotBlank()) { "Non-open-day policy must not be blank" }
    }
}

public data class RecurringExecutionNotification public constructor(
    public val id: String,
    public val planId: RecurringPlanId,
    public val transactionId: TransactionId,
    public val scheduledTradeDate: LocalDate,
    public val fundId: FundId,
    public val amount: BigDecimal,
    public val deliveredAt: Instant? = null,
) {
    init {
        require(id.isNotBlank()) { "Recurring notification id must not be blank" }
        require(amount.signum() > 0) { "Recurring notification amount must be positive" }
    }
}
