package com.ndxinvest.providers.adapter

import com.ndxinvest.market.model.MarketSessionState
import com.ndxinvest.market.model.MarketSourceId
import com.ndxinvest.providers.contract.ProviderFailureKind
import com.ndxinvest.providers.contract.ProviderPayloadException
import com.ndxinvest.providers.json.InstantEncoding
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MarketJsonParsersTest {
    @Test
    fun `NDX quote fixture maps into the internal model without binary floating point`() {
        val quote = ndxQuoteParser().parse(fixture("ndx_quote.json"), FETCHED_AT)

        assertEquals(BigDecimal("24321.1250"), quote.point)
        assertEquals(4, quote.point.scale())
        assertEquals(BigDecimal("-0.00504900"), quote.changeRate)
        assertEquals(MarketSessionState.OPEN, quote.marketState)
        assertEquals(SOURCE, quote.source)
    }

    @Test
    fun `NDX history fixture is sorted and keeps exact OHLC decimals`() {
        val history = NdxHistoryJsonParser(
            SOURCE,
            NdxHistoryFieldMapping("data.rows", "day", "o", "h", "l", "c"),
        ).parse(fixture("ndx_history.json"), FETCHED_AT)

        assertEquals(listOf(LocalDate.parse("2026-09-10"), LocalDate.parse("2026-09-11")), history.map { it.date })
        assertEquals(BigDecimal("24321.1250"), history.last().close)
        assertEquals(4, history.last().close.scale())
    }

    @Test
    fun `USD CNY fixture supports epoch timestamps and optional exact change rate`() {
        val quote = UsdCnyQuoteJsonParser(
            SOURCE,
            UsdCnyFieldMapping(
                rate = "fx.rate",
                changeRate = "fx.change_rate",
                quoteTime = "fx.time_epoch_ms",
                instantEncoding = InstantEncoding.EPOCH_MILLIS,
            ),
        ).parse(fixture("usd_cny_quote.json"), FETCHED_AT)

        assertEquals(BigDecimal("7.12345600"), quote.rate)
        assertEquals(BigDecimal("0.00125000"), quote.changeRate)
        assertEquals(Instant.ofEpochMilli(1_789_156_800_000L), quote.quoteTime)
    }

    @Test
    fun `renamed null wrong-type and malformed quote payloads have distinct failures`() {
        val renamed = """{"quote":{"renamed":"1","change":"0","change_rate":"0","time":"2026-09-11T20:00:00Z","session":"OPEN"}}"""
        val nullPoint = """{"quote":{"point":null,"change":"0","change_rate":"0","time":"2026-09-11T20:00:00Z","session":"OPEN"}}"""
        val wrongType = """{"quote":{"point":[],"change":"0","change_rate":"0","time":"2026-09-11T20:00:00Z","session":"OPEN"}}"""

        assertPayloadFailure(ProviderFailureKind.MISSING_FIELD) { ndxQuoteParser().parse(renamed, FETCHED_AT) }
        assertPayloadFailure(ProviderFailureKind.NULL_FIELD) { ndxQuoteParser().parse(nullPoint, FETCHED_AT) }
        assertPayloadFailure(ProviderFailureKind.WRONG_TYPE) { ndxQuoteParser().parse(wrongType, FETCHED_AT) }
        assertPayloadFailure(ProviderFailureKind.MALFORMED_JSON) { ndxQuoteParser().parse("{", FETCHED_AT) }
    }

    @Test
    fun `wrong history date is rejected rather than silently reassigned`() {
        val body = """{"data":{"rows":[{"day":"09-11-2026","o":"1","h":"2","l":"1","c":"2"}]}}"""

        assertPayloadFailure(ProviderFailureKind.INVALID_VALUE) {
            NdxHistoryJsonParser(
                SOURCE,
                NdxHistoryFieldMapping("data.rows", "day", "o", "h", "l", "c"),
            ).parse(body, FETCHED_AT)
        }
    }

    private fun ndxQuoteParser(): NdxQuoteJsonParser = NdxQuoteJsonParser(
        SOURCE,
        NdxQuoteFieldMapping(
            point = "quote.point",
            change = "quote.change",
            changeRate = "quote.change_rate",
            quoteTime = "quote.time",
            marketState = "quote.session",
        ),
    )

    private fun assertPayloadFailure(kind: ProviderFailureKind, block: () -> Unit) {
        val error = assertIs<ProviderPayloadException>(runCatching(block).exceptionOrNull())
        assertEquals(kind, error.kind)
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/fixtures/$name")).readText()

    private companion object {
        val SOURCE: MarketSourceId = MarketSourceId("fixture-provider")
        val FETCHED_AT: Instant = Instant.parse("2026-09-11T20:00:01Z")
    }
}
