package com.ndxinvest.providers.config

import com.ndxinvest.market.model.MarketSourceId
import com.ndxinvest.market.model.NdxQuote
import com.ndxinvest.market.model.UsdCnyQuote
import com.ndxinvest.network.ProviderRequest
import com.ndxinvest.network.ProviderTransport
import com.ndxinvest.providers.adapter.EastMoneyUsdCnyQuoteParser
import com.ndxinvest.providers.adapter.SinaNdxQuoteParser
import com.ndxinvest.providers.adapter.TencentNdxQuoteParser
import com.ndxinvest.providers.adapter.TencentUsdCnyQuoteParser
import com.ndxinvest.providers.contract.DataProvider
import com.ndxinvest.providers.contract.HttpJsonProvider
import com.ndxinvest.providers.contract.ProviderId

public object TencentNdxQuoteBinding {
    public val providerId: ProviderId = ProviderId("tencent")

    public fun request(): ProviderRequest = ProviderRequest(
        endpoint = TENCENT_ENDPOINT,
        query = mapOf("q" to "marketStat,usNDX"),
    )

    public fun provider(transport: ProviderTransport): DataProvider<NdxQuote> = HttpJsonProvider(
        id = providerId,
        request = request(),
        transport = transport,
        parser = TencentNdxQuoteParser(MarketSourceId(providerId.value)),
    )
}

public object TencentUsdCnyBinding {
    public val providerId: ProviderId = ProviderId("tencent")

    public fun request(): ProviderRequest = ProviderRequest(
        endpoint = TENCENT_ENDPOINT,
        query = mapOf("q" to "whUSDCNY"),
    )

    public fun provider(transport: ProviderTransport): DataProvider<UsdCnyQuote> = HttpJsonProvider(
        id = providerId,
        request = request(),
        transport = transport,
        parser = TencentUsdCnyQuoteParser(MarketSourceId(providerId.value)),
    )
}

public object EastMoneyUsdCnyBinding {
    public val providerId: ProviderId = ProviderId("eastmoney")

    public fun request(): ProviderRequest = ProviderRequest(
        endpoint = EASTMONEY_QUOTE_ENDPOINT,
        query = mapOf(
            "secid" to "121.USDCNYI",
            "fields" to "f57,f58,f43,f59,f60,f169,f170,f86",
        ),
    )

    public fun provider(transport: ProviderTransport): DataProvider<UsdCnyQuote> = HttpJsonProvider(
        id = providerId,
        request = request(),
        transport = transport,
        parser = EastMoneyUsdCnyQuoteParser(MarketSourceId(providerId.value)),
    )
}

public object SinaNdxQuoteBinding {
    public val providerId: ProviderId = ProviderId("sina")

    public fun request(): ProviderRequest = ProviderRequest(
        endpoint = SINA_ENDPOINT,
        headers = mapOf("Referer" to SINA_REFERER),
    )

    public fun provider(transport: ProviderTransport): DataProvider<NdxQuote> = HttpJsonProvider(
        id = providerId,
        request = request(),
        transport = transport,
        parser = SinaNdxQuoteParser(MarketSourceId(providerId.value)),
    )
}

private const val TENCENT_ENDPOINT: String = "https://qt.gtimg.cn/"
private const val EASTMONEY_QUOTE_ENDPOINT: String = "https://push2.eastmoney.com/api/qt/stock/get"
private const val SINA_ENDPOINT: String = "https://hq.sinajs.cn/list=gb_\$ndx"
private const val SINA_REFERER: String = "https://stock.finance.sina.com.cn/"
