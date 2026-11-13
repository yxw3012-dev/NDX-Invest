package com.ndxinvest.yjb.buy

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

interface CreationRequestStorage {
    suspend fun read():JsonArray
    suspend fun write(rows:JsonArray)
}

/** One encrypted durable intent, retained until the browser acknowledges a known result.
 * The caller holds the native transaction gate when reconciling against actual journals.
 */
class CreationRequests(private val storage:CreationRequestStorage,private val id:()->String={UUID.randomUUID().toString()}) {
    private val mutex=Mutex()
    private suspend fun current():JsonObject?=try {
        val rows=storage.read();require(rows.size<=1)
        rows.firstOrNull()?.jsonObject?.also {r->
            UUID.fromString(r["requestId"]!!.jsonPrimitive.content)
            require(r["prefix"]!!.jsonPrimitive.content in setOf("buy-create:","sell-create:","plan-action:"))
            val body=r["body"]!!.jsonObject;require(body.toString().length<=7000&&!body.containsKey("requestId"))
            if(r["prefix"]!!.jsonPrimitive.content=="plan-action:")require(body["action"]?.jsonPrimitive?.content=="create")
            r["result"]?.jsonObject?.let {require(it["requestId"]==r["requestId"]);require(it["ok"]!!.jsonPrimitive.booleanOrNull!=null)}
        }
    }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){throw BuyFailure(BuyProblem.STORAGE)}
    suspend fun prepare(prefix:String,body:JsonObject):JsonObject=mutex.withLock {
        if(prefix !in setOf("buy-create:","sell-create:","plan-action:")||body.toString().length>7000||body.containsKey("requestId"))throw BuyFailure(BuyProblem.INPUT)
        if(prefix=="plan-action:"&&body["action"]?.jsonPrimitive?.content!="create")throw BuyFailure(BuyProblem.INPUT)
        current()?.let {existing->
            if(existing["prefix"]?.jsonPrimitive?.content!=prefix||existing["body"]!=body)throw BuyFailure(BuyProblem.PENDING_TRANSACTION)
            return@withLock existing
        }
        val next=buildJsonObject {put("requestId",id());put("prefix",prefix);put("body",body)}
        storage.write(JsonArray(listOf(next)));next
    }
    suspend fun pending():JsonObject?=mutex.withLock {current()}
    suspend fun accepts(prefix:String,request:JsonObject):Boolean=mutex.withLock {
        val existing=current()?:return@withLock false
        existing["requestId"]==request["requestId"]&&existing["prefix"]?.jsonPrimitive?.content==prefix&&existing["body"]==JsonObject(request.filterKeys {it!="requestId"})
    }
    suspend fun complete(requestId:String,result:JsonObject):JsonObject=mutex.withLock {
        val existing=current()?:return@withLock result
        if(existing["requestId"]?.jsonPrimitive?.content!=requestId)return@withLock result
        val next=buildJsonObject {existing.forEach {(k,v)->put(k,v)};put("result",result)}
        storage.write(JsonArray(listOf(next)));result
    }
    suspend fun acknowledge(requestId:String)=mutex.withLock {
        val existing=current()?:return@withLock
        if(existing["requestId"]?.jsonPrimitive?.content==requestId&&existing.containsKey("result"))storage.write(JsonArray(emptyList()))
    }
}
