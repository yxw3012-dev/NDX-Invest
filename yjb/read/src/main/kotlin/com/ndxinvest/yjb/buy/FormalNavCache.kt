package com.ndxinvest.yjb.buy

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

interface FormalNavStorage {
    suspend fun read():JsonArray
    suspend fun write(rows:JsonArray)
}

/** Only formal market NAVs, never holdings. Caller serializes cross-instance writes.
 * Five-minute successful-read reuse survives process restart. Old dates are retained;
 * a correction is an explicit protocol problem, not an automatic rewrite of a synced trade.
 */
class FormalNavCache(private val storage:FormalNavStorage,private val now:()->Instant=Instant::now) {
    private val mutex=Mutex()
    suspend fun history(fundId:String,fetch:suspend()->Map<LocalDate,java.math.BigDecimal>):Map<LocalDate,java.math.BigDecimal> = mutex.withLock {
        val rows=storage.read()
        val entries=try {
            require(rows.size<=1000)
            rows.map {it.jsonObject}.also {require(it.map {r->r["fundId"]!!.jsonPrimitive.content}.distinct().size==it.size)}
        }catch(_:Exception){throw BuyFailure(BuyProblem.STORAGE)}
        val old=entries.singleOrNull {it["fundId"]!!.jsonPrimitive.content==fundId}
        val saved=try {old?.get("navs")?.jsonArray?.map {it.jsonObject}.orEmpty().map {
            LocalDate.parse(it["day"]!!.jsonPrimitive.content) to it["unit_net"]!!.jsonPrimitive.content.toBigDecimal()
        }.also {r->require(r.size<=20000&&r.map {it.first}.distinct().size==r.size&&r.all {it.second.signum()>0&&it.second.precision()<=128&&it.second.scale() in -64..64})}.toMap()}
        catch(_:Exception){throw BuyFailure(BuyProblem.STORAGE)}
        val time=now()
        val last=try {old?.get("readAt")?.jsonPrimitive?.content?.let(Instant::parse)}catch(_:Exception){throw BuyFailure(BuyProblem.STORAGE)}
        if(last!=null&&last<=time&&time<last.plusSeconds(300))return@withLock saved
        val fetched=fetch()
        if(fetched.any {(date,value)->saved[date]?.compareTo(value)?.let {it!=0}==true})throw BuyFailure(BuyProblem.NAV_PROTOCOL)
        val merged=saved+fetched
        if(merged.size>20000)throw BuyFailure(BuyProblem.STORAGE)
        val row=buildJsonObject {
            put("fundId",fundId);put("readAt",time.toString())
            put("navs",JsonArray(merged.toSortedMap().map {(date,value)->buildJsonObject {put("day",date.toString());put("unit_net",value.toPlainString())}}))
        }
        storage.write(JsonArray(entries.filter {it["fundId"]!!.jsonPrimitive.content!=fundId}+row))
        merged
    }
}
