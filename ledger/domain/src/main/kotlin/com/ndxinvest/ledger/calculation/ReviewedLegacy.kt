package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.*
import com.ndxinvest.ledger.rules.RuleVersionResolver
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Explicit imported remaining facts or a final sale of one reviewed Legacy Lot; no invented historical cash. */
public data class ReviewedLegacy public constructor(
    public val fund: Fund,
    public val history: LedgerHistorySnapshot,
    public val rules: List<FundRuleVersion>,
    public val transaction: LedgerTransaction,
    public val expectedLot: LegacyLot?,
    public val updatedLot: LegacyLot,
    public val entry: LedgerEntry,
) {
    public fun validate(now: Instant, zone: ZoneId) {
        val tx = transaction
        val precision = requireNotNull(fund.precision)
        val today = now.atZone(zone).toLocalDate()
        require(history.fundId == fund.id && tx.fundId == fund.id && updatedLot.fundId == fund.id && entry.fundId == fund.id)
        require(LedgerHistoryInspector.inspect(history) is LedgerHistoryInspection.Consistent) { "旧持仓账本需先核对" }
        require(history.transactions.none { it.id == tx.id } && history.entries.none { it.id == entry.id })
        require(tx.status == TransactionStatus.CONFIRMED_ACTUAL && tx.authority == DataAuthority.USER_OR_PLATFORM_FINAL &&
            !tx.tradeDate.isAfter(today) && !tx.createdAt.isAfter(now) && tx.updatedAt == tx.createdAt &&
            !tx.applicationTime.isAfter(tx.createdAt))
        require(tx.navDate == null && tx.unitNav == null && tx.confirmationDate == null && tx.cashFlowDate == null &&
            tx.requestedAmount == null && tx.grossAmount == null && tx.purchaseFee == null && tx.redemptionFee == null && !tx.isManualCorrected)
        val rule = try { RuleVersionResolver.resolve(fund.id, tx.tradeDate, rules) }
            catch (missing: IllegalStateException) { throw IllegalArgumentException("实际日期没有可用基金规则", missing) }
        require(rule.id == tx.ruleVersionId && !rule.fetchedAt.isAfter(tx.createdAt))
        require(history.transactions.none { it.type == TransactionType.REDEMPTION &&
            it.status in setOf(TransactionStatus.PLANNED, TransactionStatus.PENDING_CALC, TransactionStatus.ABNORMAL) }) {
            "存在未结束卖出，请先核对，不能同时调整旧持仓"
        }
        require(updatedLot.trackingStartDate?.isAfter(tx.tradeDate) != true)
        if (expectedLot == null) {
            require(tx.applicationTime == tx.createdAt)
            require(tx.type == TransactionType.ADJUSTMENT && tx.origin == TransactionOrigin.IMPORTED &&
                tx.actualCashFlow == null && tx.netAmount == null && tx.settlementDate == null)
            require(updatedLot.remainingShares.signum() > 0 && tx.shares == updatedLot.remainingShares &&
                precision.roundShares(updatedLot.remainingShares).compareTo(updatedLot.remainingShares) == 0 &&
                precision.roundMoney(updatedLot.costBasis).compareTo(updatedLot.costBasis) == 0)
            require(history.legacyLots.none { it.id == updatedLot.id } && history.realLots.none { it.id == updatedLot.id })
            require(entry is LegacyLotOpened && entry.lot == updatedLot)
        } else {
            require(!tx.applicationTime.atZone(zone).toLocalDate().isAfter(tx.tradeDate))
            require(tx.type == TransactionType.REDEMPTION && tx.origin == TransactionOrigin.USER_ENTERED &&
                history.legacyLots.singleOrNull { it.id == expectedLot.id } == expectedLot)
            val shares = requireNotNull(tx.shares)
            val cash = requireNotNull(tx.actualCashFlow)
            val settlement = requireNotNull(tx.settlementDate)
            require(cash.signum() >= 0 && tx.netAmount == cash && !settlement.isAfter(today) &&
                !settlement.isBefore(tx.tradeDate) && precision.roundMoney(cash).compareTo(cash) == 0 &&
                precision.roundShares(shares).compareTo(shares) == 0)
            require(history.transactions.filter { it.origin == TransactionOrigin.IMPORTED }.none { it.tradeDate.isAfter(tx.tradeDate) }) {
                "卖出日期早于已登记旧持仓快照，请先核对历史"
            }
            require(history.entries.filterIsInstance<LegacyLotRedeemed>().filter { it.consumption.lotId == expectedLot.id }
                .none { old -> history.transactions.single { it.id == old.sourceTransactionId }.tradeDate.isAfter(tx.tradeDate) }) {
                "不能倒插旧持仓卖出并改变已保存成本"
            }
            val calculated = LegacyLotCalculator.redeem(expectedLot, shares, cash, precision)
            require(updatedLot == calculated.updatedLot && entry is LegacyLotRedeemed && entry.sourceTransactionId == tx.id &&
                entry.consumption == LotConsumption(expectedLot.id, calculated.redeemedShares, calculated.allocatedCostBasis))
        }
        val projected = history.copy(transactions = history.transactions + tx,
            legacyLots = history.legacyLots.filterNot { it.id == updatedLot.id } + updatedLot,
            entries = history.entries + when (entry) {
                is LegacyLotOpened -> entry.copy(sequence = (history.entries.maxOfOrNull { it.sequence } ?: 0) + 1)
                is LegacyLotRedeemed -> entry.copy(sequence = (history.entries.maxOfOrNull { it.sequence } ?: 0) + 1)
            })
        require(LedgerHistoryInspector.inspect(projected) is LedgerHistoryInspection.Consistent) { "新记录与旧持仓历史不一致" }
    }
}
