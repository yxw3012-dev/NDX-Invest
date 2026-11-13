package com.ndxinvest.yjb.buy

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.*
import kotlin.test.*

class CloseoutPersistenceTest {
    private class Requests:CreationRequestStorage {
        var rows=JsonArray(emptyList());var fail=false
        override suspend fun read()=rows
        override suspend fun write(rows:JsonArray){if(fail)throw BuyFailure(BuyProblem.STORAGE);this.rows=Json.parseToJsonElement(rows.toString()).jsonArray}
    }
    @Test fun durableIdSurvivesRestartAndIsRetainedUntilKnownResultIsAcknowledged()=runTest {
        val s=Requests();val body=buildJsonObject {put("fundId","yjb:1:17");put("amountInput","100")}
        val a=CreationRequests(s).prepare("buy-create:",body);val id=a["requestId"]!!.jsonPrimitive.content
        val restarted=CreationRequests(s)
        assertEquals(a,restarted.prepare("buy-create:",body));restarted.acknowledge(id);assertNotNull(restarted.pending())
        assertFailsWith<BuyFailure>{restarted.prepare("buy-create:",buildJsonObject {put("amountInput","200")})}
        val request=buildJsonObject {body.forEach {(k,v)->put(k,v)};put("requestId",id)}
        assertTrue(restarted.accepts("buy-create:",request));assertFalse(restarted.accepts("sell-create:",request))
        val result=buildJsonObject {put("requestId",id);put("ok",true);put("message","已保存")}
        restarted.complete(id,result);assertEquals(result,CreationRequests(s).pending()!!["result"])
        restarted.acknowledge(id);assertNull(restarted.pending())
        assertNotEquals(id,restarted.prepare("buy-create:",body)["requestId"]!!.jsonPrimitive.content)
    }
    @Test fun intentStorageFailureAndMalformedJournalBlockCreation()=runTest {
        val s=Requests();s.fail=true
        assertFailsWith<BuyFailure>{CreationRequests(s).prepare("sell-create:",buildJsonObject {put("sharesInput","1")})}
        s.fail=false;s.rows=JsonArray(listOf(buildJsonObject {put("requestId","bad")}))
        assertEquals(BuyProblem.STORAGE,assertFailsWith<BuyFailure>{CreationRequests(s).pending()}.problem)
    }
    @Test fun formalNavCacheSurvivesRestartRetainsOlderDatesAndRejectsCorrections()=runTest {
        var clock=Instant.parse("2026-10-08T01:00:00Z");var rows=JsonArray(emptyList());var calls=0
        val storage=object:FormalNavStorage {override suspend fun read()=rows;override suspend fun write(next:JsonArray){rows=next}}
        val day=LocalDate.parse("2026-09-30");val later=LocalDate.parse("2026-10-08")
        assertEquals(BigDecimal("2.12345678"),FormalNavCache(storage,{clock}).history("17"){calls++;mapOf(day to BigDecimal("2.12345678"))}[day])
        clock=clock.plusSeconds(299)
        FormalNavCache(storage,{clock}).history("17"){error("restart must reuse fresh persisted NAV")};assertEquals(1,calls)
        clock=clock.plusSeconds(2)
        val merged=FormalNavCache(storage,{clock}).history("17"){calls++;mapOf(later to BigDecimal("3"))}
        assertEquals(setOf(day,later),merged.keys);assertEquals(2,calls)
        clock=clock.plusSeconds(301)
        assertEquals(BuyProblem.NAV_PROTOCOL,assertFailsWith<BuyFailure>{FormalNavCache(storage,{clock}).history("17"){mapOf(day to BigDecimal("4"))}}.problem)
        assertTrue(rows.toString().contains("2.12345678"));assertFalse(rows.toString().contains("token"))
    }
}
