package com.ndxinvest.providers.config

import com.ndxinvest.market.model.NdxDaily
import com.ndxinvest.network.ProviderRequest
import com.ndxinvest.network.ProviderTransport
import com.ndxinvest.network.TransportResult
import com.ndxinvest.providers.contract.ProviderFailureKind
import com.ndxinvest.providers.contract.ProviderId
import com.ndxinvest.providers.contract.ProviderResult
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class NasdaqNdxHistoryBindingTest {
    @Test
    fun `request targets official NDX index history with an explicit bounded page`() {
        val request = NasdaqNdxHistoryBinding.request(FROM, TO, pageSize = 2, offset = 2)

        assertEquals(ProviderId("nasdaq"), NasdaqNdxHistoryBinding.providerId)
        assertEquals("https://api.nasdaq.com/api/quote/NDX/historical", request.endpoint)
        assertEquals(
            mapOf(
                "assetclass" to "index",
                "fromdate" to "2025-09-08",
                "todate" to "2025-09-11",
                "limit" to "2",
                "offset" to "2",
            ),
            request.query,
        )
        assertEquals(
            mapOf(
                "User-Agent" to
                    "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "NDX-Invest/0.1 Mobile Safari/537.36",
            ),
            request.headers,
        )
        assertNull(request.credential)
    }

    @Test
    fun `provider fetches every page then returns one sorted atomic history`() = runTest {
        val transport = SequenceTransport(
            listOf(
                response("nasdaq_ndx_history_page_0.json", FIRST_FETCH),
                response("nasdaq_ndx_history_page_2.json", SECOND_FETCH),
            ),
        )

        val result = assertIs<ProviderResult.Success<*>>(
            NasdaqNdxHistoryBinding.provider(transport, FROM, TO, pageSize = 2).fetch(),
        )

        val history = assertIs<List<*>>(result.value).map { assertIs<NdxDaily>(it) }
        assertEquals(
            listOf("2025-09-08", "2025-09-09", "2025-09-10", "2025-09-11"),
            history.map { it.date.toString() },
        )
        assertEquals(ProviderId("nasdaq"), result.providerId)
        assertEquals(SECOND_FETCH, result.fetchedAt)
        assertEquals(listOf("0", "2"), transport.requests.map { it.query.getValue("offset") })
    }

    @Test
    fun `provider rejects changing totals and never returns a partial history`() = runTest {
        val changedTotal = fixture("nasdaq_ndx_history_page_2.json")
            .replace("\"totalRecords\":4", "\"totalRecords\":5")
        val transport = SequenceTransport(
            listOf(
                response("nasdaq_ndx_history_page_0.json", FIRST_FETCH),
                TransportResult.Response(200, changedTotal, SECOND_FETCH),
            ),
        )

        val result = assertIs<ProviderResult.Failure>(
            NasdaqNdxHistoryBinding.provider(transport, FROM, TO, pageSize = 2).fetch(),
        )

        assertEquals(listOf(ProviderFailureKind.INVALID_VALUE), result.failures.map { it.kind })
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `provider rejects dates repeated across otherwise valid pages`() = runTest {
        val repeatedDates = fixture("nasdaq_ndx_history_page_2.json")
            .replace("09/09/2025", "09/11/2025")
            .replace("09/08/2025", "09/10/2025")
        val transport = SequenceTransport(
            listOf(
                response("nasdaq_ndx_history_page_0.json", FIRST_FETCH),
                TransportResult.Response(200, repeatedDates, SECOND_FETCH),
            ),
        )

        val result = assertIs<ProviderResult.Failure>(
            NasdaqNdxHistoryBinding.provider(transport, FROM, TO, pageSize = 2).fetch(),
        )

        assertEquals(listOf(ProviderFailureKind.CONFLICTING_DUPLICATE), result.failures.map { it.kind })
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `provider propagates a later page failure instead of publishing the first page`() = runTest {
        val transport = SequenceTransport(
            listOf(
                response("nasdaq_ndx_history_page_0.json", FIRST_FETCH),
                TransportResult.NetworkError(),
            ),
        )

        val result = assertIs<ProviderResult.Failure>(
            NasdaqNdxHistoryBinding.provider(transport, FROM, TO, pageSize = 2).fetch(),
        )

        assertEquals(listOf(ProviderFailureKind.NETWORK), result.failures.map { it.kind })
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `provider rejects a short non-final page to keep request count bounded`() = runTest {
        val shortFirstPage = fixture("nasdaq_ndx_history_page_0.json")
            .replace(
                ",{\"date\":\"09/10/2025\",\"close\":\"23,849.27\",\"volume\":\"--\",\"open\":\"23,931.32\",\"high\":\"23,958.54\",\"low\":\"23,760.09\"}",
                "",
            )
        val transport = SequenceTransport(listOf(TransportResult.Response(200, shortFirstPage, FIRST_FETCH)))

        val result = assertIs<ProviderResult.Failure>(
            NasdaqNdxHistoryBinding.provider(transport, FROM, TO, pageSize = 2).fetch(),
        )

        assertEquals(listOf(ProviderFailureKind.INVALID_VALUE), result.failures.map { it.kind })
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `binding rejects reversed ranges unsafe page sizes and negative offsets`() {
        assertFailsWith<IllegalArgumentException> { NasdaqNdxHistoryBinding.request(TO, FROM, 2, 0) }
        assertFailsWith<IllegalArgumentException> { NasdaqNdxHistoryBinding.request(FROM, TO, 0, 0) }
        assertFailsWith<IllegalArgumentException> { NasdaqNdxHistoryBinding.request(FROM, TO, 5001, 0) }
        assertFailsWith<IllegalArgumentException> { NasdaqNdxHistoryBinding.request(FROM, TO, 2, -1) }
    }

    private class SequenceTransport(responses: List<TransportResult>) : ProviderTransport {
        private val responses: ArrayDeque<TransportResult> = ArrayDeque(responses)
        val requests: MutableList<ProviderRequest> = mutableListOf()

        override suspend fun execute(request: ProviderRequest): TransportResult {
            requests += request
            return responses.removeFirst()
        }
    }

    private fun response(name: String, at: Instant): TransportResult.Response =
        TransportResult.Response(200, fixture(name), at)

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/fixtures/$name")).readText()

    private companion object {
        val FROM: LocalDate = LocalDate.parse("2025-09-08")
        val TO: LocalDate = LocalDate.parse("2025-09-11")
        val FIRST_FETCH: Instant = Instant.parse("2026-09-13T18:44:30Z")
        val SECOND_FETCH: Instant = Instant.parse("2026-09-13T18:44:33Z")
    }
}
