package com.ndxinvest.ledger.model

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

public data class FundPrecision public constructor(
    public val moneyScale: Int,
    public val shareScale: Int,
    public val navScale: Int,
    public val roundingMode: RoundingMode,
) {
    init {
        require(moneyScale >= 0) { "Money scale must not be negative" }
        require(shareScale >= 0) { "Share scale must not be negative" }
        require(navScale >= 0) { "NAV scale must not be negative" }
    }

    public fun roundMoney(value: BigDecimal): BigDecimal = value.setScale(moneyScale, roundingMode)

    public fun roundShares(value: BigDecimal): BigDecimal = value.setScale(shareScale, roundingMode)

    public fun roundNav(value: BigDecimal): BigDecimal = value.setScale(navScale, roundingMode)

    public fun zeroMoney(): BigDecimal = BigDecimal.ZERO.setScale(moneyScale)

    public fun zeroShares(): BigDecimal = BigDecimal.ZERO.setScale(shareScale)

    public companion object {
        public val CALCULATION_CONTEXT: MathContext = MathContext.DECIMAL128
    }
}
