package com.ndxinvest.providers.adapter

import com.ndxinvest.market.model.MarketSessionState
import com.ndxinvest.market.model.MarketSourceId
import com.ndxinvest.market.model.NdxDaily
import com.ndxinvest.market.model.NdxQuote
import com.ndxinvest.market.model.UsdCnyQuote
import com.ndxinvest.providers.contract.JsonPayloadParser
import com.ndxinvest.providers.contract.ProviderFailureKind
import com.ndxinvest.providers.json.InstantEncoding
import com.ndxinvest.providers.json.StrictJsonReader
import com.ndxinvest.providers.json.fail
import java.time.Instant

public data class NdxQuoteFieldMapping public constructor(
    public val point: String,
    public val change: String,
    public val changeRate: String,
    public val quoteTime: String,
    public val marketState: String,
    public val instantEncoding: InstantEncoding = InstantEncoding.ISO_8601,
    public val marketStateValues: Map<String, MarketSessionState> = mapOf(
        "PRE_MARKET" to MarketSessionState.PRE_MARKET,
        "OPEN" to MarketSessionState.OPEN,
        "AFTER_HOURS" to MarketSessionState.AFTER_HOURS,
        "CLOSED" to MarketSessionState.CLOSED,
    ),
) {
    init {
        require(listOf(point, change, changeRate, quoteTime, marketState).none { it.isBlank() }) {
            "NDX quote field mappings must not be blank"
        }
    }
}

public class NdxQuoteJsonParser public constructor(
    private val source: MarketSourceId,
    private val mapping: NdxQuoteFieldMapping,
) : JsonPayloadParser<NdxQuote> {
    override fun parse(body: String, fetchedAt: Instant): NdxQuote {
        val root = StrictJsonReader.parse(body)
        val stateValue = root.requiredString(mapping.marketState)
        return NdxQuote(
            point = root.requiredDecimal(mapping.point),
            change = root.requiredDecimal(mapping.change),
            changeRate = root.requiredDecimal(mapping.changeRate),
            quoteTime = root.requiredInstant(mapping.quoteTime, mapping.instantEncoding),
            marketState = mapping.marketStateValues[stateValue] ?: MarketSessionState.UNKNOWN,
            source = source,
        )
    }
}

public data class UsdCnyFieldMapping public constructor(
    public val rate: String,
    public val changeRate: String,
    public val quoteTime: String,
    public val instantEncoding: InstantEncoding = InstantEncoding.ISO_8601,
) {
    init {
        require(listOf(rate, changeRate, quoteTime).none { it.isBlank() }) {
            "USD/CNY field mappings must not be blank"
        }
    }
}

public class UsdCnyQuoteJsonParser public constructor(
    private val source: MarketSourceId,
    private val mapping: UsdCnyFieldMapping,
) : JsonPayloadParser<UsdCnyQuote> {
    override fun parse(body: String, fetchedAt: Instant): UsdCnyQuote {
        val root = StrictJsonReader.parse(body)
        return UsdCnyQuote(
            rate = root.requiredDecimal(mapping.rate),
            changeRate = root.optionalDecimal(mapping.changeRate),
            quoteTime = root.requiredInstant(mapping.quoteTime, mapping.instantEncoding),
            source = source,
        )
    }
}

public data class NdxHistoryFieldMapping public constructor(
    public val records: String,
    public val date: String,
    public val open: String,
    public val high: String,
    public val low: String,
    public val close: String,
    public val datePattern: String = "uuuu-MM-dd",
) {
    init {
        require(listOf(records, date, open, high, low, close).none { it.isBlank() }) {
            "NDX history field mappings must not be blank"
        }
    }
}

public class NdxHistoryJsonParser public constructor(
    private val source: MarketSourceId,
    private val mapping: NdxHistoryFieldMapping,
) : JsonPayloadParser<List<NdxDaily>> {
    override fun parse(body: String, fetchedAt: Instant): List<NdxDaily> {
        val root = StrictJsonReader.parse(body)
        val values = root.requiredArray(mapping.records).map { element ->
            val record = StrictJsonReader.from(element)
            NdxDaily(
                date = record.requiredDate(mapping.date, mapping.datePattern),
                open = record.requiredDecimal(mapping.open),
                high = record.requiredDecimal(mapping.high),
                low = record.requiredDecimal(mapping.low),
                close = record.requiredDecimal(mapping.close),
                source = source,
            )
        }
        if (values.isEmpty()) fail(ProviderFailureKind.INVALID_VALUE, "NDX history response contains no records")
        if (values.map(NdxDaily::date).distinct().size != values.size) {
            fail(ProviderFailureKind.CONFLICTING_DUPLICATE, "NDX history contains duplicate dates")
        }
        return values.sortedBy(NdxDaily::date)
    }
}
