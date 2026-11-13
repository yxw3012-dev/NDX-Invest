package com.ndxinvest.providers.config

import com.ndxinvest.market.model.MarketSourceId
import com.ndxinvest.market.model.NdxDaily
import com.ndxinvest.network.ProviderRequest
import com.ndxinvest.network.ProviderTransport
import com.ndxinvest.providers.adapter.NASDAQ_HISTORY_MAX_RECORDS
import com.ndxinvest.providers.adapter.NasdaqNdxHistoryPageParser
import com.ndxinvest.providers.contract.DataProvider
import com.ndxinvest.providers.contract.HttpJsonProvider
import com.ndxinvest.providers.contract.ProviderFailure
import com.ndxinvest.providers.contract.ProviderFailureKind
import com.ndxinvest.providers.contract.ProviderId
import com.ndxinvest.providers.contract.ProviderResult
import java.time.Instant
import java.time.LocalDate
import kotlin.math.min

public object NasdaqNdxHistoryBinding {
    public val providerId: ProviderId = ProviderId("nasdaq")

    public fun request(
        fromDate: LocalDate,
        toDate: LocalDate,
        pageSize: Int = MAX_PAGE_SIZE,
        offset: Int = 0,
    ): ProviderRequest {
        requireValidRequest(fromDate, toDate, pageSize, offset)
        return ProviderRequest(
            endpoint = ENDPOINT,
            headers = mapOf("User-Agent" to NASDAQ_USER_AGENT),
            query = mapOf(
                "assetclass" to "index",
                "fromdate" to fromDate.toString(),
                "todate" to toDate.toString(),
                "limit" to pageSize.toString(),
                "offset" to offset.toString(),
            ),
        )
    }

    public fun provider(
        transport: ProviderTransport,
        fromDate: LocalDate,
        toDate: LocalDate,
        pageSize: Int = MAX_PAGE_SIZE,
    ): DataProvider<List<NdxDaily>> {
        requireValidRequest(fromDate, toDate, pageSize, offset = 0)
        return NasdaqNdxHistoryProvider(transport, fromDate, toDate, pageSize)
    }

    private fun requireValidRequest(fromDate: LocalDate, toDate: LocalDate, pageSize: Int, offset: Int) {
        require(!fromDate.isAfter(toDate)) { "Nasdaq history range must not be reversed" }
        require(pageSize in 1..MAX_PAGE_SIZE) { "Nasdaq history page size must be between 1 and $MAX_PAGE_SIZE" }
        require(offset >= 0) { "Nasdaq history offset must not be negative" }
    }

    private const val ENDPOINT: String = "https://api.nasdaq.com/api/quote/NDX/historical"
    private const val NASDAQ_USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "NDX-Invest/0.1 Mobile Safari/537.36"
    private const val MAX_PAGE_SIZE: Int = 5_000

    private class NasdaqNdxHistoryProvider(
        private val transport: ProviderTransport,
        private val fromDate: LocalDate,
        private val toDate: LocalDate,
        private val pageSize: Int,
    ) : DataProvider<List<NdxDaily>> {
        override val id: ProviderId = providerId

        override suspend fun fetch(): ProviderResult<List<NdxDaily>> {
            val records = mutableListOf<NdxDaily>()
            val dates = mutableSetOf<LocalDate>()
            var expectedTotal: Int? = null
            var offset = 0
            var latestFetchedAt: Instant? = null

            while (expectedTotal == null || offset < expectedTotal) {
                val pageProvider = HttpJsonProvider(
                    id = id,
                    request = request(fromDate, toDate, pageSize, offset),
                    transport = transport,
                    parser = NasdaqNdxHistoryPageParser(
                        source = MarketSourceId(id.value),
                        fromDate = fromDate,
                        toDate = toDate,
                    ),
                )
                when (val result = pageProvider.fetch()) {
                    is ProviderResult.Failure -> return result
                    is ProviderResult.Success -> {
                        val page = result.value
                        val declaredTotal = expectedTotal
                        if (declaredTotal != null && declaredTotal != page.totalRecords) {
                            return invalidPage("Nasdaq history total changed between pages")
                        }
                        expectedTotal = page.totalRecords
                        val expectedPageRecords = min(pageSize, page.totalRecords - offset)
                        if (page.records.size != expectedPageRecords) {
                            return invalidPage("Nasdaq history page size conflicts with its declared total")
                        }
                        if (page.records.any { !dates.add(it.date) }) {
                            return invalidPage(
                                "Nasdaq history pages contain duplicate dates",
                                ProviderFailureKind.CONFLICTING_DUPLICATE,
                            )
                        }
                        records += page.records
                        offset += page.records.size
                        latestFetchedAt = latestFetchedAt
                            ?.takeIf { it.isAfter(result.fetchedAt) }
                            ?: result.fetchedAt
                    }
                }
            }

            if (records.size != expectedTotal) {
                return invalidPage("Nasdaq history pages did not produce the declared total")
            }
            return ProviderResult.Success(
                value = records.sortedBy(NdxDaily::date),
                providerId = id,
                fetchedAt = requireNotNull(latestFetchedAt),
            )
        }

        private fun invalidPage(
            detail: String,
            kind: ProviderFailureKind = ProviderFailureKind.INVALID_VALUE,
        ): ProviderResult.Failure = ProviderResult.Failure(
            listOf(
                ProviderFailure(
                    providerId = id,
                    kind = kind,
                    retryable = false,
                    safeDetail = detail,
                ),
            ),
        )
    }
}
