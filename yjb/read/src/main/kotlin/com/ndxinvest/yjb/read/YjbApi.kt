package com.ndxinvest.yjb.read

import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class ReadError { AUTH_EXPIRED, NETWORK, PROTOCOL, LOGIN_FAILED, STORAGE, RATE_LIMITED }
class YjbFailure(val kind: ReadError) : Exception(kind.name)

/** Private data is deliberately absent from toString(), logs and thrown errors. */
class ReadRequest(val method: String, val path: String, val headers: Map<String,String>, val body: String?) {
    override fun toString() = "YjbReadRequest[REDACTED]"
}
data class ReadResponse(val status: Int, val body: String, val retryAfter: String? = null) { override fun toString() = "YjbReadResponse[REDACTED]" }
fun interface YjbWire { suspend fun exchange(request: ReadRequest): ReadResponse }

class HttpsYjbWire : YjbWire {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(java.time.Duration.ofSeconds(30)).build()
    override suspend fun exchange(request: ReadRequest): ReadResponse {
        if(!allowedReadPath(request.method,request.path))throw YjbFailure(ReadError.PROTOCOL)
        val builder=Request.Builder().url(YjbReadApi.BASE+request.path)
        request.headers.forEach { (k,v)->builder.header(k,v) }
        if(request.method=="POST")builder.post(requireNotNull(request.body).toRequestBody("application/json".toMediaType()))
        val call=client.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object:okhttp3.Callback {
                override fun onFailure(call:okhttp3.Call,e:java.io.IOException) {
                    if(continuation.isActive)continuation.resumeWithException(YjbFailure(ReadError.NETWORK))
                }
                override fun onResponse(call:okhttp3.Call,response:okhttp3.Response) {
                    try {
                        val result=read(response)
                        if(continuation.isActive)continuation.resume(result)
                    }catch(e:YjbFailure){if(continuation.isActive)continuation.resumeWithException(e)}
                    catch(_:Exception){if(continuation.isActive)continuation.resumeWithException(YjbFailure(ReadError.NETWORK))}
                }
            })
        }
    }
    private fun read(response:okhttp3.Response):ReadResponse = response.use {
        rateLimitedResponse(response)?.let { return it }
        if(response.code==401)return ReadResponse(401,"")
        try {
                val body=response.body
                val bytes=body.byteStream().use { input ->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true) { val n=input.read(buffer);if(n<0)break
                        if(output.size()+n>MAX_RESPONSE_BYTES)throw YjbFailure(ReadError.PROTOCOL)
                        output.write(buffer,0,n)
                    };output.toByteArray()
                }
                try { ReadResponse(response.code,bytes.toString(Charsets.UTF_8)) }finally{bytes.fill(0)}
        } catch (e: YjbFailure) { throw e }
        catch (_: Exception) { throw YjbFailure(ReadError.NETWORK) }
    }
    private companion object { const val MAX_RESPONSE_BYTES=2L*1024*1024 }
}

class YjbReadApi(
    private val wire: YjbWire,
    private val protocolKey: suspend () -> CharArray,
    private val now: () -> Instant = Instant::now,
    private val rateLimit: YjbRateLimit = YjbRateLimit.shared,
) {
    companion object { const val BASE="https://app-api.yangjibao.com" }
    private val json=Json { ignoreUnknownKeys=true }
    suspend fun call(path: String, token: CharArray, body: JsonObject?=null): JsonElement {
        // A closed allowlist: POST is limited to login, read-only market batch and fund search.
        val allowed=allowedReadPath(if(body==null)"GET" else "POST",path)
        if(!allowed) throw YjbFailure(ReadError.PROTOCOL)
        val response=try{rateLimit.exchange(retryRead=body==null||path=="/market/v1/fund/batch") {
            val key=try{protocolKey()}catch(e:CancellationException){throw e}catch(_:Exception){throw YjbFailure(ReadError.STORAGE)}
            val timestamp=now().epochSecond.toString()
            val signature=try { sign(path,token,key,timestamp) } finally { key.fill('\u0000') }
            wire.exchange(ReadRequest(if(body==null) "GET" else "POST",path,
                mapOf("Authorization" to "ios:${token.concatToString()}","Request-Time" to timestamp,
                    "Request-Sign" to signature,"Accept" to "application/json"),body?.toString()))
        }}
            catch(e:CancellationException){throw e}catch(e:YjbFailure){throw e}catch(_:Exception){throw YjbFailure(ReadError.NETWORK)}
        if(response.status==401) throw YjbFailure(ReadError.AUTH_EXPIRED)
        if(response.status!=200) throw YjbFailure(if(response.status>=500) ReadError.NETWORK else ReadError.PROTOCOL)
        val envelope=try { json.parseToJsonElement(response.body).jsonObject } catch (_: Exception) { throw YjbFailure(ReadError.PROTOCOL) }
        val code=(envelope["code"] as? JsonPrimitive)?.contentOrNull
        if(code in setOf("401","1000")) throw YjbFailure(ReadError.AUTH_EXPIRED)
        if(code!="200") throw YjbFailure(ReadError.PROTOCOL)
        return envelope["data"]?.takeUnless { it is JsonNull }
            ?: if(path=="/send_code")JsonObject(emptyMap()) else throw YjbFailure(ReadError.PROTOCOL)
    }
    suspend fun searchFunds(keyword:String,token:CharArray):JsonArray = decode {
        val query=keyword.trim()
        if(query.isEmpty()||query.length>64||query.any {it.isISOControl()})throw YjbFailure(ReadError.PROTOCOL)
        val data=call("/content/v1/search/fund",token,buildJsonObject {put("keyword",query)}).jsonObject
        val funds=data["funds"] as? JsonArray?:throw YjbFailure(ReadError.PROTOCOL)
        if(funds.size>500)throw YjbFailure(ReadError.PROTOCOL)
        JsonArray(funds.map {item->
            val fund=item.jsonObject
            val id=fund.text("fund_id")?.takeIf {Regex("[0-9]{1,20}").matches(it)}?:throw YjbFailure(ReadError.PROTOCOL)
            val code=fund.text("code")?.takeIf {Regex("[0-9]{6}").matches(it)}?:throw YjbFailure(ReadError.PROTOCOL)
            val name=fund.text("name")?.takeIf {it.isNotBlank()&&it.length<=256}?:throw YjbFailure(ReadError.PROTOCOL)
            buildJsonObject {put("fundId",id);put("code",code);put("name",name)}
        })
    }
    suspend fun sendCode(phone: String, device: CharArray) {
        if(!Regex("1[0-9]{10}").matches(phone)) throw YjbFailure(ReadError.LOGIN_FAILED)
        call("/send_code",device,buildJsonObject { put("phone",phone) })
    }
    suspend fun login(phone: String, code: String, device: CharArray): CharArray = decode {
        if(!Regex("1[0-9]{10}").matches(phone)||!Regex("[0-9]{4,8}").matches(code)) throw YjbFailure(ReadError.LOGIN_FAILED)
        val data=call("/login",device,buildJsonObject {
            put("phone",phone);put("mode","phone");put("verify_code",code);put("invite_code","");put("is_band_wechat",1)
        }).jsonObject
        (data["token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it.length<8192 && it.all { c->c.code in 0x21..0x7e } }
            ?.toCharArray() ?: throw YjbFailure(ReadError.LOGIN_FAILED)
    }
    suspend fun snapshot(token: CharArray): JsonObject = decode {
        val account=call("/users/v1/account",token).jsonObject
        val accounts=call("/users/v1/user-account",token).jsonObject["list"] as? JsonArray ?: throw YjbFailure(ReadError.PROTOCOL)
        val groups=accounts.map { item ->
            val a=item.jsonObject;val id=a.text("id")?.takeIf { Regex("[0-9]+").matches(it) } ?: throw YjbFailure(ReadError.PROTOCOL)
            val funds=call("/position/v1/static/fund-accounts/$id/funds",token).jsonObject["list"] as? JsonArray ?: throw YjbFailure(ReadError.PROTOCOL)
            buildJsonObject { put("accountId",id);put("accountTitle",a.text("title")?:"基金账户");put("funds",funds) }
        }
        val ids=groups.flatMap { it["funds"]!!.jsonArray.mapNotNull { f -> f.jsonObject.text("fund_id") } }.distinct()
        val quotes=if(ids.isEmpty()) JsonArray(emptyList()) else {
            val batches=ids.chunked(50).flatMap { batch ->
                val result=call("/market/v1/fund/batch",token,buildJsonObject { put("funds",JsonArray(batch.map { id -> buildJsonObject { put("fund_id",id);put("data_source","1") } })) })
                result as? JsonArray ?: throw YjbFailure(ReadError.PROTOCOL)
            }
            JsonArray(batches)
        }
        // Strip the complete account response; only a display name and masked identity survive.
        val identity=account.text("phone")?:account.text("id")?:""
        buildJsonObject {
            put("displayName",account.text("nickname")?:account.text("nick_name")?:"养基宝账户")
            put("maskedId",if(identity.length>=7) identity.take(3)+"****"+identity.takeLast(4) else "已认证账户")
            put("groups",JsonArray(groups));put("quotes",quotes);put("lastSyncAt",now().toString())
        }
    }
    fun sign(path: String,token: CharArray,key: CharArray,timestamp: String): String {
        val input=(BASE+path.substringBefore('?')+token.concatToString()+key.concatToString()+timestamp).toByteArray(Charsets.UTF_8)
        return try { MessageDigest.getInstance("MD5").digest(input).joinToString("") { "%02x".format(it) } } finally { input.fill(0) }
    }
    private inline fun <T> decode(block:()->T):T=try {block()}
        catch(e:CancellationException){throw e}catch(e:YjbFailure){throw e}catch(_:Exception){throw YjbFailure(ReadError.PROTOCOL)}
}
internal fun allowedReadPath(method:String,path:String)=when(method) {
    "GET"->path in setOf("/users/v1/account","/users/v1/user-account")||Regex("/position/v1/static/fund-accounts/[0-9]+/funds").matches(path)||Regex("/market/v1/fund-nav/fund-history-nav\\?fund_id=[0-9]+").matches(path)
    "POST"->path in setOf("/send_code","/login","/market/v1/fund/batch","/content/v1/search/fund")
    else->false
}
internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
