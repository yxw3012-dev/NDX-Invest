package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.time.Instant

/** Explicit revision of one selected Lot allocation; the original redemption event is never edited. */
public data class RedemptionCostRevision public constructor(
    public val entryId: LedgerEntryId,
    public val transactionId: TransactionId,
    public val expected: LotConsumption,
    public val corrected: LotConsumption,
)

/** Append-only platform-final cost correction. Saved precision is a witness, not a later rule lookup. */
public data class ConsumedPurchaseCostCorrected public constructor(
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
        require(correctedLot == expectedLot.copy(grossCashOut = correctedLot.grossCashOut,
            purchaseFee = correctedLot.purchaseFee, costBasis = correctedLot.costBasis)) {
            "Historical cost correction cannot change shares, net, NAV, dates or identity" }
        require(expectedLot.grossCashOut.compareTo(correctedLot.grossCashOut) != 0)
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
        require(state.realLots.singleOrNull { it.id == expectedLot.id } == expectedLot) { "Historical correction source Lot changed" }
        val events = state.recordedRedemptions.filter { it.consumptions.any { consumption -> consumption.lotId == expectedLot.id } }
        require(events.map { it.id } == allocations.map { it.entryId }) { "Every affected earlier sale must be reviewed in sequence" }
        var shares = expectedLot.originalShares
        var oldCost = expectedLot.grossCashOut
        var newCost = correctedLot.grossCashOut
        val effective = state.effectiveConsumptions.toMutableMap()
        events.zip(allocations).forEach { (event, revision) ->
            val current = effective.getValue(event.id)
            val selected = current.single { it.lotId == expectedLot.id }
            require(event.fundId == fundId && event.sourceTransactionId == revision.transactionId && selected == revision.expected)
            fun allocate(cost: BigDecimal): BigDecimal = if (selected.shares.compareTo(shares) == 0) precision.roundMoney(cost)
                else precision.roundMoney(cost.multiply(selected.shares, FundPrecision.CALCULATION_CONTEXT).divide(shares, FundPrecision.CALCULATION_CONTEXT))
            require(selected.shares <= shares && selected.allocatedCostBasis.compareTo(allocate(oldCost)) == 0 &&
                revision.corrected.allocatedCostBasis.compareTo(allocate(newCost)) == 0) { "Cost revision must retain sequential proportional allocation" }
            shares = precision.roundShares(shares.subtract(selected.shares))
            oldCost = precision.roundMoney(oldCost.subtract(selected.allocatedCostBasis))
            newCost = precision.roundMoney(newCost.subtract(revision.corrected.allocatedCostBasis))
            effective[event.id] = current.map { if (it.lotId == selected.lotId) revision.corrected else it }
        }
        require(shares.compareTo(expectedLot.remainingShares) == 0 && oldCost.compareTo(expectedLot.costBasis) == 0 &&
            newCost.compareTo(correctedLot.costBasis) == 0) { "Historical correction must conserve consumed and remaining cost" }
        return state.copy(realLots = state.realLots.map { if (it.id == expectedLot.id) correctedLot else it }, effectiveConsumptions = effective)
    }

    private fun BigDecimal.exact(scale: Int): Boolean = precision() <= 100 && this.scale() in 0..12 && stripTrailingZeros().scale() <= scale
}
