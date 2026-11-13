package com.ndxinvest.providers.persistence

import com.ndxinvest.market.model.MarketSessionState
import com.ndxinvest.market.model.MarketSourceId
import com.ndxinvest.market.model.NdxDaily
import com.ndxinvest.market.model.NdxQuote
import com.ndxinvest.market.model.UsdCnyQuote
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderCacheCodecsTest {
    @Test
    fun `market cache codecs preserve exact decimals`() {
        assertEquals(ndxQuote(), roundTrip(ProviderCacheCodecs.ndxQuote, ndxQuote()))

        val fx = UsdCnyQuote(
            rate = BigDecimal("7.12345600"),
            changeRate = null,
            quoteTime = FETCHED_AT.minusSeconds(1),
            source = MarketSourceId("tencent"),
        )
        assertEquals(fx, roundTrip(ProviderCacheCodecs.usdCnyQuote, fx))

        val history = listOf(
            NdxDaily(DATE.minusDays(1), bd("10.00"), bd("12.00"), bd("9.00"), bd("11.00"), MarketSourceId("stooq")),
            NdxDaily(DATE, bd("11.00"), bd("13.00"), bd("10.00"), bd("12.00"), MarketSourceId("stooq")),
        )
        assertEquals(history, roundTrip(ProviderCacheCodecs.ndxHistory, history))

    }

    private fun ndxQuote(): NdxQuote = NdxQuote(
        point = BigDecimal("24321.1250"),
        change = BigDecimal("-123.4500"),
        changeRate = BigDecimal("-0.00504900"),
        quoteTime = FETCHED_AT.minusSeconds(1),
        marketState = MarketSessionState.OPEN,
        source = MarketSourceId("tencent"),
    )

    private fun <T> roundTrip(codec: ProviderCacheCodec<T>, value: T): T = codec.decode(codec.encode(value))

    private fun bd(value: String): BigDecimal = BigDecimal(value)

    private companion object {
        val FETCHED_AT: Instant = Instant.parse("2026-09-11T20:00:00Z")
        val DATE: LocalDate = LocalDate.parse("2026-09-11")
    }
}
