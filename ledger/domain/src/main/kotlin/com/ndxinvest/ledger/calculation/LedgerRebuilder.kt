package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.LedgerEntryId
import com.ndxinvest.ledger.model.LegacyLot
import com.ndxinvest.ledger.model.Lot
import com.ndxinvest.ledger.model.LotId
import com.ndxinvest.ledger.model.LotType
import com.ndxinvest.ledger.model.TransactionId
import com.ndxinvest.ledger.model.NavRecord
import com.ndxinvest.ledger.model.DataAuthority
import java.math.BigDecimal

public sealed interface LedgerEntry {
    public val id: LedgerEntryId
    public val sequence: Long
    public val fundId: FundId
}

public data class RealLotOpened public constructor(
    public override val id: LedgerEntryId,
    public override val sequence: Long,
    public val lot: Lot,
) : LedgerEntry {
    public override val fundId: FundId
        get() = lot.fundId

    init {
        require(sequence > 0) { "Ledger sequence must be positive" }
        require(lot.type == LotType.REAL) { "RealLotOpened requires a real Lot" }
        require(lot.remainingShares.compareTo(lot.originalShares) == 0) {
            "A newly opened real Lot must contain all original shares"
        }
    }
}

public data class LegacyLotOpened public constructor(
    public override val id: LedgerEntryId,
    public override val sequence: Long,
    public val lot: LegacyLot,
) : LedgerEntry {
    public override val fundId: FundId
        get() = lot.fundId

    init {
        require(sequence > 0) { "Ledger sequence must be positive" }
        require(lot.remainingShares.signum() > 0) { "A newly opened Legacy Lot needs positive shares" }
    }
}

public data class LotConsumption public constructor(
    public val lotId: LotId,
    public val shares: BigDecimal,
    public val allocatedCostBasis: BigDecimal,
) {
    init {
        require(shares.signum() > 0) { "Consumed Lot shares must be positive" }
        require(allocatedCostBasis.signum() >= 0) { "Allocated Lot cost must not be negative" }
    }
}

public data class LotsRedeemed public constructor(
    public override val id: LedgerEntryId,
    public override val sequence: Long,
    public override val fundId: FundId,
    public val consumptions: List<LotConsumption>,
    /** Missing for retained old events; never inferred from an event ID. */
    public val sourceTransactionId: TransactionId? = null,
) : LedgerEntry {
    init {
        require(sequence > 0) { "Ledger sequence must be positive" }
        require(consumptions.isNotEmpty()) { "A redemption needs at least one Lot consumption" }
        require(consumptions.map { it.lotId }.distinct().size == consumptions.size) {
            "A redemption must consume each real Lot at most once"
        }
    }
}

public data class LegacyLotRedeemed public constructor(
    public override val id: LedgerEntryId,
    public override val sequence: Long,
    public override val fundId: FundId,
    public val consumption: LotConsumption,
    public val sourceTransactionId: TransactionId? = null,
) : LedgerEntry {
    init {
        require(sequence > 0) { "Ledger sequence must be positive" }
    }
}

public data class AssumedPurchaseReversed public constructor(
    public override val id: LedgerEntryId,
    public override val sequence: Long,
    public override val fundId: FundId,
    public val sourceTransactionId: TransactionId,
) : LedgerEntry {
    init {
        require(sequence > 0) { "Ledger sequence must be positive" }
    }
}

/** Appends final platform shares/date without rewriting the original opening event. */
public data class UnconsumedPurchaseLotCorrected public constructor(
    public override val id: LedgerEntryId,
    public override val sequence: Long,
    public val expectedLot: Lot,
    public val correctedLot: Lot,
) : LedgerEntry {
    public override val fundId: FundId get() = expectedLot.fundId

    init {
        require(sequence > 0) { "Ledger sequence must be positive" }
        require(expectedLot.type == LotType.REAL && correctedLot.type == LotType.REAL)
        require(expectedLot.grossCashOut.signum() > 0 && expectedLot.netSubscriptionAmount.signum() > 0 &&
            expectedLot.netSubscriptionAmount.add(expectedLot.purchaseFee).compareTo(expectedLot.grossCashOut) == 0
        ) { "Purchase correction requires complete original cash and fee facts" }
        require(expectedLot.remainingShares.compareTo(expectedLot.originalShares) == 0 &&
            expectedLot.costBasis.compareTo(expectedLot.grossCashOut) == 0
        ) { "Consumed or cost-adjusted purchase Lots need historical reconciliation" }
        require(correctedLot.remainingShares.compareTo(correctedLot.originalShares) == 0)
        require(correctedLot == expectedLot.copy(
            originalShares = correctedLot.originalShares,
            remainingShares = correctedLot.remainingShares,
            confirmationDate = correctedLot.confirmationDate,
        )) { "This correction may change only platform shares and confirmation date" }
        require(expectedLot.originalShares.compareTo(correctedLot.originalShares) != 0 ||
            expectedLot.confirmationDate != correctedLot.confirmationDate
        ) { "Lot correction must change a fact" }
    }
}

/** Explicit platform money/fee reconciliation at unchanged NAV; original opening history stays intact. */
public data class UnconsumedPurchaseAmountsCorrected public constructor(
    public override val id: LedgerEntryId,
    public override val sequence: Long,
    public val expectedLot: Lot,
    public val correctedLot: Lot,
) : LedgerEntry {
    public override val fundId: FundId get() = expectedLot.fundId

    init {
        require(sequence > 0)
        listOf(expectedLot, correctedLot).forEach { lot ->
            require(lot.type == LotType.REAL && lot.remainingShares.compareTo(lot.originalShares) == 0 &&
                lot.costBasis.compareTo(lot.grossCashOut) == 0 && lot.grossCashOut.signum() > 0 &&
                lot.netSubscriptionAmount.signum() > 0 &&
                lot.netSubscriptionAmount.add(lot.purchaseFee).compareTo(lot.grossCashOut) == 0
            ) { "Amount correction needs an unconsumed purchase with complete cash/fee facts" }
        }
        require(correctedLot == expectedLot.copy(
            confirmationDate = correctedLot.confirmationDate,
            originalShares = correctedLot.originalShares, remainingShares = correctedLot.remainingShares,
            grossCashOut = correctedLot.grossCashOut, netSubscriptionAmount = correctedLot.netSubscriptionAmount,
            purchaseFee = correctedLot.purchaseFee, costBasis = correctedLot.costBasis,
        )) { "Amount correction must retain Lot identity, fund, source and original NAV" }
        require(expectedLot.grossCashOut.compareTo(correctedLot.grossCashOut) != 0 ||
            expectedLot.netSubscriptionAmount.compareTo(correctedLot.netSubscriptionAmount) != 0 ||
            expectedLot.purchaseFee.compareTo(correctedLot.purchaseFee) != 0
        ) { "Amount correction needs a monetary difference" }
    }
}

/** Preserves the exact official NAV witness used for an explicitly confirmed correction. */
public data class UnconsumedPurchaseNavCorrected public constructor(
    public override val id: LedgerEntryId,
    public override val sequence: Long,
    public val expectedLot: Lot,
    public val correctedLot: Lot,
    public val officialNav: NavRecord,
) : LedgerEntry {
    public override val fundId: FundId get() = expectedLot.fundId
    init {
        require(sequence > 0)
        listOf(expectedLot, correctedLot).forEach { lot ->
            require(lot.type == LotType.REAL && lot.remainingShares.compareTo(lot.originalShares) == 0 &&
                lot.grossCashOut.signum() > 0 && lot.netSubscriptionAmount.signum() > 0 &&
                lot.costBasis.compareTo(lot.grossCashOut) == 0 &&
                lot.netSubscriptionAmount.add(lot.purchaseFee).compareTo(lot.grossCashOut) == 0
            ) { "NAV correction requires complete unconsumed purchase facts" }
        }
        require(correctedLot == expectedLot.copy(
            confirmationDate = correctedLot.confirmationDate, unitNav = correctedLot.unitNav,
            originalShares = correctedLot.originalShares, remainingShares = correctedLot.remainingShares,
            grossCashOut = correctedLot.grossCashOut, netSubscriptionAmount = correctedLot.netSubscriptionAmount,
            purchaseFee = correctedLot.purchaseFee, costBasis = correctedLot.costBasis,
        )) { "NAV correction must preserve Lot identity, source and fund" }
        require(officialNav.authority == DataAuthority.FUND_OFFICIAL && officialNav.fundId == fundId &&
            officialNav.unitNav.compareTo(correctedLot.unitNav) == 0 &&
            expectedLot.unitNav.compareTo(correctedLot.unitNav) != 0 && !officialNav.navDate.isAfter(correctedLot.confirmationDate)
        ) { "NAV correction needs a different, matching official NAV witness" }
    }
}

public data class RebuiltLedger public constructor(
    public val realLots: List<Lot>,
    public val legacyLots: List<LegacyLot>,
    public val appliedEntryIds: Set<LedgerEntryId>,
    public val lastSequence: Long,
    /** Original immutable events and their current effective allocation; corrections never edit these events. */
    public val recordedRedemptions: List<LotsRedeemed> = emptyList(),
    public val effectiveConsumptions: Map<LedgerEntryId, List<LotConsumption>> = emptyMap(),
) {
    public val totalShares: BigDecimal
        get() = realLots.fold(BigDecimal.ZERO) { total, lot -> total.add(lot.remainingShares) }
            .add(legacyLots.fold(BigDecimal.ZERO) { total, lot -> total.add(lot.remainingShares) })

    public val totalRemainingCostBasis: BigDecimal
        get() = realLots.fold(BigDecimal.ZERO) { total, lot -> total.add(lot.costBasis) }
            .add(legacyLots.fold(BigDecimal.ZERO) { total, lot -> total.add(lot.costBasis) })

    public companion object {
        public fun empty(): RebuiltLedger = RebuiltLedger(
            realLots = emptyList(),
            legacyLots = emptyList(),
            appliedEntryIds = emptySet(),
            lastSequence = 0,
        )
    }
}

public object LedgerRebuilder {
    public fun rebuild(entries: List<LedgerEntry>): RebuiltLedger {
        require(entries.map { it.id }.distinct().size == entries.size) { "Ledger entry identities must be unique" }
        require(entries.map { it.sequence }.distinct().size == entries.size) { "Ledger sequences must be unique" }
        return entries.sortedBy { it.sequence }.fold(RebuiltLedger.empty()) { state, entry ->
            apply(state, entry)
        }
    }

    public fun apply(state: RebuiltLedger, entry: LedgerEntry): RebuiltLedger {
        require(entry.id !in state.appliedEntryIds) { "Ledger entry was already applied: ${entry.id.value}" }
        require(entry.sequence > state.lastSequence) { "Ledger entries must be applied in sequence order" }
        val updated = when (entry) {
            is RealLotOpened -> applyRealLotOpened(state, entry)
            is LegacyLotOpened -> applyLegacyLotOpened(state, entry)
            is LotsRedeemed -> applyRealRedemption(state, entry)
            is LegacyLotRedeemed -> applyLegacyRedemption(state, entry)
            is AssumedPurchaseReversed -> applyAssumedPurchaseReversal(state, entry)
            is UnconsumedPurchaseLotCorrected -> {
                require(state.realLots.singleOrNull { it.id == entry.expectedLot.id } == entry.expectedLot) {
                    "Purchase correction requires the unchanged, unconsumed source Lot"
                }
                state.copy(realLots = state.realLots.map {
                    if (it.id == entry.expectedLot.id) entry.correctedLot else it
                })
            }
            is UnconsumedPurchaseAmountsCorrected -> {
                require(state.realLots.singleOrNull { it.id == entry.expectedLot.id } == entry.expectedLot) {
                    "Amount correction requires the unchanged source Lot"
                }
                state.copy(realLots = state.realLots.map {
                    if (it.id == entry.expectedLot.id) entry.correctedLot else it
                })
            }
            is UnconsumedPurchaseNavCorrected -> {
                require(state.realLots.singleOrNull { it.id == entry.expectedLot.id } == entry.expectedLot) {
                    "NAV correction requires the unchanged source Lot"
                }
                state.copy(realLots = state.realLots.map {
                    if (it.id == entry.expectedLot.id) entry.correctedLot else it
                })
            }
            is ConsumedPurchaseCostCorrected -> entry.applyTo(state)
            is ConsumedPurchaseSharesCorrected -> entry.applyTo(state)
        }
        return updated.copy(
            appliedEntryIds = updated.appliedEntryIds + entry.id,
            lastSequence = entry.sequence,
        )
    }

    private fun applyRealLotOpened(state: RebuiltLedger, entry: RealLotOpened): RebuiltLedger {
        require(state.findFundId(entry.lot.id) == null) { "Lot identity already exists: ${entry.lot.id.value}" }
        return state.copy(realLots = state.realLots + entry.lot)
    }

    private fun applyLegacyLotOpened(state: RebuiltLedger, entry: LegacyLotOpened): RebuiltLedger {
        require(state.findFundId(entry.lot.id) == null) { "Lot identity already exists: ${entry.lot.id.value}" }
        return state.copy(legacyLots = state.legacyLots + entry.lot)
    }

    private fun applyRealRedemption(state: RebuiltLedger, entry: LotsRedeemed): RebuiltLedger {
        val consumptionById = entry.consumptions.associateBy { it.lotId }
        consumptionById.forEach { (lotId, consumption) ->
            val lot = state.realLots.singleOrNull { it.id == lotId }
                ?: error("Redemption references an unknown real Lot: ${lotId.value}")
            require(lot.fundId == entry.fundId) { "Redemption fund does not match consumed real Lot" }
            validateConsumption(lot.remainingShares, lot.costBasis, consumption)
        }
        return state.copy(
            recordedRedemptions = state.recordedRedemptions + entry,
            effectiveConsumptions = state.effectiveConsumptions + (entry.id to entry.consumptions),
            realLots = state.realLots.map { lot ->
                consumptionById[lot.id]?.let { consumption ->
                    val remainingShares = lot.remainingShares.subtract(consumption.shares)
                    val remainingCost = lot.costBasis.subtract(consumption.allocatedCostBasis)
                    lot.copy(
                        remainingShares = remainingShares,
                        costBasis = if (remainingShares.signum() == 0) BigDecimal.ZERO.setScale(lot.costBasis.scale()) else remainingCost,
                    )
                } ?: lot
            },
        )
    }

    private fun applyLegacyRedemption(state: RebuiltLedger, entry: LegacyLotRedeemed): RebuiltLedger {
        val lot = state.legacyLots.singleOrNull { it.id == entry.consumption.lotId }
            ?: error("Redemption references an unknown Legacy Lot: ${entry.consumption.lotId.value}")
        require(lot.fundId == entry.fundId) { "Redemption fund does not match consumed Legacy Lot" }
        validateConsumption(lot.remainingShares, lot.costBasis, entry.consumption)
        return state.copy(
            legacyLots = state.legacyLots.map { existing ->
                if (existing.id != lot.id) {
                    existing
                } else {
                    val remainingShares = existing.remainingShares.subtract(entry.consumption.shares)
                    val remainingCost = existing.costBasis.subtract(entry.consumption.allocatedCostBasis)
                    existing.copy(
                        remainingShares = remainingShares,
                        costBasis = if (remainingShares.signum() == 0) BigDecimal.ZERO.setScale(existing.costBasis.scale()) else remainingCost,
                    )
                }
            },
        )
    }

    private fun applyAssumedPurchaseReversal(
        state: RebuiltLedger,
        entry: AssumedPurchaseReversed,
    ): RebuiltLedger {
        val lot = state.realLots.singleOrNull { it.sourceTransactionId == entry.sourceTransactionId }
            ?: error("Assumed purchase reversal references an unknown transaction: ${entry.sourceTransactionId.value}")
        require(lot.fundId == entry.fundId) { "Assumed purchase reversal fund does not match its Lot" }
        require(lot.remainingShares.compareTo(lot.originalShares) == 0) {
            "An assumed purchase cannot be reversed after its Lot has been consumed"
        }
        require(lot.costBasis.compareTo(lot.grossCashOut) == 0) {
            "An assumed purchase cannot be reversed after its Lot cost has changed"
        }
        return state.copy(realLots = state.realLots.filterNot { it.id == lot.id })
    }

    private fun validateConsumption(
        availableShares: BigDecimal,
        availableCost: BigDecimal,
        consumption: LotConsumption,
    ) {
        require(consumption.shares.compareTo(availableShares) <= 0) { "Lot consumption exceeds available shares" }
        require(consumption.allocatedCostBasis.compareTo(availableCost) <= 0) { "Lot consumption exceeds available cost" }
        require(
            consumption.shares.compareTo(availableShares) != 0 ||
                consumption.allocatedCostBasis.compareTo(availableCost) == 0,
        ) { "A fully consumed Lot must allocate all remaining cost" }
    }

    private fun RebuiltLedger.findFundId(lotId: LotId): FundId? =
        realLots.firstOrNull { it.id == lotId }?.fundId
            ?: legacyLots.firstOrNull { it.id == lotId }?.fundId
}
