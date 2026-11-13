package com.ndxinvest.market.model

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@JvmInline
public value class MarketSourceId public constructor(public val value: String) {
    init {
        require(value.isNotBlank()) { "Market source must not be blank" }
    }
}

public data class NdxDaily public constructor(
    public val date: LocalDate,
    public val open: BigDecimal,
    public val high: BigDecimal,
    public val low: BigDecimal,
    public val close: BigDecimal,
    public val source: MarketSourceId,
) {
    init {
        require(close.signum() > 0) { "NDX daily close must be positive" }
        require(listOf(open, high, low).all { it.signum() >= 0 }) {
            "NDX auxiliary daily prices must not be negative"
        }
    }
}

public enum class MarketSessionState {
    PRE_MARKET,
    OPEN,
    AFTER_HOURS,
    CLOSED,
    UNKNOWN,
}

public data class NdxQuote public constructor(
    public val point: BigDecimal,
    public val change: BigDecimal,
    public val changeRate: BigDecimal,
    public val quoteTime: Instant,
    public val marketState: MarketSessionState,
    public val source: MarketSourceId,
) {
    init {
        require(point.signum() > 0) { "NDX point must be positive" }
    }
}

public data class UsdCnyQuote public constructor(
    public val rate: BigDecimal,
    public val changeRate: BigDecimal?,
    public val quoteTime: Instant,
    public val source: MarketSourceId,
) {
    init {
        require(rate.signum() > 0) { "USD/CNY rate must be positive" }
    }
}

public enum class DataDelivery {
    NETWORK,
    CACHE,
}

public enum class FreshnessState {
    LIVE_FRESH,
    CACHED,
    STALE,
    ERROR,
}

public data class DataFreshness public constructor(
    public val state: FreshnessState,
    public val source: MarketSourceId?,
    public val fetchedAt: Instant?,
    public val age: Duration?,
)
