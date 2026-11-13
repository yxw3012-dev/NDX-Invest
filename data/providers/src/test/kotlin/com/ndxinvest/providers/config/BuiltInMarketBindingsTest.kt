package com.ndxinvest.providers.config

import com.ndxinvest.market.model.NdxQuote
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

class BuiltInMarketBindingsTest {
    @Test
    fun `Tencent NDX binding requests the verified quote and US market-state variables`() {
        val request = TencentNdxQuoteBinding.request()

        assertEquals(ProviderId("tencent"), TencentNdxQuoteBinding.providerId)
        assertEquals("https://qt.gtimg.cn/", request.endpoint)
        assertEquals(mapOf("q" to "marketStat,usNDX"), request.query)
        assertEquals(emptyMap(), request.headers)
        assertNull(request.credential)
    }

    @Test
    fun `Tencent USD CNY binding requests the official wh symbol`() {
        val request = TencentUsdCnyBinding.request()

        assertEquals(ProviderId("tencent"), TencentUsdCnyBinding.providerId)
        assertEquals("https://qt.gtimg.cn/", request.endpoint)
        assertEquals(mapOf("q" to "whUSDCNY"), request.query)
        assertEquals(emptyMap(), request.headers)
        assertNull(request.credential)
    }

    @Test
    fun `Sina NDX binding carries only the public Finance referer`() {
        val request = SinaNdxQuoteBinding.request()

        assertEquals(ProviderId("sina"), SinaNdxQuoteBinding.providerId)
        assertEquals("https://hq.sinajs.cn/list=gb_\$ndx", request.endpoint)
        assertEquals(emptyMap(), request.query)
        assertEquals(mapOf("Referer" to "https://stock.finance.sina.com.cn/"), request.headers)
        assertNull(request.credential)
    }

    @Test
    fun `built-in providers attach each verified parser and preserve source identity`() = runTest {
        val tencentNdx = assertIs<ProviderResult.Success<*>>(
            TencentNdxQuoteBinding.provider(transport(fixture("tencent_ndx_quote.txt"))).fetch(),
        )
        val tencentFx = assertIs<ProviderResult.Success<*>>(
            TencentUsdCnyBinding.provider(transport(fixture("tencent_usd_cny_quote.txt"))).fetch(),
        )
        val sinaNdx = assertIs<ProviderResult.Success<*>>(
            SinaNdxQuoteBinding.provider(transport(fixture("sina_ndx_quote.txt"))).fetch(),
        )

        assertEquals(ProviderId("tencent"), tencentNdx.providerId)
        assertEquals(BigDecimal("29368.44"), assertIs<NdxQuote>(tencentNdx.value).point)
        assertEquals(BigDecimal("6.7070"), assertIs<UsdCnyQuote>(tencentFx.value).rate)
        assertEquals(ProviderId("sina"), sinaNdx.providerId)
        assertEquals(BigDecimal("29368.4389"), assertIs<NdxQuote>(sinaNdx.value).point)
    }

    private fun transport(body: String): ProviderTransport = ProviderTransport {
        TransportResult.Response(200, body, FETCHED_AT)
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/fixtures/$name")).readText()

    private companion object {
        val FETCHED_AT: Instant = Instant.parse("2026-09-13T12:00:00Z")
    }
}
