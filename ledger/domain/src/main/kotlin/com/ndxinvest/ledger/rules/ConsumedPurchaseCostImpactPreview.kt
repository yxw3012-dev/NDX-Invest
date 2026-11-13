package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.calculation.*
import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Explicit platform facts. This restricted preview keeps net, NAV, shares and confirmation unchanged. */
public data class ConsumedPurchaseCostFacts public constructor(
    public val actualCashPaid: BigDecimal,
    public val netAmount: BigDecimal,
    public val purchaseFee: BigDecimal,
    public val shares: BigDecimal,
    public val unitNav: BigDecimal,
    public val paymentDate: LocalDate,
    public val confirmationDate: LocalDate,
)

public data class ConsumedPurchaseSaleCostImpact public constructor(
    public val sale: LedgerTransaction,
    public val event: LotsRedeemed,
    public val originalConsumption: LotConsumption,
    public val proposedConsumption: LotConsumption,
    public val originalSaleCost: BigDecimal,
    public val proposedSaleCost: BigDecimal,
    /** Null unless the sale has final, dated actual cash; estimated cash is never treated as income. */
    public val originalRecordedIncome: BigDecimal?,
    public val proposedRecordedIncome: BigDecimal?,
)

/** Read-only impact analysis, NOT a reviewed write bundle. No transaction/event/audit is generated. */
public data class ConsumedPurchaseCostImpactPreview public constructor(
    public val fund: Fund,
    public val history: LedgerHistorySnapshot,
    public val originalPurchase: LedgerTransaction,
    public val facts: ConsumedPurchaseCostFacts,
    public val originalLot: Lot,
    public val proposedLot: Lot,
    public val sales: List<ConsumedPurchaseSaleCostImpact>,
    public val preparedAt: Instant,
) {
    public fun isBasedOn(currentFund: Fund, currentHistory: LedgerHistorySnapshot): Boolean = fund == currentFund && history == currentHistory

    public fun validate(now: Instant, today: LocalDate) {
        require(!preparedAt.isAfter(now)) { "预览时间不能来自未来" }
        require(this == prepare(fund, history, originalPurchase, facts, preparedAt, today)) { "历史成本影响预览被修改或缺少受影响交易" }
    }

    public companion object {
        public fun prepare(fund: Fund, history: LedgerHistorySnapshot, originalPurchase: LedgerTransaction,
            facts: ConsumedPurchaseCostFacts, preparedAt: Instant, today: LocalDate): ConsumedPurchaseCostImpactPreview {
            require(fund.id == history.fundId && originalPurchase.fundId == fund.id &&
                history.transactions.singleOrNull { it.id == originalPurchase.id } == originalPurchase) { "原买入不属于本基金或账本已变化" }
            val precision = requireNotNull(fund.precision) { "请先确认基金精度" }
            val inspection = LedgerHistoryInspector.inspect(history)
            require(inspection is LedgerHistoryInspection.Consistent) { "账本历史不一致，不能推算历史成本影响" }
            require(originalPurchase.type in setOf(TransactionType.PURCHASE, TransactionType.RECURRING_PURCHASE) &&
                originalPurchase.origin != TransactionOrigin.IMPORTED &&
                (originalPurchase.status == TransactionStatus.CALCULATED && originalPurchase.authority == DataAuthority.APP_RULE_CALCULATION &&
                    originalPurchase.actualCashFlow == null && originalPurchase.cashFlowDate == null && originalPurchase.settlementDate == null ||
                 originalPurchase.status == TransactionStatus.CONFIRMED_ACTUAL && originalPurchase.authority == DataAuthority.USER_OR_PLATFORM_FINAL)) {
                "只预览已有真实买入或定投的成本差异，不补旧持仓或再投资历史" }
            require(history.transactions.none { it.type == TransactionType.REDEMPTION &&
                it.status in setOf(TransactionStatus.PLANNED, TransactionStatus.PENDING_CALC, TransactionStatus.ABNORMAL) }) {
                "尚有待处理卖出，请先核对，不能预览过期的历史分摊" }
            val lot = requireNotNull(history.realLots.singleOrNull { it.sourceTransactionId == originalPurchase.id }) { "缺少唯一关联买入持仓" }
            require(lot.type == LotType.REAL && lot.remainingShares.compareTo(lot.originalShares) < 0) { "仅用于已经卖出过的买入批次" }
            fun money(value: BigDecimal): Boolean = value.precision() <= 100 && value.scale() in 0..12 &&
                value.stripTrailingZeros().scale() <= precision.moneyScale
            fun shares(value: BigDecimal): Boolean = value.precision() <= 100 && value.scale() in 0..12 &&
                value.stripTrailingZeros().scale() <= precision.shareScale
            require(listOf(lot.grossCashOut, lot.netSubscriptionAmount, lot.purchaseFee, lot.costBasis,
                facts.actualCashPaid, facts.netAmount, facts.purchaseFee).all(::money) &&
                listOf(lot.originalShares, lot.remainingShares, facts.shares).all(::shares) &&
                lot.grossCashOut.signum() > 0 && lot.netSubscriptionAmount.signum() > 0 &&
                lot.netSubscriptionAmount.add(lot.purchaseFee).compareTo(lot.grossCashOut) == 0 &&
                facts.actualCashPaid.signum() > 0 && facts.netAmount.signum() > 0 && facts.purchaseFee.signum() >= 0 &&
                facts.netAmount.add(facts.purchaseFee).compareTo(facts.actualCashPaid) == 0) {
                "实际扣款、净金额和明确申购费须精度正确且完整相加，不从差额猜手续费" }
            require(facts.shares.compareTo(lot.originalShares) == 0 && facts.netAmount.compareTo(lot.netSubscriptionAmount) == 0 &&
                facts.unitNav.compareTo(lot.unitNav) == 0 && facts.unitNav.precision() <= 100 && facts.unitNav.scale() in 0..12 &&
                facts.unitNav.stripTrailingZeros().scale() <= precision.navScale && facts.confirmationDate == lot.confirmationDate) {
                "本预览不改变份额、净金额、净值或确认日；这些差异需要另行核对历史分配" }
            require(facts.actualCashPaid.compareTo(lot.grossCashOut) != 0) { "买入成本未变化，无需历史成本预览" }
            require(originalPurchase.navDate != null && !facts.confirmationDate.isBefore(originalPurchase.navDate) &&
                !facts.confirmationDate.isAfter(today) && !facts.paymentDate.isAfter(facts.confirmationDate) &&
                !facts.paymentDate.isAfter(today) && !preparedAt.isBefore(originalPurchase.updatedAt) &&
                !preparedAt.isBefore(originalPurchase.applicationTime)) { "请核对真实扣款、确认日期及预览时间，不能使用未来事实" }
            if (originalPurchase.status == TransactionStatus.CONFIRMED_ACTUAL) {
                val oldCash = requireNotNull(originalPurchase.actualCashFlow) { "已确认买入缺少原实际扣款，不能猜补" }
                val oldDate = requireNotNull(originalPurchase.cashFlowDate ?: originalPurchase.settlementDate) { "已确认买入缺少原扣款日期" }
                require(money(oldCash.abs()) && oldCash.compareTo(lot.grossCashOut.negate()) == 0 &&
                    !oldDate.isAfter(lot.confirmationDate) && !oldDate.isAfter(today)) { "原实际扣款与批次成本不一致，请先核对" }
            }
            val events = history.entries.filterIsInstance<LotsRedeemed>().sortedBy { it.sequence }
                .filter { event -> event.consumptions.any { it.lotId == lot.id } }
            require(events.isNotEmpty()) { "缺少明确关联的历史卖出，不按名字或日期猜测" }
            val lastDefinition = history.entries.mapNotNull { entry -> when (entry) {
                is RealLotOpened -> entry.sequence.takeIf { entry.lot.id == lot.id }
                is UnconsumedPurchaseLotCorrected -> entry.sequence.takeIf { entry.expectedLot.id == lot.id }
                is UnconsumedPurchaseAmountsCorrected -> entry.sequence.takeIf { entry.expectedLot.id == lot.id }
                is UnconsumedPurchaseNavCorrected -> entry.sequence.takeIf { entry.expectedLot.id == lot.id }
                else -> null
            } }.maxOrNull()
            require(lastDefinition != null && lastDefinition < events.first().sequence) { "批次定义或历史修改顺序需要人工核对" }
            var remainingShares = lot.originalShares
            var oldRemainingCost = lot.grossCashOut
            var newRemainingCost = precision.roundMoney(facts.actualCashPaid)
            fun allocate(cost: BigDecimal, amount: BigDecimal): BigDecimal = if (amount.compareTo(remainingShares) == 0)
                precision.roundMoney(cost) else precision.roundMoney(cost.multiply(amount, FundPrecision.CALCULATION_CONTEXT)
                    .divide(remainingShares, FundPrecision.CALCULATION_CONTEXT))
            val impacts = events.map { event ->
                val effective = inspection.rebuiltLedger.effectiveConsumptions.getValue(event.id)
                require((event.consumptions + effective).all { shares(it.shares) && money(it.allocatedCostBasis) }) { "逐笔分摊精度不正确，不能用合计抵消误差" }
                val consumption = effective.single { it.lotId == lot.id }
                require(consumption.shares.compareTo(remainingShares) <= 0 && consumption.allocatedCostBasis.compareTo(allocate(oldRemainingCost, consumption.shares)) == 0) {
                    "历史分摊不是原比例规则结果，不能覆盖未知的人工成本调整" }
                val sale = requireNotNull(history.transactions.singleOrNull { it.id == event.sourceTransactionId })
                require(!sale.tradeDate.isAfter(today) && sale.navDate?.isAfter(today) != true && sale.confirmationDate?.isAfter(today) != true &&
                    !sale.updatedAt.isAfter(preparedAt) && !sale.applicationTime.isAfter(preparedAt)) { "历史卖出包含未来事实，不能预览" }
                val verified = requireNotNull(inspection.redemptionCosts.singleOrNull { it.entryId == event.id && it.transactionId == sale.id })
                val newCost = allocate(newRemainingCost, consumption.shares)
                val totalCost = precision.roundMoney(verified.allocatedCost.subtract(consumption.allocatedCostBasis).add(newCost))
                remainingShares = precision.roundShares(remainingShares.subtract(consumption.shares))
                oldRemainingCost = precision.roundMoney(oldRemainingCost.subtract(consumption.allocatedCostBasis))
                newRemainingCost = precision.roundMoney(newRemainingCost.subtract(newCost))
                val finalCash = if (sale.status == TransactionStatus.CONFIRMED_ACTUAL && sale.authority == DataAuthority.USER_OR_PLATFORM_FINAL) sale.actualCashFlow else null
                if (finalCash != null) {
                    val cashDate = requireNotNull(sale.cashFlowDate ?: sale.settlementDate) { "原卖出缺少实际到账日期" }
                    require(finalCash.signum() >= 0 && money(finalCash) && !cashDate.isBefore(sale.tradeDate) && !cashDate.isAfter(today)) {
                        "原卖出实际到账需先核对，不使用估算收益" }
                }
                ConsumedPurchaseSaleCostImpact(sale, event, consumption, consumption.copy(allocatedCostBasis = newCost),
                    verified.allocatedCost, totalCost, finalCash?.let { precision.roundMoney(it.subtract(verified.allocatedCost)) },
                    finalCash?.let { precision.roundMoney(it.subtract(totalCost)) })
            }
            require(remainingShares.compareTo(lot.remainingShares) == 0 && oldRemainingCost.compareTo(lot.costBasis) == 0) {
                "原剩余份额或成本与历史分摊不一致，不能猜补" }
            val proposed = lot.copy(grossCashOut = precision.roundMoney(facts.actualCashPaid), purchaseFee = precision.roundMoney(facts.purchaseFee),
                costBasis = newRemainingCost)
            return ConsumedPurchaseCostImpactPreview(fund, history, originalPurchase, facts, lot, proposed, impacts, preparedAt)
        }
    }
}
