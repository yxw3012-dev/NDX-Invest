package com.ndxinvest.market.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MarketFactsTest {
    @Test
    fun `NDX daily preserves official auxiliary prices while requiring a positive close`() {
        val daily = NdxDaily(
            date = LocalDate.parse("2026-09-11"),
            open = BigDecimal("25000.00"),
            high = BigDecimal("25200.00"),
            low = BigDecimal("24800.00"),
            close = BigDecimal("25100.00"),
            source = MarketSourceId("eastmoney"),
        )

        assertEquals(BigDecimal("25100.00"), daily.close)
        assertEquals(BigDecimal.ZERO, daily.copy(open = BigDecimal.ZERO, high = BigDecimal.ZERO, low = BigDecimal.ZERO).open)
        assertEquals(BigDecimal("24900.00"), daily.copy(high = BigDecimal("24900.00")).high)
        assertFailsWith<IllegalArgumentException> { daily.copy(close = BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { daily.copy(low = BigDecimal("-1.00")) }
    }

    @Test
    fun `live quote models retain exact decimals and reject non-positive market values`() {
        val quote = NdxQuote(
            point = BigDecimal("24321.1250"),
            change = BigDecimal("-123.4500"),
            changeRate = BigDecimal("-0.00504900"),
            quoteTime = Instant.parse("2026-09-11T20:00:00Z"),
            marketState = MarketSessionState.OPEN,
            source = MarketSourceId("tencent"),
        )
        val fx = UsdCnyQuote(
            rate = BigDecimal("7.12345600"),
            changeRate = null,
            quoteTime = quote.quoteTime,
            source = MarketSourceId("tencent"),
        )

        assertEquals(BigDecimal("24321.1250"), quote.point)
        assertEquals(BigDecimal("7.12345600"), fx.rate)
        assertFailsWith<IllegalArgumentException> { quote.copy(point = BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { fx.copy(rate = BigDecimal.ZERO) }
    }
}
