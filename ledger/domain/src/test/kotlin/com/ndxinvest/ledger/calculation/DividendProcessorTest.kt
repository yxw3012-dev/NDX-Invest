package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.DividendTreatment
import com.ndxinvest.ledger.model.FundPrecision
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DividendProcessorTest {
    private val precision = FundPrecision(2, 2, 4, RoundingMode.HALF_UP)

    @Test
    fun `Test D cash dividend is external income and never becomes portfolio cash`() {
        val result = DividendProcessor.process(
            currentShares = BigDecimal("1000.00"),
            dividendAmount = BigDecimal("50.00"),
            paymentDate = LocalDate.parse("2026-09-11"),
            treatment = DividendTreatment.CASH,
            reinvestmentUnitNav = null,
            precision = precision,
        )

        assertEquals(BigDecimal("1000.00"), result.updatedShares)
        assertEquals(BigDecimal("0.00"), result.addedShares)
        assertEquals(BigDecimal("50.00"), result.externalCashFlow?.amount)
        assertEquals(BigDecimal("50.00"), result.realizedIncome)
        assertEquals(BigDecimal("0.00"), result.currentPortfolioCashIncrease)
    }

    @Test
    fun `Test E dividend reinvestment adds shares without external investment or XIRR cash flow`() {
        val result = DividendProcessor.process(
            currentShares = BigDecimal("1000.00"),
            dividendAmount = BigDecimal("50.00"),
            paymentDate = LocalDate.parse("2026-09-11"),
            treatment = DividendTreatment.REINVESTED,
            reinvestmentUnitNav = BigDecimal("1.2500"),
            precision = precision,
        )

        assertEquals(BigDecimal("40.00"), result.addedShares)
        assertEquals(BigDecimal("1040.00"), result.updatedShares)
        assertNull(result.externalCashFlow)
        assertEquals(BigDecimal("0.00"), result.externalInvestmentIncrease)
        assertEquals(BigDecimal("50.00"), result.reinvestmentCostBasis)
        assertEquals(BigDecimal("0.00"), result.realizedIncome)
    }
}
