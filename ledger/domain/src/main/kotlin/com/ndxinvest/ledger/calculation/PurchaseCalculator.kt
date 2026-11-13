package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.FundPrecision
import com.ndxinvest.ledger.model.PurchaseFeeRule
import java.math.BigDecimal

public data class PurchaseCalculation public constructor(
    public val grossAmount: BigDecimal,
    public val netSubscriptionAmount: BigDecimal,
    public val purchaseFee: BigDecimal,
    public val confirmedShares: BigDecimal,
    public val unitNav: BigDecimal,
)

public object PurchaseCalculator {
    public fun calculate(
        grossAmount: BigDecimal,
        unitNav: BigDecimal,
        feeRule: PurchaseFeeRule,
        precision: FundPrecision,
    ): PurchaseCalculation {
        require(grossAmount.signum() > 0) { "Purchase gross amount must be positive" }
        require(unitNav.signum() > 0) { "Purchase NAV must be positive" }

        val gross = precision.roundMoney(grossAmount)
        val nav = precision.roundNav(unitNav)
        require(gross.signum() > 0) { "Rounded purchase gross amount must be positive" }
        require(nav.signum() > 0) { "Rounded purchase NAV must be positive" }
        val fee: BigDecimal
        val net: BigDecimal

        val applicableFeeRule = when (feeRule) {
            is PurchaseFeeRule.Tiered -> feeRule.ruleFor(gross)
            else -> feeRule
        }
        when (applicableFeeRule) {
            PurchaseFeeRule.None -> {
                fee = precision.zeroMoney()
                net = gross
            }

            is PurchaseFeeRule.Fixed -> {
                fee = precision.roundMoney(applicableFeeRule.amount)
                require(fee.compareTo(gross) < 0) { "Fixed purchase fee must be less than gross amount" }
                net = precision.roundMoney(gross.subtract(fee))
            }

            is PurchaseFeeRule.Rate -> {
                val divisor = BigDecimal.ONE.add(applicableFeeRule.rate)
                net = precision.roundMoney(
                    gross.divide(divisor, FundPrecision.CALCULATION_CONTEXT),
                )
                fee = precision.roundMoney(gross.subtract(net))
            }

            is PurchaseFeeRule.Tiered -> error("Nested tiered purchase fees are prohibited")
        }

        require(net.signum() > 0) { "Net subscription amount must be positive" }
        val shares = precision.roundShares(
            net.divide(nav, FundPrecision.CALCULATION_CONTEXT),
        )
        require(shares.signum() > 0) { "Confirmed purchase shares must be positive" }
        return PurchaseCalculation(
            grossAmount = gross,
            netSubscriptionAmount = net,
            purchaseFee = fee,
            confirmedShares = shares,
            unitNav = nav,
        )
    }
}
