package com.ndxinvest.ledger.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

public enum class TransactionCorrectionField {
    CONFIRMATION_DATE,
    SETTLEMENT_DATE,
    UNIT_NAV,
    SHARES,
    ACTUAL_CASH_FLOW,
    PURCHASE_FEE,
    REDEMPTION_FEE,
    CASH_FLOW_DATE,
    GROSS_AMOUNT,
    NET_AMOUNT,
}

public sealed interface CorrectionValue {
    public data object Missing : CorrectionValue
    public data class Decimal public constructor(public val value: BigDecimal) : CorrectionValue
    public data class Date public constructor(public val value: LocalDate) : CorrectionValue
}

public enum class CorrectionSource { USER, SALES_PLATFORM_FINAL }

public data class TransactionCorrection public constructor(
    public val id: CorrectionId,
    public val transactionId: TransactionId,
    public val field: TransactionCorrectionField,
    public val originalValue: CorrectionValue,
    public val correctedValue: CorrectionValue,
    public val source: CorrectionSource,
    public val correctedAt: Instant,
)

public data class CorrectedTransaction public constructor(
    public val transaction: LedgerTransaction,
    public val audit: TransactionCorrection,
)
