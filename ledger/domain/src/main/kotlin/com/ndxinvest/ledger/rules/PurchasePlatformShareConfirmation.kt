package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.calculation.UnconsumedPurchaseLotCorrected
import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Matching cash/NAV/fees only. Monetary discrepancies and consumed Lots are deliberately excluded. */
public data class PurchasePlatformShareConfirmation public constructor(
    public val expected: LedgerTransaction,
    public val expectedLot: Lot,
    public val confirmed: LedgerTransaction,
    public val entry: UnconsumedPurchaseLotCorrected,
    public val audits: List<TransactionCorrection>,
) {
    public companion object {
        public fun prepare(
            expected: LedgerTransaction,
            lot: Lot,
            precision: FundPrecision,
            actualCashPaid: BigDecimal,
            paymentDate: LocalDate,
            platformShares: BigDecimal,
            confirmationDate: LocalDate,
            recordedAt: Instant,
            today: LocalDate,
        ): PurchasePlatformShareConfirmation {
            require(expected.type in setOf(TransactionType.PURCHASE, TransactionType.RECURRING_PURCHASE) &&
                expected.status == TransactionStatus.CALCULATED && expected.authority == DataAuthority.APP_RULE_CALCULATION &&
                expected.origin != TransactionOrigin.IMPORTED && expected.actualCashFlow == null &&
                expected.cashFlowDate == null && expected.settlementDate == null
            ) { "只有尚未确认实际扣款的已计算申购或定投可修正" }
            require(lot.sourceTransactionId == expected.id && lot.fundId == expected.fundId && lot.type == LotType.REAL &&
                lot.originalShares.compareTo(expected.shares ?: BigDecimal.ZERO) == 0 &&
                lot.grossCashOut.compareTo(expected.grossAmount ?: BigDecimal.ZERO) == 0 &&
                lot.netSubscriptionAmount.compareTo(expected.netAmount ?: BigDecimal.ZERO) == 0 &&
                expected.purchaseFee != null && lot.purchaseFee.compareTo(expected.purchaseFee) == 0 &&
                lot.unitNav.compareTo(expected.unitNav ?: BigDecimal.ZERO) == 0 && lot.confirmationDate == expected.confirmationDate
            ) { "Lot 与原计算不一致，请先处理账本异常" }
            require(actualCashPaid.signum() > 0 && actualCashPaid.stripTrailingZeros().scale() <= precision.moneyScale &&
                actualCashPaid.compareTo(lot.grossCashOut) == 0 && lot.netSubscriptionAmount.signum() > 0 &&
                lot.netSubscriptionAmount.add(lot.purchaseFee).compareTo(actualCashPaid) == 0
            ) { "此流程仅支持扣款、净申购金额和费用与原计算一致的记录" }
            require(platformShares.signum() > 0 && platformShares.stripTrailingZeros().scale() <= precision.shareScale) {
                "平台份额必须为正数且符合基金精度"
            }
            require(expected.navDate != null && !confirmationDate.isBefore(expected.navDate) &&
                !confirmationDate.isBefore(expected.tradeDate) && !paymentDate.isAfter(confirmationDate) &&
                !confirmationDate.isAfter(today) && !paymentDate.isAfter(today)
            ) { "请核对平台真实扣款及份额确认日期，不得使用未来日期" }
            val shares = precision.roundShares(platformShares)
            require(shares.compareTo(lot.originalShares) != 0 || confirmationDate != lot.confirmationDate) {
                "平台份额和确认日期与原计算一致，请使用匹配扣款确认入口"
            }
            val correctedLot = lot.copy(originalShares = shares, remainingShares = shares, confirmationDate = confirmationDate)
            val entry = UnconsumedPurchaseLotCorrected(
                LedgerEntryId("platform-lot-correction:${expected.id.value}"), 1, lot, correctedLot,
            )
            var transaction = expected
            val audits = mutableListOf<TransactionCorrection>()
            fun correct(field: TransactionCorrectionField, value: CorrectionValue) {
                val result = TransactionCorrectionService.applySalesPlatformFinalCorrection(
                    transaction, CorrectionId("platform-share-confirmation:${expected.id.value}:${field.name}"),
                    field, value, recordedAt,
                )
                transaction = result.transaction
                audits += result.audit
            }
            if (shares.compareTo(lot.originalShares) != 0) correct(TransactionCorrectionField.SHARES, CorrectionValue.Decimal(shares))
            if (confirmationDate != lot.confirmationDate) correct(TransactionCorrectionField.CONFIRMATION_DATE, CorrectionValue.Date(confirmationDate))
            correct(TransactionCorrectionField.ACTUAL_CASH_FLOW, CorrectionValue.Decimal(precision.roundMoney(actualCashPaid).negate()))
            correct(TransactionCorrectionField.CASH_FLOW_DATE, CorrectionValue.Date(paymentDate))
            return PurchasePlatformShareConfirmation(expected, lot, transaction.copy(status = TransactionStatus.CONFIRMED_ACTUAL), entry, audits)
        }
    }
}
