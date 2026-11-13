package com.ndxinvest.providers.adapter

import com.ndxinvest.market.model.MarketSessionState
import com.ndxinvest.market.model.MarketSourceId
import com.ndxinvest.market.model.NdxQuote
import com.ndxinvest.market.model.UsdCnyQuote
import com.ndxinvest.providers.contract.JsonPayloadParser
import com.ndxinvest.providers.contract.ProviderFailureKind
import com.ndxinvest.providers.json.fail
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

public class TencentNdxQuoteParser public constructor(
    private val source: MarketSourceId,
) : JsonPayloadParser<NdxQuote> {
    override fun parse(body: String, fetchedAt: Instant): NdxQuote {
        val fields = DelimitedMarketPayload.requiredVariable(body, NDX_VARIABLE).split('~')
        DelimitedMarketPayload.requireIdentity(DelimitedMarketPayload.requiredField(fields, TYPE_INDEX), NDX_TYPE)
        DelimitedMarketPayload.requireIdentity(DelimitedMarketPayload.requiredField(fields, SYMBOL_INDEX), NDX_SYMBOL)
        DelimitedMarketPayload.requireIdentity(DelimitedMarketPayload.requiredField(fields, CURRENCY_INDEX), USD)

        val point = DelimitedMarketPayload.requiredDecimal(fields, POINT_INDEX)
        val previousClose = DelimitedMarketPayload.requiredDecimal(fields, PREVIOUS_CLOSE_INDEX)
        val change = DelimitedMarketPayload.requiredDecimal(fields, CHANGE_INDEX)
        DelimitedMarketPayload.requireExactChange(point, previousClose, change)

        return NdxQuote(
            point = point,
            change = change,
            changeRate = DelimitedMarketPayload.percentToFraction(
                DelimitedMarketPayload.requiredDecimal(fields, CHANGE_RATE_INDEX),
            ),
            quoteTime = parseLocalInstant(
                DelimitedMarketPayload.requiredField(fields, QUOTE_TIME_INDEX),
                NDX_TIME_FORMATTER,
                NEW_YORK,
            ),
            marketState = parseUsMarketState(
                DelimitedMarketPayload.requiredVariable(body, MARKET_STATE_VARIABLE),
            ),
            source = source,
        )
    }

    private fun parseUsMarketState(value: String): MarketSessionState {
        val matches = value.split('|').filter { it.startsWith(US_STATE_PREFIX) }
        if (matches.isEmpty()) fail(ProviderFailureKind.MISSING_FIELD, "Tencent US market state is missing")
        if (matches.size > 1) {
            fail(ProviderFailureKind.CONFLICTING_DUPLICATE, "Tencent US market state is duplicated")
        }
        val code = matches.single().removePrefix(US_STATE_PREFIX).substringBefore('_')
        if (code.isBlank()) fail(ProviderFailureKind.INVALID_VALUE, "Tencent US market state is invalid")
        return when (code) {
            "open" -> MarketSessionState.OPEN
            "close" -> MarketSessionState.CLOSED
            else -> MarketSessionState.UNKNOWN
        }
    }

    private companion object {
        const val MARKET_STATE_VARIABLE: String = "v_marketStat"
        const val NDX_VARIABLE: String = "v_usNDX"
        const val NDX_TYPE: String = "200"
        const val NDX_SYMBOL: String = ".NDX"
        const val USD: String = "USD"
        const val US_STATE_PREFIX: String = "US_"
        const val TYPE_INDEX: Int = 0
        const val SYMBOL_INDEX: Int = 2
        const val POINT_INDEX: Int = 3
        const val PREVIOUS_CLOSE_INDEX: Int = 4
        const val QUOTE_TIME_INDEX: Int = 30
        const val CHANGE_INDEX: Int = 31
        const val CHANGE_RATE_INDEX: Int = 32
        const val CURRENCY_INDEX: Int = 35
        val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
        val NDX_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
    }
}

public class TencentUsdCnyQuoteParser public constructor(
    private val source: MarketSourceId,
) : JsonPayloadParser<UsdCnyQuote> {
    override fun parse(body: String, fetchedAt: Instant): UsdCnyQuote {
        val fields = DelimitedMarketPayload.requiredVariable(body, VARIABLE).split('~')
        DelimitedMarketPayload.requireIdentity(DelimitedMarketPayload.requiredField(fields, TYPE_INDEX), TYPE)
        DelimitedMarketPayload.requireIdentity(DelimitedMarketPayload.requiredField(fields, SYMBOL_INDEX), SYMBOL)

        val rate = DelimitedMarketPayload.requiredDecimal(fields, RATE_INDEX)
        val previousClose = DelimitedMarketPayload.requiredDecimal(fields, PREVIOUS_CLOSE_INDEX)
        val change = DelimitedMarketPayload.requiredDecimal(fields, CHANGE_INDEX)
        DelimitedMarketPayload.requireExactChange(rate, previousClose, change)

        return UsdCnyQuote(
            rate = rate,
            changeRate = DelimitedMarketPayload.percentToFraction(
                DelimitedMarketPayload.requiredDecimal(fields, CHANGE_RATE_INDEX),
            ),
            quoteTime = parseLocalInstant(
                DelimitedMarketPayload.requiredField(fields, QUOTE_TIME_INDEX),
                FX_TIME_FORMATTER,
                SHANGHAI,
            ),
            source = source,
        )
    }

    private companion object {
        const val VARIABLE: String = "v_whUSDCNY"
        const val TYPE: String = "310"
        const val SYMBOL: String = "USDCNY"
        const val TYPE_INDEX: Int = 0
        const val SYMBOL_INDEX: Int = 2
        const val RATE_INDEX: Int = 3
        const val QUOTE_TIME_INDEX: Int = 5
        const val PREVIOUS_CLOSE_INDEX: Int = 6
        const val CHANGE_INDEX: Int = 12
        const val CHANGE_RATE_INDEX: Int = 13
        val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
        val FX_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
    }
}

private fun parseLocalInstant(text: String, formatter: DateTimeFormatter, zone: ZoneId): Instant {
    val local = try {
        LocalDateTime.parse(text, formatter)
    } catch (_: DateTimeParseException) {
        fail(ProviderFailureKind.INVALID_VALUE, "Provider timestamp is invalid")
    }
    val offsets = zone.rules.getValidOffsets(local)
    if (offsets.size != 1) fail(ProviderFailureKind.INVALID_VALUE, "Provider timestamp is ambiguous")
    return local.toInstant(offsets.single())
}
