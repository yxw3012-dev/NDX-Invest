package com.ndxinvest.providers.adapter

import com.ndxinvest.market.model.MarketSessionState
import com.ndxinvest.market.model.MarketSourceId
import com.ndxinvest.providers.contract.ProviderFailureKind
import com.ndxinvest.providers.contract.ProviderPayloadException
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SinaNdxQuoteParserTest {
    @Test
    fun `official Sina NDX fixture maps exact decimals and explicit EDT timestamp`() {
        val quote = parser().parse(fixture(), FETCHED_AT)

        assertEquals(BigDecimal("29368.4389"), quote.point)
        assertEquals(BigDecimal("264.9261"), quote.change)
        assertEquals(BigDecimal("0.0091"), quote.changeRate)
        assertEquals(Instant.parse("2026-09-11T21:16:00Z"), quote.quoteTime)
        assertEquals(MarketSessionState.UNKNOWN, quote.marketState)
        assertEquals(SOURCE, quote.source)
    }

    @Test
    fun `Sina NDX rejects missing and duplicate assignments`() {
        val fixture = fixture()
        payloadFailure(ProviderFailureKind.MISSING_FIELD) { parser().parse("", FETCHED_AT) }
        payloadFailure(ProviderFailureKind.CONFLICTING_DUPLICATE) { parser().parse("$fixture\n$fixture", FETCHED_AT) }
    }

    @Test
    fun `Sina NDX rejects unknown timezone malformed year and shifted change`() {
        val fixture = fixture()
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            parser().parse(fixture.replace(" EDT,", " UTC,"), FETCHED_AT)
        }
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            parser().parse(fixture.replace(",2026,", ",year,"), FETCHED_AT)
        }
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            parser().parse(fixture.replace(",264.9261,", ",264.9262,"), FETCHED_AT)
        }
    }

    private fun parser(): SinaNdxQuoteParser = SinaNdxQuoteParser(SOURCE)

    private fun payloadFailure(kind: ProviderFailureKind, block: () -> Unit): ProviderPayloadException {
        val error = assertIs<ProviderPayloadException>(runCatching(block).exceptionOrNull())
        assertEquals(kind, error.kind)
        return error
    }

    private fun fixture(): String = checkNotNull(javaClass.getResource("/fixtures/sina_ndx_quote.txt")).readText()

    private companion object {
        val SOURCE: MarketSourceId = MarketSourceId("sina")
        val FETCHED_AT: Instant = Instant.parse("2026-09-13T12:00:00Z")
    }
}
