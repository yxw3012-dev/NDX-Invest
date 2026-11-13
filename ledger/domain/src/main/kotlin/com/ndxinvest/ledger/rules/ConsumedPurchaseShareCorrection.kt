package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.calculation.*
import com.ndxinvest.ledger.model.*
import java.time.Instant
import java.time.LocalDate

/** Exact reviewed share-only discrepancy; actual payment may be explicitly confirmed, never guessed. */
public data class ConsumedPurchaseShareCorrection public constructor(
    public val reviewId: String,
    public val preview: ConsumedPurchaseShareImpactPreview,
    public val confirmed: LedgerTransaction,
    public val entry: ConsumedPurchaseSharesCorrected,
    public val audits: List<TransactionCorrection>,
) {
    public fun validate(now: Instant, today: LocalDate) {
        preview.validate(now, today)
        require(this == prepare(reviewId, preview, today)) { "份额更正凭据被修改或缺少完整审计记录" }
    }
    public companion object {
        public fun prepare(reviewId: String, preview: ConsumedPurchaseShareImpactPreview, today: LocalDate): ConsumedPurchaseShareCorrection {
            require(reviewId.length in 1..100 && reviewId.isNotBlank())
            preview.validate(preview.preparedAt, today)
            val expected = preview.originalPurchase
            val precision = requireNotNull(preview.fund.precision)
            var transaction = expected
            val audits = mutableListOf<TransactionCorrection>()
            fun correct(field: TransactionCorrectionField, value: CorrectionValue) {
                val result = TransactionCorrectionService.applySalesPlatformFinalCorrection(transaction,
                    CorrectionId("consumed-shares:$reviewId:${field.name}"), field, value, preview.preparedAt)
                transaction = result.transaction; audits += result.audit
            }
            correct(TransactionCorrectionField.SHARES, CorrectionValue.Decimal(preview.proposedLot.originalShares))
            if (expected.status == TransactionStatus.CALCULATED) {
                correct(TransactionCorrectionField.ACTUAL_CASH_FLOW, CorrectionValue.Decimal(precision.roundMoney(preview.facts.actualCashPaid).negate()))
                correct(TransactionCorrectionField.CASH_FLOW_DATE, CorrectionValue.Date(preview.facts.paymentDate))
            }
            transaction = transaction.copy(status = TransactionStatus.CONFIRMED_ACTUAL)
            val entry = ConsumedPurchaseSharesCorrected(LedgerEntryId("consumed-shares:$reviewId"), 1, preview.originalLot, preview.proposedLot,
                preview.sales.map { RedemptionCostRevision(it.event.id, it.sale.id, it.originalConsumption, it.proposedConsumption) },
                precision, preview.preparedAt)
            val reviewed = preview.history.copy(transactions = preview.history.transactions.map { if (it.id == expected.id) transaction else it },
                realLots = preview.history.realLots.map { if (it.id == entry.expectedLot.id) entry.correctedLot else it },
                entries = preview.history.entries + entry.copy(sequence = preview.history.entries.maxOf { it.sequence } + 1))
            require(LedgerHistoryInspector.inspect(reviewed) is LedgerHistoryInspection.Consistent) { "份额更正后的账本不能完整重放" }
            return ConsumedPurchaseShareCorrection(reviewId, preview, transaction, entry, audits)
        }
    }
}
