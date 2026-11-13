package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.calculation.UnconsumedPurchaseNavCorrected
import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

public data class PurchasePlatformNavConfirmation public constructor(
    public val expected: LedgerTransaction,
    public val expectedLot: Lot,
    public val confirmed: LedgerTransaction,
    public val entry: UnconsumedPurchaseNavCorrected,
    public val audits: List<TransactionCorrection>,
) {
    public companion object {
        public fun prepare(
            expected: LedgerTransaction, lot: Lot, precision: FundPrecision, officialNav: NavRecord,
            actualCashPaid: BigDecimal, platformNetAmount: BigDecimal, platformPurchaseFee: BigDecimal,
            platformUnitNav: BigDecimal, platformShares: BigDecimal, paymentDate: LocalDate,
            confirmationDate: LocalDate, recordedAt: Instant, today: LocalDate,
        ): PurchasePlatformNavConfirmation {
            require(expected.type in setOf(TransactionType.PURCHASE, TransactionType.RECURRING_PURCHASE) &&
                expected.status == TransactionStatus.CALCULATED && expected.authority == DataAuthority.APP_RULE_CALCULATION &&
                expected.origin != TransactionOrigin.IMPORTED && expected.actualCashFlow == null &&
                expected.cashFlowDate == null && expected.settlementDate == null
            ) { "仅可修正尚未最终确认的已计算申购或定投" }
            require(lot.sourceTransactionId == expected.id && lot.fundId == expected.fundId && lot.type == LotType.REAL &&
                lot.originalShares.compareTo(expected.shares ?: BigDecimal.ZERO) == 0 &&
                lot.grossCashOut.compareTo(expected.grossAmount ?: BigDecimal.ZERO) == 0 &&
                lot.netSubscriptionAmount.compareTo(expected.netAmount ?: BigDecimal.ZERO) == 0 &&
                expected.purchaseFee != null && lot.purchaseFee.compareTo(expected.purchaseFee) == 0 &&
                lot.unitNav.compareTo(expected.unitNav ?: BigDecimal.ZERO) == 0 && lot.confirmationDate == expected.confirmationDate
            ) { "原交易和 Lot 不一致，请先核对账本" }
            require(officialNav.authority == DataAuthority.FUND_OFFICIAL && officialNav.fundId == expected.fundId &&
                officialNav.navDate == expected.navDate && !officialNav.fetchedAt.isAfter(recordedAt) &&
                platformUnitNav.compareTo(officialNav.unitNav) == 0 && platformUnitNav.compareTo(lot.unitNav) != 0 &&
                platformUnitNav.stripTrailingZeros().scale() <= precision.navScale
            ) { "须有原净值日的基金官方净值，且平台净值与它一致；不能换基金、换净值日或只凭输入覆盖" }
            require(actualCashPaid.signum() > 0 && platformNetAmount.signum() > 0 && platformPurchaseFee.signum() >= 0 &&
                listOf(actualCashPaid, platformNetAmount, platformPurchaseFee).all { it.stripTrailingZeros().scale() <= precision.moneyScale } &&
                platformNetAmount.add(platformPurchaseFee).compareTo(actualCashPaid) == 0
            ) { "实际扣款、净申购金额和明确申购费必须精度正确，且净金额加费用等于扣款" }
            require(platformShares.signum() > 0 && platformShares.stripTrailingZeros().scale() <= precision.shareScale) {
                "平台确认份额必须为正数且符合基金精度"
            }
            require(!confirmationDate.isBefore(officialNav.navDate) && !confirmationDate.isBefore(expected.tradeDate) &&
                !paymentDate.isAfter(confirmationDate) && !confirmationDate.isAfter(today) && !paymentDate.isAfter(today)
            ) { "请核对真实扣款和确认日期，不得使用未来日期" }
            val gross = precision.roundMoney(actualCashPaid)
            val net = precision.roundMoney(platformNetAmount)
            val fee = precision.roundMoney(platformPurchaseFee)
            val shares = precision.roundShares(platformShares)
            val nav = precision.roundNav(platformUnitNav)
            val correctedLot = lot.copy(grossCashOut = gross, netSubscriptionAmount = net, purchaseFee = fee, costBasis = gross,
                originalShares = shares, remainingShares = shares, unitNav = nav, confirmationDate = confirmationDate)
            val entry = UnconsumedPurchaseNavCorrected(LedgerEntryId("platform-nav-correction:${expected.id.value}"), 1, lot, correctedLot, officialNav)
            var transaction = expected
            val audits = mutableListOf<TransactionCorrection>()
            fun correct(field: TransactionCorrectionField, value: CorrectionValue) {
                val result = TransactionCorrectionService.applySalesPlatformFinalCorrection(
                    transaction, CorrectionId("platform-nav-confirmation:${expected.id.value}:${field.name}"), field, value, recordedAt,
                )
                transaction = result.transaction
                audits += result.audit
            }
            correct(TransactionCorrectionField.UNIT_NAV, CorrectionValue.Decimal(nav))
            if (gross.compareTo(lot.grossCashOut) != 0) correct(TransactionCorrectionField.GROSS_AMOUNT, CorrectionValue.Decimal(gross))
            if (net.compareTo(lot.netSubscriptionAmount) != 0) correct(TransactionCorrectionField.NET_AMOUNT, CorrectionValue.Decimal(net))
            if (fee.compareTo(lot.purchaseFee) != 0) correct(TransactionCorrectionField.PURCHASE_FEE, CorrectionValue.Decimal(fee))
            if (shares.compareTo(lot.originalShares) != 0) correct(TransactionCorrectionField.SHARES, CorrectionValue.Decimal(shares))
            if (confirmationDate != lot.confirmationDate) correct(TransactionCorrectionField.CONFIRMATION_DATE, CorrectionValue.Date(confirmationDate))
            correct(TransactionCorrectionField.ACTUAL_CASH_FLOW, CorrectionValue.Decimal(gross.negate()))
            correct(TransactionCorrectionField.CASH_FLOW_DATE, CorrectionValue.Date(paymentDate))
            return PurchasePlatformNavConfirmation(expected, lot, transaction.copy(status = TransactionStatus.CONFIRMED_ACTUAL), entry, audits)
        }
    }
}
