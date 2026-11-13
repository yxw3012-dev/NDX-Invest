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

class TencentMarketPayloadParsersTest {
    @Test
    fun `official Tencent NDX fixture maps exact decimals New York time and closed state`() {
        val quote = TencentNdxQuoteParser(SOURCE).parse(fixture("tencent_ndx_quote.txt"), FETCHED_AT)

        assertEquals(BigDecimal("29368.44"), quote.point)
        assertEquals(BigDecimal("264.93"), quote.change)
        assertEquals(BigDecimal("0.0091"), quote.changeRate)
        assertEquals(Instant.parse("2026-09-11T21:15:59Z"), quote.quoteTime)
        assertEquals(MarketSessionState.CLOSED, quote.marketState)
        assertEquals(SOURCE, quote.source)
    }

    @Test
    fun `unknown Tencent US session code is preserved as unknown instead of guessed`() {
        val body = fixture("tencent_ndx_quote.txt").replace("US_close_已休市", "US_halt_状态未知")
        val quote = TencentNdxQuoteParser(SOURCE).parse(body, FETCHED_AT)

        assertEquals(MarketSessionState.UNKNOWN, quote.marketState)
    }

    @Test
    fun `Tencent NDX rejects wrong identity missing assignment duplicate assignment and shifted change`() {
        val fixture = fixture("tencent_ndx_quote.txt")
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            TencentNdxQuoteParser(SOURCE).parse(fixture.replace("~.NDX~", "~.IXIC~"), FETCHED_AT)
        }
        payloadFailure(ProviderFailureKind.MISSING_FIELD) {
            TencentNdxQuoteParser(SOURCE).parse(fixture.substringBefore("v_usNDX"), FETCHED_AT)
        }
        payloadFailure(ProviderFailureKind.CONFLICTING_DUPLICATE) {
            TencentNdxQuoteParser(SOURCE).parse("$fixture\n${fixture.substringAfter('\n')}", FETCHED_AT)
        }
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            TencentNdxQuoteParser(SOURCE).parse(fixture.replace("~264.93~0.91~", "~264.94~0.91~"), FETCHED_AT)
        }
    }

    @Test
    fun `official Tencent USD CNY fixture maps exact decimals and China local timestamp`() {
        val quote = TencentUsdCnyQuoteParser(SOURCE).parse(fixture("tencent_usd_cny_quote.txt"), FETCHED_AT)

        assertEquals(BigDecimal("6.7070"), quote.rate)
        assertEquals(BigDecimal("-0.0007"), quote.changeRate)
        assertEquals(Instant.parse("2026-09-11T18:59:52Z"), quote.quoteTime)
        assertEquals(SOURCE, quote.source)
    }

    @Test
    fun `Tencent USD CNY rejects wrong identity malformed timestamp and inconsistent change`() {
        val fixture = fixture("tencent_usd_cny_quote.txt")
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            TencentUsdCnyQuoteParser(SOURCE).parse(fixture.replace("~USDCNY~", "~USDCNH~"), FETCHED_AT)
        }
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            TencentUsdCnyQuoteParser(SOURCE).parse(fixture.replace("20260912025952", "202609120259XX"), FETCHED_AT)
        }
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            TencentUsdCnyQuoteParser(SOURCE).parse(fixture.replace("~-0.0045~-0.07~", "~-0.0044~-0.07~"), FETCHED_AT)
        }
    }

    private fun payloadFailure(kind: ProviderFailureKind, block: () -> Unit): ProviderPayloadException {
        val error = assertIs<ProviderPayloadException>(runCatching(block).exceptionOrNull())
        assertEquals(kind, error.kind)
        return error
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/fixtures/$name")).readText()

    private companion object {
        val SOURCE: MarketSourceId = MarketSourceId("tencent")
        val FETCHED_AT: Instant = Instant.parse("2026-09-13T12:00:00Z")
    }
}
