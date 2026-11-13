package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.calculation.*
import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Explicit final receipt facts only. The already consumed shares/cost and every ledger event stay intact. */
public data class FinalRedemptionReceiptCorrection public constructor(
    public val reviewId: String,
    public val fund: Fund,
    public val history: LedgerHistorySnapshot,
    public val expected: LedgerTransaction,
    public val confirmed: LedgerTransaction,
    public val cost: VerifiedRedemptionCost,
    public val audits: List<TransactionCorrection>,
) {
    public val previousCashDate: LocalDate get() = requireNotNull(expected.cashFlowDate ?: expected.settlementDate)
    public val previousRecordedIncome: BigDecimal get() = requireNotNull(fund.precision).roundMoney(requireNotNull(expected.actualCashFlow).subtract(cost.allocatedCost))
    public val correctedRecordedIncome: BigDecimal get() = requireNotNull(fund.precision).roundMoney(requireNotNull(confirmed.actualCashFlow).subtract(cost.allocatedCost))

    public fun validate(now: Instant, today: LocalDate) {
        require(!confirmed.updatedAt.isAfter(now)) { "更正时间不能来自未来" }
        val recreated = prepare(reviewId, fund, history, expected, requireNotNull(confirmed.actualCashFlow),
            requireNotNull(confirmed.cashFlowDate), confirmed.confirmationDate, confirmed.updatedAt, today)
        require(this == recreated) { "到账更正凭据不完整或包含其他修改" }
    }

    public companion object {
        public fun prepare(reviewId: String, fund: Fund, history: LedgerHistorySnapshot, expected: LedgerTransaction,
            actualCashReceived: BigDecimal, actualCashDate: LocalDate, platformConfirmationDate: LocalDate?,
            recordedAt: Instant, today: LocalDate): FinalRedemptionReceiptCorrection {
            require(reviewId.length in 1..100 && reviewId.isNotBlank())
            require(fund.id == history.fundId && expected.fundId == fund.id && history.transactions.singleOrNull { it.id == expected.id } == expected) {
                "原卖出不属于本基金或账本已变化" }
            require(expected.type == TransactionType.REDEMPTION && expected.status == TransactionStatus.CONFIRMED_ACTUAL &&
                expected.authority == DataAuthority.USER_OR_PLATFORM_FINAL && expected.origin != TransactionOrigin.IMPORTED) {
                "此入口仅更正已确认到账的真实卖出记录" }
            val precision = requireNotNull(fund.precision) { "请先确认基金精度" }
            val originalCash = requireNotNull(expected.actualCashFlow) { "缺少原实际到账金额" }
            val oldDate = requireNotNull(expected.cashFlowDate ?: expected.settlementDate) { "缺少原实际到账日期" }
            require(originalCash.signum() >= 0 && originalCash.stripTrailingZeros().scale() <= precision.moneyScale &&
                expected.shares?.let { it.signum() > 0 && it.stripTrailingZeros().scale() <= precision.shareScale } == true &&
                !oldDate.isBefore(expected.tradeDate) && !oldDate.isAfter(today)) { "原到账或份额需先核对，不能猜补" }
            require(actualCashReceived.precision() <= 100 && actualCashReceived.scale() in 0..12 && actualCashReceived.signum() >= 0 &&
                actualCashReceived.stripTrailingZeros().scale() <= precision.moneyScale &&
                !actualCashDate.isBefore(expected.tradeDate) && !actualCashDate.isAfter(today)) { "请填写精度正确的实际到账金额及已发生的到账日期" }
            require(!recordedAt.isBefore(expected.updatedAt) && !recordedAt.isBefore(expected.createdAt) &&
                !recordedAt.isBefore(expected.applicationTime)) { "更正时间不能早于原记录" }
            val inspection = LedgerHistoryInspector.inspect(history)
            require(inspection is LedgerHistoryInspection.Consistent) { "历史账本需先核对，不能直接改到账" }
            val cost = requireNotNull(inspection.redemptionCosts.singleOrNull { it.transactionId == expected.id }) { "缺少明确关联的历史卖出成本" }
            val consumed = when (val event = history.entries.single { it.id == cost.entryId }) {
                is LotsRedeemed -> event.consumptions
                is LegacyLotRedeemed -> listOf(event.consumption)
                else -> error("缺少历史卖出事件")
            }
            require(consumed.all { it.allocatedCostBasis.stripTrailingZeros().scale() <= precision.moneyScale &&
                it.shares.stripTrailingZeros().scale() <= precision.shareScale }) { "逐笔历史分摊精度不正确，不能用合计抵消误差" }
            require(cost.shares.compareTo(requireNotNull(expected.shares)) == 0 && cost.allocatedCost.signum() >= 0 &&
                cost.allocatedCost.stripTrailingZeros().scale() <= precision.moneyScale) { "历史分摊成本或份额不符合基金精度" }
            require(platformConfirmationDate != null || expected.confirmationDate == null && cost.lotType == LotType.LEGACY) {
                "不能删除已知确认日，真实持仓卖出须提供平台确认日" }
            require(platformConfirmationDate == null || !platformConfirmationDate.isBefore(expected.navDate ?: expected.tradeDate) &&
                !platformConfirmationDate.isAfter(today)) { "平台确认日不能早于原交易/净值日或来自未来" }
            val cash = precision.roundMoney(actualCashReceived)
            require(cash.compareTo(originalCash) != 0 || actualCashDate != oldDate || platformConfirmationDate != expected.confirmationDate ||
                expected.netAmount?.compareTo(cash) != 0) { "到账金额和日期未变化，无需更正" }
            var transaction = expected
            val audits = mutableListOf<TransactionCorrection>()
            fun correct(field: TransactionCorrectionField, value: CorrectionValue) {
                val correction = TransactionCorrectionService.applySalesPlatformFinalCorrection(transaction,
                    CorrectionId("receipt-correction:$reviewId:${field.name}"), field, value, recordedAt)
                transaction = correction.transaction; audits += correction.audit
            }
            if (cash.compareTo(originalCash) != 0) correct(TransactionCorrectionField.ACTUAL_CASH_FLOW, CorrectionValue.Decimal(cash))
            if (expected.netAmount?.compareTo(cash) != 0) correct(TransactionCorrectionField.NET_AMOUNT, CorrectionValue.Decimal(cash))
            if (expected.cashFlowDate != actualCashDate) correct(TransactionCorrectionField.CASH_FLOW_DATE, CorrectionValue.Date(actualCashDate))
            if (platformConfirmationDate != expected.confirmationDate) correct(TransactionCorrectionField.CONFIRMATION_DATE,
                CorrectionValue.Date(requireNotNull(platformConfirmationDate)))
            return FinalRedemptionReceiptCorrection(reviewId, fund, history, expected, transaction, cost, audits)
        }
    }
}
