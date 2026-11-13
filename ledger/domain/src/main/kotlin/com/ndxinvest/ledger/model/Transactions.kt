package com.ndxinvest.ledger.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

public enum class TransactionType {
    PURCHASE,
    REDEMPTION,
    RECURRING_PURCHASE,
    CASH_DIVIDEND,
    DIVIDEND_REINVESTMENT,
    ADJUSTMENT,
}

public enum class TransactionOrigin {
    USER_ENTERED,
    AUTO_PLAN_ASSUMED,
    IMPORTED,
}

public enum class TransactionStatus(public val isFinal: Boolean) {
    PLANNED(false),
    PENDING_CALC(false),
    CALCULATED(false),
    CONFIRMED_ACTUAL(true),
    ABNORMAL(false),
    NOT_EXECUTED(true),
    CANCELLED(true),
}

public enum class CalculationIssue {
    MISSING_NAV_DATE,
    MISSING_OFFICIAL_NAV,
}

public data class LedgerTransaction public constructor(
    public val id: TransactionId,
    public val fundId: FundId,
    public val type: TransactionType,
    public val status: TransactionStatus,
    public val origin: TransactionOrigin,
    public val authority: DataAuthority,
    public val applicationTime: Instant,
    public val tradeDate: LocalDate,
    public val navDate: LocalDate?,
    public val confirmationDate: LocalDate?,
    public val settlementDate: LocalDate?,
    public val ruleVersionId: RuleVersionId,
    public val requestedAmount: BigDecimal? = null,
    public val grossAmount: BigDecimal? = null,
    public val netAmount: BigDecimal? = null,
    public val shares: BigDecimal? = null,
    public val unitNav: BigDecimal? = null,
    public val purchaseFee: BigDecimal? = null,
    public val redemptionFee: BigDecimal? = null,
    public val actualCashFlow: BigDecimal? = null,
    public val isManualCorrected: Boolean = false,
    public val createdAt: Instant = applicationTime,
    public val updatedAt: Instant = applicationTime,
    /** Actual external cash date, distinct from effective trade, NAV, confirmation and settlement dates. */
    public val cashFlowDate: LocalDate? = null,
) {
    init {
        require(cashFlowDate == null || actualCashFlow != null) {
            "An actual cash date requires an actual cash amount"
        }
        require(navDate == null || !navDate.isBefore(tradeDate)) {
            "NAV date must not precede the effective trade date"
        }
        require(confirmationDate == null || !confirmationDate.isBefore(tradeDate)) {
            "Confirmation date must not precede the effective trade date"
        }
        require(settlementDate == null || !settlementDate.isBefore(tradeDate)) {
            "Settlement date must not precede the effective trade date"
        }
        require(status != TransactionStatus.CONFIRMED_ACTUAL || authority == DataAuthority.USER_OR_PLATFORM_FINAL) {
            "A final actual transaction requires final user or platform authority"
        }
        require(requestedAmount == null || requestedAmount.signum() >= 0) { "Requested amount must not be negative" }
        require(grossAmount == null || grossAmount.signum() >= 0) { "Gross amount must not be negative" }
        require(netAmount == null || netAmount.signum() >= 0) { "Net amount must not be negative" }
        require(shares == null || shares.signum() >= 0) { "Transaction shares must not be negative" }
        require(unitNav == null || unitNav.signum() > 0) { "Transaction NAV must be positive" }
        require(purchaseFee == null || purchaseFee.signum() >= 0) { "Purchase fee must not be negative" }
        require(redemptionFee == null || redemptionFee.signum() >= 0) { "Redemption fee must not be negative" }
        require(!updatedAt.isBefore(createdAt)) { "Transaction update time must not precede creation time" }
    }
}

public data class RecurringExecutionKey public constructor(
    public val planId: RecurringPlanId,
    public val scheduledTradeDate: LocalDate,
)
