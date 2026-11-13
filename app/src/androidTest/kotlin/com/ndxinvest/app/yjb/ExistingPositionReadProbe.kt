package com.ndxinvest.app.yjb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ndxinvest.app.BuildConfig
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import com.ndxinvest.yjb.read.*
import com.ndxinvest.yjb.verification.ExistingPositionEvidence as E
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** Explicit, test-APK-only preparation. No POST /fund_hold path or writer exists in this tool. */
@RunWith(AndroidJUnit4::class)
class ExistingPositionReadProbe {
    @Test fun prepareOnly() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val mode=args.getString("ndxProbeMode")
        // Ordinary instrumentation suites do not touch a real account or create probe records.
        assumeTrue(BuildConfig.FLAVOR=="production" && mode in setOf("INSPECT","PREPARE"))
        val nameContains=args.getString("ndxProbeFundName")?.takeIf {it.isNotBlank()} ?: error("FUND_SELECTION_REQUIRED")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val storage=AndroidYjbStorage(context)
        val token=storage.token() ?: error("NO_SESSION")
        try {
            val api=YjbReadApi(HttpsYjbWire(),storage::protocolKey)
            val snapshot=api.snapshot(token)
            val quotes=snapshot["quotes"]!!.jsonArray.associate {it.jsonObject.text("fund_id") to it.jsonObject}
            class Selection(val accountId:String,val fundId:String,val code:String,val name:String)
            val matches=snapshot["groups"]!!.jsonArray.flatMap {group ->
                val g=group.jsonObject
                g["funds"]!!.jsonArray.mapNotNull {item ->
                    val h=item.jsonObject; val fundId=h.text("fund_id") ?: error("PROTOCOL")
                    val q=quotes[fundId]
                    val name=q?.text("short_name") ?: h.text("short_name") ?: ""
                    val code=q?.text("code") ?: h.text("code") ?: ""
                    val accountId=g.text("accountId") ?: error("PROTOCOL")
                    val accountMatches=args.getString("ndxProbeAccountId")?.let {it==accountId} ?: true
                    val codeMatches=args.getString("ndxProbeFundCode")?.let {it==code} ?: true
                    if(accountMatches && codeMatches && name.contains(nameContains))Selection(accountId,fundId,code,name) else null
                }
            }
            safeStatus("MATCH_COUNT=${matches.size}")
            if(matches.size!=1) {
                matches.forEach {safeStatus("CANDIDATE_CODE=${publicText(it.code)} NAME=${publicText(it.name)}")}
                error(if(matches.isEmpty())"NO_SELECTED_FUND" else "AMBIGUOUS_FUND_SELECTION")
            }
            val fund=matches.single()
            // Fresh selected-account GET immediately before capturing the full selected holding.
            val list=api.call("/position/v1/static/fund-accounts/${fund.accountId}/funds",token).jsonObject["list"]!!.jsonArray
            val rows=list.filter {it.jsonObject.text("fund_id")==fund.fundId}
            check(rows.size==1) {"SELECTED_HOLDING_NOT_UNIQUE"}
            val baseline=rows.single().jsonObject
            val shareRaw=baseline.text("hold_share") ?: error("MISSING_SHARES")
            val costRaw=baseline.text("hold_cost") ?: error("MISSING_COST")
            val position=E.Position(E.decimal(shareRaw),E.decimal(costRaw))
            val targetKeys=listOf("ndxProbeShareTarget","ndxProbeCostTarget","ndxProbeShareLimit","ndxProbeCostLimit","ndxProbeTotalCostLimit")
            val supplied=targetKeys.map {args.getString(it)}
            if(mode=="PREPARE")check(supplied.all {it!=null}) {"EXPLICIT_TARGETS_AND_LIMITS_REQUIRED"}
            if(mode=="INSPECT")check(supplied.all {it==null}) {"USE_PREPARE_FOR_TARGETS"}
            var eligible=false
            val record=buildJsonObject {
                put("format",1);put("probeId",UUID.randomUUID().toString());put("capturedAt",Instant.now().toString())
                put("mode",mode);put("realPostExecuted",false);put("realWriteVerified",false)
                put("accountId",fund.accountId);put("fundId",fund.fundId);put("fundCode",fund.code);put("fundName",fund.name)
                put("baseline",baseline);put("baselineShareLexeme",shareRaw);put("baselineCostLexeme",costRaw)
                if(mode=="PREPARE") {
                    val values=supplied.map {requireNotNull(it)}
                    val target=E.Position(E.decimal(values[0]),E.decimal(values[1]))
                    val limits=E.Limits(E.decimal(values[2]),E.decimal(values[3]),E.decimal(values[4]))
                    val safety=E.replacementSafety(position,target,limits)
                    eligible=safety.eligibleForReview
                    put("distinguishing",safety.distinguishing);put("withinExplicitLimits",safety.withinLimits)
                    put("eligibleForUserReview",eligible)
                    put("reviewDigest",E.reviewDigest(fund.accountId,fund.fundId,baseline.toString(),
                        values[0],values[1],values[2],values[3],values[4]))
                    put("targetsAndLimits",buildJsonObject {targetKeys.zip(values).forEach {(key,value)->put(key,value)}})
                    put("possibleOutcomes",JsonArray(safety.outcomes.map {outcome->buildJsonObject {
                        put("shareSemantics",outcome.shareSemantics.name);put("costSemantics",outcome.costSemantics.name)
                        put("shares",outcome.position.shares.toPlainString());put("unitCost",outcome.position.unitCost.toPlainString())
                        put("totalCost",outcome.position.totalCost.toPlainString())
                    }}))
                    // An exact, unrounded request artifact for review, not execution.
                    if(eligible)put("candidateRequestBody",buildJsonObject {
                        put("account_id",Json.parseToJsonElement(fund.accountId));put("sync_optional",0)
                        put("items",buildJsonArray {add(buildJsonObject {
                            put("fund_id",Json.parseToJsonElement(fund.fundId));put("fund_code",fund.code)
                            put("hold_share",Json.parseToJsonElement(values[0]));put("hold_cost",Json.parseToJsonElement(values[1]))
                        })})
                    })
                }
            }
            val encrypted=AndroidEncryptedImports(context,AndroidKeystoreCredentialKeyProvider("ndx_yjb_write_probe_v1"),
                "ndx-yjb-write-probe-v1.enc","ndx-yjb-write-probe-v1")
            val previous=encrypted.read()
            check(encrypted.write(JsonArray(previous+record))) {"STORAGE"}
            check(encrypted.read().last()==record) {"BASELINE_READBACK_FAILED"}
            safeStatus("SELECTED_CODE=${publicText(fund.code)} NAME=${publicText(fund.name)}")
            safeStatus("BASELINE_ENCRYPTED_AND_READBACK_OK=true NONZERO_BASELINE=${position.shares.signum()>0 && position.unitCost.signum()>0}")
            safeStatus("MODE=$mode ELIGIBLE_FOR_REVIEW=$eligible REAL_POST_EXECUTED=false")
        } catch(e:YjbFailure) {
            // Never include raw response, identity, password, token, signature or exception causes.
            throw AssertionError(e.kind.name)
        } finally {token.fill('\u0000')}
    }
    private fun JsonObject.text(key:String)=(this[key] as? JsonPrimitive)?.contentOrNull
    private fun publicText(value:String)=value.replace(Regex("[\\r\\n\\t]")," ").take(100)
    private fun safeStatus(message:String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {putString("ndxProbe",message)})
    }
}
