package com.ndxinvest.providers.adapter

import com.ndxinvest.market.model.MarketSourceId
import com.ndxinvest.providers.contract.ProviderFailureKind
import com.ndxinvest.providers.contract.ProviderPayloadException
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EastMoneyUsdCnyQuoteParserTest {
    @Test
    fun `official EastMoney USD CNY fixture maps exact scaled values and epoch timestamp`() {
        val quote = parser.parse(fixture(), FETCHED_AT)

        assertEquals(BigDecimal("6.7117"), quote.rate)
        assertEquals(BigDecimal("0.0004"), quote.changeRate)
        assertEquals(Instant.parse("2026-09-15T14:23:00Z"), quote.quoteTime)
        assertEquals(SOURCE, quote.source)
    }

    @Test
    fun `EastMoney USD CNY rejects unsuccessful response wrong identity and changed precision`() {
        val body = fixture()
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            parser.parse(body.replace("\"rc\":0", "\"rc\":1"), FETCHED_AT)
        }
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            parser.parse(body.replace("\"USDCNYI\"", "\"USDCNH\""), FETCHED_AT)
        }
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            parser.parse(body.replace("\"USDCNYI\"", "\"USDCNYC\""), FETCHED_AT)
        }
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            parser.parse(body.replace("\"美元人民币混合\"", "\"美元兑离岸人民币\""), FETCHED_AT)
        }
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            parser.parse(body.replace("\"f59\":4", "\"f59\":3"), FETCHED_AT)
        }
    }

    @Test
    fun `EastMoney USD CNY rejects inconsistent change percentage and invalid timestamp`() {
        val body = fixture()
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            parser.parse(body.replace("\"f169\":26", "\"f169\":27"), FETCHED_AT)
        }
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            parser.parse(body.replace("\"f170\":4", "\"f170\":5"), FETCHED_AT)
        }
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            parser.parse(body.replace("\"f86\":1789482180", "\"f86\":0"), FETCHED_AT)
        }
    }

    private fun payloadFailure(kind: ProviderFailureKind, block: () -> Unit): ProviderPayloadException {
        val error = assertIs<ProviderPayloadException>(runCatching(block).exceptionOrNull())
        assertEquals(kind, error.kind)
        return error
    }

    private fun fixture(): String =
        checkNotNull(javaClass.getResource("/fixtures/eastmoney_usd_cny_quote.json")).readText()

    private companion object {
        val SOURCE: MarketSourceId = MarketSourceId("eastmoney")
        val FETCHED_AT: Instant = Instant.parse("2026-09-15T14:30:00Z")
        val parser: EastMoneyUsdCnyQuoteParser = EastMoneyUsdCnyQuoteParser(SOURCE)
    }
}
