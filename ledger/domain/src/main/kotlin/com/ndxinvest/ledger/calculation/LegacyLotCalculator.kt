package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.FundPrecision
import com.ndxinvest.ledger.model.LegacyLot
import java.math.BigDecimal

public data class LegacyLotRedemptionCalculation public constructor(
    public val redeemedShares: BigDecimal,
    public val allocatedCostBasis: BigDecimal,
    public val actualCashReceived: BigDecimal,
    public val realizedReturn: BigDecimal,
    public val updatedLot: LegacyLot,
    public val inferredRedemptionFee: BigDecimal?,
)

public object LegacyLotCalculator {
    public fun redeem(
        lot: LegacyLot,
        sharesToRedeem: BigDecimal,
        actualCashReceived: BigDecimal,
        precision: FundPrecision,
    ): LegacyLotRedemptionCalculation {
        require(lot.remainingShares.signum() > 0) { "Legacy Lot has no remaining shares" }
        require(sharesToRedeem.signum() > 0) { "Legacy Lot redemption shares must be positive" }
        require(actualCashReceived.signum() >= 0) { "Actual cash received must not be negative" }

        val shares = precision.roundShares(sharesToRedeem)
        require(shares.signum() > 0) { "Rounded Legacy Lot redemption shares must be positive" }
        require(shares.compareTo(lot.remainingShares) <= 0) {
            "Legacy Lot redemption shares exceed available shares"
        }
        val allocatedCost = if (shares.compareTo(lot.remainingShares) == 0) {
            precision.roundMoney(lot.costBasis)
        } else {
            precision.roundMoney(
                lot.costBasis.multiply(shares, FundPrecision.CALCULATION_CONTEXT)
                    .divide(lot.remainingShares, FundPrecision.CALCULATION_CONTEXT),
            )
        }
        val remainingShares = precision.roundShares(lot.remainingShares.subtract(shares))
        val remainingCost = if (remainingShares.signum() == 0) {
            precision.zeroMoney()
        } else {
            precision.roundMoney(lot.costBasis.subtract(allocatedCost))
        }
        val cash = precision.roundMoney(actualCashReceived)

        return LegacyLotRedemptionCalculation(
            redeemedShares = shares,
            allocatedCostBasis = allocatedCost,
            actualCashReceived = cash,
            realizedReturn = precision.roundMoney(cash.subtract(allocatedCost)),
            updatedLot = lot.copy(
                remainingShares = remainingShares,
                costBasis = remainingCost,
            ),
            inferredRedemptionFee = null,
        )
    }
}
