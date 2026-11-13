package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.FundPrecision
import com.ndxinvest.ledger.model.LegacyLot
import com.ndxinvest.ledger.model.LotId
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LegacyLotCalculatorTest {
    private val precision = FundPrecision(2, 2, 4, RoundingMode.HALF_UP)

    @Test
    fun `Test C allocates Legacy Lot average cost without inventing historical fees`() {
        val lot = LegacyLot(
            id = LotId("legacy-1"),
            fundId = FundId("fund-a"),
            remainingShares = BigDecimal("10000.00"),
            costBasis = BigDecimal("12000.00"),
            trackingStartDate = null,
        )

        val result = LegacyLotCalculator.redeem(
            lot = lot,
            sharesToRedeem = BigDecimal("2000.00"),
            actualCashReceived = BigDecimal("3180.00"),
            precision = precision,
        )

        assertEquals(BigDecimal("2400.00"), result.allocatedCostBasis)
        assertEquals(BigDecimal("780.00"), result.realizedReturn)
        assertEquals(BigDecimal("8000.00"), result.updatedLot.remainingShares)
        assertEquals(BigDecimal("9600.00"), result.updatedLot.costBasis)
        assertEquals(null, result.inferredRedemptionFee)
    }

    @Test
    fun `Legacy Lot redemption rejects a positive input that rounds to zero shares`() {
        val lot = LegacyLot(
            id = LotId("legacy-1"),
            fundId = FundId("fund-a"),
            remainingShares = BigDecimal("100.00"),
            costBasis = BigDecimal("120.00"),
            trackingStartDate = null,
        )

        assertFailsWith<IllegalArgumentException> {
            LegacyLotCalculator.redeem(
                lot = lot,
                sharesToRedeem = BigDecimal("0.004"),
                actualCashReceived = BigDecimal("1.00"),
                precision = precision,
            )
        }
    }
}
