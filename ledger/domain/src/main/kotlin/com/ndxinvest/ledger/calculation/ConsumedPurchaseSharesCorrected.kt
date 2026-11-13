package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.time.Instant

/** Append-only share revision. Original openings, sale quantities and cash are never rewritten. */
public data class ConsumedPurchaseSharesCorrected public constructor(
    public override val id: LedgerEntryId,
    public override val sequence: Long,
    public val expectedLot: Lot,
    public val correctedLot: Lot,
    public val allocations: List<RedemptionCostRevision>,
    public val precision: FundPrecision,
    public val recordedAt: Instant,
) : LedgerEntry {
    public override val fundId: FundId get() = expectedLot.fundId
    init {
        require(sequence > 0 && allocations.isNotEmpty())
        require(listOf(precision.moneyScale, precision.shareScale, precision.navScale).all { it in 0..12 })
        require(expectedLot.type == LotType.REAL && expectedLot.remainingShares < expectedLot.originalShares)
        require(correctedLot == expectedLot.copy(originalShares = correctedLot.originalShares,
            remainingShares = correctedLot.remainingShares, costBasis = correctedLot.costBasis)) {
            "Historical share correction cannot change cash, fees, net, NAV, dates or identity" }
        require(expectedLot.originalShares.compareTo(correctedLot.originalShares) != 0)
        listOf(expectedLot, correctedLot).forEach { lot ->
            require(lot.grossCashOut.signum() > 0 && lot.netSubscriptionAmount.signum() > 0 && lot.purchaseFee.signum() >= 0 &&
                lot.netSubscriptionAmount.add(lot.purchaseFee).compareTo(lot.grossCashOut) == 0)
            require(listOf(lot.grossCashOut, lot.netSubscriptionAmount, lot.purchaseFee, lot.costBasis).all { it.exact(precision.moneyScale) } &&
                listOf(lot.originalShares, lot.remainingShares).all { it.exact(precision.shareScale) } && lot.unitNav.exact(precision.navScale))
        }
        require(allocations.map { it.entryId }.distinct().size == allocations.size &&
            allocations.map { it.transactionId }.distinct().size == allocations.size)
        require(allocations.all { it.expected.lotId == expectedLot.id &&
            it.corrected == it.expected.copy(allocatedCostBasis = it.corrected.allocatedCostBasis) &&
            it.expected.shares.exact(precision.shareScale) && it.expected.allocatedCostBasis.exact(precision.moneyScale) &&
            it.corrected.allocatedCostBasis.exact(precision.moneyScale) })
    }

    internal fun applyTo(state: RebuiltLedger): RebuiltLedger {
        require(state.realLots.singleOrNull { it.id == expectedLot.id } == expectedLot) { "Historical share correction source changed" }
        val events = state.recordedRedemptions.filter { it.consumptions.any { consumption -> consumption.lotId == expectedLot.id } }
        require(events.map { it.id } == allocations.map { it.entryId }) { "Every affected sale must be reviewed in sequence" }
        var oldShares = expectedLot.originalShares; var newShares = correctedLot.originalShares
        var oldCost = expectedLot.grossCashOut; var newCost = correctedLot.grossCashOut
        val effective = state.effectiveConsumptions.toMutableMap()
        fun allocate(cost: BigDecimal, available: BigDecimal, sold: BigDecimal): BigDecimal =
            if (sold.compareTo(available) == 0) precision.roundMoney(cost) else precision.roundMoney(
                cost.multiply(sold, FundPrecision.CALCULATION_CONTEXT).divide(available, FundPrecision.CALCULATION_CONTEXT))
        events.zip(allocations).forEach { (event, revision) ->
            val current = effective.getValue(event.id)
            require(event.consumptions.size == 1 && current.size == 1) { "Multi-batch share remapping is not supported" }
            val selected = current.single()
            require(event.fundId == fundId && event.sourceTransactionId == revision.transactionId && selected == revision.expected)
            require(selected.shares <= oldShares && selected.shares <= newShares &&
                selected.allocatedCostBasis.compareTo(allocate(oldCost, oldShares, selected.shares)) == 0 &&
                revision.corrected.allocatedCostBasis.compareTo(allocate(newCost, newShares, selected.shares)) == 0) {
                "Share revision must retain sold quantities and sequential proportional cost" }
            oldShares = precision.roundShares(oldShares.subtract(selected.shares)); newShares = precision.roundShares(newShares.subtract(selected.shares))
            oldCost = precision.roundMoney(oldCost.subtract(selected.allocatedCostBasis)); newCost = precision.roundMoney(newCost.subtract(revision.corrected.allocatedCostBasis))
            effective[event.id] = listOf(revision.corrected)
        }
        require(oldShares.compareTo(expectedLot.remainingShares) == 0 && newShares.compareTo(correctedLot.remainingShares) == 0 &&
            oldCost.compareTo(expectedLot.costBasis) == 0 && newCost.compareTo(correctedLot.costBasis) == 0) {
            "Historical share correction must conserve sold/residual shares and gross cost" }
        return state.copy(realLots = state.realLots.map { if (it.id == expectedLot.id) correctedLot else it }, effectiveConsumptions = effective)
    }
    private fun BigDecimal.exact(scale: Int): Boolean = precision() <= 100 && this.scale() in 0..12 && stripTrailingZeros().scale() <= scale
}
