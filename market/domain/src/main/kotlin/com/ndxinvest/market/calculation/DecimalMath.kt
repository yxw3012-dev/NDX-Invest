package com.ndxinvest.market.calculation

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal object DecimalMath {
    fun pow(base: BigDecimal, exponent: BigDecimal, context: MathContext): BigDecimal {
        require(base.signum() > 0) { "Fractional decimal power requires a positive base" }
        if (exponent.signum() == 0) return BigDecimal.ONE
        if (base.compareTo(BigDecimal.ONE) == 0) return BigDecimal.ONE
        val work = extended(context)
        return exp(ln(base, work).multiply(exponent, work), work).round(context)
    }

    private fun ln(value: BigDecimal, context: MathContext): BigDecimal {
        var scaled = value
        var powerOfTwo = 0
        while (scaled > UPPER_LN_RANGE) {
            scaled = scaled.divide(TWO, context)
            powerOfTwo += 1
        }
        while (scaled < LOWER_LN_RANGE) {
            scaled = scaled.multiply(TWO, context)
            powerOfTwo -= 1
        }

        val z = scaled.subtract(BigDecimal.ONE, context)
            .divide(scaled.add(BigDecimal.ONE, context), context)
        val zSquared = z.multiply(z, context)
        var term = z
        var sum = term
        var denominator = 3L
        val epsilon = epsilon(context)
        repeat(MAX_SERIES_TERMS) {
            term = term.multiply(zSquared, context)
            val addition = term.divide(BigDecimal.valueOf(denominator), context)
            sum = sum.add(addition, context)
            if (addition.abs() < epsilon) {
                return sum.multiply(TWO, context).add(
                    LN_TWO.multiply(BigDecimal.valueOf(powerOfTwo.toLong()), context),
                    context,
                )
            }
            denominator += 2
        }
        error("Natural logarithm did not converge")
    }

    private fun exp(value: BigDecimal, context: MathContext): BigDecimal {
        if (value.signum() == 0) return BigDecimal.ONE
        val partitions = value.abs().setScale(0, RoundingMode.CEILING).toBigInteger().toInt() + 1
        require(partitions in 1..MAX_EXP_PARTITIONS) { "Decimal exponent is outside the supported range" }
        val reduced = value.divide(BigDecimal.valueOf(partitions.toLong()), context)
        var term = BigDecimal.ONE
        var sum = BigDecimal.ONE
        val epsilon = epsilon(context)
        for (index in 1..MAX_SERIES_TERMS) {
            term = term.multiply(reduced, context).divide(BigDecimal.valueOf(index.toLong()), context)
            sum = sum.add(term, context)
            if (term.abs() < epsilon) return sum.pow(partitions, context)
        }
        error("Decimal exponential did not converge")
    }

    private fun extended(context: MathContext): MathContext = MathContext(
        (if (context.precision == 0) DEFAULT_PRECISION else context.precision) + GUARD_DIGITS,
        context.roundingMode,
    )

    private fun epsilon(context: MathContext): BigDecimal =
        BigDecimal.ONE.scaleByPowerOfTen(-context.precision)

    private val TWO: BigDecimal = BigDecimal("2")
    private val LOWER_LN_RANGE: BigDecimal = BigDecimal("0.75")
    private val UPPER_LN_RANGE: BigDecimal = BigDecimal("1.5")
    private val LN_TWO: BigDecimal = BigDecimal(
        "0.693147180559945309417232121458176568075500134360255254120680009",
    )
    private const val GUARD_DIGITS: Int = 12
    private const val DEFAULT_PRECISION: Int = 34
    private const val MAX_SERIES_TERMS: Int = 10_000
    private const val MAX_EXP_PARTITIONS: Int = 10_000
}
