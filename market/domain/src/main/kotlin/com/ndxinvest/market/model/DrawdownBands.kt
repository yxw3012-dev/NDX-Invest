package com.ndxinvest.market.model

import java.math.BigDecimal

@JvmInline
public value class DrawdownBandId public constructor(public val value: String) {
    init {
        require(value.isNotBlank()) { "Drawdown band id must not be blank" }
    }
}

public data class DrawdownBand public constructor(
    public val id: DrawdownBandId,
    public val thresholdRate: BigDecimal,
    public val plannedAmount: BigDecimal,
) {
    init {
        require(thresholdRate < BigDecimal.ZERO && thresholdRate > BigDecimal.ONE.negate()) {
            "Drawdown threshold must be between -1 and 0"
        }
        require(plannedAmount.signum() > 0) { "Drawdown planned amount must be positive" }
    }
}
