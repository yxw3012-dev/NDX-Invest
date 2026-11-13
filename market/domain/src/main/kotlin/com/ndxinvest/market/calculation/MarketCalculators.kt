package com.ndxinvest.market.calculation

import com.ndxinvest.market.model.DataDelivery
import com.ndxinvest.market.model.DataFreshness
import com.ndxinvest.market.model.FreshnessState
import com.ndxinvest.market.model.MarketSourceId
import com.ndxinvest.market.model.NdxDaily
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

public data class CurrentDrawdown public constructor(
    public val currentClose: BigDecimal,
    public val allTimeHighClose: BigDecimal,
    public val allTimeHighDate: LocalDate,
    public val drawdownRate: BigDecimal,
)

public data class MaximumDrawdown public constructor(
    public val rate: BigDecimal,
    public val peakDate: LocalDate,
    public val troughDate: LocalDate,
)

public data class LongTermPerformance public constructor(
    public val startDate: LocalDate,
    public val endDate: LocalDate,
    public val cumulativeReturn: BigDecimal,
    public val cagr: BigDecimal,
    public val maximumDrawdown: MaximumDrawdown,
    public val annualizedVolatility: BigDecimal,
)

public object CurrentDrawdownCalculator {
    public fun calculate(
        history: List<NdxDaily>,
        currentClose: BigDecimal = history.lastOrNull()?.close ?: error("NDX history must not be empty"),
        outputScale: Int = DEFAULT_RATE_SCALE,
        roundingMode: RoundingMode = RoundingMode.HALF_UP,
    ): CurrentDrawdown {
        validateHistory(history)
        require(outputScale >= 0) { "Drawdown output scale must not be negative" }
        require(currentClose.signum() > 0) { "Current NDX close must be positive" }
        val ordered = history.sortedBy { it.date }
        val ath = ordered.maxWith(compareBy<NdxDaily>({ it.close }, { it.date }))
        val effectiveAth = if (currentClose > ath.close) currentClose else ath.close
        val effectiveAthDate = if (currentClose > ath.close) ordered.last().date else ath.date
        val rate = currentClose.divide(effectiveAth, CONTEXT)
            .subtract(BigDecimal.ONE)
            .setScale(outputScale, roundingMode)
        return CurrentDrawdown(currentClose, effectiveAth, effectiveAthDate, rate)
    }
}

public object MaximumDrawdownCalculator {
    public fun calculate(
        history: List<NdxDaily>,
        outputScale: Int = DEFAULT_RATE_SCALE,
        roundingMode: RoundingMode = RoundingMode.HALF_UP,
    ): MaximumDrawdown {
        validateHistory(history)
        require(outputScale >= 0) { "Drawdown output scale must not be negative" }
        val ordered = history.sortedBy { it.date }
        var peak = ordered.first()
        var deepestRate = BigDecimal.ZERO
        var deepestPeakDate = peak.date
        var deepestTroughDate = peak.date
        ordered.drop(1).forEach { daily ->
            if (daily.close > peak.close) {
                peak = daily
            } else {
                val rate = daily.close.divide(peak.close, CONTEXT).subtract(BigDecimal.ONE, CONTEXT)
                if (rate < deepestRate) {
                    deepestRate = rate
                    deepestPeakDate = peak.date
                    deepestTroughDate = daily.date
                }
            }
        }
        return MaximumDrawdown(
            rate = deepestRate.setScale(outputScale, roundingMode),
            peakDate = deepestPeakDate,
            troughDate = deepestTroughDate,
        )
    }
}

public object CagrCalculator {
    public fun calculate(
        startValue: BigDecimal,
        endValue: BigDecimal,
        periodYears: BigDecimal,
        outputScale: Int = DEFAULT_RATE_SCALE,
        roundingMode: RoundingMode = RoundingMode.HALF_UP,
    ): BigDecimal {
        require(outputScale >= 0) { "CAGR output scale must not be negative" }
        require(startValue.signum() > 0 && endValue.signum() > 0) { "CAGR values must be positive" }
        require(periodYears.signum() > 0) { "CAGR period must be positive" }
        val ratio = endValue.divide(startValue, CONTEXT)
        val exponent = BigDecimal.ONE.divide(periodYears, CONTEXT)
        return DecimalMath.pow(ratio, exponent, CONTEXT)
            .subtract(BigDecimal.ONE, CONTEXT)
            .setScale(outputScale, roundingMode)
    }
}

public object AnnualizedVolatilityCalculator {
    public fun calculate(
        history: List<NdxDaily>,
        tradingDaysPerYear: Int = 252,
        outputScale: Int = DEFAULT_RATE_SCALE,
        roundingMode: RoundingMode = RoundingMode.HALF_UP,
    ): BigDecimal {
        validateHistory(history)
        require(outputScale >= 0) { "Volatility output scale must not be negative" }
        require(history.size >= 3) { "Volatility requires at least two daily returns" }
        require(tradingDaysPerYear > 0) { "Trading days per year must be positive" }
        val ordered = history.sortedBy { it.date }
        val returns = ordered.zipWithNext { previous, current ->
            current.close.divide(previous.close, CONTEXT).subtract(BigDecimal.ONE, CONTEXT)
        }
        val count = BigDecimal.valueOf(returns.size.toLong())
        val mean = returns.fold(BigDecimal.ZERO) { total, value -> total.add(value, CONTEXT) }
            .divide(count, CONTEXT)
        val squaredDeviations = returns.fold(BigDecimal.ZERO) { total, value ->
            val deviation = value.subtract(mean, CONTEXT)
            total.add(deviation.multiply(deviation, CONTEXT), CONTEXT)
        }
        val sampleVariance = squaredDeviations.divide(BigDecimal.valueOf((returns.size - 1).toLong()), CONTEXT)
        return sampleVariance.sqrt(CONTEXT)
            .multiply(BigDecimal.valueOf(tradingDaysPerYear.toLong()).sqrt(CONTEXT), CONTEXT)
            .setScale(outputScale, roundingMode)
    }
}

public object LongTermPerformanceCalculator {
    public fun calculate(
        history: List<NdxDaily>,
        periodYears: BigDecimal,
        outputScale: Int = DEFAULT_RATE_SCALE,
        roundingMode: RoundingMode = RoundingMode.HALF_UP,
    ): LongTermPerformance {
        validateHistory(history)
        require(outputScale >= 0) { "Performance output scale must not be negative" }
        val ordered = history.sortedBy { it.date }
        require(ordered.size >= 3) { "Long-term performance requires at least three daily closes" }
        val cumulative = ordered.last().close.divide(ordered.first().close, CONTEXT)
            .subtract(BigDecimal.ONE, CONTEXT)
            .setScale(outputScale, roundingMode)
        return LongTermPerformance(
            startDate = ordered.first().date,
            endDate = ordered.last().date,
            cumulativeReturn = cumulative,
            cagr = CagrCalculator.calculate(
                ordered.first().close,
                ordered.last().close,
                periodYears,
                outputScale,
                roundingMode,
            ),
            maximumDrawdown = MaximumDrawdownCalculator.calculate(history, outputScale, roundingMode),
            annualizedVolatility = AnnualizedVolatilityCalculator.calculate(
                history,
                outputScale = outputScale,
                roundingMode = roundingMode,
            ),
        )
    }
}

public object FreshnessEvaluator {
    public fun evaluate(
        hasUsableValue: Boolean,
        delivery: DataDelivery?,
        source: MarketSourceId?,
        fetchedAt: Instant?,
        evaluatedAt: Instant,
        freshFor: Duration,
    ): DataFreshness {
        require(!freshFor.isNegative && !freshFor.isZero) { "Freshness window must be positive" }
        if (!hasUsableValue) return DataFreshness(FreshnessState.ERROR, null, null, null)
        requireNotNull(delivery) { "Usable market data requires a delivery mode" }
        requireNotNull(source) { "Usable market data requires a source" }
        requireNotNull(fetchedAt) { "Usable market data requires a fetch time" }
        require(!fetchedAt.isAfter(evaluatedAt)) { "Market data cannot be fetched in the future" }
        val age = Duration.between(fetchedAt, evaluatedAt)
        val state = when {
            age > freshFor -> FreshnessState.STALE
            delivery == DataDelivery.CACHE -> FreshnessState.CACHED
            else -> FreshnessState.LIVE_FRESH
        }
        return DataFreshness(state, source, fetchedAt, age)
    }
}

private fun validateHistory(history: List<NdxDaily>) {
    require(history.isNotEmpty()) { "NDX history must not be empty" }
    require(history.map { it.date }.distinct().size == history.size) { "NDX daily dates must be unique" }
}

private val CONTEXT: MathContext = MathContext.DECIMAL128
private const val DEFAULT_RATE_SCALE: Int = 8
