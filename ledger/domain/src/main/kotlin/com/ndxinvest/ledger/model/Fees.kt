package com.ndxinvest.ledger.model

import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate

public sealed interface PurchaseFeeRule {
    public data object None : PurchaseFeeRule

    public data class Rate public constructor(public val rate: BigDecimal) : PurchaseFeeRule {
        init {
            require(rate.signum() >= 0) { "Purchase fee rate must not be negative" }
        }
    }

    public data class Fixed public constructor(public val amount: BigDecimal) : PurchaseFeeRule {
        init {
            require(amount.signum() >= 0) { "Fixed purchase fee must not be negative" }
        }
    }

    public data class Tiered public constructor(public val tiers: List<PurchaseFeeTier>) : PurchaseFeeRule {
        init {
            require(tiers.isNotEmpty()) { "A tiered purchase fee must contain at least one tier" }
            require(tiers.first().minGrossAmountInclusive.compareTo(BigDecimal.ZERO) == 0) {
                "A tiered purchase fee must start at zero"
            }
            require(tiers.last().maxGrossAmountExclusive == null) {
                "A tiered purchase fee must end with an unbounded tier"
            }
            require(tiers.dropLast(1).none { it.maxGrossAmountExclusive == null }) {
                "Only the final purchase fee tier may be unbounded"
            }
            require(
                tiers.zipWithNext().all { (left, right) ->
                    left.maxGrossAmountExclusive?.compareTo(right.minGrossAmountInclusive) == 0
                },
            ) { "Purchase fee tiers must be ordered, contiguous, and non-overlapping" }
        }

        public fun ruleFor(grossAmount: BigDecimal): PurchaseFeeRule {
            require(grossAmount.signum() > 0) { "Purchase gross amount must be positive" }
            return tiers.first { tier ->
                grossAmount.compareTo(tier.minGrossAmountInclusive) >= 0 &&
                    (tier.maxGrossAmountExclusive == null ||
                        grossAmount.compareTo(tier.maxGrossAmountExclusive) < 0)
            }.feeRule
        }
    }
}

public data class PurchaseFeeTier public constructor(
    public val minGrossAmountInclusive: BigDecimal,
    public val maxGrossAmountExclusive: BigDecimal?,
    public val feeRule: PurchaseFeeRule,
) {
    init {
        require(minGrossAmountInclusive.signum() >= 0) {
            "Purchase fee tier minimum must not be negative"
        }
        require(
            maxGrossAmountExclusive == null ||
                maxGrossAmountExclusive.compareTo(minGrossAmountInclusive) > 0,
        ) { "Purchase fee tier maximum must be greater than its minimum" }
        require(feeRule !is PurchaseFeeRule.Tiered) { "Purchase fee tiers cannot contain another tiered rule" }
    }
}

public enum class HoldingPeriodUnit {
    DAYS,
    MONTHS,
    YEARS,
}

public data class HoldingPeriodBoundary public constructor(
    public val value: Long,
    public val unit: HoldingPeriodUnit,
) {
    init {
        require(value > 0) { "A holding-period boundary must be positive" }
    }

    public fun dateFrom(confirmationDate: LocalDate): LocalDate = when (unit) {
        HoldingPeriodUnit.DAYS -> confirmationDate.plusDays(value)
        HoldingPeriodUnit.MONTHS -> confirmationDate.plusMonths(value)
        HoldingPeriodUnit.YEARS -> confirmationDate.plusYears(value)
    }

    internal fun isStrictlyBefore(other: HoldingPeriodBoundary): Boolean {
        if (unit == other.unit) return value < other.value
        return maximumElapsedDays() < other.minimumElapsedDays()
    }

    private fun minimumElapsedDays(): BigInteger = value.asBigInteger().multiply(
        when (unit) {
            HoldingPeriodUnit.DAYS -> BigInteger.ONE
            HoldingPeriodUnit.MONTHS -> BigInteger.valueOf(28)
            HoldingPeriodUnit.YEARS -> BigInteger.valueOf(365)
        },
    )

    private fun maximumElapsedDays(): BigInteger = value.asBigInteger().multiply(
        when (unit) {
            HoldingPeriodUnit.DAYS -> BigInteger.ONE
            HoldingPeriodUnit.MONTHS -> BigInteger.valueOf(31)
            HoldingPeriodUnit.YEARS -> BigInteger.valueOf(366)
        },
    )

    private fun Long.asBigInteger(): BigInteger = BigInteger.valueOf(this)
}

public data class RedemptionFeeTier public constructor(
    public val maxHoldingPeriodExclusive: HoldingPeriodBoundary?,
    public val rate: BigDecimal,
) {
    init {
        require(rate.signum() >= 0) { "Redemption fee rate must not be negative" }
    }
}

public data class RedemptionFeeSchedule public constructor(
    public val tiers: List<RedemptionFeeTier>,
) {
    init {
        require(tiers.isNotEmpty()) { "A redemption fee schedule must contain at least one tier" }
        require(tiers.count { it.maxHoldingPeriodExclusive == null } == 1) {
            "A redemption fee schedule must contain exactly one unbounded tier"
        }
        require(tiers.last().maxHoldingPeriodExclusive == null) {
            "The unbounded redemption fee tier must be last"
        }
        val finiteBoundaries = tiers.mapNotNull { it.maxHoldingPeriodExclusive }
        require(finiteBoundaries.zipWithNext().all { (left, right) -> left.isStrictlyBefore(right) }) {
            "Redemption fee tier boundaries must be unambiguously and strictly increasing"
        }
    }

    public fun rateFor(holdingDays: Long): BigDecimal {
        require(holdingDays >= 0) { "Holding days must not be negative" }
        require(tiers.mapNotNull { it.maxHoldingPeriodExclusive }.all { it.unit == HoldingPeriodUnit.DAYS }) {
            "A day count cannot resolve calendar month or year fee boundaries"
        }
        return tiers.first { tier ->
            tier.maxHoldingPeriodExclusive?.let { holdingDays < it.value } ?: true
        }.rate
    }

    public fun rateFor(confirmationDate: LocalDate, redemptionDate: LocalDate): BigDecimal {
        require(!redemptionDate.isBefore(confirmationDate)) {
            "Redemption date must not precede confirmation date"
        }
        return tiers.first { tier ->
            tier.maxHoldingPeriodExclusive?.let { boundary ->
                redemptionDate.isBefore(boundary.dateFrom(confirmationDate))
            } ?: true
        }.rate
    }

    public companion object {
        public fun noFee(): RedemptionFeeSchedule = RedemptionFeeSchedule(
            tiers = listOf(
                RedemptionFeeTier(maxHoldingPeriodExclusive = null, rate = BigDecimal.ZERO),
            ),
        )
    }
}
