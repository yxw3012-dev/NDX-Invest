package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.FundPrecision
import com.ndxinvest.ledger.model.HoldingPeriodBoundary
import com.ndxinvest.ledger.model.HoldingPeriodUnit
import com.ndxinvest.ledger.model.Lot
import com.ndxinvest.ledger.model.LotAllocationPolicy
import com.ndxinvest.ledger.model.LotId
import com.ndxinvest.ledger.model.LotType
import com.ndxinvest.ledger.model.RedemptionFeeSchedule
import com.ndxinvest.ledger.model.RedemptionFeeTier
import com.ndxinvest.ledger.model.TransactionId
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RedemptionCalculatorTest {
    private val fundId = FundId("fund-a")
    private val precision = FundPrecision(2, 2, 4, RoundingMode.HALF_UP)

    @Test
    fun `Test B applies FIFO across lots and calculates each lot fee from its own holding period`() {
        val lots = listOf(
            lot(id = "lot-a", date = "2025-01-01", shares = "1000.00", cost = "1000.00"),
            lot(id = "lot-b", date = "2026-09-01", shares = "1000.00", cost = "2000.00"),
        )
        val fees = RedemptionFeeSchedule(
            tiers = listOf(
                RedemptionFeeTier(
                    maxHoldingPeriodExclusive = HoldingPeriodBoundary(30, HoldingPeriodUnit.DAYS),
                    rate = BigDecimal("0.01"),
                ),
                RedemptionFeeTier(maxHoldingPeriodExclusive = null, rate = BigDecimal.ZERO),
            ),
        )

        val result = RedemptionCalculator.calculate(
            lots = lots,
            sharesToRedeem = BigDecimal("1200.00"),
            unitNav = BigDecimal("3.0000"),
            tradeDate = LocalDate.parse("2026-09-11"),
            feeSchedule = fees,
            allocationPolicy = LotAllocationPolicy.FIFO,
            precision = precision,
        )

        assertEquals(listOf(BigDecimal("1000.00"), BigDecimal("200.00")), result.allocations.map { it.shares })
        assertEquals(listOf(BigDecimal("0.00"), BigDecimal("6.00")), result.allocations.map { it.redemptionFee })
        assertEquals(BigDecimal("1400.00"), result.allocatedCostBasis)
        assertEquals(BigDecimal("3600.00"), result.grossRedemptionAmount)
        assertEquals(BigDecimal("6.00"), result.redemptionFee)
        assertEquals(BigDecimal("3594.00"), result.cashReceived)
        assertEquals(BigDecimal("2194.00"), result.realizedReturn)
        assertEquals(BigDecimal("0.00"), result.updatedLots[0].remainingShares)
        assertEquals(BigDecimal("0.00"), result.updatedLots[0].costBasis)
        assertEquals(BigDecimal("800.00"), result.updatedLots[1].remainingShares)
        assertEquals(BigDecimal("1600.00"), result.updatedLots[1].costBasis)
    }

    @Test
    fun `actual platform cash received overrides the calculated cash but not lot cost allocation`() {
        val result = RedemptionCalculator.calculate(
            lots = listOf(lot(id = "lot-a", date = "2026-01-01", shares = "100.00", cost = "200.00")),
            sharesToRedeem = BigDecimal("20.00"),
            unitNav = BigDecimal("3.0000"),
            tradeDate = LocalDate.parse("2026-09-11"),
            feeSchedule = RedemptionFeeSchedule.noFee(),
            allocationPolicy = LotAllocationPolicy.FIFO,
            precision = precision,
            actualCashReceived = BigDecimal("59.50"),
        )

        assertEquals(BigDecimal("60.00"), result.calculatedCashReceived)
        assertEquals(BigDecimal("59.50"), result.cashReceived)
        assertEquals(BigDecimal("40.00"), result.allocatedCostBasis)
        assertEquals(BigDecimal("19.50"), result.realizedReturn)
    }

    @Test
    fun `missing explicit allocation rule falls back to FIFO and keeps the source marker`() {
        val result = RedemptionCalculator.calculate(
            lots = listOf(
                lot(id = "newer", date = "2026-02-01", shares = "10.00", cost = "20.00"),
                lot(id = "older", date = "2026-01-01", shares = "10.00", cost = "10.00"),
            ),
            sharesToRedeem = BigDecimal("12.00"),
            unitNav = BigDecimal.ONE,
            tradeDate = LocalDate.parse("2026-09-11"),
            feeSchedule = RedemptionFeeSchedule.noFee(),
            allocationPolicy = LotAllocationPolicy.DEFAULT_FIFO,
            precision = precision,
        )

        assertEquals(listOf(LotId("older"), LotId("newer")), result.allocations.map { it.lotId })
        assertEquals(LotAllocationPolicy.DEFAULT_FIFO, result.allocationPolicy)
    }

    @Test
    fun `rounded zero redemption and duplicate Lot identities are rejected`() {
        val oneLot = lot(id = "lot-a", date = "2026-01-01", shares = "100.00", cost = "200.00")
        assertFailsWith<IllegalArgumentException> {
            RedemptionCalculator.calculate(
                lots = listOf(oneLot),
                sharesToRedeem = BigDecimal("0.004"),
                unitNav = BigDecimal.ONE,
                tradeDate = LocalDate.parse("2026-09-11"),
                feeSchedule = RedemptionFeeSchedule.noFee(),
                allocationPolicy = LotAllocationPolicy.FIFO,
                precision = precision,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RedemptionCalculator.calculate(
                lots = listOf(oneLot, oneLot),
                sharesToRedeem = BigDecimal("1.00"),
                unitNav = BigDecimal.ONE,
                tradeDate = LocalDate.parse("2026-09-11"),
                feeSchedule = RedemptionFeeSchedule.noFee(),
                allocationPolicy = LotAllocationPolicy.FIFO,
                precision = precision,
            )
        }
    }

    @Test
    fun `redemption fee tier upper boundary is exclusive`() {
        val result = RedemptionCalculator.calculate(
            lots = listOf(lot(id = "lot-a", date = "2026-08-12", shares = "10.00", cost = "10.00")),
            sharesToRedeem = BigDecimal("1.00"),
            unitNav = BigDecimal("10.0000"),
            tradeDate = LocalDate.parse("2026-09-11"),
            feeSchedule = RedemptionFeeSchedule(
                listOf(
                    RedemptionFeeTier(HoldingPeriodBoundary(30, HoldingPeriodUnit.DAYS), BigDecimal("0.01")),
                    RedemptionFeeTier(null, BigDecimal.ZERO),
                ),
            ),
            allocationPolicy = LotAllocationPolicy.FIFO,
            precision = precision,
        )

        assertEquals(30, result.allocations.single().holdingDays)
        assertEquals(BigDecimal("0.00"), result.redemptionFee)
    }

    @Test
    fun `calendar year fee boundaries are not flattened into fixed day counts`() {
        val fees = RedemptionFeeSchedule(
            listOf(
                RedemptionFeeTier(
                    maxHoldingPeriodExclusive = HoldingPeriodBoundary(7, HoldingPeriodUnit.DAYS),
                    rate = BigDecimal("0.015"),
                ),
                RedemptionFeeTier(
                    maxHoldingPeriodExclusive = HoldingPeriodBoundary(1, HoldingPeriodUnit.YEARS),
                    rate = BigDecimal("0.005"),
                ),
                RedemptionFeeTier(
                    maxHoldingPeriodExclusive = HoldingPeriodBoundary(2, HoldingPeriodUnit.YEARS),
                    rate = BigDecimal("0.003"),
                ),
                RedemptionFeeTier(maxHoldingPeriodExclusive = null, rate = BigDecimal.ZERO),
            ),
        )
        val confirmationDate = LocalDate.parse("2023-03-01")

        assertEquals(BigDecimal("0.015"), fees.rateFor(confirmationDate, confirmationDate.plusDays(6)))
        assertEquals(BigDecimal("0.005"), fees.rateFor(confirmationDate, confirmationDate.plusDays(7)))
        assertEquals(
            BigDecimal("0.005"),
            fees.rateFor(confirmationDate, LocalDate.parse("2024-02-29")),
            "365 elapsed days are still less than one calendar year for this leap-spanning lot",
        )
        assertEquals(BigDecimal("0.003"), fees.rateFor(confirmationDate, LocalDate.parse("2024-03-01")))
        assertEquals(BigDecimal.ZERO, fees.rateFor(confirmationDate, LocalDate.parse("2025-03-01")))
    }

    @Test
    fun `redemption fee schedule rejects descending and calendar-ambiguous boundaries`() {
        assertFailsWith<IllegalArgumentException> {
            RedemptionFeeSchedule(
                listOf(
                    RedemptionFeeTier(HoldingPeriodBoundary(2, HoldingPeriodUnit.YEARS), BigDecimal("0.01")),
                    RedemptionFeeTier(HoldingPeriodBoundary(1, HoldingPeriodUnit.YEARS), BigDecimal.ZERO),
                    RedemptionFeeTier(null, BigDecimal.ZERO),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RedemptionFeeSchedule(
                listOf(
                    RedemptionFeeTier(HoldingPeriodBoundary(365, HoldingPeriodUnit.DAYS), BigDecimal("0.01")),
                    RedemptionFeeTier(HoldingPeriodBoundary(1, HoldingPeriodUnit.YEARS), BigDecimal.ZERO),
                    RedemptionFeeTier(null, BigDecimal.ZERO),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            HoldingPeriodBoundary(0, HoldingPeriodUnit.DAYS)
        }
    }

    @Test
    fun `redemption cannot consume more shares than the real Lots contain`() {
        assertFailsWith<IllegalArgumentException> {
            RedemptionCalculator.calculate(
                lots = listOf(lot(id = "lot-a", date = "2026-01-01", shares = "10.00", cost = "10.00")),
                sharesToRedeem = BigDecimal("10.01"),
                unitNav = BigDecimal.ONE,
                tradeDate = LocalDate.parse("2026-09-11"),
                feeSchedule = RedemptionFeeSchedule.noFee(),
                allocationPolicy = LotAllocationPolicy.FIFO,
                precision = precision,
            )
        }
    }

    private fun lot(id: String, date: String, shares: String, cost: String): Lot = Lot(
        id = LotId(id),
        fundId = fundId,
        sourceTransactionId = TransactionId("tx-$id"),
        type = LotType.REAL,
        confirmationDate = LocalDate.parse(date),
        originalShares = BigDecimal(shares),
        remainingShares = BigDecimal(shares),
        grossCashOut = BigDecimal(cost),
        netSubscriptionAmount = BigDecimal(cost),
        costBasis = BigDecimal(cost),
        unitNav = BigDecimal.ONE,
        purchaseFee = BigDecimal.ZERO,
    )
}
