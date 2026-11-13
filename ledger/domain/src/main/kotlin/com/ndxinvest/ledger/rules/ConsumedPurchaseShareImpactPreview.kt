package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.calculation.*
import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Explicit platform facts; only original purchase shares differ in this restricted preview. */
public data class ConsumedPurchaseShareFacts public constructor(
    public val actualCashPaid: BigDecimal, public val netAmount: BigDecimal, public val purchaseFee: BigDecimal,
    public val shares: BigDecimal, public val unitNav: BigDecimal,
    public val paymentDate: LocalDate, public val confirmationDate: LocalDate,
)

/** Read-only evidence, not a write bundle, event or replacement ledger history. */
public data class ConsumedPurchaseShareImpactPreview public constructor(
    public val fund: Fund, public val history: LedgerHistorySnapshot, public val originalPurchase: LedgerTransaction,
    public val facts: ConsumedPurchaseShareFacts, public val originalLot: Lot, public val proposedLot: Lot,
    public val sales: List<ConsumedPurchaseSaleCostImpact>, public val preparedAt: Instant,
) {
    public fun isBasedOn(currentFund: Fund, currentHistory: LedgerHistorySnapshot): Boolean = fund == currentFund && history == currentHistory
    public fun validate(now: Instant, today: LocalDate) {
        require(!preparedAt.isAfter(now)) { "预览时间不能来自未来" }
        require(this == prepare(fund, history, originalPurchase, facts, preparedAt, today)) { "历史份额影响预览被修改或缺少受影响卖出" }
    }
    public companion object {
        public fun prepare(fund: Fund, history: LedgerHistorySnapshot, originalPurchase: LedgerTransaction,
            facts: ConsumedPurchaseShareFacts, preparedAt: Instant, today: LocalDate): ConsumedPurchaseShareImpactPreview {
            require(fund.id == history.fundId && originalPurchase.fundId == fund.id &&
                history.transactions.singleOrNull { it.id == originalPurchase.id } == originalPurchase) { "原买入不属于本基金或账本已变化" }
            val precision = requireNotNull(fund.precision) { "请先确认基金精度" }
            val inspection = LedgerHistoryInspector.inspect(history)
            require(inspection is LedgerHistoryInspection.Consistent) { "账本历史不一致，不能推算份额影响" }
            require(originalPurchase.type in setOf(TransactionType.PURCHASE, TransactionType.RECURRING_PURCHASE) &&
                originalPurchase.origin != TransactionOrigin.IMPORTED &&
                (originalPurchase.status == TransactionStatus.CALCULATED && originalPurchase.authority == DataAuthority.APP_RULE_CALCULATION &&
                    originalPurchase.actualCashFlow == null && originalPurchase.cashFlowDate == null && originalPurchase.settlementDate == null ||
                 originalPurchase.status == TransactionStatus.CONFIRMED_ACTUAL && originalPurchase.authority == DataAuthority.USER_OR_PLATFORM_FINAL)) {
                "仅预览真实买入或定投的份额差异，不补旧持仓或再投资历史" }
            require(history.transactions.none { it.type == TransactionType.REDEMPTION &&
                it.status in setOf(TransactionStatus.PLANNED, TransactionStatus.PENDING_CALC, TransactionStatus.ABNORMAL) }) {
                "尚有待处理卖出，请先核对，不能预览过期的分配" }
            val lot = requireNotNull(history.realLots.singleOrNull { it.sourceTransactionId == originalPurchase.id }) { "缺少唯一关联买入批次" }
            require(lot.type == LotType.REAL && lot.remainingShares < lot.originalShares) { "仅用于已经卖出过的买入批次" }
            fun exact(value: BigDecimal, scale: Int): Boolean = value.precision() <= 100 && value.scale() in 0..12 && value.stripTrailingZeros().scale() <= scale
            fun money(value: BigDecimal): Boolean = exact(value, precision.moneyScale)
            fun shares(value: BigDecimal): Boolean = exact(value, precision.shareScale)
            require(listOf(lot.grossCashOut, lot.netSubscriptionAmount, lot.purchaseFee, lot.costBasis,
                facts.actualCashPaid, facts.netAmount, facts.purchaseFee).all(::money) &&
                history.realLots.all { shares(it.originalShares) && shares(it.remainingShares) } && shares(facts.shares) &&
                exact(facts.unitNav, precision.navScale) && facts.shares.signum() > 0 &&
                lot.grossCashOut.signum() > 0 && lot.netSubscriptionAmount.signum() > 0 &&
                lot.netSubscriptionAmount.add(lot.purchaseFee).compareTo(lot.grossCashOut) == 0) { "金额、费用、份额和净值须完整且符合基金精度" }
            require(facts.actualCashPaid.compareTo(lot.grossCashOut) == 0 && facts.netAmount.compareTo(lot.netSubscriptionAmount) == 0 &&
                facts.purchaseFee.compareTo(lot.purchaseFee) == 0 && facts.unitNav.compareTo(lot.unitNav) == 0 && facts.confirmationDate == lot.confirmationDate) {
                "此预览仅改变买入份额，不改变扣款、费用、净金额、净值或确认日" }
            require(facts.shares.compareTo(lot.originalShares) != 0) { "买入份额未变化，无需历史份额预览" }
            require(originalPurchase.navDate != null && !facts.confirmationDate.isBefore(originalPurchase.navDate) &&
                !facts.confirmationDate.isBefore(originalPurchase.tradeDate) && !facts.confirmationDate.isAfter(today) &&
                !facts.paymentDate.isAfter(facts.confirmationDate) && !facts.paymentDate.isAfter(today) &&
                !preparedAt.isBefore(originalPurchase.updatedAt) && !preparedAt.isBefore(originalPurchase.applicationTime)) { "请核对实际日期及时间，不能使用未来事实" }
            if (originalPurchase.status == TransactionStatus.CONFIRMED_ACTUAL) {
                val cash = requireNotNull(originalPurchase.actualCashFlow) { "已确认买入缺少明确原扣款" }
                val date = requireNotNull(originalPurchase.cashFlowDate ?: originalPurchase.settlementDate) { "已确认买入缺少原扣款日期" }
                require(money(cash.abs()) && cash.compareTo(lot.grossCashOut.negate()) == 0 && date == facts.paymentDate) {
                    "份额预览不更正原实际扣款或日期，请先使用对应流程" }
            }
            val events = history.entries.filterIsInstance<LotsRedeemed>().sortedBy { it.sequence }.filter { entry -> entry.consumptions.any { it.lotId == lot.id } }
            require(events.isNotEmpty()) { "缺少明确关联的历史卖出，不按日期或名称猜测" }
            val lastDefinition = history.entries.mapNotNull { entry -> when (entry) {
                is RealLotOpened -> entry.sequence.takeIf { entry.lot.id == lot.id }
                is UnconsumedPurchaseLotCorrected -> entry.sequence.takeIf { entry.expectedLot.id == lot.id }
                is UnconsumedPurchaseAmountsCorrected -> entry.sequence.takeIf { entry.expectedLot.id == lot.id }
                is UnconsumedPurchaseNavCorrected -> entry.sequence.takeIf { entry.expectedLot.id == lot.id }
                else -> null
            } }.maxOrNull()
            require(lastDefinition != null && lastDefinition < events.first().sequence) { "批次定义或修改顺序需另行核对" }
            // A single eligible batch leaves no FIFO/fee remapping choice. Inspect later sales too,
            // including those made after the original batch was depleted; no rule is guessed or recomputed.
            var prefix = RebuiltLedger.empty()
            val transactions = history.transactions.associateBy { it.id }
            history.entries.sortedBy { it.sequence }.forEach { entry ->
                if (entry is LotsRedeemed && entry.sequence > lastDefinition) {
                    val sale = requireNotNull(transactions[entry.sourceTransactionId])
                    if (entry.consumptions.any { it.lotId == lot.id }) {
                        require(entry.consumptions.size == 1 && prefix.realLots.none { it.id != lot.id &&
                            it.remainingShares.signum() > 0 && !it.confirmationDate.isAfter(sale.tradeDate) }) {
                            "存在其他可用批次或多批次卖出，需要完整历史分配核对" }
                    } else require(sale.tradeDate.isBefore(lot.confirmationDate)) { "份额变化可能影响后续其他批次卖出，需要完整历史分配核对" }
                }
                prefix = LedgerRebuilder.apply(prefix, entry)
            }
            var oldShares = lot.originalShares; var newShares = precision.roundShares(facts.shares)
            var oldCost = lot.grossCashOut; var newCost = lot.grossCashOut
            fun allocate(cost: BigDecimal, available: BigDecimal, consumed: BigDecimal): BigDecimal =
                if (consumed.compareTo(available) == 0) precision.roundMoney(cost) else precision.roundMoney(
                    cost.multiply(consumed, FundPrecision.CALCULATION_CONTEXT).divide(available, FundPrecision.CALCULATION_CONTEXT))
            val impacts = events.map { event ->
                val effective = inspection.rebuiltLedger.effectiveConsumptions.getValue(event.id)
                require((event.consumptions + effective).all { shares(it.shares) && money(it.allocatedCostBasis) }) { "逐笔卖出精度需先核对" }
                val consumption = effective.single { it.lotId == lot.id }
                require(consumption.shares <= newShares) { "更正份额不足以覆盖已记录卖出，需要完整历史分配核对" }
                require(consumption.allocatedCostBasis.compareTo(allocate(oldCost, oldShares, consumption.shares)) == 0) { "原成本不是顺序比例分摊结果，不能覆盖未知人工调整" }
                val sale = requireNotNull(transactions[event.sourceTransactionId])
                require(!sale.tradeDate.isAfter(today) && sale.navDate?.isAfter(today) != true && sale.confirmationDate?.isAfter(today) != true &&
                    !sale.updatedAt.isAfter(preparedAt) && !sale.applicationTime.isAfter(preparedAt)) { "历史卖出含未来事实，不能预览" }
                val verified = requireNotNull(inspection.redemptionCosts.singleOrNull { it.entryId == event.id && it.transactionId == sale.id })
                val revised = allocate(newCost, newShares, consumption.shares)
                oldShares = precision.roundShares(oldShares.subtract(consumption.shares)); newShares = precision.roundShares(newShares.subtract(consumption.shares))
                oldCost = precision.roundMoney(oldCost.subtract(consumption.allocatedCostBasis)); newCost = precision.roundMoney(newCost.subtract(revised))
                val finalCash = if (sale.status == TransactionStatus.CONFIRMED_ACTUAL && sale.authority == DataAuthority.USER_OR_PLATFORM_FINAL) sale.actualCashFlow else null
                if (finalCash != null) {
                    val date = requireNotNull(sale.cashFlowDate ?: sale.settlementDate) { "原卖出缺少实际到账日期" }
                    require(finalCash.signum() >= 0 && money(finalCash) && !date.isBefore(sale.tradeDate) && !date.isAfter(today)) { "原卖出实际到账需先核对" }
                }
                ConsumedPurchaseSaleCostImpact(sale, event, consumption, consumption.copy(allocatedCostBasis = revised), verified.allocatedCost, revised,
                    finalCash?.let { precision.roundMoney(it.subtract(verified.allocatedCost)) }, finalCash?.let { precision.roundMoney(it.subtract(revised)) })
            }
            require(oldShares.compareTo(lot.remainingShares) == 0 && oldCost.compareTo(lot.costBasis) == 0) { "原剩余份额或成本与历史分摊不一致" }
            val proposed = lot.copy(originalShares = precision.roundShares(facts.shares), remainingShares = newShares, costBasis = newCost)
            return ConsumedPurchaseShareImpactPreview(fund, history, originalPurchase, facts, lot, proposed, impacts, preparedAt)
        }
    }
}
