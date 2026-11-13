package com.ndxinvest.ledger.model

import java.math.BigDecimal
import java.time.LocalDate

public enum class LotType {
    REAL,
    LEGACY,
}

public enum class LotAllocationPolicy {
    FIFO,
    DEFAULT_FIFO,
}

public data class Lot public constructor(
    public val id: LotId,
    public val fundId: FundId,
    public val sourceTransactionId: TransactionId,
    public val type: LotType,
    public val confirmationDate: LocalDate,
    public val originalShares: BigDecimal,
    public val remainingShares: BigDecimal,
    public val grossCashOut: BigDecimal,
    public val netSubscriptionAmount: BigDecimal,
    public val purchaseFee: BigDecimal,
    public val costBasis: BigDecimal,
    public val unitNav: BigDecimal,
) {
    init {
        require(originalShares.signum() > 0) { "Original lot shares must be positive" }
        require(remainingShares.signum() >= 0) { "Remaining lot shares must not be negative" }
        require(remainingShares.compareTo(originalShares) <= 0) {
            "Remaining lot shares must not exceed original shares"
        }
        require(grossCashOut.signum() >= 0) { "Gross cash out must not be negative" }
        require(netSubscriptionAmount.signum() >= 0) { "Net subscription amount must not be negative" }
        require(purchaseFee.signum() >= 0) { "Purchase fee must not be negative" }
        require(costBasis.signum() >= 0) { "Lot cost basis must not be negative" }
        require(unitNav.signum() > 0) { "Lot NAV must be positive" }
        require(remainingShares.signum() != 0 || costBasis.signum() == 0) {
            "A depleted lot must not retain cost basis"
        }
    }
}

public data class LegacyLot public constructor(
    public val id: LotId,
    public val fundId: FundId,
    public val remainingShares: BigDecimal,
    public val costBasis: BigDecimal,
    public val trackingStartDate: LocalDate?,
) {
    init {
        require(remainingShares.signum() >= 0) { "Legacy Lot shares must not be negative" }
        require(costBasis.signum() >= 0) { "Legacy Lot cost basis must not be negative" }
        require(remainingShares.signum() != 0 || costBasis.signum() == 0) {
            "A depleted Legacy Lot must not retain cost basis"
        }
    }
}
