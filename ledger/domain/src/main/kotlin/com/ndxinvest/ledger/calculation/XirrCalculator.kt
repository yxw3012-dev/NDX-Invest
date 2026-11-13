package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.CashFlowKind
import com.ndxinvest.ledger.model.DatedCashFlow
import com.ndxinvest.ledger.model.HistoryScope
import com.ndxinvest.ledger.model.XirrLabel
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.temporal.ChronoUnit

public sealed interface XirrResult {
    public val label: XirrLabel
}

public data class XirrCalculation public constructor(
    public val annualRate: BigDecimal,
    override val label: XirrLabel,
) : XirrResult

public enum class XirrUnavailableReason {
    TOO_SHORT_PERIOD,
    NO_ROOT,
    MULTIPLE_ROOTS,
}

public data class XirrUnavailable public constructor(
    public val reason: XirrUnavailableReason,
    override val label: XirrLabel,
) : XirrResult

public object XirrCalculator {
    public fun calculate(
        cashFlows: List<DatedCashFlow>,
        historyScope: HistoryScope,
        outputScale: Int = 8,
        roundingMode: RoundingMode = RoundingMode.HALF_UP,
    ): XirrResult {
        require(outputScale >= 0) { "XIRR output scale must not be negative" }
        require(cashFlows.size >= 2) { "XIRR requires at least two dated cash flows" }
        require(cashFlows.any { it.amount.signum() < 0 }) { "XIRR requires a negative cash flow" }
        require(cashFlows.any { it.amount.signum() > 0 }) { "XIRR requires a positive cash flow" }
        require(
            historyScope != HistoryScope.TRACKING_PERIOD ||
                cashFlows.any { it.kind == CashFlowKind.TRACKING_BASELINE },
        ) { "Tracking-period XIRR requires an explicit tracking baseline" }

        val label = historyScope.label()
        val ordered = cashFlows.sortedBy { it.date }
        val firstDate = ordered.first().date
        if (firstDate == ordered.last().date) {
            return XirrUnavailable(XirrUnavailableReason.TOO_SHORT_PERIOD, label)
        }
        val dayOffsets = ordered.map { cashFlow ->
            val days = ChronoUnit.DAYS.between(firstDate, cashFlow.date)
            require(days <= Int.MAX_VALUE) { "XIRR date range is too large" }
            days.toInt() to cashFlow.amount
        }

        val roots = findSupportedRoots(dayOffsets)
        if (roots.isEmpty()) return XirrUnavailable(XirrUnavailableReason.NO_ROOT, label)
        if (roots.size > 1) return XirrUnavailable(XirrUnavailableReason.MULTIPLE_ROOTS, label)

        val annualRate = roots.single().pow(DAYS_PER_YEAR, CONTEXT)
            .subtract(BigDecimal.ONE, CONTEXT)
            .setScale(outputScale, roundingMode)
        return XirrCalculation(annualRate = annualRate, label = label)
    }

    private fun findSupportedRoots(cashFlows: List<Pair<Int, BigDecimal>>): List<BigDecimal> {
        val roots = mutableListOf<BigDecimal>()
        var left = MIN_DAILY_FACTOR
        var leftNpv = npvForDailyFactor(cashFlows, left)
        repeat(SCAN_INTERVALS) { interval ->
            val right = MIN_DAILY_FACTOR.add(
                SCAN_STEP.multiply(BigDecimal.valueOf((interval + 1).toLong()), CONTEXT),
                CONTEXT,
            )
            val rightNpv = npvForDailyFactor(cashFlows, right)
            when {
                leftNpv.signum() == 0 -> roots.addDistinct(left)
                rightNpv.signum() == 0 -> roots.addDistinct(right)
                leftNpv.signum() != rightNpv.signum() -> roots.addDistinct(
                    bisectRoot(cashFlows, left, right, leftNpv),
                )
            }
            left = right
            leftNpv = rightNpv
        }
        return roots
    }

    private fun bisectRoot(
        cashFlows: List<Pair<Int, BigDecimal>>,
        initialLeft: BigDecimal,
        initialRight: BigDecimal,
        initialLeftNpv: BigDecimal,
    ): BigDecimal {
        var left = initialLeft
        var right = initialRight
        var leftNpv = initialLeftNpv
        repeat(MAX_ITERATIONS) {
            val midpoint = left.add(right, CONTEXT).divide(TWO, CONTEXT)
            if (midpoint == left || midpoint == right) return midpoint
            val midpointNpv = npvForDailyFactor(cashFlows, midpoint)
            if (midpointNpv.signum() == 0) return midpoint
            if (midpointNpv.signum() == leftNpv.signum()) {
                left = midpoint
                leftNpv = midpointNpv
            } else {
                right = midpoint
            }
        }
        return left.add(right, CONTEXT).divide(TWO, CONTEXT)
    }

    private fun MutableList<BigDecimal>.addDistinct(candidate: BigDecimal) {
        if (none { it.subtract(candidate, CONTEXT).abs() <= ROOT_TOLERANCE }) add(candidate)
    }

    private fun npvForDailyFactor(
        cashFlows: List<Pair<Int, BigDecimal>>,
        dailyFactor: BigDecimal,
    ): BigDecimal = cashFlows.fold(BigDecimal.ZERO) { total, (days, amount) ->
        val discounted = if (days == 0) amount else amount.divide(dailyFactor.pow(days, CONTEXT), CONTEXT)
        total.add(discounted, CONTEXT)
    }

    private fun HistoryScope.label(): XirrLabel = when (this) {
        HistoryScope.FULL_HISTORY -> XirrLabel.ANNUALIZED_RETURN
        HistoryScope.TRACKING_PERIOD -> XirrLabel.TRACKING_PERIOD_ANNUALIZED
    }

    private val CONTEXT: MathContext = MathContext.DECIMAL128
    private val TWO: BigDecimal = BigDecimal("2")
    private val MIN_DAILY_FACTOR: BigDecimal = BigDecimal("0.100")
    private val SCAN_STEP: BigDecimal = BigDecimal("0.001")
    private val ROOT_TOLERANCE: BigDecimal = BigDecimal("0.00000000000000000001")
    private const val SCAN_INTERVALS: Int = 1900
    private const val DAYS_PER_YEAR: Int = 365
    private const val MAX_ITERATIONS: Int = 256
}
