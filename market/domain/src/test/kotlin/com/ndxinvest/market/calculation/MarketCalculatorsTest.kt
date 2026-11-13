package com.ndxinvest.market.calculation

import com.ndxinvest.market.model.DataDelivery
import com.ndxinvest.market.model.FreshnessState
import com.ndxinvest.market.model.MarketSourceId
import com.ndxinvest.market.model.NdxDaily
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MarketCalculatorsTest {
    @Test
    fun `current drawdown uses local all-time-high close`() {
        val result = CurrentDrawdownCalculator.calculate(
            history = history("100", "120", "90"),
            currentClose = BigDecimal("90"),
        )

        assertEquals(BigDecimal("120"), result.allTimeHighClose)
        assertEquals(LocalDate.parse("2026-01-02"), result.allTimeHighDate)
        assertEquals(BigDecimal("-0.25000000"), result.drawdownRate)
    }

    @Test
    fun `a new current high produces zero drawdown without an external drawdown API`() {
        val result = CurrentDrawdownCalculator.calculate(history("100", "120", "110"), BigDecimal("130"))

        assertEquals(BigDecimal("130"), result.allTimeHighClose)
        assertEquals(BigDecimal("0.00000000"), result.drawdownRate)
    }

    @Test
    fun `maximum drawdown tracks the peak that precedes the deepest trough`() {
        val result = MaximumDrawdownCalculator.calculate(history("100", "120", "90", "110", "80", "130"))

        assertEquals(BigDecimal("-0.33333333"), result.rate)
        assertEquals(LocalDate.parse("2026-01-02"), result.peakDate)
        assertEquals(LocalDate.parse("2026-01-05"), result.troughDate)
    }

    @Test
    fun `CAGR uses decimal fractional power for gains and losses`() {
        assertEquals(
            BigDecimal("0.10000000"),
            CagrCalculator.calculate(BigDecimal("100"), BigDecimal("121"), BigDecimal("2")),
        )
        assertEquals(
            BigDecimal("-0.10000000"),
            CagrCalculator.calculate(BigDecimal("100"), BigDecimal("81"), BigDecimal("2")),
        )
    }

    @Test
    fun `annualized volatility uses sample deviation of close-to-close simple returns`() {
        assertEquals(
            BigDecimal("2.24499443"),
            AnnualizedVolatilityCalculator.calculate(history("100", "110", "99")),
        )
        assertEquals(
            BigDecimal("0.00000000"),
            AnnualizedVolatilityCalculator.calculate(history("100", "110", "121")),
        )
    }

    @Test
    fun `long-term performance returns all four required metrics from one date window`() {
        val result = LongTermPerformanceCalculator.calculate(
            history = history("100", "90", "121"),
            periodYears = BigDecimal("2"),
        )

        assertEquals(BigDecimal("0.21000000"), result.cumulativeReturn)
        assertEquals(BigDecimal("0.10000000"), result.cagr)
        assertEquals(BigDecimal("-0.10000000"), result.maximumDrawdown.rate)
        assertTrue(result.annualizedVolatility.signum() > 0)
    }

    @Test
    fun `fresh network cache stale and error remain distinct user-visible states`() {
        val now = Instant.parse("2026-09-12T12:00:00Z")
        val source = MarketSourceId("tencent")

        assertEquals(
            FreshnessState.LIVE_FRESH,
            FreshnessEvaluator.evaluate(true, DataDelivery.NETWORK, source, now.minusSeconds(60), now, Duration.ofMinutes(5)).state,
        )
        assertEquals(
            FreshnessState.CACHED,
            FreshnessEvaluator.evaluate(true, DataDelivery.CACHE, source, now.minusSeconds(60), now, Duration.ofMinutes(5)).state,
        )
        assertEquals(
            FreshnessState.STALE,
            FreshnessEvaluator.evaluate(true, DataDelivery.CACHE, source, now.minusSeconds(301), now, Duration.ofMinutes(5)).state,
        )
        assertEquals(
            FreshnessState.ERROR,
            FreshnessEvaluator.evaluate(false, null, null, null, now, Duration.ofMinutes(5)).state,
        )
    }

    @Test
    fun `market calculations reject duplicate dates and future fetch timestamps`() {
        val duplicated = listOf(daily("2026-01-01", "100"), daily("2026-01-01", "101"))
        assertFailsWith<IllegalArgumentException> { MaximumDrawdownCalculator.calculate(duplicated) }
        assertFailsWith<IllegalArgumentException> {
            FreshnessEvaluator.evaluate(
                true,
                DataDelivery.NETWORK,
                MarketSourceId("tencent"),
                Instant.parse("2026-09-12T12:01:00Z"),
                Instant.parse("2026-09-12T12:00:00Z"),
                Duration.ofMinutes(5),
            )
        }
    }

    private fun history(vararg closes: String): List<NdxDaily> = closes.mapIndexed { index, close ->
        daily(LocalDate.parse("2026-01-01").plusDays(index.toLong()).toString(), close)
    }

    private fun daily(date: String, close: String): NdxDaily {
        val price = BigDecimal(close)
        return NdxDaily(
            date = LocalDate.parse(date),
            open = price,
            high = price,
            low = price,
            close = price,
            source = MarketSourceId("fixture"),
        )
    }
}
