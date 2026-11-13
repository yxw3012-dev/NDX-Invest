package com.ndxinvest.ledger.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

public enum class DataAuthority(public val priority: Int) {
    TYPE_DEFAULT(0),
    APP_RULE_CALCULATION(1),
    RELIABLE_AGGREGATOR(2),
    FUND_OFFICIAL(3),
    USER_OR_PLATFORM_FINAL(4),
    ;

    public fun canOverride(other: DataAuthority): Boolean = priority >= other.priority
}

public data class NavRecord public constructor(
    public val fundId: FundId,
    public val navDate: LocalDate,
    public val unitNav: BigDecimal,
    public val accumulatedNav: BigDecimal?,
    public val authority: DataAuthority,
    public val source: DataSourceId,
    public val fetchedAt: Instant,
) {
    init {
        require(unitNav.signum() > 0) { "Official NAV must be positive" }
        require(accumulatedNav == null || accumulatedNav.signum() > 0) {
            "Accumulated NAV must be positive when present"
        }
        require(authority.priority >= DataAuthority.RELIABLE_AGGREGATOR.priority) {
            "A NavRecord must come from a published-data authority"
        }
    }
}

public enum class ValuationDateBasis {
    COMMON_NAV_DATE,
    EACH_FUND_LATEST_PUBLISHED,
}
