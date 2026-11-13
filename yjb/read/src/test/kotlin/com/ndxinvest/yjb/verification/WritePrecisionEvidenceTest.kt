package com.ndxinvest.yjb.verification

import kotlin.test.*
import kotlinx.serialization.json.*
import com.ndxinvest.yjb.verification.WritePrecisionEvidence as W
import com.ndxinvest.yjb.verification.ExistingPositionEvidence as E

/** Synthetic fixtures validate the gate. They do not establish real server precision. */
class WritePrecisionEvidenceTest {
    private fun record():JsonObject {
        val b=Json.parseToJsonElement("""{"fund_id":2,"hold_share":100.12,"hold_cost":2.1234}""").jsonObject
        val digest=E.reviewDigest("1","2",b.toString(),"100.13012345","2.12352345","0.02","0.0002","1")
        return buildJsonObject {
            put("semanticsEvidence",W.SEMANTICS);put("realPostMayHaveBeenSent",true);put("ownedAccountChecked",true)
            put("freshBaselineChecked",true);put("getVerified",true);put("probeId","SYNTHETIC_ONLY")
            put("postStartedAt","2026-10-03T00:00:00Z");put("getAt","2026-10-03T00:00:01Z")
            put("accountId","1");put("fundId","2");put("baseline",b)
            put("actual",Json.parseToJsonElement("""{"fund_id":2,"hold_share":100.13,"hold_cost":2.1235}"""))
            put("shareTarget","100.13012345");put("costTarget","2.12352345")
            put("shareLimit","0.02");put("costLimit","0.0002");put("totalCostLimit","1")
            put("approvedDigest",digest);put("reviewDigest",digest)
        }
    }
    @Test fun userVerifiedReplacementUsesOnlyReplacementRisk() {
        val p=E.Position("1000".toBigDecimal(),"2".toBigDecimal())
        val t=E.Position("1000.01012345".toBigDecimal(),"2.00012345".toBigDecimal())
        val safety=E.replacementSafety(p,t,E.Limits("0.02".toBigDecimal(),"0.0002".toBigDecimal(),"1".toBigDecimal()))
        assertTrue(safety.eligibleForReview);assertEquals(1,safety.outcomes.size)
    }
    @Test fun observedTwoAndFourScalesRequireRealRecordedReadback() {
        val verified=W.verified(record())
        assertEquals(2,verified.profile.shareScale);assertEquals(4,verified.profile.unitCostScale)
        assertEquals(8,verified.shares.requestedDigits);assertEquals(2,verified.shares.returnedDigits)
        assertEquals("VerifiedWriteEvidence[REDACTED]",verified.toString())
    }
    @Test fun noDefaultsForUnchangedIgnoredTruncatedOrUnexpectedFields() {
        assertFailsWith<IllegalArgumentException>{W.observedField("1","1.00001","1")}
        assertFailsWith<IllegalArgumentException>{W.observedField("1","1.12345678","1.1234")}
        assertFailsWith<IllegalArgumentException>{W.observedField("1","1.12345678","2.12345678")}
        assertFailsWith<IllegalArgumentException>{W.observedField("1","1.12345678","1.123456780001")}
    }
    @Test fun trailingZeroFormattingDoesNotClaimMaximumStoragePrecision() {
        val o=W.observedField("1","1.12345678","1.123457000")
        assertEquals(9,o.returnedDigits);assertEquals(6,o.operativeScale)
        assertEquals(8,o.requestedDigits)
    }
    @Test fun missingPostApprovalOrWrongReadCannotActivateWriter() {
        for((k,v) in listOf("getVerified" to JsonPrimitive(false),"realPostMayHaveBeenSent" to JsonPrimitive(false),
            "freshBaselineChecked" to JsonPrimitive(false),"ownedAccountChecked" to JsonPrimitive(false),
            "approvedDigest" to JsonPrimitive("wrong"),"getAt" to JsonPrimitive("2026-10-02T00:00:00Z"),
            "actual" to Json.parseToJsonElement("""{"fund_id":3,"hold_share":100.13,"hold_cost":2.1235}"""))) {
            assertFails {W.verified(JsonObject(record()+mapOf(k to v)))}
        }
    }
    @Test fun changingTheTargetOrSafetyBoundsInvalidatesProof() {
        assertFails {W.verified(JsonObject(record()+mapOf("shareTarget" to JsonPrimitive("100.14"))))}
        assertFails {W.verified(JsonObject(record()+mapOf("totalCostLimit" to JsonPrimitive("0.000001"))))}
    }
}
