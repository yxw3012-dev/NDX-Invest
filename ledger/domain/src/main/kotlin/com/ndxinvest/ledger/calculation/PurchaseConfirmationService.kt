package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.CalculationIssue
import com.ndxinvest.ledger.model.DataAuthority
import com.ndxinvest.ledger.model.FundPrecision
import com.ndxinvest.ledger.model.LedgerTransaction
import com.ndxinvest.ledger.model.NavRecord
import com.ndxinvest.ledger.model.PurchaseFeeRule
import com.ndxinvest.ledger.model.TransactionStatus
import com.ndxinvest.ledger.model.TransactionType
import java.math.BigDecimal
import java.time.Instant

public data class PurchaseConfirmationOutcome public constructor(
    public val transaction: LedgerTransaction,
    public val calculation: PurchaseCalculation?,
    public val issue: CalculationIssue?,
)

public object PurchaseConfirmationService {
    public fun calculate(
        transaction: LedgerTransaction,
        grossAmount: BigDecimal,
        feeRule: PurchaseFeeRule,
        navRecords: List<NavRecord>,
        precision: FundPrecision,
        calculatedAt: Instant,
    ): PurchaseConfirmationOutcome {
        require(transaction.type in setOf(TransactionType.PURCHASE, TransactionType.RECURRING_PURCHASE)) {
            "Purchase confirmation requires a purchase transaction"
        }
        require(!transaction.status.isFinal) { "A final transaction cannot be recalculated" }
        require(!calculatedAt.isBefore(transaction.updatedAt)) {
            "Calculation time must not precede the last transaction update"
        }

        val expectedNavDate = transaction.navDate
            ?: return abnormal(transaction, CalculationIssue.MISSING_NAV_DATE, calculatedAt)
        val matchingNav = navRecords
            .filter { it.fundId == transaction.fundId && it.navDate == expectedNavDate }
            .maxByOrNull { it.authority.priority }
            ?: return abnormal(transaction, CalculationIssue.MISSING_OFFICIAL_NAV, calculatedAt)
        val calculation = PurchaseCalculator.calculate(
            grossAmount = grossAmount,
            unitNav = matchingNav.unitNav,
            feeRule = feeRule,
            precision = precision,
        )
        return PurchaseConfirmationOutcome(
            transaction = transaction.copy(
                status = TransactionStatus.CALCULATED,
                authority = DataAuthority.APP_RULE_CALCULATION,
                requestedAmount = calculation.grossAmount,
                grossAmount = calculation.grossAmount,
                netAmount = calculation.netSubscriptionAmount,
                shares = calculation.confirmedShares,
                unitNav = calculation.unitNav,
                purchaseFee = calculation.purchaseFee,
                updatedAt = calculatedAt,
            ),
            calculation = calculation,
            issue = null,
        )
    }

    private fun abnormal(
        transaction: LedgerTransaction,
        issue: CalculationIssue,
        calculatedAt: Instant,
    ): PurchaseConfirmationOutcome = PurchaseConfirmationOutcome(
        transaction = transaction.copy(
            status = TransactionStatus.ABNORMAL,
            updatedAt = calculatedAt,
        ),
        calculation = null,
        issue = issue,
    )
}
