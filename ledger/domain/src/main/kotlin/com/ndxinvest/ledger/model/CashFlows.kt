package com.ndxinvest.ledger.model

import java.math.BigDecimal
import java.time.LocalDate

public enum class DividendTreatment {
    CASH,
    REINVESTED,
}

public enum class CashFlowKind {
    PURCHASE,
    REDEMPTION,
    CASH_DIVIDEND,
    TRACKING_BASELINE,
    TERMINAL_VALUE,
}

public data class DatedCashFlow public constructor(
    public val date: LocalDate,
    public val amount: BigDecimal,
    public val kind: CashFlowKind,
) {
    init {
        require(amount.signum() != 0) { "A dated cash flow must not be zero" }
        when (kind) {
            CashFlowKind.PURCHASE,
            CashFlowKind.TRACKING_BASELINE,
            -> require(amount.signum() < 0) { "$kind must be a negative external cash flow" }

            CashFlowKind.REDEMPTION,
            CashFlowKind.CASH_DIVIDEND,
            CashFlowKind.TERMINAL_VALUE,
            -> require(amount.signum() > 0) { "$kind must be a positive external cash flow" }
        }
    }
}

public enum class HistoryScope {
    FULL_HISTORY,
    TRACKING_PERIOD,
}

public enum class XirrLabel {
    ANNUALIZED_RETURN,
    TRACKING_PERIOD_ANNUALIZED,
}
