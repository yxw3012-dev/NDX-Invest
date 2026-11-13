package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.calculation.*
import com.ndxinvest.ledger.model.*
import java.time.Instant
import java.time.LocalDate

/** Explicitly reviewed cost-only mutation; no saved sale cash/fee/share/date/rule is recomputed. */
public data class ConsumedPurchaseCostCorrection public constructor(
    public val reviewId: String,
    public val preview: ConsumedPurchaseCostImpactPreview,
    public val confirmed: LedgerTransaction,
    public val entry: ConsumedPurchaseCostCorrected,
    public val audits: List<TransactionCorrection>,
) {
    public fun validate(now: Instant, today: LocalDate) {
        preview.validate(now, today)
        require(this == prepare(reviewId, preview, today)) { "成本更正凭据被修改或缺少完整审计记录" }
    }

    public companion object {
        public fun prepare(reviewId: String, preview: ConsumedPurchaseCostImpactPreview, today: LocalDate): ConsumedPurchaseCostCorrection {
            require(reviewId.length in 1..100 && reviewId.isNotBlank())
            preview.validate(preview.preparedAt, today)
            val expected = preview.originalPurchase
            val precision = requireNotNull(preview.fund.precision)
            val facts = preview.facts
            var transaction = expected
            val audits = mutableListOf<TransactionCorrection>()
            fun correct(field: TransactionCorrectionField, value: CorrectionValue) {
                val result = TransactionCorrectionService.applySalesPlatformFinalCorrection(transaction,
                    CorrectionId("consumed-cost:$reviewId:${field.name}"), field, value, preview.preparedAt)
                transaction = result.transaction; audits += result.audit
            }
            correct(TransactionCorrectionField.GROSS_AMOUNT, CorrectionValue.Decimal(preview.proposedLot.grossCashOut))
            correct(TransactionCorrectionField.PURCHASE_FEE, CorrectionValue.Decimal(preview.proposedLot.purchaseFee))
            val cash = precision.roundMoney(facts.actualCashPaid).negate()
            if (expected.actualCashFlow?.compareTo(cash) != 0) correct(TransactionCorrectionField.ACTUAL_CASH_FLOW, CorrectionValue.Decimal(cash))
            if (expected.cashFlowDate != facts.paymentDate) correct(TransactionCorrectionField.CASH_FLOW_DATE, CorrectionValue.Date(facts.paymentDate))
            transaction = transaction.copy(status = TransactionStatus.CONFIRMED_ACTUAL)
            val entry = ConsumedPurchaseCostCorrected(LedgerEntryId("consumed-cost:$reviewId"), 1, preview.originalLot, preview.proposedLot,
                preview.sales.map { RedemptionCostRevision(it.event.id, it.sale.id, it.originalConsumption, it.proposedConsumption) },
                precision, preview.preparedAt)
            val reviewedHistory = preview.history.copy(transactions = preview.history.transactions.map { if (it.id == expected.id) transaction else it },
                realLots = preview.history.realLots.map { if (it.id == entry.expectedLot.id) entry.correctedLot else it },
                entries = preview.history.entries + entry.copy(sequence = preview.history.entries.maxOf { it.sequence } + 1))
            require(LedgerHistoryInspector.inspect(reviewedHistory) is LedgerHistoryInspection.Consistent) { "更正后的账本不能完整重放" }
            return ConsumedPurchaseCostCorrection(reviewId, preview, transaction, entry, audits)
        }
    }
}
