package com.ndxinvest.providers.runtime

import com.ndxinvest.market.calculation.FreshnessEvaluator
import com.ndxinvest.market.model.DataDelivery
import com.ndxinvest.market.model.DataFreshness
import com.ndxinvest.market.model.FreshnessState
import com.ndxinvest.market.model.MarketSourceId
import com.ndxinvest.providers.contract.DataProvider
import com.ndxinvest.providers.contract.ProviderFailure
import com.ndxinvest.providers.contract.ProviderFailureKind
import com.ndxinvest.providers.contract.ProviderId
import com.ndxinvest.providers.contract.ProviderResult
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public class ProviderFallbackChain<T> public constructor(
    private val providers: List<DataProvider<T>>,
) {
    init {
        require(providers.isNotEmpty()) { "A Provider fallback chain must not be empty" }
        require(providers.map { it.id }.distinct().size == providers.size) {
            "A Provider fallback chain must not contain duplicate Provider ids"
        }
    }

    public suspend fun fetch(): ProviderResult<T> {
        val failures = mutableListOf<ProviderFailure>()
        providers.forEach { provider ->
            when (val result = provider.fetch()) {
                is ProviderResult.Success -> {
                    if (result.providerId != provider.id) {
                        failures += ProviderFailure(
                            providerId = provider.id,
                            kind = ProviderFailureKind.INVALID_VALUE,
                            retryable = false,
                            safeDetail = "Provider returned inconsistent source provenance",
                        )
                        return@forEach
                    }
                    return result.copy(priorFailures = failures + result.priorFailures)
                }

                is ProviderResult.Failure -> failures += result.failures
            }
        }
        return ProviderResult.Failure(failures)
    }
}

public data class CachedProviderValue<T> public constructor(
    public val value: T,
    public val providerId: ProviderId,
    public val fetchedAt: Instant,
)

public interface ProviderCache<T> {
    public suspend fun read(): CachedProviderValue<T>?

    public suspend fun write(value: CachedProviderValue<T>)
}

public sealed interface ProviderSnapshot<out T> {
    public data class Available<T> public constructor(
        public val value: T,
        public val freshness: DataFreshness,
        public val providerId: ProviderId,
        public val failures: List<ProviderFailure>,
    ) : ProviderSnapshot<T> {
        init {
            require(freshness.state != FreshnessState.ERROR) { "Available data cannot have ERROR freshness" }
        }
    }

    public data class Unavailable public constructor(
        public val freshness: DataFreshness,
        public val failures: List<ProviderFailure>,
    ) : ProviderSnapshot<Nothing> {
        init {
            require(freshness.state == FreshnessState.ERROR) { "Unavailable data must have ERROR freshness" }
        }
    }
}

public class CachedProviderLoader<T> public constructor(
    private val chain: ProviderFallbackChain<T>,
    private val cache: ProviderCache<T>,
    private val freshFor: Duration,
) {
    init {
        require(!freshFor.isNegative && !freshFor.isZero) { "Provider freshness window must be positive" }
    }

    public suspend fun load(
        evaluatedAt: Instant,
        networkEvaluatedAt: () -> Instant = { evaluatedAt },
    ): ProviderSnapshot<T> = when (val network = chain.fetch()) {
        is ProviderResult.Success -> resolveNetwork(network, networkEvaluatedAt())
        is ProviderResult.Failure -> resolveCache(network.failures, evaluatedAt)
    }

    public suspend fun loadCacheFirst(
        evaluatedAt: Instant,
        networkEvaluatedAt: () -> Instant = { evaluatedAt },
    ): ProviderSnapshot<T> {
        val cached = cache.read()
        if (cached != null) {
            val available = availableFromCache(cached, emptyList(), evaluatedAt)
            if (available.freshness.state != FreshnessState.STALE) return available
        }
        return load(evaluatedAt, networkEvaluatedAt)
    }

    private suspend fun resolveNetwork(
        network: ProviderResult.Success<T>,
        evaluatedAt: Instant,
    ): ProviderSnapshot.Available<T> {
        val existing = cache.read()
        if (existing != null && network.fetchedAt.isBefore(existing.fetchedAt)) {
            return availableFromCache(existing, network.priorFailures, evaluatedAt)
        }
        val value = CachedProviderValue(network.value, network.providerId, network.fetchedAt)
        cache.write(value)
        return ProviderSnapshot.Available(
            value = network.value,
            freshness = FreshnessEvaluator.evaluate(
                hasUsableValue = true,
                delivery = DataDelivery.NETWORK,
                source = MarketSourceId(network.providerId.value),
                fetchedAt = network.fetchedAt,
                evaluatedAt = evaluatedAt,
                freshFor = freshFor,
            ),
            providerId = network.providerId,
            failures = network.priorFailures,
        )
    }

    private suspend fun resolveCache(
        failures: List<ProviderFailure>,
        evaluatedAt: Instant,
    ): ProviderSnapshot<T> {
        val cached = cache.read()
            ?: return ProviderSnapshot.Unavailable(
                freshness = FreshnessEvaluator.evaluate(
                    hasUsableValue = false,
                    delivery = null,
                    source = null,
                    fetchedAt = null,
                    evaluatedAt = evaluatedAt,
                    freshFor = freshFor,
                ),
                failures = failures,
            )
        return availableFromCache(cached, failures, evaluatedAt)
    }

    private fun availableFromCache(
        cached: CachedProviderValue<T>,
        failures: List<ProviderFailure>,
        evaluatedAt: Instant,
    ): ProviderSnapshot.Available<T> = ProviderSnapshot.Available(
        value = cached.value,
        freshness = FreshnessEvaluator.evaluate(
            hasUsableValue = true,
            delivery = DataDelivery.CACHE,
            source = MarketSourceId(cached.providerId.value),
            fetchedAt = cached.fetchedAt,
            evaluatedAt = evaluatedAt,
            freshFor = freshFor,
        ),
        providerId = cached.providerId,
        failures = failures,
    )
}

@JvmInline
public value class DailyBudgetKey public constructor(public val value: String) {
    init {
        require(value.isNotBlank()) { "Daily request budget key must not be blank" }
    }
}

public interface DailySuccessStore {
    public suspend fun findSuccess(key: DailyBudgetKey, date: LocalDate): DailySuccessRecord?

    public suspend fun recordSuccess(record: DailySuccessRecord)
}

public data class DailySuccessRecord public constructor(
    public val key: DailyBudgetKey,
    public val date: LocalDate,
    public val providerId: ProviderId,
    public val fetchedAt: Instant,
)

public sealed interface BudgetedProviderResult<out T> {
    public data class Fetched<T> public constructor(
        public val result: ProviderResult.Success<T>,
    ) : BudgetedProviderResult<T>

    public data class Failed public constructor(
        public val result: ProviderResult.Failure,
    ) : BudgetedProviderResult<Nothing>

    public data class AlreadySatisfied public constructor(
        public val record: DailySuccessRecord,
    ) : BudgetedProviderResult<Nothing>

    public data class WeekendSkipped public constructor(
        public val date: LocalDate,
    ) : BudgetedProviderResult<Nothing>
}

public class DailySuccessfulRequestBudget public constructor(
    private val store: DailySuccessStore,
    private val skipWeekends: Boolean = true,
) {
    private val mutex: Mutex = Mutex()

    public suspend fun <T> fetch(
        key: DailyBudgetKey,
        date: LocalDate,
        provider: DataProvider<T>,
    ): BudgetedProviderResult<T> = mutex.withLock {
        if (skipWeekends && date.dayOfWeek in WEEKEND) {
            return@withLock BudgetedProviderResult.WeekendSkipped(date)
        }
        val existing = store.findSuccess(key, date)
        if (existing != null) {
            return@withLock BudgetedProviderResult.AlreadySatisfied(existing)
        }
        when (val result = provider.fetch()) {
            is ProviderResult.Success -> {
                store.recordSuccess(DailySuccessRecord(key, date, result.providerId, result.fetchedAt))
                BudgetedProviderResult.Fetched(result)
            }

            is ProviderResult.Failure -> BudgetedProviderResult.Failed(result)
        }
    }

    private companion object {
        val WEEKEND: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    }
}
