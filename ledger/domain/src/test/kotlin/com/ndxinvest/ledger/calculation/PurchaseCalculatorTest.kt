package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.FundPrecision
import com.ndxinvest.ledger.model.PurchaseFeeRule
import com.ndxinvest.ledger.model.PurchaseFeeTier
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PurchaseCalculatorTest {
    private val precision = FundPrecision(
        moneyScale = 2,
        shareScale = 2,
        navScale = 4,
        roundingMode = RoundingMode.HALF_UP,
    )

    @Test
    fun `Test A calculates a rate based front-end purchase fee without double charging fund expenses`() {
        val result = PurchaseCalculator.calculate(
            grossAmount = BigDecimal("1000.00"),
            unitNav = BigDecimal("1.4000"),
            feeRule = PurchaseFeeRule.Rate(BigDecimal("0.0012")),
            precision = precision,
        )

        assertEquals(BigDecimal("998.80"), result.netSubscriptionAmount)
        assertEquals(BigDecimal("1.20"), result.purchaseFee)
        assertEquals(BigDecimal("713.43"), result.confirmedShares)
        assertEquals(BigDecimal("1000.00"), result.grossAmount)
        assertEquals(BigDecimal("1.4000"), result.unitNav)
    }

    @Test
    fun `fixed and zero fee rules preserve the gross cash identity`() {
        val fixed = PurchaseCalculator.calculate(
            grossAmount = BigDecimal("1000.00"),
            unitNav = BigDecimal("2.0000"),
            feeRule = PurchaseFeeRule.Fixed(BigDecimal("5.00")),
            precision = precision,
        )
        val none = PurchaseCalculator.calculate(
            grossAmount = BigDecimal("1000.00"),
            unitNav = BigDecimal("2.0000"),
            feeRule = PurchaseFeeRule.None,
            precision = precision,
        )

        assertEquals(BigDecimal("995.00"), fixed.netSubscriptionAmount)
        assertEquals(BigDecimal("5.00"), fixed.purchaseFee)
        assertEquals(BigDecimal("497.50"), fixed.confirmedShares)
        assertEquals(BigDecimal("1000.00"), none.netSubscriptionAmount)
        assertEquals(BigDecimal("0.00"), none.purchaseFee)
        assertEquals(BigDecimal("500.00"), none.confirmedShares)
    }

    @Test
    fun `rounded zero purchases and fees consuming all cash are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PurchaseCalculator.calculate(
                grossAmount = BigDecimal("0.004"),
                unitNav = BigDecimal.ONE,
                feeRule = PurchaseFeeRule.None,
                precision = precision,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PurchaseCalculator.calculate(
                grossAmount = BigDecimal("1.00"),
                unitNav = BigDecimal.ONE,
                feeRule = PurchaseFeeRule.Fixed(BigDecimal("1.00")),
                precision = precision,
            )
        }
    }

    @Test
    fun `large amount and very small rate remain exact BigDecimal facts`() {
        val result = PurchaseCalculator.calculate(
            grossAmount = BigDecimal("999999999999999999.99"),
            unitNav = BigDecimal("0.0001"),
            feeRule = PurchaseFeeRule.Rate(BigDecimal("0.000001")),
            precision = precision,
        )

        assertEquals(BigDecimal("999999000000999998.99"), result.netSubscriptionAmount)
        assertEquals(BigDecimal("999999000001.00"), result.purchaseFee)
        assertEquals(BigDecimal("9999990000009999989900.00"), result.confirmedShares)
        assertEquals(result.grossAmount, result.netSubscriptionAmount.add(result.purchaseFee))
    }

    @Test
    fun `tiered purchase fee selects each exact gross amount boundary including fixed top tier`() {
        val feeRule = PurchaseFeeRule.Tiered(
            listOf(
                PurchaseFeeTier(BigDecimal.ZERO, BigDecimal("100.00"), PurchaseFeeRule.Rate(BigDecimal("0.10"))),
                PurchaseFeeTier(BigDecimal("100.00"), BigDecimal("200.00"), PurchaseFeeRule.Rate(BigDecimal("0.20"))),
                PurchaseFeeTier(BigDecimal("200.00"), BigDecimal("300.00"), PurchaseFeeRule.Rate(BigDecimal("0.30"))),
                PurchaseFeeTier(BigDecimal("300.00"), null, PurchaseFeeRule.Fixed(BigDecimal("5.00"))),
            ),
        )

        val belowFirstBoundary = calculate(BigDecimal("99.99"), feeRule)
        val atFirstBoundary = calculate(BigDecimal("100.00"), feeRule)
        val atSecondBoundary = calculate(BigDecimal("200.00"), feeRule)
        val atFixedBoundary = calculate(BigDecimal("300.00"), feeRule)

        assertEquals(BigDecimal("9.09"), belowFirstBoundary.purchaseFee)
        assertEquals(BigDecimal("16.67"), atFirstBoundary.purchaseFee)
        assertEquals(BigDecimal("46.15"), atSecondBoundary.purchaseFee)
        assertEquals(BigDecimal("5.00"), atFixedBoundary.purchaseFee)
        assertEquals(BigDecimal("295.00"), atFixedBoundary.netSubscriptionAmount)
        assertEquals(
            atFixedBoundary.grossAmount,
            atFixedBoundary.netSubscriptionAmount.add(atFixedBoundary.purchaseFee),
        )
    }

    @Test
    fun `tiered purchase fee rejects gaps overlaps missing zero start and nested schedules`() {
        val first = PurchaseFeeTier(BigDecimal.ZERO, BigDecimal("100"), PurchaseFeeRule.None)
        val last = PurchaseFeeTier(BigDecimal("100"), null, PurchaseFeeRule.None)

        assertFailsWith<IllegalArgumentException> {
            PurchaseFeeRule.Tiered(listOf(first, last.copy(minGrossAmountInclusive = BigDecimal("101"))))
        }
        assertFailsWith<IllegalArgumentException> {
            PurchaseFeeRule.Tiered(listOf(first, last.copy(minGrossAmountInclusive = BigDecimal("99"))))
        }
        assertFailsWith<IllegalArgumentException> {
            PurchaseFeeRule.Tiered(listOf(first.copy(minGrossAmountInclusive = BigDecimal.ONE), last))
        }
        assertFailsWith<IllegalArgumentException> {
            PurchaseFeeRule.Tiered(
                listOf(
                    first.copy(feeRule = PurchaseFeeRule.Tiered(listOf(first, last))),
                    last,
                ),
            )
        }
    }

    private fun calculate(grossAmount: BigDecimal, feeRule: PurchaseFeeRule): PurchaseCalculation =
        PurchaseCalculator.calculate(
            grossAmount = grossAmount,
            unitNav = BigDecimal.ONE,
            feeRule = feeRule,
            precision = precision,
        )
}
