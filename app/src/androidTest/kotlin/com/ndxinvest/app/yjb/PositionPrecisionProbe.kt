package com.ndxinvest.app.yjb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ndxinvest.app.BuildConfig
import com.ndxinvest.yjb.buy.*
import com.ndxinvest.yjb.read.*
import com.ndxinvest.yjb.verification.ExistingPositionEvidence as E
import com.ndxinvest.yjb.verification.WritePrecisionEvidence as W
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.util.UUID

/** Test APK only. PLAN is read-only. POST requires an explicitly reviewed digest and a one-POST opt-in. */
@RunWith(AndroidJUnit4::class)
class PositionPrecisionProbe {
    @Test fun controlledPrecisionOnly()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val mode=args.getString("ndxPrecisionMode")
        assumeTrue(BuildConfig.FLAVOR=="production" && mode in setOf("PLAN","POST","RECONCILE"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val session=AndroidYjbStorage(context)
        val token=session.token()?:error("NO_SESSION")
        val evidence=AndroidWriteEvidenceStore(context)
        val api=YjbReadApi(HttpsYjbWire(),session::protocolKey)
        try {
            withContext(Dispatchers.IO) {
                // Serialize explicit invocations across process restarts; the persisted marker is the one-POST guard.
                RandomAccessFile(File(context.noBackupFilesDir,"ndx-yjb-precision-probe.lock"),"rw").use {file->
                    file.channel.lock().use {
                        when(mode) {
                            "PLAN"->plan(api,token,evidence)
                            else->{
                                val digest=args.getString("ndxApprovedDigest")?:error("REVIEWED_DIGEST_REQUIRED")
                                val rows=evidence.read()
                                val index=rows.indexOfLast {it.jsonObject.text("reviewDigest")==digest}
                                check(index>=0) {"PLAN_NOT_FOUND"}
                                var record=rows[index].jsonObject
                                check(record.text("fundCode")=="160213") {"WRONG_SELECTED_FUND"}
                                if(mode=="POST") {
                                    check(args.getString("ndxAllowRealWrite")=="YES_ONE_POST") {"EXPLICIT_ONE_POST_APPROVAL_REQUIRED"}
                                    check(!record.flag("realPostMayHaveBeenSent")) {"POST_ALREADY_ATTEMPTED_USE_GET_ONLY"}
                                    check(record.flag("eligibleForReview")) {"PLAN_OUTSIDE_LIMITS"}
                                    val baseline=record["baseline"]!!.jsonObject
                                    val fresh=current(api,token,record.text("accountId"),record.text("fundId"))
                                    check(position(fresh).equivalent(position(baseline))) {"BASELINE_CHANGED_NO_POST"}
                                    val rebound=E.reviewDigest(record.text("accountId"),record.text("fundId"),baseline.toString(),
                                        record.text("shareTarget"),record.text("costTarget"),record.text("shareLimit"),record.text("costLimit"),record.text("totalCostLimit"))
                                    check(rebound==digest) {"PLAN_DIGEST_CHANGED"}
                                    check(record["candidateRequestBody"]==requestBody(record.text("accountId"),record.text("fundId"),record.text("fundCode"),
                                        record.text("shareTarget"),record.text("costTarget"))) {"REQUEST_DIFFERS_FROM_REVIEWED_TARGET"}
                                    record=JsonObject(record+buildJsonObject {
                                        put("approvedDigest",digest);put("freshBaseline",fresh);put("freshBaselineChecked",true)
                                        put("ownedAccountChecked",true);put("postStartedAt",Instant.now().toString())
                                        put("realPostMayHaveBeenSent",true);put("status","POST_MAY_HAVE_BEEN_SENT")
                                    })
                                    save(evidence,index,record) // never send if marker cannot be persisted and read back
                                    val result=try {postExact(session,token,record)}catch(e:CancellationException){throw e}
                                        catch(e:YjbFailure){e.kind.name}catch(_:Exception){"NETWORK_OR_PROTOCOL"}
                                    record=JsonObject(record+mapOf("postResultCategory" to JsonPrimitive(result)))
                                    save(evidence,index,record)
                                }else check(record.flag("realPostMayHaveBeenSent")) {"NO_POST_TO_RECONCILE"}
                                verify(api,token,evidence,index,record) // exactly one GET attempt; no second POST
                            }
                        }
                    }
                }
            }
        }catch(e:YjbFailure){throw AssertionError(e.kind.name)}
        finally{token.fill('\u0000')}
    }

    private suspend fun plan(api:YjbReadApi,token:CharArray,store:AndroidWriteEvidenceStore) {
        // Fixed to the fund explicitly selected by the user. No fund-search/add/remove API.
        val snapshot=api.snapshot(token)
        val quotes=snapshot["quotes"]!!.jsonArray.associate {it.jsonObject.text("fund_id") to it.jsonObject}
        val selected=snapshot["groups"]!!.jsonArray.flatMap {g->g.jsonObject["funds"]!!.jsonArray.mapNotNull {h->
            val row=h.jsonObject;val q=quotes[row.text("fund_id")]
            if((q?.text("code")?:row.text("code"))=="160213")Triple(g.jsonObject.text("accountId"),row.text("fund_id"),q?.text("short_name")?:row.text("short_name")) else null
        }}
        check(selected.size==1) {"SELECTED_FUND_MISSING_OR_AMBIGUOUS"}
        val (accountId,fundId,name)=selected.single()
        val baseline=current(api,token,accountId,fundId)
        val old=position(baseline)
        // These are proposed identifiable test inputs, not claims about accepted precision. Never executed by PLAN.
        val s=old.shares.add("0.01012345".toBigDecimal()).toPlainString()
        val c=old.unitCost.add("0.00012345".toBigDecimal()).toPlainString()
        val sl="0.02";val cl="0.0002";val tl="1.00"
        val target=E.Position(E.decimal(s),E.decimal(c))
        val safety=E.replacementSafety(E.Position(old.shares,old.unitCost),target,E.Limits(E.decimal(sl),E.decimal(cl),E.decimal(tl)))
        val digest=E.reviewDigest(accountId,fundId,baseline.toString(),s,c,sl,cl,tl)
        val record=buildJsonObject {
            put("format",1);put("probeId",UUID.randomUUID().toString());put("capturedAt",Instant.now().toString())
            put("accountId",accountId);put("fundId",fundId);put("fundCode","160213");put("fundName",name)
            put("semanticsEvidence",W.SEMANTICS);put("baseline",baseline);put("reviewDigest",digest)
            put("shareTarget",s);put("costTarget",c);put("shareLimit",sl);put("costLimit",cl);put("totalCostLimit",tl)
            put("eligibleForReview",safety.eligibleForReview);put("realPostMayHaveBeenSent",false);put("getVerified",false)
            put("candidateRequestBody",requestBody(accountId,fundId,"160213",s,c));put("status","PLAN_ONLY")
        }
        store.write(JsonArray(store.read()+record))
        // Explicit review output contains only public fund metadata and the proposed numeric comparison, never credentials/account IDs.
        status("ndxPrecisionReview",buildJsonObject {
            put("fundCode","160213");put("fundName",name);put("reviewDigest",digest)
            put("baselineShares",old.shares.toPlainString());put("baselineUnitCost",old.unitCost.toPlainString())
            put("submittedShares",s);put("submittedUnitCost",c)
            put("shareChange",(target.shares-old.shares).toPlainString());put("unitCostChange",(target.unitCost-old.unitCost).toPlainString())
            put("totalCostChange",(target.totalCost-old.totalCost).toPlainString())
            put("maxTotalCostChange",tl);put("eligibleForReview",safety.eligibleForReview);put("realPostExecuted",false)
        }.toString())
    }

    private suspend fun verify(api:YjbReadApi,token:CharArray,store:AndroidWriteEvidenceStore,index:Int,record:JsonObject) {
        val actual=try {current(api,token,record.text("accountId"),record.text("fundId"))}
        catch(e:CancellationException){throw e}catch(e:Exception) {
            save(store,index,JsonObject(record+mapOf("status" to JsonPrimitive("GET_PENDING"),"getError" to JsonPrimitive((e as? YjbFailure)?.kind?.name?:"PROTOCOL"))))
            status("ndxPrecisionResult","GET_PENDING_NO_SECOND_POST");return
        }
        val proposed=JsonObject(record+buildJsonObject {put("actual",actual);put("getAt",Instant.now().toString());put("getVerified",true)})
        val verified=runCatching {W.verified(proposed)}.getOrNull()
        val final=JsonObject(proposed+buildJsonObject {
            put("getVerified",verified!=null);put("status",if(verified!=null)"PRECISION_VERIFIED" else "PRECISION_UNPROVEN")
            if(verified!=null){put("shareScale",verified.profile.shareScale);put("unitCostScale",verified.profile.unitCostScale)}
        })
        save(store,index,final)
        status("ndxPrecisionResult",buildJsonObject {
            put("getVerified",verified!=null);put("status",final["status"]!!)
            put("submittedShares",record.text("shareTarget"));put("returnedShares",actual.text("hold_share"))
            put("submittedUnitCost",record.text("costTarget"));put("returnedUnitCost",actual.text("hold_cost"))
            if(verified!=null){put("shareScale",verified.profile.shareScale);put("unitCostScale",verified.profile.unitCostScale)
                put("requestedShareDigits",verified.shares.requestedDigits);put("returnedShareDigits",verified.shares.returnedDigits)
                put("requestedCostDigits",verified.cost.requestedDigits);put("returnedCostDigits",verified.cost.returnedDigits)}
        }.toString())
    }
    private suspend fun current(api:YjbReadApi,token:CharArray,account:String,fund:String):JsonObject {
        check(Regex("[0-9]+").matches(account)&&Regex("[0-9]+").matches(fund)) {"INVALID_SELECTION"}
        val accounts=api.call("/users/v1/user-account",token).jsonObject["list"]!!.jsonArray
        check(accounts.any {it.jsonObject.text("id")==account}) {"ACCOUNT_MISMATCH"}
        val rows=api.call("/position/v1/static/fund-accounts/$account/funds",token).jsonObject["list"]!!.jsonArray
            .filter {it.jsonObject.text("fund_id")==fund}
        check(rows.size==1) {"HOLDING_MISSING_OR_AMBIGUOUS"}
        return rows.single().jsonObject
    }
    private suspend fun postExact(storage:AndroidYjbStorage,token:CharArray,record:JsonObject):String {
        val timestamp=Instant.now().epochSecond.toString();val secret=storage.protocolKey()
        val signature=try {YjbReadApi(HttpsYjbWire(),storage::protocolKey).sign("/fund_hold",token,secret,timestamp)}finally {secret.fill('\u0000')}
        val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(java.time.Duration.ofSeconds(30)).build()
        val request=Request.Builder().url(YjbReadApi.BASE+"/fund_hold")
            .header("Authorization","ios:${token.concatToString()}").header("Request-Time",timestamp).header("Request-Sign",signature)
            .header("Accept","application/json").post(record["candidateRequestBody"]!!.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).execute().use {response->
            if(response.code==401)return@use "AUTH_EXPIRED"
            if(response.code!=200)return@use "HTTP_FAILURE"
            val bytes=response.body.byteStream().use {input->
                val out=java.io.ByteArrayOutputStream();val chunk=ByteArray(4096)
                while(true){val n=input.read(chunk);if(n<0)break;check(out.size()+n<=65536) {"PROTOCOL"};out.write(chunk,0,n)}
                out.toByteArray()
            }
            try {
                when(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["code"]?.jsonPrimitive?.content) {
                    "200"->"HTTP_OK_NOT_YET_VERIFIED";"401","1000"->"AUTH_EXPIRED";else->"ENVELOPE_FAILURE"
                }
            }finally{bytes.fill(0)}
        }
    }
    private fun requestBody(account:String,fund:String,code:String,s:String,c:String)=buildJsonObject {
        put("account_id",Json.parseToJsonElement(account));put("sync_optional",0)
        put("items",buildJsonArray {add(buildJsonObject {
            put("fund_id",Json.parseToJsonElement(fund));put("fund_code",code)
            put("hold_share",Json.parseToJsonElement(s));put("hold_cost",Json.parseToJsonElement(c))
        })})
    }
    private suspend fun save(store:AndroidWriteEvidenceStore,index:Int,record:JsonObject) {
        val rows=store.read();check(index in rows.indices) {"STORAGE"}
        store.write(JsonArray(rows.mapIndexed {i,row->if(i==index)record else row}))
    }
    private fun position(row:JsonObject)=BuyBaseline(E.decimal(row.text("hold_share")),E.decimal(row.text("hold_cost")))
    private fun JsonObject.text(k:String)=(get(k) as? JsonPrimitive)?.contentOrNull?:error("PROTOCOL")
    private fun JsonObject.flag(k:String)=(get(k) as? JsonPrimitive)?.booleanOrNull==true
    private fun status(key:String,value:String)=InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {putString(key,value)})
}
