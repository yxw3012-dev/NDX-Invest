package com.ndxinvest.yjb.buy

import com.ndxinvest.yjb.read.*
import java.math.RoundingMode
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Evidence of replacement and real POST/GET operating scales is required. No guessed defaults or maximum-scale claims. */
data class VerifiedWritePrecision(val evidence:String,val shareScale:Int,val unitCostScale:Int) {
    init {require(evidence.isNotBlank()&&shareScale in 0..24&&unitCostScale in 0..24)}
}
class YjbFinalPositionWriter(private val profile:VerifiedWritePrecision,
    private val token:suspend()->CharArray?,private val key:suspend()->CharArray,
    private val wire:YjbWire=BuyPostHttpsWire(),private val now:()->Instant=Instant::now,
    private val rateLimit:YjbRateLimit=YjbRateLimit.shared,
):VerifiedYjbWrite {
    override val evidence=profile.evidence
    override fun normalize(target:BuyBaseline)=BuyBaseline(target.shares.setScale(profile.shareScale,RoundingMode.HALF_UP),target.unitCost.setScale(profile.unitCostScale,RoundingMode.HALF_UP),target.exists)
    override fun matches(actual:BuyBaseline,target:BuyBaseline)=normalize(actual).equivalent(normalize(target))
    override suspend fun post(fund:BuyFund,target:BuyBaseline)=postGuarded(fund,target) {}
    override suspend fun postGuarded(fund:BuyFund,target:BuyBaseline,beforeSend:suspend()->Unit) {
        if(!Regex("[0-9]+").matches(fund.accountId)||!Regex("[0-9]+").matches(fund.fundId)||!Regex("[0-9]{6}").matches(fund.fundCode))throw BuyFailure(BuyProblem.INPUT)
        val session=token()?:throw BuyFailure(BuyProblem.AUTH_EXPIRED)
        var secret:CharArray?=null
        try {
            val path="/fund_hold"
            val final=normalize(target)
            val body=buildJsonObject {
                put("account_id",Json.parseToJsonElement(fund.accountId));put("sync_optional",0)
                put("items",buildJsonArray {add(buildJsonObject {put("fund_id",Json.parseToJsonElement(fund.fundId));put("fund_code",fund.fundCode);put("hold_share",Json.parseToJsonElement(final.shares.toPlainString()));put("hold_cost",Json.parseToJsonElement(final.unitCost.toPlainString()))})})
            }
            val response=rateLimit.exchange(retryRead=false) {
                secret=key()
                val timestamp=now().epochSecond.toString()
                val signature=YjbReadApi(wire,key,now).sign(path,session,requireNotNull(secret),timestamp)
                try { beforeSend();wire.exchange(ReadRequest("POST",path,mapOf("Authorization" to "ios:${session.concatToString()}","Request-Time" to timestamp,"Request-Sign" to signature,"Accept" to "application/json"),body.toString())) }
                finally { secret.fill('\u0000') }
            }
            if(response.status==401)throw BuyFailure(BuyProblem.AUTH_EXPIRED)
            if(response.status!=200)throw BuyFailure(BuyProblem.NETWORK)
            val code=Json.parseToJsonElement(response.body).jsonObject["code"]?.jsonPrimitive?.content
            if(code in setOf("1000","401"))throw BuyFailure(BuyProblem.AUTH_EXPIRED)
            if(code!="200")throw BuyFailure(BuyProblem.VERIFY_FAILED)
            // No status transition here: the repository MUST GET and compare the result.
        }catch(e:CancellationException){throw e}catch(e:BuyFailure){throw e}
        catch(e:YjbFailure){throw BuyFailure(if(e.kind==ReadError.RATE_LIMITED)BuyProblem.RATE_LIMITED else BuyProblem.NETWORK)}
        catch(_:Exception){throw BuyFailure(BuyProblem.NETWORK)}
        finally{session.fill('\u0000');secret?.fill('\u0000')}
    }
}
private class BuyPostHttpsWire:YjbWire {
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(java.time.Duration.ofSeconds(30)).build()
    override suspend fun exchange(request:ReadRequest):ReadResponse {
        if(request.method!="POST"||request.path!="/fund_hold")throw BuyFailure(BuyProblem.WRITE_UNVERIFIED)
        val builder=Request.Builder().url(YjbReadApi.BASE+request.path)
        request.headers.forEach {(k,v)->builder.header(k,v)}
        builder.post(requireNotNull(request.body).toRequestBody("application/json".toMediaType()))
        val call=client.newCall(builder.build())
        return suspendCancellableCoroutine {c->c.invokeOnCancellation {call.cancel()};call.enqueue(object:okhttp3.Callback {
            override fun onFailure(call:okhttp3.Call,e:java.io.IOException){if(c.isActive)c.resumeWithException(BuyFailure(BuyProblem.NETWORK))}
            override fun onResponse(call:okhttp3.Call,response:okhttp3.Response){response.use {try {
                rateLimitedResponse(it)?.let { limited -> if(c.isActive)c.resume(limited);return }
                val bytes=it.body.byteStream().use {stream->
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                    while(true){val count=stream.read(buffer);if(count<0)break;out.write(buffer,0,count);if(out.size()>64*1024)throw BuyFailure(BuyProblem.VERIFY_FAILED)}
                    buffer.fill(0);out.toByteArray()
                }
                if(bytes.size>64*1024)throw BuyFailure(BuyProblem.VERIFY_FAILED)
                val result=ReadResponse(it.code,bytes.toString(Charsets.UTF_8));bytes.fill(0)
                if(c.isActive)c.resume(result)
            }catch(_:Exception){if(c.isActive)c.resumeWithException(BuyFailure(BuyProblem.NETWORK))}}}
        })}
    }
}
