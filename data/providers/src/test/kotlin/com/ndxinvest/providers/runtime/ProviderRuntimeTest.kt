package com.ndxinvest.providers.runtime

import com.ndxinvest.market.model.FreshnessState
import com.ndxinvest.network.ProviderRequest
import com.ndxinvest.network.ProviderTransport
import com.ndxinvest.network.TransportResult
import com.ndxinvest.providers.contract.DataProvider
import com.ndxinvest.providers.contract.DefaultProviderOrder
import com.ndxinvest.providers.contract.HttpJsonProvider
import com.ndxinvest.providers.contract.JsonPayloadParser
import com.ndxinvest.providers.contract.ProviderFailure
import com.ndxinvest.providers.contract.ProviderFailureKind
import com.ndxinvest.providers.contract.ProviderId
import com.ndxinvest.providers.contract.ProviderResult
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProviderRuntimeTest {
    @Test
    fun `transport timeout rate limit and non-2xx are normalized without response-body leakage`() = runTest {
        val timeout = httpProvider(TransportResult.Timeout("secret-in-runtime-error")).fetch()
        val network = httpProvider(TransportResult.NetworkError("secret-in-runtime-error")).fetch()
        val credential = httpProvider(TransportResult.CredentialUnavailable).fetch()
        val oversized = httpProvider(TransportResult.ResponseTooLarge).fetch()
        val rateLimit = httpProvider(TransportResult.Response(429, "sensitive-body", NOW)).fetch()
        val serverError = httpProvider(TransportResult.Response(503, "sensitive-body", NOW)).fetch()

        assertEquals(ProviderFailureKind.TIMEOUT, assertIs<ProviderResult.Failure>(timeout).failures.single().kind)
        assertFalse(assertIs<ProviderResult.Failure>(timeout).failures.single().safeDetail.contains("secret-in-runtime-error"))
        assertFalse(assertIs<ProviderResult.Failure>(network).failures.single().safeDetail.contains("secret-in-runtime-error"))
        assertEquals(
            ProviderFailureKind.CREDENTIAL_UNAVAILABLE,
            assertIs<ProviderResult.Failure>(credential).failures.single().kind,
        )
        assertEquals(
            ProviderFailureKind.RESPONSE_TOO_LARGE,
            assertIs<ProviderResult.Failure>(oversized).failures.single().kind,
        )
        assertEquals(ProviderFailureKind.RATE_LIMITED, assertIs<ProviderResult.Failure>(rateLimit).failures.single().kind)
        val httpFailure = assertIs<ProviderResult.Failure>(serverError).failures.single()
        assertEquals(ProviderFailureKind.HTTP_ERROR, httpFailure.kind)
        assertEquals(503, httpFailure.statusCode)
        assertFalse(httpFailure.safeDetail.contains("sensitive-body"))
    }

    @Test
    fun `thrown transport exception becomes a safe retryable network failure`() = runTest {
        val provider = HttpJsonProvider(
            id = ProviderId("provider"),
            request = ProviderRequest("https://example.invalid/data"),
            transport = ProviderTransport { error("secret-runtime-message") },
            parser = JsonPayloadParser<String> { body, _ -> body },
        )

        val failure = assertIs<ProviderResult.Failure>(provider.fetch()).failures.single()

        assertEquals(ProviderFailureKind.NETWORK, failure.kind)
        assertTrue(failure.retryable)
        assertFalse(failure.safeDetail.contains("secret-runtime-message"))
    }

    @Test
    fun `transport cancellation is propagated instead of converted into a network failure`() = runTest {
        val provider = HttpJsonProvider(
            id = ProviderId("provider"),
            request = ProviderRequest("https://example.invalid/data"),
            transport = ProviderTransport { throw CancellationException("cancel test") },
            parser = JsonPayloadParser<String> { body, _ -> body },
        )

        assertFailsWith<CancellationException> { provider.fetch() }
    }

    @Test
    fun `request configuration rejects inline credential query keys`() {
        val inlineUrlError = runCatching {
            ProviderRequest("https://example.invalid/data?access_token=inline")
        }.exceptionOrNull()
        val configuredQueryError = runCatching {
            ProviderRequest(
                endpoint = "https://example.invalid/data",
                query = mapOf("access_token" to "inline"),
            )
        }.exceptionOrNull()

        assertIs<IllegalArgumentException>(inlineUrlError)
        assertIs<IllegalArgumentException>(configuredQueryError)
    }

    @Test
    fun `fallback selects the first successful backup and retains primary failure provenance`() = runTest {
        val primary = StaticProvider<String>(
            ProviderId("tencent"),
            ProviderResult.Failure(listOf(failure("tencent", ProviderFailureKind.TIMEOUT))),
        )
        val backup = StaticProvider(
            ProviderId("sina"),
            ProviderResult.Success("normalized-quote", ProviderId("sina"), NOW),
        )

        val result = assertIs<ProviderResult.Success<String>>(ProviderFallbackChain(listOf(primary, backup)).fetch())

        assertEquals("normalized-quote", result.value)
        assertEquals(ProviderId("sina"), result.providerId)
        assertEquals(listOf(ProviderId("tencent")), result.priorFailures.map { it.providerId })
        assertEquals(1, primary.calls)
        assertEquals(1, backup.calls)
    }

    @Test
    fun `all Provider failures remain ordered by configured fallback priority`() = runTest {
        val result = ProviderFallbackChain(
            listOf(
                StaticProvider<String>(
                    ProviderId("primary"),
                    ProviderResult.Failure(listOf(failure("primary", ProviderFailureKind.TIMEOUT))),
                ),
                StaticProvider(
                    ProviderId("backup"),
                    ProviderResult.Failure(listOf(failure("backup", ProviderFailureKind.HTTP_ERROR))),
                ),
            ),
        ).fetch()

        assertEquals(
            listOf(ProviderId("primary"), ProviderId("backup")),
            assertIs<ProviderResult.Failure>(result).failures.map { it.providerId },
        )
    }

    @Test
    fun `fallback rejects a success that claims another Provider identity`() = runTest {
        val dishonest = StaticProvider(
            ProviderId("configured-source"),
            ProviderResult.Success("value", ProviderId("claimed-source"), NOW),
        )

        val result = assertIs<ProviderResult.Failure>(ProviderFallbackChain(listOf(dishonest)).fetch())

        assertEquals(ProviderId("configured-source"), result.failures.single().providerId)
        assertEquals(ProviderFailureKind.INVALID_VALUE, result.failures.single().kind)
    }

    @Test
    fun `network cache stale and error states cannot masquerade as one another`() = runTest {
        val live = loader(
            providerResult = ProviderResult.Success("live", ProviderId("tencent"), NOW),
            cached = null,
        ).load(NOW)
        val cached = loader(
            providerResult = failedResult(),
            cached = CachedProviderValue("cached", ProviderId("tencent"), NOW.minus(Duration.ofMinutes(30))),
        ).load(NOW)
        val stale = loader(
            providerResult = failedResult(),
            cached = CachedProviderValue("stale", ProviderId("tencent"), NOW.minus(Duration.ofHours(2))),
        ).load(NOW)
        val error = loader(providerResult = failedResult(), cached = null).load(NOW)

        assertEquals(FreshnessState.LIVE_FRESH, assertIs<ProviderSnapshot.Available<String>>(live).freshness.state)
        assertEquals(FreshnessState.CACHED, assertIs<ProviderSnapshot.Available<String>>(cached).freshness.state)
        assertEquals(FreshnessState.STALE, assertIs<ProviderSnapshot.Available<String>>(stale).freshness.state)
        assertEquals(FreshnessState.ERROR, assertIs<ProviderSnapshot.Unavailable>(error).freshness.state)
    }

    @Test
    fun `network response uses completion time while a truly future timestamp still fails`() = runTest {
        val responseTime = NOW.plusMillis(50)
        val loader = loader(
            providerResult = ProviderResult.Success("live", ProviderId("tencent"), responseTime),
            cached = null,
        )

        val live = assertIs<ProviderSnapshot.Available<String>>(
            loader.load(NOW) { NOW.plusMillis(100) },
        )
        assertEquals(FreshnessState.LIVE_FRESH, live.freshness.state)
        assertEquals(Duration.ofMillis(50), live.freshness.age)

        assertFailsWith<IllegalArgumentException> {
            loader(
                providerResult = ProviderResult.Success(
                    "future",
                    ProviderId("tencent"),
                    NOW.plusMillis(101),
                ),
                cached = null,
            ).load(NOW) { NOW.plusMillis(100) }
        }
    }

    @Test
    fun `an older network response cannot regress a newer cached fact`() = runTest {
        val cache = MemoryCache(CachedProviderValue("newer-cache", ProviderId("sina"), NOW))
        val loader = CachedProviderLoader(
            ProviderFallbackChain(
                listOf(
                    StaticProvider(
                        ProviderId("tencent"),
                        ProviderResult.Success("older-network", ProviderId("tencent"), NOW.minusSeconds(30)),
                    ),
                ),
            ),
            cache,
            Duration.ofHours(1),
        )

        val result = assertIs<ProviderSnapshot.Available<String>>(loader.load(NOW))

        assertEquals("newer-cache", result.value)
        assertEquals(FreshnessState.CACHED, result.freshness.state)
        assertEquals("newer-cache", cache.value?.value)
    }

    @Test
    fun `cache first loading skips network while cached history remains fresh`() = runTest {
        val provider = StaticProvider(
            ProviderId("nasdaq"),
            ProviderResult.Success("network", ProviderId("nasdaq"), NOW.plusSeconds(1)),
        )
        val loader = CachedProviderLoader(
            chain = ProviderFallbackChain(listOf(provider)),
            cache = MemoryCache(CachedProviderValue("cached-history", ProviderId("nasdaq"), NOW)),
            freshFor = Duration.ofDays(1),
        )

        val cached = assertIs<ProviderSnapshot.Available<String>>(loader.loadCacheFirst(NOW.plusSeconds(60)))

        assertEquals("cached-history", cached.value)
        assertEquals(FreshnessState.CACHED, cached.freshness.state)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `cache first loading refreshes stale history and replaces it with network data`() = runTest {
        val provider = StaticProvider(
            ProviderId("nasdaq"),
            ProviderResult.Success("network", ProviderId("nasdaq"), NOW),
        )
        val cache = MemoryCache(
            CachedProviderValue("stale-history", ProviderId("nasdaq"), NOW.minus(Duration.ofDays(2))),
        )
        val loader = CachedProviderLoader(
            chain = ProviderFallbackChain(listOf(provider)),
            cache = cache,
            freshFor = Duration.ofDays(1),
        )

        val refreshed = assertIs<ProviderSnapshot.Available<String>>(loader.loadCacheFirst(NOW))

        assertEquals("network", refreshed.value)
        assertEquals(FreshnessState.LIVE_FRESH, refreshed.freshness.state)
        assertEquals(1, provider.calls)
        assertEquals("network", cache.value?.value)
    }

    @Test
    fun `daily request budget records only success and allows a failed request to retry`() = runTest {
        val store = MemoryDailySuccessStore()
        val provider = QueueProvider(
            ProviderId("daily-source"),
            ArrayDeque(
                listOf(
                    failedResult("daily-source"),
                    ProviderResult.Success("value", ProviderId("daily-source"), NOW),
                ),
            ),
        )
        val budget = DailySuccessfulRequestBudget(store)

        assertIs<BudgetedProviderResult.Failed>(budget.fetch(DAILY_BUDGET, WEEKDAY, provider))
        assertIs<BudgetedProviderResult.Fetched<String>>(budget.fetch(DAILY_BUDGET, WEEKDAY, provider))
        assertIs<BudgetedProviderResult.AlreadySatisfied>(budget.fetch(DAILY_BUDGET, WEEKDAY, provider))
        assertEquals(2, provider.calls)
    }

    @Test
    fun `concurrent refreshes yield only one successful request per day`() = runTest {
        val store = MemoryDailySuccessStore()
        val provider = StaticProvider(
            ProviderId("daily-source"),
            ProviderResult.Success("value", ProviderId("daily-source"), NOW),
        )
        val budget = DailySuccessfulRequestBudget(store)

        val results = List(8) { async { budget.fetch(DAILY_BUDGET, WEEKDAY, provider) } }.awaitAll()

        assertEquals(1, provider.calls)
        assertEquals(1, results.count { it is BudgetedProviderResult.Fetched })
        assertEquals(7, results.count { it is BudgetedProviderResult.AlreadySatisfied })
    }

    @Test
    fun `daily budget may skip weekends without calling the Provider`() = runTest {
        val provider = StaticProvider(
            ProviderId("daily-source"),
            ProviderResult.Success("value", ProviderId("daily-source"), NOW),
        )

        val result = DailySuccessfulRequestBudget(MemoryDailySuccessStore())
            .fetch(DAILY_BUDGET, LocalDate.parse("2026-09-12"), provider)

        assertIs<BudgetedProviderResult.WeekendSkipped>(result)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `default source order matches the frozen product priority`() {
        assertEquals(listOf("tencent", "sina", "eastmoney"), DefaultProviderOrder.ndxQuote.map { it.value })
        assertEquals(listOf("eastmoney", "stooq", "nasdaq"), DefaultProviderOrder.ndxHistory.map { it.value })
        assertEquals(listOf("tencent", "eastmoney"), DefaultProviderOrder.usdCny.map { it.value })
        assertEquals(listOf("tiantian-eastmoney", "gffunds-official"), DefaultProviderOrder.fundNav.map { it.value })
    }

    private fun httpProvider(result: TransportResult): HttpJsonProvider<String> = HttpJsonProvider(
        id = ProviderId("provider"),
        request = ProviderRequest("https://example.invalid/data"),
        transport = ProviderTransport { result },
        parser = JsonPayloadParser { body, _ -> body },
    )

    private fun loader(
        providerResult: ProviderResult<String>,
        cached: CachedProviderValue<String>?,
    ): CachedProviderLoader<String> = CachedProviderLoader(
        chain = ProviderFallbackChain(listOf(StaticProvider(ProviderId("tencent"), providerResult))),
        cache = MemoryCache(cached),
        freshFor = Duration.ofHours(1),
    )

    private fun failedResult(providerId: String = "tencent"): ProviderResult.Failure =
        ProviderResult.Failure(listOf(failure(providerId, ProviderFailureKind.TIMEOUT)))

    private fun failure(id: String, kind: ProviderFailureKind): ProviderFailure = ProviderFailure(
        providerId = ProviderId(id),
        kind = kind,
        retryable = true,
        safeDetail = kind.name,
    )

    private class StaticProvider<T>(
        override val id: ProviderId,
        private val result: ProviderResult<T>,
    ) : DataProvider<T> {
        var calls: Int = 0

        override suspend fun fetch(): ProviderResult<T> {
            calls += 1
            return result
        }
    }

    private class QueueProvider<T>(
        override val id: ProviderId,
        private val results: ArrayDeque<ProviderResult<T>>,
    ) : DataProvider<T> {
        var calls: Int = 0

        override suspend fun fetch(): ProviderResult<T> {
            calls += 1
            return results.removeFirst()
        }
    }

    private class MemoryCache<T>(
        var value: CachedProviderValue<T>?,
    ) : ProviderCache<T> {
        override suspend fun read(): CachedProviderValue<T>? = value

        override suspend fun write(value: CachedProviderValue<T>) {
            this.value = value
        }
    }

    private class MemoryDailySuccessStore : DailySuccessStore {
        private val successes = mutableMapOf<Pair<DailyBudgetKey, LocalDate>, DailySuccessRecord>()

        override suspend fun findSuccess(key: DailyBudgetKey, date: LocalDate): DailySuccessRecord? =
            successes[key to date]

        override suspend fun recordSuccess(record: DailySuccessRecord) {
            successes.putIfAbsent(record.key to record.date, record)
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-11T20:00:00Z")
        val WEEKDAY: LocalDate = LocalDate.parse("2026-09-11")
        val DAILY_BUDGET: DailyBudgetKey = DailyBudgetKey("market:daily-refresh")
    }
}
