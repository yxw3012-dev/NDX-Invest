package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.FundPrecision
import com.ndxinvest.ledger.model.LegacyLot
import com.ndxinvest.ledger.model.Lot
import com.ndxinvest.ledger.model.NavRecord
import com.ndxinvest.ledger.model.ValuationDateBasis
import java.math.BigDecimal
import java.time.LocalDate

public data class PositionProjection public constructor(
    public val fundId: FundId,
    public val totalShares: BigDecimal,
    public val remainingCostBasis: BigDecimal,
    public val averageCostNav: BigDecimal?,
    public val latestUnitNav: BigDecimal,
    public val navDate: LocalDate,
    public val currentValue: BigDecimal,
    public val holdingReturn: BigDecimal,
    public val holdingReturnRate: BigDecimal?,
)

public data class PortfolioValuation public constructor(
    public val totalCurrentValue: BigDecimal,
    public val dateBasis: ValuationDateBasis,
    public val commonNavDate: LocalDate?,
    public val navDates: Map<FundId, LocalDate>,
)

public data class PerformanceCalculation public constructor(
    public val currentPortfolioValue: BigDecimal,
    public val externalCashInvested: BigDecimal,
    public val externalCashReturned: BigDecimal,
    public val cumulativeReturn: BigDecimal,
)

public object PositionProjector {
    public fun project(
        fundId: FundId,
        realLots: List<Lot>,
        legacyLots: List<LegacyLot>,
        latestNav: NavRecord,
        precision: FundPrecision,
    ): PositionProjection {
        require(latestNav.fundId == fundId) { "NAV must belong to the projected fund" }
        require(realLots.all { it.fundId == fundId }) { "Every real lot must belong to the projected fund" }
        require(legacyLots.all { it.fundId == fundId }) { "Every Legacy Lot must belong to the projected fund" }

        val totalShares = precision.roundShares(
            realLots.fold(BigDecimal.ZERO) { total, lot -> total.add(lot.remainingShares) }
                .add(legacyLots.fold(BigDecimal.ZERO) { total, lot -> total.add(lot.remainingShares) }),
        )
        val remainingCost = precision.roundMoney(
            realLots.fold(BigDecimal.ZERO) { total, lot -> total.add(lot.costBasis) }
                .add(legacyLots.fold(BigDecimal.ZERO) { total, lot -> total.add(lot.costBasis) }),
        )
        val nav = precision.roundNav(latestNav.unitNav)
        val currentValue = precision.roundMoney(
            totalShares.multiply(nav, FundPrecision.CALCULATION_CONTEXT),
        )
        val holdingReturn = precision.roundMoney(currentValue.subtract(remainingCost))
        val averageCost = if (totalShares.signum() == 0) {
            null
        } else {
            precision.roundNav(
                remainingCost.divide(totalShares, FundPrecision.CALCULATION_CONTEXT),
            )
        }
        val holdingReturnRate = if (remainingCost.signum() == 0) {
            null
        } else {
            holdingReturn.divide(remainingCost, RATE_SCALE, precision.roundingMode)
        }

        return PositionProjection(
            fundId = fundId,
            totalShares = totalShares,
            remainingCostBasis = remainingCost,
            averageCostNav = averageCost,
            latestUnitNav = nav,
            navDate = latestNav.navDate,
            currentValue = currentValue,
            holdingReturn = holdingReturn,
            holdingReturnRate = holdingReturnRate,
        )
    }

    private const val RATE_SCALE: Int = 8
}

public object PortfolioValuator {
    public fun aggregate(
        positions: List<PositionProjection>,
        precision: FundPrecision,
    ): PortfolioValuation {
        require(positions.isNotEmpty()) { "At least one position is required for portfolio valuation" }
        require(positions.map { it.fundId }.distinct().size == positions.size) {
            "Portfolio valuation must contain at most one position per fund"
        }
        val navDates = positions.associate { it.fundId to it.navDate }
        val distinctDates = navDates.values.toSet()
        val commonDate = distinctDates.singleOrNull()
        return PortfolioValuation(
            totalCurrentValue = precision.roundMoney(
                positions.fold(BigDecimal.ZERO) { total, position -> total.add(position.currentValue) },
            ),
            dateBasis = if (commonDate == null) {
                ValuationDateBasis.EACH_FUND_LATEST_PUBLISHED
            } else {
                ValuationDateBasis.COMMON_NAV_DATE
            },
            commonNavDate = commonDate,
            navDates = navDates,
        )
    }
}

public object PerformanceCalculator {
    public fun calculate(
        currentPositionValue: BigDecimal,
        externalCashInvested: BigDecimal,
        externalCashReturned: BigDecimal,
        precision: FundPrecision,
    ): PerformanceCalculation {
        require(currentPositionValue.signum() >= 0) { "Current position value must not be negative" }
        require(externalCashInvested.signum() >= 0) { "External invested cash must not be negative" }
        require(externalCashReturned.signum() >= 0) { "External returned cash must not be negative" }
        val currentValue = precision.roundMoney(currentPositionValue)
        val invested = precision.roundMoney(externalCashInvested)
        val returned = precision.roundMoney(externalCashReturned)
        return PerformanceCalculation(
            currentPortfolioValue = currentValue,
            externalCashInvested = invested,
            externalCashReturned = returned,
            cumulativeReturn = precision.roundMoney(currentValue.add(returned).subtract(invested)),
        )
    }
}
