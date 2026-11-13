package com.ndxinvest.app.yjb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import com.ndxinvest.yjb.buy.*
import com.ndxinvest.yjb.read.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Instant

/** Test APK only: one explicitly authorized initial-position creation, never a BUY. */
@RunWith(AndroidJUnit4::class)
class InitialPositionCreateProbe {
    @Test fun createDachengOnceAndReadBack()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ndxAllowInitialCreate")=="YES_ONE_POST_000834_100_6.4142")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val storage=AndroidYjbStorage(context)
        val api=YjbReadApi(HttpsYjbWire(),storage::protocolKey)
        val encrypted=AndroidEncryptedImports(context,AndroidKeystoreCredentialKeyProvider("ndx_initial_create_probe_v1"),"ndx-initial-create-probe-v1.enc","ndx-initial-create-probe-v1")
        val target=BuyBaseline(BigDecimal("100.00"),BigDecimal("6.4142"))
        val token=storage.token()?:error("NO_APP_SESSION")
        try {
            NativeTransactionGate.mutex.withLock {
                val matches=api.searchFunds("000834",token).map {it.jsonObject}.filter {it["code"]!!.jsonPrimitive.content=="000834"}
                check(matches.size==1&&matches.single()["fundId"]!!.jsonPrimitive.content=="10869") {"FUND_IDENTITY_MISMATCH"}
                val accounts=api.call("/users/v1/user-account",token).jsonObject["list"]!!.jsonArray
                check(accounts.size==1) {"ACCOUNT_SELECTION_REQUIRED"}
                val account=accounts.single().jsonObject["id"]!!.jsonPrimitive.content
                check(Regex("[0-9]+").matches(account)) {"INVALID_ACCOUNT"}
                val path="/position/v1/static/fund-accounts/$account/funds"
                val before=api.call(path,token).jsonObject
                val list=before["list"]!!.jsonArray
                fun selected(rows:JsonArray)=rows.filter {it.jsonObject["fund_id"]!!.jsonPrimitive.content=="10869"}
                fun position(row:JsonElement)=row.jsonObject.let {BuyBaseline(BigDecimal(it["hold_share"]!!.jsonPrimitive.content),BigDecimal(it["hold_cost"]!!.jsonPrimitive.content))}
                val existing=selected(list)
                if(existing.isNotEmpty()) {
                    check(existing.size==1&&position(existing.single()).equivalent(target)) {"EXISTING_POSITION_DIFFERS_NO_POST"}
                    status("TARGET_ALREADY_PRESENT_VERIFIED; NO_POST")
                    return@withLock
                }
                check(encrypted.read().isEmpty()) {"PRIOR_ATTEMPT_NO_REPOST"}
                val record=buildJsonObject {
                    put("createdAt",Instant.now().toString());put("accountId",account);put("fundCode","000834");put("fundId","10869")
                    put("baseline",before);put("targetShares","100.00");put("targetUnitCost","6.4142");put("postMayHaveBeenSent",true)
                }
                check(encrypted.write(JsonArray(listOf(record)))&&encrypted.read().single()==record) {"EVIDENCE_SAVE_FAILED_NO_POST"}
                status("BASELINE_ABSENT; ONE_POST_START")
                val writer=YjbFinalPositionWriter(BundledYjbWriteProtocol.precision,storage::token,storage::protocolKey)
                val postCategory=try {writer.post(BuyFund(account,"10869","000834","大成纳斯达克100ETF联接(QDII)A"),target);"ACKNOWLEDGED"}
                    catch(e:CancellationException){throw e}catch(e:BuyFailure){e.problem.name}catch(_:Exception){"UNAVAILABLE"}
                status("POST_RESULT=$postCategory; NO_RETRY")
                val after=api.call(path,token).jsonObject
                val rows=after["list"]!!.jsonArray
                val actual=selected(rows)
                val verified=actual.size==1&&position(actual.single()).equivalent(target)
                val others=rows.filter {it.jsonObject["fund_id"]!!.jsonPrimitive.content!="10869"}
                val unchanged=others.size==list.size&&list.all {old->
                    val same=others.filter {it.jsonObject["fund_id"]==old.jsonObject["fund_id"]}
                    same.size==1&&position(old).equivalent(position(same.single()))
                }
                val completed=JsonObject(record+buildJsonObject {put("postResult",postCategory);put("actual",after);put("getVerified",verified);put("otherPositionsUnchanged",unchanged)})
                check(encrypted.write(JsonArray(listOf(completed)))) {"EVIDENCE_SAVE_FAILED"}
                status("GET_VERIFIED=$verified; OTHER_POSITIONS_UNCHANGED=$unchanged")
                if(actual.size==1) {
                    val h=actual.single().jsonObject
                    status("SHARES=${h["hold_share"]?.jsonPrimitive?.content}; UNIT_COST=${h["hold_cost"]?.jsonPrimitive?.content}; MARKET_VALUE=${h["money"]?.jsonPrimitive?.content}; PROFIT=${h["hold_earn"]?.jsonPrimitive?.content}")
                }
                check(verified&&unchanged) {"NOT_VERIFIED_NO_RETRY"}
            }
        }catch(e:YjbFailure){throw AssertionError(e.kind.name)}finally{token.fill('\u0000')}
    }
    private fun status(message:String){InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {putString("ndxInitialCreate",message)})}
}
