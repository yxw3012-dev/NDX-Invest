package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.FundPrecision
import com.ndxinvest.ledger.model.Lot
import com.ndxinvest.ledger.model.LotAllocationPolicy
import com.ndxinvest.ledger.model.LotId
import com.ndxinvest.ledger.model.LotType
import com.ndxinvest.ledger.model.RedemptionFeeSchedule
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

public data class RedemptionLotAllocation public constructor(
    public val lotId: LotId,
    public val shares: BigDecimal,
    public val allocatedCostBasis: BigDecimal,
    public val grossRedemptionAmount: BigDecimal,
    public val redemptionFeeRate: BigDecimal,
    public val redemptionFee: BigDecimal,
    public val holdingDays: Long,
)

public data class RedemptionCalculation public constructor(
    public val allocations: List<RedemptionLotAllocation>,
    public val updatedLots: List<Lot>,
    public val allocatedCostBasis: BigDecimal,
    public val grossRedemptionAmount: BigDecimal,
    public val redemptionFee: BigDecimal,
    public val calculatedCashReceived: BigDecimal,
    public val cashReceived: BigDecimal,
    public val realizedReturn: BigDecimal,
    public val allocationPolicy: LotAllocationPolicy,
    public val usedActualCashReceived: Boolean,
)

public object RedemptionCalculator {
    public fun calculate(
        lots: List<Lot>,
        sharesToRedeem: BigDecimal,
        unitNav: BigDecimal,
        tradeDate: LocalDate,
        feeSchedule: RedemptionFeeSchedule,
        allocationPolicy: LotAllocationPolicy,
        precision: FundPrecision,
        actualCashReceived: BigDecimal? = null,
    ): RedemptionCalculation {
        require(lots.isNotEmpty()) { "At least one real lot is required for redemption" }
        require(lots.map { it.id }.distinct().size == lots.size) { "Lot identities must be unique" }
        require(lots.all { it.type == LotType.REAL }) { "Legacy Lots require LegacyLotCalculator" }
        require(lots.map { it.fundId }.distinct().size == 1) { "All lots must belong to one fund" }
        require(sharesToRedeem.signum() > 0) { "Redemption shares must be positive" }
        require(unitNav.signum() > 0) { "Redemption NAV must be positive" }
        require(actualCashReceived == null || actualCashReceived.signum() >= 0) {
            "Actual cash received must not be negative"
        }

        val shares = precision.roundShares(sharesToRedeem)
        val nav = precision.roundNav(unitNav)
        require(shares.signum() > 0) { "Rounded redemption shares must be positive" }
        require(nav.signum() > 0) { "Rounded redemption NAV must be positive" }
        val availableShares = lots.fold(BigDecimal.ZERO) { total, lot ->
            total.add(lot.remainingShares)
        }
        require(shares.compareTo(availableShares) <= 0) { "Redemption shares exceed available shares" }

        val orderedLots = when (allocationPolicy) {
            LotAllocationPolicy.FIFO,
            LotAllocationPolicy.DEFAULT_FIFO,
            -> lots.sortedWith(compareBy<Lot>({ it.confirmationDate }, { it.id.value }))
        }
        var sharesRemaining = shares
        val allocations = mutableListOf<RedemptionLotAllocation>()
        val updatedById = mutableMapOf<LotId, Lot>()

        for (lot in orderedLots) {
            if (sharesRemaining.signum() == 0) {
                break
            }
            if (lot.remainingShares.signum() == 0) {
                continue
            }
            require(!tradeDate.isBefore(lot.confirmationDate)) {
                "Trade date must not precede a consumed lot confirmation date"
            }

            val allocatedShares = if (sharesRemaining.compareTo(lot.remainingShares) >= 0) {
                lot.remainingShares
            } else {
                sharesRemaining
            }
            val allocatedCost = if (allocatedShares.compareTo(lot.remainingShares) == 0) {
                precision.roundMoney(lot.costBasis)
            } else {
                precision.roundMoney(
                    lot.costBasis.multiply(allocatedShares, FundPrecision.CALCULATION_CONTEXT)
                        .divide(lot.remainingShares, FundPrecision.CALCULATION_CONTEXT),
                )
            }
            val grossAmount = precision.roundMoney(
                allocatedShares.multiply(nav, FundPrecision.CALCULATION_CONTEXT),
            )
            val holdingDays = ChronoUnit.DAYS.between(lot.confirmationDate, tradeDate)
            val feeRate = feeSchedule.rateFor(lot.confirmationDate, tradeDate)
            val fee = precision.roundMoney(
                grossAmount.multiply(feeRate, FundPrecision.CALCULATION_CONTEXT),
            )
            val remainingLotShares = precision.roundShares(lot.remainingShares.subtract(allocatedShares))
            val remainingCost = if (remainingLotShares.signum() == 0) {
                precision.zeroMoney()
            } else {
                precision.roundMoney(lot.costBasis.subtract(allocatedCost))
            }

            allocations += RedemptionLotAllocation(
                lotId = lot.id,
                shares = precision.roundShares(allocatedShares),
                allocatedCostBasis = allocatedCost,
                grossRedemptionAmount = grossAmount,
                redemptionFeeRate = feeRate,
                redemptionFee = fee,
                holdingDays = holdingDays,
            )
            updatedById[lot.id] = lot.copy(
                remainingShares = remainingLotShares,
                costBasis = remainingCost,
            )
            sharesRemaining = precision.roundShares(sharesRemaining.subtract(allocatedShares))
        }

        check(sharesRemaining.signum() == 0) { "Redemption allocation did not consume all requested shares" }
        val updatedLots = lots.map { lot -> updatedById[lot.id] ?: lot }
        val allocatedCost = allocations.sumMoney(precision) { it.allocatedCostBasis }
        val grossAmount = allocations.sumMoney(precision) { it.grossRedemptionAmount }
        val totalFee = allocations.sumMoney(precision) { it.redemptionFee }
        val calculatedCash = precision.roundMoney(grossAmount.subtract(totalFee))
        val cash = actualCashReceived?.let(precision::roundMoney) ?: calculatedCash
        val realizedReturn = precision.roundMoney(cash.subtract(allocatedCost))

        return RedemptionCalculation(
            allocations = allocations,
            updatedLots = updatedLots,
            allocatedCostBasis = allocatedCost,
            grossRedemptionAmount = grossAmount,
            redemptionFee = totalFee,
            calculatedCashReceived = calculatedCash,
            cashReceived = cash,
            realizedReturn = realizedReturn,
            allocationPolicy = allocationPolicy,
            usedActualCashReceived = actualCashReceived != null,
        )
    }
}

private inline fun <T> Iterable<T>.sumMoney(
    precision: FundPrecision,
    selector: (T) -> BigDecimal,
): BigDecimal = precision.roundMoney(fold(BigDecimal.ZERO) { total, item -> total.add(selector(item)) })
