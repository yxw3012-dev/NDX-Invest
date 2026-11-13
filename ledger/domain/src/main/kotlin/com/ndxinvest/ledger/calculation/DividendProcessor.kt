package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.CashFlowKind
import com.ndxinvest.ledger.model.DatedCashFlow
import com.ndxinvest.ledger.model.DividendTreatment
import com.ndxinvest.ledger.model.FundPrecision
import java.math.BigDecimal
import java.time.LocalDate

public data class DividendProcessingResult public constructor(
    public val treatment: DividendTreatment,
    public val updatedShares: BigDecimal,
    public val addedShares: BigDecimal,
    public val externalCashFlow: DatedCashFlow?,
    public val externalInvestmentIncrease: BigDecimal,
    public val realizedIncome: BigDecimal,
    public val reinvestmentCostBasis: BigDecimal,
    public val currentPortfolioCashIncrease: BigDecimal,
)

public object DividendProcessor {
    public fun process(
        currentShares: BigDecimal,
        dividendAmount: BigDecimal,
        paymentDate: LocalDate,
        treatment: DividendTreatment,
        reinvestmentUnitNav: BigDecimal?,
        precision: FundPrecision,
    ): DividendProcessingResult {
        require(currentShares.signum() >= 0) { "Current shares must not be negative" }
        require(dividendAmount.signum() > 0) { "Dividend amount must be positive" }

        val shares = precision.roundShares(currentShares)
        val amount = precision.roundMoney(dividendAmount)
        require(amount.signum() > 0) { "Rounded dividend amount must be positive" }

        return when (treatment) {
            DividendTreatment.CASH -> DividendProcessingResult(
                treatment = treatment,
                updatedShares = shares,
                addedShares = precision.zeroShares(),
                externalCashFlow = DatedCashFlow(
                    date = paymentDate,
                    amount = amount,
                    kind = CashFlowKind.CASH_DIVIDEND,
                ),
                externalInvestmentIncrease = precision.zeroMoney(),
                realizedIncome = amount,
                reinvestmentCostBasis = precision.zeroMoney(),
                currentPortfolioCashIncrease = precision.zeroMoney(),
            )

            DividendTreatment.REINVESTED -> {
                require(reinvestmentUnitNav != null && reinvestmentUnitNav.signum() > 0) {
                    "Dividend reinvestment requires a positive official NAV"
                }
                val nav = precision.roundNav(reinvestmentUnitNav)
                require(nav.signum() > 0) { "Rounded reinvestment NAV must be positive" }
                val addedShares = precision.roundShares(
                    amount.divide(nav, FundPrecision.CALCULATION_CONTEXT),
                )
                require(addedShares.signum() > 0) { "Dividend reinvestment must create positive shares" }
                DividendProcessingResult(
                    treatment = treatment,
                    updatedShares = precision.roundShares(shares.add(addedShares)),
                    addedShares = addedShares,
                    externalCashFlow = null,
                    externalInvestmentIncrease = precision.zeroMoney(),
                    realizedIncome = precision.zeroMoney(),
                    reinvestmentCostBasis = amount,
                    currentPortfolioCashIncrease = precision.zeroMoney(),
                )
            }
        }
    }
}
