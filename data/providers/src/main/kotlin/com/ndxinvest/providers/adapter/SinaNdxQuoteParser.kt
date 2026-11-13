package com.ndxinvest.providers.adapter

import com.ndxinvest.market.model.MarketSessionState
import com.ndxinvest.market.model.MarketSourceId
import com.ndxinvest.market.model.NdxQuote
import com.ndxinvest.providers.contract.JsonPayloadParser
import com.ndxinvest.providers.contract.ProviderFailureKind
import com.ndxinvest.providers.json.fail
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

public class SinaNdxQuoteParser public constructor(
    private val source: MarketSourceId,
) : JsonPayloadParser<NdxQuote> {
    override fun parse(body: String, fetchedAt: Instant): NdxQuote {
        val fields = DelimitedMarketPayload.requiredVariable(body, VARIABLE).split(',')
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
            quoteTime = parseQuoteTime(
                DelimitedMarketPayload.requiredField(fields, QUOTE_TIME_INDEX),
                DelimitedMarketPayload.requiredField(fields, YEAR_INDEX),
            ),
            marketState = MarketSessionState.UNKNOWN,
            source = source,
        )
    }

    private fun parseQuoteTime(text: String, year: String): Instant {
        if (!YEAR.matches(year)) fail(ProviderFailureKind.INVALID_VALUE, "Sina quote year is invalid")
        val match = QUOTE_TIME.matchEntire(text)
            ?: fail(ProviderFailureKind.INVALID_VALUE, "Sina quote timestamp is invalid")
        val offset = when (match.groupValues[2]) {
            "EDT" -> ZoneOffset.ofHours(-4)
            "EST" -> ZoneOffset.ofHours(-5)
            else -> fail(ProviderFailureKind.INVALID_VALUE, "Sina quote timezone is invalid")
        }
        val local = try {
            LocalDateTime.parse("${match.groupValues[1]} $year", TIME_FORMATTER)
        } catch (_: DateTimeParseException) {
            fail(ProviderFailureKind.INVALID_VALUE, "Sina quote timestamp is invalid")
        }
        return local.toInstant(offset)
    }

    private companion object {
        const val VARIABLE: String = "hq_str_gb_\$ndx"
        const val POINT_INDEX: Int = 1
        const val CHANGE_RATE_INDEX: Int = 2
        const val CHANGE_INDEX: Int = 4
        const val QUOTE_TIME_INDEX: Int = 25
        const val PREVIOUS_CLOSE_INDEX: Int = 26
        const val YEAR_INDEX: Int = 29
        val YEAR: Regex = Regex("[0-9]{4}")
        val QUOTE_TIME: Regex = Regex("([A-Z][a-z]{2} [0-9]{1,2} [0-9]{2}:[0-9]{2}[AP]M) (EDT|EST|[A-Z]{3})")
        val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("MMM d hh:mma uuuu", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.STRICT)
    }
}
