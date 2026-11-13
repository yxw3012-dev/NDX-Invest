package com.ndxinvest.yjb.verification

import kotlin.test.*
import com.ndxinvest.yjb.verification.ExistingPositionEvidence as E

/** All values are synthetic: these tests provide no real YJB protocol evidence. */
class ExistingPositionEvidenceTest {
    private fun p(shares: String, cost: String) = E.Position(shares.toBigDecimal(), cost.toBigDecimal())
    private fun limits(shares: String, cost: String, total: String) = E.Limits(shares.toBigDecimal(),cost.toBigDecimal(),total.toBigDecimal())
    @Test fun smallReplacementDeltaCanStillDoubleAnExistingHolding() {
        val safety=E.safety(p("1000","1.5"),p("1000.000123","1.500123"),limits("0.01","0.01","1"))
        assertFalse(safety.eligibleForReview)
        assertEquals(4,safety.outcomes.size)
        assertTrue(safety.outcomes.any {it.position.shares.toPlainString()=="2000.000123"})
    }
    @Test fun tinyTargetWouldDestroyLargeHoldingUnderReplacement() {
        assertFalse(E.safety(p("1000","1.5"),p("0.000123","0.000123"),limits("0.01","0.01","1")).withinLimits)
    }
    @Test fun allFourHypothesesMustFitExplicitBounds() {
        assertTrue(E.safety(p("0.001","0.002"),p("0.001234","0.002345"),limits("0.002","0.003","0.00002")).eligibleForReview)
        assertFalse(E.safety(p("0.001","0.002"),p("0.001234","0.002345"),limits("0.002","0.003","0.000001")).withinLimits)
    }
    @Test fun zeroOrUnchangedBaselineCannotProveBothFields() {
        assertFalse(E.safety(p("0","1"),p("0.001","1.01"),limits("5","5","10")).distinguishing)
        assertFalse(E.safety(p("1","0"),p("1.001","0.01"),limits("5","5","10")).distinguishing)
        assertFalse(E.safety(p("1","1"),p("1","1.01"),limits("5","5","10")).distinguishing)
    }
    @Test fun exactReplacementAdditiveAndUnchangedAreIndependent() {
        fun classify(actual:String)=E.classify("12".toBigDecimal(),"12.123456".toBigDecimal(),actual.toBigDecimal())
        assertEquals(E.Semantics.REPLACEMENT,classify("12.1234560"))
        assertEquals(E.Semantics.ADDITIVE,classify("24.123456"))
        assertEquals(E.Semantics.UNCHANGED,classify("12"))
        assertEquals(E.Semantics.UNKNOWN,classify("12.1235")) // never silently round away uncertainty
        assertEquals(E.Semantics.AMBIGUOUS,E.classify("0".toBigDecimal(),"1".toBigDecimal(),"1".toBigDecimal()))
        assertEquals(E.Semantics.AMBIGUOUS,E.classify("1".toBigDecimal(),"0".toBigDecimal(),"1".toBigDecimal()))
    }
    @Test fun aSampleDoesNotProveMaximumOrDatabasePrecision() {
        val observed=E.observeDecimal("1.12345600","1.123456")
        assertTrue(observed.numericallyEqual)
        assertEquals(8,observed.submittedFractionDigits)
        assertEquals(6,observed.submittedMeaningfulFractionDigits)
        assertEquals(6,observed.returnedFractionDigits)
        assertNull(observed.maximumAcceptedScale);assertNull(observed.physicalStorageScale)
        assertFalse(E.observeDecimal("1.123456","1.1235").numericallyEqual)
    }
    @Test fun malformedAndUnboundedInputIsRejectedWithoutEchoingValues() {
        for(value in listOf("-1","NaN","1e999999"," 1","0.1\n","01.1","9".repeat(257))) {
            assertEquals("INVALID_DECIMAL",assertFailsWith<IllegalArgumentException>{E.decimal(value)}.message)
        }
        assertEquals("ProbePosition[REDACTED]",p("123","4").toString())
        assertEquals("ProbeLimits[REDACTED]",limits("1","1","1").toString())
    }
    @Test fun reviewDigestBindsIdentityBaselineTargetsAndLimits() {
        fun digest(fund:String="2",baseline:String="{\"hold_share\":1}",target:String="1.01",limit:String="1")=
            E.reviewDigest("1",fund,baseline,target,"2.01",limit,"2","3")
        val original=digest()
        assertEquals(64,original.length)
        assertEquals(original,digest())
        assertNotEquals(original,digest(fund="3"))
        assertNotEquals(original,digest(baseline="{\"hold_share\":2}"))
        assertNotEquals(original,digest(target="1.010"))
        assertNotEquals(original,digest(limit="2"))
    }
}
