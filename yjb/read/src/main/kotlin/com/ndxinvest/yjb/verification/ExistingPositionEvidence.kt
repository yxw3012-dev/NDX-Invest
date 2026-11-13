package com.ndxinvest.yjb.verification

import java.math.BigDecimal
import java.security.MessageDigest

/** Analysis only. Contains no network, writer, Transaction or VerifiedWritePrecision factory. */
object ExistingPositionEvidence {
    class Position(val shares: BigDecimal, val unitCost: BigDecimal) {
        init { require(shares.signum() >= 0 && unitCost.signum() >= 0) { "INVALID_POSITION" } }
        val totalCost: BigDecimal get() = shares.multiply(unitCost)
        override fun toString() = "ProbePosition[REDACTED]"
    }

    class Limits(val shares: BigDecimal, val unitCost: BigDecimal, val totalCost: BigDecimal) {
        init { require(listOf(shares, unitCost, totalCost).all { it.signum() >= 0 }) { "INVALID_LIMITS" } }
        override fun toString() = "ProbeLimits[REDACTED]"
    }

    enum class Semantics { REPLACEMENT, ADDITIVE, UNCHANGED, UNKNOWN, AMBIGUOUS }
    class Outcome(val shareSemantics: Semantics, val costSemantics: Semantics, val position: Position)

    class Safety(val outcomes: List<Outcome>, val distinguishing: Boolean, val withinLimits: Boolean) {
        // Even a bounded probe cannot rule out undocumented server semantics. User review is still required.
        val eligibleForReview: Boolean get() = distinguishing && withinLimits
    }

    /** Evaluate independent share/cost replacement and additive hypotheses, not just B - A. */
    fun safety(baseline: Position, target: Position, limits: Limits): Safety {
        val outcomes = listOf(Semantics.REPLACEMENT, Semantics.ADDITIVE).flatMap { shares ->
            listOf(Semantics.REPLACEMENT, Semantics.ADDITIVE).map { cost ->
                Outcome(shares, cost, Position(
                    if(shares == Semantics.REPLACEMENT) target.shares else baseline.shares + target.shares,
                    if(cost == Semantics.REPLACEMENT) target.unitCost else baseline.unitCost + target.unitCost,
                ))
            }
        }
        val distinguishing = baseline.shares.signum() > 0 && baseline.unitCost.signum() > 0 &&
            target.shares.signum() > 0 && target.unitCost.signum() > 0 &&
            baseline.shares.compareTo(target.shares) != 0 && baseline.unitCost.compareTo(target.unitCost) != 0
        val bounded = outcomes.all {
            (it.position.shares - baseline.shares).abs() <= limits.shares &&
            (it.position.unitCost - baseline.unitCost).abs() <= limits.unitCost &&
            (it.position.totalCost - baseline.totalCost).abs() <= limits.totalCost
        }
        return Safety(outcomes, distinguishing, bounded)
    }

    /** Prior real replacement semantics were confirmed by the user. No additive probing. */
    fun replacementSafety(baseline: Position, target: Position, limits: Limits): Safety {
        val bounded=(target.shares-baseline.shares).abs()<=limits.shares &&
            (target.unitCost-baseline.unitCost).abs()<=limits.unitCost &&
            (target.totalCost-baseline.totalCost).abs()<=limits.totalCost
        val changed=target.shares.compareTo(baseline.shares)!=0 && target.unitCost.compareTo(baseline.unitCost)!=0
        return Safety(listOf(Outcome(Semantics.REPLACEMENT,Semantics.REPLACEMENT,target)),changed,bounded)
    }

    fun classify(baseline: BigDecimal, submitted: BigDecimal, actual: BigDecimal): Semantics {
        if(baseline.signum() == 0 || submitted.signum() == 0 || submitted.compareTo(baseline) == 0) return Semantics.AMBIGUOUS
        val replacement = actual.compareTo(submitted) == 0
        val additive = actual.compareTo(baseline + submitted) == 0
        return when {
            replacement && additive -> Semantics.AMBIGUOUS
            replacement -> Semantics.REPLACEMENT
            additive -> Semantics.ADDITIVE
            actual.compareTo(baseline) == 0 -> Semantics.UNCHANGED
            else -> Semantics.UNKNOWN
        }
    }

    class DecimalObservation(val submittedFractionDigits: Int, val returnedFractionDigits: Int,
        val numericallyEqual: Boolean, val submittedMeaningfulFractionDigits: Int) {
        // A single sample cannot establish a maximum accepted scale or physical database storage scale.
        val maximumAcceptedScale: Int? get() = null
        val physicalStorageScale: Int? get() = null
    }

    fun observeDecimal(submitted: String, returned: String): DecimalObservation {
        val sent = decimal(submitted); val got = decimal(returned)
        return DecimalObservation(sent.scale().coerceAtLeast(0), got.scale().coerceAtLeast(0),
            sent.compareTo(got) == 0, sent.stripTrailingZeros().scale().coerceAtLeast(0))
    }

    /** Exact decimals only; protect resource usage without inventing YJB precision constraints. */
    fun decimal(value: String): BigDecimal {
        require(value.length in 1..256 && Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?").matches(value)) { "INVALID_DECIMAL" }
        return BigDecimal(value)
    }

    /** Bind a review to identity, full baseline JSON, exact decimal lexemes and explicit limits. */
    fun reviewDigest(accountId: String, fundId: String, baselineJson: String,
        shareTarget: String, costTarget: String, shareLimit: String, costLimit: String, totalLimit: String): String {
        require(Regex("[0-9]+").matches(accountId) && Regex("[0-9]+").matches(fundId)) { "INVALID_ID" }
        listOf(shareTarget,costTarget,shareLimit,costLimit,totalLimit).forEach(::decimal)
        val fields = listOf("NDX_YJB_PROBE_V1",accountId,fundId,baselineJson,
            shareTarget,costTarget,shareLimit,costLimit,totalLimit)
        val bytes = fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }.toByteArray(Charsets.UTF_8)
        return try { MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
            finally { bytes.fill(0) }
    }
}
