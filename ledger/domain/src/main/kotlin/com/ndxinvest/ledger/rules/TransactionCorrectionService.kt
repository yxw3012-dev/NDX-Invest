package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.model.CorrectedTransaction
import com.ndxinvest.ledger.model.CorrectionId
import com.ndxinvest.ledger.model.CorrectionSource
import com.ndxinvest.ledger.model.CorrectionValue
import com.ndxinvest.ledger.model.DataAuthority
import com.ndxinvest.ledger.model.LedgerTransaction
import com.ndxinvest.ledger.model.TransactionCorrection
import com.ndxinvest.ledger.model.TransactionCorrectionField
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

public object TransactionCorrectionService {
    public fun applyManualCorrection(
        transaction: LedgerTransaction,
        correctionId: CorrectionId,
        field: TransactionCorrectionField,
        correctedValue: CorrectionValue,
        correctedAt: Instant,
    ): CorrectedTransaction = applyCorrection(
        transaction = transaction,
        correctionId = correctionId,
        field = field,
        correctedValue = correctedValue,
        source = CorrectionSource.USER,
        correctedAt = correctedAt,
    )

    public fun applySalesPlatformFinalCorrection(
        transaction: LedgerTransaction,
        correctionId: CorrectionId,
        field: TransactionCorrectionField,
        correctedValue: CorrectionValue,
        correctedAt: Instant,
    ): CorrectedTransaction = applyCorrection(
        transaction = transaction,
        correctionId = correctionId,
        field = field,
        correctedValue = correctedValue,
        source = CorrectionSource.SALES_PLATFORM_FINAL,
        correctedAt = correctedAt,
    )

    private fun applyCorrection(
        transaction: LedgerTransaction,
        correctionId: CorrectionId,
        field: TransactionCorrectionField,
        correctedValue: CorrectionValue,
        source: CorrectionSource,
        correctedAt: Instant,
    ): CorrectedTransaction {
        require(!correctedAt.isBefore(transaction.updatedAt)) {
            "Correction time must not precede the last transaction update"
        }
        val originalValue = transaction.valueOf(field)
        require(!originalValue.sameFactAs(correctedValue)) { "A correction must change the selected fact" }
        val corrected = transaction.withCorrectedValue(field, correctedValue).copy(
            authority = DataAuthority.USER_OR_PLATFORM_FINAL,
            isManualCorrected = true,
            updatedAt = correctedAt,
        )
        return CorrectedTransaction(
            transaction = corrected,
            audit = TransactionCorrection(
                id = correctionId,
                transactionId = transaction.id,
                field = field,
                originalValue = originalValue,
                correctedValue = correctedValue,
                source = source,
                correctedAt = correctedAt,
            ),
        )
    }

    private fun LedgerTransaction.valueOf(field: TransactionCorrectionField): CorrectionValue = when (field) {
        TransactionCorrectionField.CONFIRMATION_DATE -> confirmationDate.toCorrectionValue()
        TransactionCorrectionField.SETTLEMENT_DATE -> settlementDate.toCorrectionValue()
        TransactionCorrectionField.CASH_FLOW_DATE -> cashFlowDate.toCorrectionValue()
        TransactionCorrectionField.UNIT_NAV -> unitNav.toCorrectionValue()
        TransactionCorrectionField.SHARES -> shares.toCorrectionValue()
        TransactionCorrectionField.ACTUAL_CASH_FLOW -> actualCashFlow.toCorrectionValue()
        TransactionCorrectionField.PURCHASE_FEE -> purchaseFee.toCorrectionValue()
        TransactionCorrectionField.REDEMPTION_FEE -> redemptionFee.toCorrectionValue()
        TransactionCorrectionField.GROSS_AMOUNT -> grossAmount.toCorrectionValue()
        TransactionCorrectionField.NET_AMOUNT -> netAmount.toCorrectionValue()
    }

    private fun LedgerTransaction.withCorrectedValue(
        field: TransactionCorrectionField,
        value: CorrectionValue,
    ): LedgerTransaction = when (field) {
        TransactionCorrectionField.CONFIRMATION_DATE -> copy(confirmationDate = value.asDateOrNull(field))
        TransactionCorrectionField.SETTLEMENT_DATE -> copy(settlementDate = value.asDateOrNull(field))
        TransactionCorrectionField.CASH_FLOW_DATE -> copy(cashFlowDate = value.asDateOrNull(field))
        TransactionCorrectionField.UNIT_NAV -> copy(unitNav = value.asDecimalOrNull(field))
        TransactionCorrectionField.SHARES -> copy(shares = value.asDecimalOrNull(field))
        TransactionCorrectionField.ACTUAL_CASH_FLOW -> copy(actualCashFlow = value.asDecimalOrNull(field))
        TransactionCorrectionField.PURCHASE_FEE -> copy(purchaseFee = value.asDecimalOrNull(field))
        TransactionCorrectionField.REDEMPTION_FEE -> copy(redemptionFee = value.asDecimalOrNull(field))
        TransactionCorrectionField.GROSS_AMOUNT -> copy(grossAmount = value.asDecimalOrNull(field))
        TransactionCorrectionField.NET_AMOUNT -> copy(netAmount = value.asDecimalOrNull(field))
    }

    private fun CorrectionValue.asDecimalOrNull(field: TransactionCorrectionField): BigDecimal? = when (this) {
        CorrectionValue.Missing -> null
        is CorrectionValue.Decimal -> value
        is CorrectionValue.Date -> throw IllegalArgumentException("$field requires a decimal correction")
    }

    private fun CorrectionValue.asDateOrNull(field: TransactionCorrectionField): LocalDate? = when (this) {
        CorrectionValue.Missing -> null
        is CorrectionValue.Date -> value
        is CorrectionValue.Decimal -> throw IllegalArgumentException("$field requires a date correction")
    }

    private fun CorrectionValue.sameFactAs(other: CorrectionValue): Boolean = when {
        this === CorrectionValue.Missing && other === CorrectionValue.Missing -> true
        this is CorrectionValue.Decimal && other is CorrectionValue.Decimal -> value.compareTo(other.value) == 0
        this is CorrectionValue.Date && other is CorrectionValue.Date -> value == other.value
        else -> false
    }

    private fun BigDecimal?.toCorrectionValue(): CorrectionValue =
        this?.let(CorrectionValue::Decimal) ?: CorrectionValue.Missing

    private fun LocalDate?.toCorrectionValue(): CorrectionValue =
        this?.let(CorrectionValue::Date) ?: CorrectionValue.Missing
}
