package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.*
import com.ndxinvest.ledger.rules.RuleVersionResolver
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Immutable manual-review evidence. Neither a guessed payment nor a replacement for old ledger facts. */
public data class ReviewedDividend public constructor(
    public val fund: Fund,
    public val history: LedgerHistorySnapshot,
    public val rules: List<FundRuleVersion>,
    public val nav: NavRecord?,
    public val transaction: LedgerTransaction,
    public val lot: Lot?,
    public val entry: RealLotOpened?,
) {
    public fun validate(now: Instant, today: LocalDate) {
        val precision = requireNotNull(fund.precision)
        val tx = transaction
        require(history.fundId == fund.id && tx.fundId == fund.id &&
            LedgerHistoryInspector.inspect(history) is LedgerHistoryInspection.Consistent)
        require(tx.status == TransactionStatus.CONFIRMED_ACTUAL && tx.origin == TransactionOrigin.USER_ENTERED &&
            tx.authority == DataAuthority.USER_OR_PLATFORM_FINAL && !tx.tradeDate.isAfter(today) &&
            !tx.createdAt.isAfter(now) && tx.updatedAt == tx.createdAt)
        val rule = try { RuleVersionResolver.resolve(fund.id, tx.tradeDate, rules) }
            catch (missing: IllegalStateException) { throw IllegalArgumentException("Missing effective dividend rule", missing) }
        require(rule.id == tx.ruleVersionId && !rule.fetchedAt.isAfter(tx.createdAt))
        val amount = requireNotNull(tx.grossAmount)
        require(amount.signum() > 0 && precision.roundMoney(amount).compareTo(amount) == 0)
        val shares = (history.realLots.map { it.remainingShares } + history.legacyLots.map { it.remainingShares })
            .fold(BigDecimal.ZERO, BigDecimal::add)
        when (tx.type) {
            TransactionType.CASH_DIVIDEND -> {
                require(nav == null && lot == null && entry == null && tx.navDate == null && tx.shares == null &&
                    tx.unitNav == null && tx.confirmationDate == null && tx.settlementDate == tx.tradeDate &&
                    tx.cashFlowDate == null && tx.actualCashFlow?.compareTo(amount) == 0)
                DividendProcessor.process(shares, amount, tx.tradeDate, DividendTreatment.CASH, null, precision)
            }
            TransactionType.DIVIDEND_REINVESTMENT -> {
                val witness = requireNotNull(nav)
                val holding = requireNotNull(lot)
                val opening = requireNotNull(entry)
                val confirmed = requireNotNull(tx.confirmationDate)
                require(history.transactions.none { old -> old.type == TransactionType.REDEMPTION &&
                    (old.status == TransactionStatus.PENDING_CALC ||
                        (old.status in setOf(TransactionStatus.CALCULATED, TransactionStatus.CONFIRMED_ACTUAL) &&
                            !old.tradeDate.isBefore(tx.tradeDate))) }) {
                    "存在待确认卖出或可能受影响的历史卖出，请先核对；不能倒插红利再投改变历史成本"
                }
                require(witness.fundId == fund.id && witness.navDate == tx.tradeDate && tx.navDate == tx.tradeDate &&
                    !witness.fetchedAt.isAfter(tx.createdAt) && !confirmed.isBefore(tx.tradeDate) && !confirmed.isAfter(today) &&
                    tx.actualCashFlow == null && tx.cashFlowDate == null && tx.settlementDate == null &&
                    tx.unitNav?.compareTo(witness.unitNav) == 0 && precision.roundNav(witness.unitNav).compareTo(witness.unitNav) == 0 &&
                    tx.netAmount?.compareTo(amount) == 0 && tx.purchaseFee?.signum() == 0)
                val calculated = DividendProcessor.process(shares, amount, confirmed, DividendTreatment.REINVESTED, witness.unitNav, precision)
                require(tx.shares?.compareTo(calculated.addedShares) == 0 && holding.type == LotType.REAL &&
                    holding.fundId == fund.id && holding.sourceTransactionId == tx.id && holding.confirmationDate == confirmed &&
                    holding.originalShares.compareTo(calculated.addedShares) == 0 && holding.remainingShares == holding.originalShares &&
                    holding.grossCashOut.signum() == 0 && holding.purchaseFee.signum() == 0 &&
                    holding.netSubscriptionAmount.compareTo(amount) == 0 && holding.costBasis.compareTo(calculated.reinvestmentCostBasis) == 0 &&
                    holding.unitNav.compareTo(witness.unitNav) == 0 && opening.lot == holding)
            }
            else -> error("Only manual dividends can use this evidence")
        }
    }
}
