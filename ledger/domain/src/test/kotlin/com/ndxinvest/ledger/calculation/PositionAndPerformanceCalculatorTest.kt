package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.DataAuthority
import com.ndxinvest.ledger.model.DataSourceId
import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.FundPrecision
import com.ndxinvest.ledger.model.LegacyLot
import com.ndxinvest.ledger.model.Lot
import com.ndxinvest.ledger.model.LotId
import com.ndxinvest.ledger.model.LotType
import com.ndxinvest.ledger.model.NavRecord
import com.ndxinvest.ledger.model.TransactionId
import com.ndxinvest.ledger.model.ValuationDateBasis
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PositionAndPerformanceCalculatorTest {
    private val precision = FundPrecision(2, 2, 4, RoundingMode.HALF_UP)

    @Test
    fun `position projection derives shares cost value and holding return from immutable lots`() {
        val fundId = FundId("fund-a")
        val position = PositionProjector.project(
            fundId = fundId,
            realLots = listOf(realLot(fundId, "real-1", "100.00", "120.00")),
            legacyLots = listOf(
                LegacyLot(
                    id = LotId("legacy-1"),
                    fundId = fundId,
                    remainingShares = BigDecimal("50.00"),
                    costBasis = BigDecimal("80.00"),
                    trackingStartDate = null,
                ),
            ),
            latestNav = NavRecord(
                fundId = fundId,
                navDate = LocalDate.parse("2026-09-11"),
                unitNav = BigDecimal("2.0000"),
                accumulatedNav = null,
                authority = DataAuthority.FUND_OFFICIAL,
                source = DataSourceId("fund-company"),
                fetchedAt = Instant.parse("2026-09-11T08:00:00Z"),
            ),
            precision = precision,
        )

        assertEquals(BigDecimal("150.00"), position.totalShares)
        assertEquals(BigDecimal("200.00"), position.remainingCostBasis)
        assertEquals(BigDecimal("1.3333"), position.averageCostNav)
        assertEquals(BigDecimal("300.00"), position.currentValue)
        assertEquals(BigDecimal("100.00"), position.holdingReturn)
        assertEquals(BigDecimal("0.50000000"), position.holdingReturnRate)
    }

    @Test
    fun `Test G mixed latest NAV dates produce a total without inventing one portfolio date`() {
        val a = projectedPosition("fund-a", "2026-09-11", "100.00")
        val b = projectedPosition("fund-b", "2026-09-10", "200.00")

        val total = PortfolioValuator.aggregate(listOf(a, b), precision)

        assertEquals(BigDecimal("300.00"), total.totalCurrentValue)
        assertEquals(ValuationDateBasis.EACH_FUND_LATEST_PUBLISHED, total.dateBasis)
        assertNull(total.commonNavDate)
        assertEquals(
            mapOf(
                FundId("fund-a") to LocalDate.parse("2026-09-11"),
                FundId("fund-b") to LocalDate.parse("2026-09-10"),
            ),
            total.navDates,
        )
    }

    @Test
    fun `cumulative return excludes withdrawn cash from current value while retaining external returns`() {
        val result = PerformanceCalculator.calculate(
            currentPositionValue = BigDecimal("900.00"),
            externalCashInvested = BigDecimal("1000.00"),
            externalCashReturned = BigDecimal("250.00"),
            precision = precision,
        )

        assertEquals(BigDecimal("150.00"), result.cumulativeReturn)
        assertEquals(BigDecimal("900.00"), result.currentPortfolioValue)
    }

    private fun projectedPosition(fund: String, navDate: String, value: String): PositionProjection =
        PositionProjection(
            fundId = FundId(fund),
            totalShares = BigDecimal("100.00"),
            remainingCostBasis = BigDecimal(value),
            averageCostNav = BigDecimal("1.0000"),
            latestUnitNav = BigDecimal(value).divide(BigDecimal("100")),
            navDate = LocalDate.parse(navDate),
            currentValue = BigDecimal(value),
            holdingReturn = BigDecimal("0.00"),
            holdingReturnRate = BigDecimal("0.00000000"),
        )

    private fun realLot(fundId: FundId, id: String, shares: String, cost: String): Lot = Lot(
        id = LotId(id),
        fundId = fundId,
        sourceTransactionId = TransactionId("tx-$id"),
        type = LotType.REAL,
        confirmationDate = LocalDate.parse("2026-01-01"),
        originalShares = BigDecimal(shares),
        remainingShares = BigDecimal(shares),
        grossCashOut = BigDecimal(cost),
        netSubscriptionAmount = BigDecimal(cost),
        purchaseFee = BigDecimal.ZERO,
        costBasis = BigDecimal(cost),
        unitNav = BigDecimal.ONE,
    )
}
