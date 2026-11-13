package com.ndxinvest.providers.config

import com.ndxinvest.market.model.UsdCnyQuote
import com.ndxinvest.network.ProviderTransport
import com.ndxinvest.network.TransportResult
import com.ndxinvest.providers.contract.ProviderId
import com.ndxinvest.providers.contract.ProviderResult
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class EastMoneyUsdCnyBindingTest {
    @Test
    fun `binding requests the verified EastMoney mixed USD CNY quote without credentials`() {
        val request = EastMoneyUsdCnyBinding.request()

        assertEquals(ProviderId("eastmoney"), EastMoneyUsdCnyBinding.providerId)
        assertEquals("https://push2.eastmoney.com/api/qt/stock/get", request.endpoint)
        assertEquals(
            mapOf(
                "secid" to "121.USDCNYI",
                "fields" to "f57,f58,f43,f59,f60,f169,f170,f86",
            ),
            request.query,
        )
        assertEquals(emptyMap(), request.headers)
        assertNull(request.credential)
    }

    @Test
    fun `binding attaches the strict parser and preserves EastMoney source identity`() = runTest {
        val provider = EastMoneyUsdCnyBinding.provider(
            ProviderTransport {
                TransportResult.Response(200, fixture(), FETCHED_AT)
            },
        )

        val result = assertIs<ProviderResult.Success<UsdCnyQuote>>(provider.fetch())

        assertEquals(ProviderId("eastmoney"), result.providerId)
        assertEquals(BigDecimal("6.7117"), result.value.rate)
        assertEquals(ProviderId("eastmoney").value, result.value.source.value)
    }

    private fun fixture(): String =
        checkNotNull(javaClass.getResource("/fixtures/eastmoney_usd_cny_quote.json")).readText()

    private companion object {
        val FETCHED_AT: Instant = Instant.parse("2026-09-15T14:30:00Z")
    }
}
