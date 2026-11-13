package com.ndxinvest.providers.persistence

import com.ndxinvest.market.model.MarketSessionState
import com.ndxinvest.market.model.MarketSourceId
import com.ndxinvest.market.model.NdxDaily
import com.ndxinvest.market.model.NdxQuote
import com.ndxinvest.market.model.UsdCnyQuote
import com.ndxinvest.providers.contract.ProviderId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

public object ProviderCacheCodecs {
    public val ndxQuote: ProviderCacheCodec<NdxQuote> = NdxQuoteCodec
    public val usdCnyQuote: ProviderCacheCodec<UsdCnyQuote> = UsdCnyQuoteCodec
    public val ndxHistory: ProviderCacheCodec<List<NdxDaily>> = NdxHistoryCodec
}

private object NdxQuoteCodec : ProviderCacheCodec<NdxQuote> {
    override val schemaVersion: Int = 1

    override fun encode(value: NdxQuote): String = buildJsonObject {
        put("point", value.point.exact())
        put("change", value.change.exact())
        put("changeRate", value.changeRate.exact())
        put("quoteTime", value.quoteTime.toString())
        put("marketState", value.marketState.name)
        put("source", value.source.value)
    }.toString()

    override fun decode(payload: String): NdxQuote = payload.objectValue().let { json ->
        NdxQuote(
            point = json.decimal("point"),
            change = json.decimal("change"),
            changeRate = json.decimal("changeRate"),
            quoteTime = Instant.parse(json.string("quoteTime")),
            marketState = enumValueOf(json.string("marketState")),
            source = MarketSourceId(json.string("source")),
        )
    }

    override fun sourceOf(value: NdxQuote): ProviderId = ProviderId(value.source.value)
}

private object UsdCnyQuoteCodec : ProviderCacheCodec<UsdCnyQuote> {
    override val schemaVersion: Int = 1

    override fun encode(value: UsdCnyQuote): String = buildJsonObject {
        put("rate", value.rate.exact())
        put("changeRate", value.changeRate?.let { JsonPrimitive(it.exact()) } ?: JsonNull)
        put("quoteTime", value.quoteTime.toString())
        put("source", value.source.value)
    }.toString()

    override fun decode(payload: String): UsdCnyQuote = payload.objectValue().let { json ->
        UsdCnyQuote(
            rate = json.decimal("rate"),
            changeRate = json.optionalDecimal("changeRate"),
            quoteTime = Instant.parse(json.string("quoteTime")),
            source = MarketSourceId(json.string("source")),
        )
    }

    override fun sourceOf(value: UsdCnyQuote): ProviderId = ProviderId(value.source.value)
}

private object NdxHistoryCodec : ProviderCacheCodec<List<NdxDaily>> {
    override val schemaVersion: Int = 1

    override fun encode(value: List<NdxDaily>): String = buildJsonObject {
        requireSingleSource(value.map { it.source.value }, "NDX history")
        put("records", buildJsonArray {
            value.forEach { daily ->
                add(
                    buildJsonObject {
                        put("date", daily.date.toString())
                        put("open", daily.open.exact())
                        put("high", daily.high.exact())
                        put("low", daily.low.exact())
                        put("close", daily.close.exact())
                        put("source", daily.source.value)
                    },
                )
            }
        })
    }.toString()

    override fun decode(payload: String): List<NdxDaily> = payload.objectValue().array("records").map { element ->
        val json = element.jsonObject
        NdxDaily(
            date = LocalDate.parse(json.string("date")),
            open = json.decimal("open"),
            high = json.decimal("high"),
            low = json.decimal("low"),
            close = json.decimal("close"),
            source = MarketSourceId(json.string("source")),
        )
    }.also { values -> requireSingleSource(values.map { it.source.value }, "NDX history") }

    override fun sourceOf(value: List<NdxDaily>): ProviderId =
        ProviderId(requireSingleSource(value.map { it.source.value }, "NDX history"))
}

private fun String.objectValue(): JsonObject = CACHE_JSON.parseToJsonElement(this).jsonObject

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.decimal(name: String): BigDecimal = BigDecimal(string(name))

private fun JsonObject.optionalDecimal(name: String): BigDecimal? = getValue(name)
    .takeUnless { it is JsonNull }
    ?.jsonPrimitive
    ?.content
    ?.let(::BigDecimal)

private fun JsonObject.array(name: String): JsonArray = getValue(name).jsonArray

private fun BigDecimal.exact(): String = toPlainString()

private fun requireSingleSource(sources: List<String>, label: String): String {
    require(sources.isNotEmpty()) { "$label cache must not be empty" }
    require(sources.distinct().size == 1) { "$label cache must have exactly one source" }
    return sources.first()
}

private val CACHE_JSON: Json = Json { ignoreUnknownKeys = false }
