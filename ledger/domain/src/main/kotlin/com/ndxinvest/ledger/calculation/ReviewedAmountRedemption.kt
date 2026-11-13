package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.*
import com.ndxinvest.ledger.rules.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

public data class ReviewedFundOpenCalendar public constructor(
    public val coverageStart: LocalDate, public val coverageEnd: LocalDate, public val openDates: Set<LocalDate>,
) {
    public fun calendar(): FundOpenDayCalendar = FundOpenDayCalendar(coverageStart, coverageEnd, openDates)
    public companion object {
        public fun from(calendar: FundOpenDayCalendar): ReviewedFundOpenCalendar =
            ReviewedFundOpenCalendar(calendar.coverageStart, calendar.coverageEnd, calendar.knownOpenDates())
    }
}

/** An amount request has no shares/cash/consumption. Only explicit platform shares can activate it. */
public data class ReviewedAmountRedemption public constructor(
    public val fund: Fund, public val history: LedgerHistorySnapshot, public val rules: List<FundRuleVersion>,
    public val transaction: LedgerTransaction, public val expected: LedgerTransaction?,
    public val calendar: ReviewedFundOpenCalendar?, public val shareAudit: TransactionCorrection?,
) {
    public fun validate(now: Instant, zone: ZoneId) {
        val tx = transaction
        val precision = requireNotNull(fund.precision) { "请先确认基金精度" }
        require(history.fundId == fund.id && tx.fundId == fund.id && tx.type == TransactionType.REDEMPTION)
        require(LedgerHistoryInspector.inspect(history) is LedgerHistoryInspection.Consistent) { "请先核对账本历史" }
        require(tx.origin == TransactionOrigin.USER_ENTERED && tx.authority == DataAuthority.APP_RULE_CALCULATION &&
            !tx.isManualCorrected && !tx.applicationTime.isAfter(tx.createdAt) && !tx.createdAt.isAfter(tx.updatedAt) && !tx.updatedAt.isAfter(now))
        val amount = requireNotNull(tx.requestedAmount)
        require(amount.signum() > 0 && precision.roundMoney(amount).compareTo(amount) == 0)
        require(tx.actualCashFlow == null && tx.cashFlowDate == null && tx.settlementDate == null &&
            tx.grossAmount == null && tx.netAmount == null && tx.unitNav == null && tx.purchaseFee == null && tx.redemptionFee == null)
        require(tx.navDate == tx.tradeDate && tx.confirmationDate != null)
        require(history.legacyLots.none { it.remainingShares.signum() > 0 }) { "包含旧持仓，请使用旧持仓卖出核对" }
        require(history.realLots.any { it.remainingShares.signum() > 0 }) { "没有可卖出的真实持仓" }
        require(history.transactions.none { it.type == TransactionType.REDEMPTION &&
            (it.status == TransactionStatus.ABNORMAL || it.status in setOf(TransactionStatus.CALCULATED, TransactionStatus.CONFIRMED_ACTUAL) &&
                !it.tradeDate.isBefore(tx.tradeDate)) }) { "存在异常或可能受影响的历史卖出，请先核对" }
        if (expected == null) {
            require(tx.status == TransactionStatus.PLANNED && tx.shares == null && tx.updatedAt == tx.createdAt && shareAudit == null)
            require(history.transactions.none { it.id == tx.id })
            val knownCalendar = requireNotNull(calendar).calendar()
            val rule = RuleVersionResolver.resolve(fund.id, tx.applicationTime.atZone(zone).toLocalDate(), rules)
            require(!rule.fetchedAt.isAfter(tx.createdAt))
            val dates = TransactionDateResolver.resolve(tx.applicationTime, zone, tx.type, rule, knownCalendar)
            require(tx.tradeDate == dates.tradeDate && tx.navDate == dates.navDate && tx.confirmationDate == dates.confirmationDate && tx.ruleVersionId == dates.ruleVersionId)
        } else {
            require(calendar == null && history.transactions.singleOrNull { it.id == expected.id } == expected)
            require(expected.status == TransactionStatus.PLANNED && expected.shares == null && !tx.tradeDate.isAfter(now.atZone(zone).toLocalDate()))
            val rule = rules.singleOrNull { it.id == tx.ruleVersionId && it.fundId == fund.id } ?: error("缺少原申请规则")
            require(rule.isEffectiveOn(tx.tradeDate) && !rule.fetchedAt.isAfter(expected.createdAt))
            val shares = requireNotNull(tx.shares)
            require(shares.signum() > 0 && precision.roundShares(shares).compareTo(shares) == 0)
            require(tx == expected.copy(status = TransactionStatus.PENDING_CALC, shares = shares, updatedAt = tx.updatedAt))
            val pending = history.transactions.filter { it.type == TransactionType.REDEMPTION && it.status == TransactionStatus.PENDING_CALC }
            require(pending.all { it.shares?.signum() == 1 }) { "存在缺少份额的待确认卖出" }
            val available = history.realLots.filter { !it.confirmationDate.isAfter(tx.tradeDate) }
                .fold(BigDecimal.ZERO) { sum, lot -> sum.add(lot.remainingShares) }
                .subtract(pending.fold(BigDecimal.ZERO) { sum, sale -> sum.add(requireNotNull(sale.shares)) })
            require(shares.compareTo(available) <= 0) { "平台份额超过扣除待确认卖出后的可用份额" }
            require(shareAudit == TransactionCorrection(CorrectionId("platform-redemption-shares:${tx.id.value}"), tx.id,
                TransactionCorrectionField.SHARES, CorrectionValue.Missing, CorrectionValue.Decimal(shares),
                CorrectionSource.SALES_PLATFORM_FINAL, tx.updatedAt))
        }
    }

    public companion object {
        /** Stable request facts survive NAV materialization and later actual-cash confirmation. */
        public fun sameRequest(expected: LedgerTransaction, actual: LedgerTransaction): Boolean =
            actual.id == expected.id && actual.fundId == expected.fundId && actual.type == TransactionType.REDEMPTION &&
                actual.origin == expected.origin && actual.applicationTime == expected.applicationTime && actual.tradeDate == expected.tradeDate &&
                actual.navDate == expected.navDate && actual.confirmationDate == expected.confirmationDate && actual.ruleVersionId == expected.ruleVersionId &&
                actual.requestedAmount == expected.requestedAmount && actual.createdAt == expected.createdAt &&
                actual.status in setOf(TransactionStatus.PLANNED, TransactionStatus.PENDING_CALC, TransactionStatus.CALCULATED,
                    TransactionStatus.CONFIRMED_ACTUAL, TransactionStatus.ABNORMAL)
    }
}
