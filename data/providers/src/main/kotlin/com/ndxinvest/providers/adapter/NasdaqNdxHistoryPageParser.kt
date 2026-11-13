package com.ndxinvest.providers.adapter

import com.ndxinvest.market.model.MarketSourceId
import com.ndxinvest.market.model.NdxDaily
import com.ndxinvest.providers.contract.JsonPayloadParser
import com.ndxinvest.providers.contract.ProviderFailureKind
import com.ndxinvest.providers.json.StrictJsonReader
import com.ndxinvest.providers.json.fail
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

internal data class NasdaqNdxHistoryPage(
    val totalRecords: Int,
    val records: List<NdxDaily>,
)

internal class NasdaqNdxHistoryPageParser(
    private val source: MarketSourceId,
    private val fromDate: LocalDate,
    private val toDate: LocalDate,
) : JsonPayloadParser<NasdaqNdxHistoryPage> {
    init {
        require(!fromDate.isAfter(toDate)) { "Nasdaq history range must not be reversed" }
    }

    override fun parse(body: String, fetchedAt: Instant): NasdaqNdxHistoryPage {
        val root = StrictJsonReader.parse(body)
        if (root.requiredLong(STATUS_CODE) != SUCCESS_CODE) {
            fail(ProviderFailureKind.INVALID_VALUE, "Nasdaq response status is not successful")
        }
        if (root.requiredString(SYMBOL) != NDX_SYMBOL) {
            fail(ProviderFailureKind.INVALID_VALUE, "Nasdaq history instrument identity is invalid")
        }
        val totalRecords = root.requiredLong(TOTAL_RECORDS)
        if (totalRecords !in 1L..NASDAQ_HISTORY_MAX_RECORDS.toLong()) {
            fail(ProviderFailureKind.INVALID_VALUE, "Nasdaq history record count is outside the safe range")
        }
        val records = root.requiredArray(RECORDS).map { element ->
            val record = root.child(element)
            val date = parseDate(record.requiredString(DATE))
            if (date.isBefore(fromDate) || date.isAfter(toDate)) {
                fail(ProviderFailureKind.INVALID_VALUE, "Nasdaq history date is outside the requested range")
            }
            val open = record.requiredNasdaqDecimal(OPEN)
            val high = record.requiredNasdaqDecimal(HIGH)
            val low = record.requiredNasdaqDecimal(LOW)
            val close = record.requiredNasdaqDecimal(CLOSE)
            try {
                NdxDaily(
                    date = date,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    source = source,
                )
            } catch (_: IllegalArgumentException) {
                fail(ProviderFailureKind.INVALID_VALUE, "Nasdaq history OHLC values are inconsistent")
            }
        }
        if (records.isEmpty()) fail(ProviderFailureKind.INVALID_VALUE, "Nasdaq history page contains no records")
        if (records.size > totalRecords) {
            fail(ProviderFailureKind.INVALID_VALUE, "Nasdaq history page exceeds its declared record count")
        }
        if (records.map(NdxDaily::date).distinct().size != records.size) {
            fail(ProviderFailureKind.CONFLICTING_DUPLICATE, "Nasdaq history page contains duplicate dates")
        }
        return NasdaqNdxHistoryPage(totalRecords.toInt(), records.sortedBy(NdxDaily::date))
    }

    private fun parseDate(text: String): LocalDate = try {
        LocalDate.parse(text, DATE_FORMATTER)
    } catch (_: DateTimeParseException) {
        fail(ProviderFailureKind.INVALID_VALUE, "Nasdaq history date is invalid")
    }

    private companion object {
        const val STATUS_CODE: String = "status.rCode"
        const val SUCCESS_CODE: Long = 200L
        const val SYMBOL: String = "data.symbol"
        const val NDX_SYMBOL: String = "NDX"
        const val TOTAL_RECORDS: String = "data.totalRecords"
        const val RECORDS: String = "data.tradesTable.rows"
        const val DATE: String = "date"
        const val OPEN: String = "open"
        const val HIGH: String = "high"
        const val LOW: String = "low"
        const val CLOSE: String = "close"
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("MM/dd/uuuu")
            .withResolverStyle(ResolverStyle.STRICT)
    }
}

private fun StrictJsonReader.requiredNasdaqDecimal(path: String): BigDecimal {
    val text = requiredString(path)
    if (!NASDAQ_DECIMAL.matches(text)) {
        fail(ProviderFailureKind.INVALID_VALUE, "Nasdaq history price is not an exact grouped decimal")
    }
    return try {
        BigDecimal(text.replace(",", ""))
    } catch (_: NumberFormatException) {
        fail(ProviderFailureKind.INVALID_VALUE, "Nasdaq history price is not an exact decimal")
    }
}

internal const val NASDAQ_HISTORY_MAX_RECORDS: Int = 50_000

private val NASDAQ_DECIMAL: Regex = Regex(
    "(?:0|[1-9][0-9]*|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]+)?",
)
