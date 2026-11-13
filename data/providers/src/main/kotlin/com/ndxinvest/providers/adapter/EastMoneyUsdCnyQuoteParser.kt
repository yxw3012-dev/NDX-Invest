package com.ndxinvest.providers.adapter

import com.ndxinvest.market.model.MarketSourceId
import com.ndxinvest.market.model.UsdCnyQuote
import com.ndxinvest.providers.contract.JsonPayloadParser
import com.ndxinvest.providers.contract.ProviderFailureKind
import com.ndxinvest.providers.json.InstantEncoding
import com.ndxinvest.providers.json.StrictJsonReader
import com.ndxinvest.providers.json.fail
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

public class EastMoneyUsdCnyQuoteParser public constructor(
    private val source: MarketSourceId,
) : JsonPayloadParser<UsdCnyQuote> {
    override fun parse(body: String, fetchedAt: Instant): UsdCnyQuote {
        val root = StrictJsonReader.parse(body)
        if (root.requiredLong(RESPONSE_CODE) != SUCCESS_CODE) {
            fail(ProviderFailureKind.INVALID_VALUE, "EastMoney USD/CNY response is not successful")
        }
        if (root.requiredString(SYMBOL) != EXPECTED_SYMBOL) {
            fail(ProviderFailureKind.INVALID_VALUE, "EastMoney USD/CNY symbol is invalid")
        }
        if (root.requiredString(NAME) != EXPECTED_NAME) {
            fail(ProviderFailureKind.INVALID_VALUE, "EastMoney USD/CNY name is invalid")
        }
        if (root.requiredLong(PRICE_PRECISION) != EXPECTED_PRICE_PRECISION.toLong()) {
            fail(ProviderFailureKind.INVALID_VALUE, "EastMoney USD/CNY precision changed")
        }

        val rate = root.requiredScaledDecimal(RATE, EXPECTED_PRICE_PRECISION)
        val previousClose = root.requiredScaledDecimal(PREVIOUS_CLOSE, EXPECTED_PRICE_PRECISION)
        val change = root.requiredScaledDecimal(CHANGE, EXPECTED_PRICE_PRECISION)
        if (rate.signum() <= 0 || previousClose.signum() <= 0) {
            fail(ProviderFailureKind.INVALID_VALUE, "EastMoney USD/CNY rate must be positive")
        }
        if (rate.subtract(previousClose).compareTo(change) != 0) {
            fail(ProviderFailureKind.INVALID_VALUE, "EastMoney USD/CNY change is inconsistent")
        }

        val changeRate = root.requiredScaledDecimal(CHANGE_RATE, CHANGE_RATE_SCALE)
        val calculatedChangeRate = change.divide(previousClose, CHANGE_RATE_SCALE, RoundingMode.HALF_UP)
        if (calculatedChangeRate.compareTo(changeRate) != 0) {
            fail(ProviderFailureKind.INVALID_VALUE, "EastMoney USD/CNY change rate is inconsistent")
        }

        val quoteTime = root.requiredInstant(QUOTE_TIME, InstantEncoding.EPOCH_SECONDS)
        if (!quoteTime.isAfter(Instant.EPOCH)) {
            fail(ProviderFailureKind.INVALID_VALUE, "EastMoney USD/CNY timestamp is invalid")
        }
        return try {
            UsdCnyQuote(
                rate = rate,
                changeRate = changeRate,
                quoteTime = quoteTime,
                source = source,
            )
        } catch (_: IllegalArgumentException) {
            fail(ProviderFailureKind.INVALID_VALUE, "EastMoney USD/CNY values violate domain constraints")
        }
    }

    private companion object {
        const val RESPONSE_CODE: String = "rc"
        const val SUCCESS_CODE: Long = 0L
        const val SYMBOL: String = "data.f57"
        const val NAME: String = "data.f58"
        const val RATE: String = "data.f43"
        const val PRICE_PRECISION: String = "data.f59"
        const val PREVIOUS_CLOSE: String = "data.f60"
        const val QUOTE_TIME: String = "data.f86"
        const val CHANGE: String = "data.f169"
        const val CHANGE_RATE: String = "data.f170"
        const val EXPECTED_SYMBOL: String = "USDCNYI"
        const val EXPECTED_NAME: String = "美元人民币混合"
        const val EXPECTED_PRICE_PRECISION: Int = 4
        const val CHANGE_RATE_SCALE: Int = 4
    }
}

private fun StrictJsonReader.requiredScaledDecimal(path: String, scale: Int): BigDecimal =
    BigDecimal.valueOf(requiredLong(path)).movePointLeft(scale)
