package com.ndxinvest.yjb.verification

import com.ndxinvest.yjb.buy.VerifiedWritePrecision
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlinx.serialization.json.*

/** Converts a real recorded POST + GET pair to conservative observed operating scales, never a guessed maximum. */
object WritePrecisionEvidence {
    const val SEMANTICS="USER_CONFIRMED_PRIOR_REAL_REPLACEMENT_2026_10_03"
    private fun JsonObject.text(k:String)=(get(k) as? JsonPrimitive)?.contentOrNull ?: error("INCOMPLETE_EVIDENCE")
    private fun JsonObject.flag(k:String)=(get(k) as? JsonPrimitive)?.booleanOrNull==true
    fun JsonObject.position()=ExistingPositionEvidence.Position(
        ExistingPositionEvidence.decimal(text("hold_share")),ExistingPositionEvidence.decimal(text("hold_cost")))

    class ObservedField(val requestedDigits:Int,val returnedDigits:Int,val operativeScale:Int) {
        override fun toString()="ObservedWriteField[$operativeScale]"
    }
    /** Unchanged values cannot distinguish rounding from an ignored field. Reject, rather than invent a scale. */
    fun observedField(baseline:String,submitted:String,returned:String):ObservedField {
        val a=ExistingPositionEvidence.decimal(baseline)
        val b=ExistingPositionEvidence.decimal(submitted)
        val actual=ExistingPositionEvidence.decimal(returned)
        require(a.compareTo(actual)!=0) {"UNCHANGED_FIELD_PRECISION_UNPROVEN"}
        val scale=actual.stripTrailingZeros().scale().coerceAtLeast(0)
        require(scale<=24 && scale<=b.scale() && b.setScale(scale,RoundingMode.HALF_UP).compareTo(actual)==0) {"UNVERIFIED_ROUNDTRIP"}
        return ObservedField(b.scale(),actual.scale(),scale)
    }

    class Verified(val accountId:String,val profile:VerifiedWritePrecision,val shares:ObservedField,val cost:ObservedField) {
        override fun toString()="VerifiedWriteEvidence[REDACTED]"
    }
    fun verified(record:JsonObject):Verified {
        require(record.text("semanticsEvidence")==SEMANTICS && record.flag("realPostMayHaveBeenSent") &&
            record.flag("ownedAccountChecked") && record.flag("freshBaselineChecked") && record.flag("getVerified")) {"UNVERIFIED_EVIDENCE"}
        val postAt=Instant.parse(record.text("postStartedAt"));val readAt=Instant.parse(record.text("getAt"))
        require(!readAt.isBefore(postAt)) {"INVALID_EVIDENCE_ORDER"}
        val accountId=record.text("accountId");val fundId=record.text("fundId")
        val baseline=record["baseline"]!!.jsonObject;val actual=record["actual"]!!.jsonObject
        require(baseline.text("fund_id")==fundId && actual.text("fund_id")==fundId) {"WRONG_POSITION"}
        val s=record.text("shareTarget");val c=record.text("costTarget")
        val sl=record.text("shareLimit");val cl=record.text("costLimit");val tl=record.text("totalCostLimit")
        val digest=ExistingPositionEvidence.reviewDigest(accountId,fundId,baseline.toString(),s,c,sl,cl,tl)
        require(record.text("approvedDigest")==digest && record.text("reviewDigest")==digest) {"UNAPPROVED_EVIDENCE"}
        val limits=ExistingPositionEvidence.Limits(ExistingPositionEvidence.decimal(sl),ExistingPositionEvidence.decimal(cl),ExistingPositionEvidence.decimal(tl))
        val proposed=ExistingPositionEvidence.Position(ExistingPositionEvidence.decimal(s),ExistingPositionEvidence.decimal(c))
        require(ExistingPositionEvidence.replacementSafety(baseline.position(),proposed,limits).eligibleForReview &&
            ExistingPositionEvidence.replacementSafety(baseline.position(),actual.position(),limits).eligibleForReview) {"OUTSIDE_APPROVED_LIMITS"}
        val shares=observedField(baseline.text("hold_share"),s,actual.text("hold_share"))
        val cost=observedField(baseline.text("hold_cost"),c,actual.text("hold_cost"))
        val evidence="${SEMANTICS};REAL_POST_GET:${record.text("probeId")};$digest;$readAt;OBSERVED_OPERATING_SCALES"
        return Verified(accountId,VerifiedWritePrecision(evidence,shares.operativeScale,cost.operativeScale),shares,cost)
    }
}
