package com.ndxinvest.yjb.buy

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfirmationTimeTest {
    private val fund=BuyFund("1","2","000001","测试基金")
    private val day=LocalDate.parse("2026-09-28")
    private val baseline=BuyBaseline(BigDecimal("100"),BigDecimal("1.2"))
    private class Gateway:BuyGateway {
        var nav:BigDecimal?=null
        override suspend fun current(fund:BuyFund)=BuyBaseline(BigDecimal("100"),BigDecimal("1.2"))
        override suspend fun officialNav(fund:BuyFund,targetDate:LocalDate)=nav
        override fun writeAvailable()=false
        override fun normalizeTarget(target:BuyBaseline)=target
        override fun matches(actual:BuyBaseline,target:BuyBaseline)=actual.equivalent(target)
        override suspend fun postFinal(fund:BuyFund,target:BuyBaseline)=error("No writes in confirmation tests")
    }
    @Test fun delayedBuyRemainsWaitingAndRecordsActualFirstObservationAfterRestart()=runTest {
        var clock=Instant.parse("2026-09-28T06:00:00Z")
        val gateway=Gateway()
        val store=object:BuyStorage {
            var data=JsonArray(emptyList())
            override suspend fun read()=BuyCodec.decode(data)
            override suspend fun write(rows:List<BuyTransaction>){data=BuyCodec.encode(rows)}
        }
        val repository=SingleBuyRepository(store,gateway,{code->BundledBuyCalendar.forCode(code)?:BuyCalendar(code,LocalDate.parse("2026-01-01"),LocalDate.parse("2026-12-31"),(0..364).map {LocalDate.parse("2026-01-01").plusDays(it.toLong())}.filter {!BuyNavDates.weekend(it)}.toSet(),"TEST_ONLY_WEEKDAYS")},{clock})
        val transaction=repository.create(BuyInput(fund,BigDecimal("100"),BigDecimal.ZERO,day,CutoffType.BEFORE_1500))
        clock=Instant.parse("2026-09-29T03:00:00Z")
        assertNull(repository.confirmNav(transaction.id).navConfirmedAt)
        clock=Instant.parse("2026-10-08T03:00:00Z");gateway.nav=BigDecimal("4.5700")
        val confirmed=repository.confirmNav(transaction.id)
        assertEquals(day,confirmed.confirmedNavDate);assertEquals(clock,confirmed.navConfirmedAt)
        assertEquals("2026-10-08",BuyCodec.ui(listOf(confirmed)).single().jsonObject["confirmationDate"]!!.jsonPrimitive.content)
        clock=Instant.parse("2026-10-09T03:00:00Z")
        val restarted=SingleBuyRepository(store,gateway,{code->BundledBuyCalendar.forCode(code)?:BuyCalendar(code,LocalDate.parse("2026-01-01"),LocalDate.parse("2026-12-31"),(0..364).map {LocalDate.parse("2026-01-01").plusDays(it.toLong())}.filter {!BuyNavDates.weekend(it)}.toSet(),"TEST_ONLY_WEEKDAYS")},{clock})
        assertEquals(confirmed.navConfirmedAt,restarted.restore().single().navConfirmedAt)
        assertEquals(confirmed.navConfirmedAt,restarted.confirmNav(transaction.id).navConfirmedAt)
    }
    @Test fun buyUsesBeijingObservationDateAndLegacyDataIsNotBackfilled() {
        val created=Instant.parse("2026-09-28T00:00:00Z")
        val t=BuyTransaction("buy",BuyInput(fund,BigDecimal("100"),BigDecimal.ZERO,day,CutoffType.BEFORE_1500),baseline,day,createdAt=created,updatedAt=created,calendarSource="test")
        val ready=BuyCalculation.confirmResolved(t,OfficialBuyNav(day,BigDecimal("2.123456789")),Instant.parse("2026-09-28T17:00:00Z"))
        assertEquals("2026-09-29",BuyCodec.ui(listOf(ready)).single().jsonObject["confirmationDate"]!!.jsonPrimitive.content)
        val legacy=JsonArray(BuyCodec.encode(listOf(ready)).map {JsonObject(it.jsonObject.filterKeys {key->key!="navConfirmedAt"})})
        val restored=BuyCodec.decode(legacy).single()
        assertNull(restored.navConfirmedAt);assertEquals(ready.confirmedNav,restored.confirmedNav)
        assertEquals(JsonNull,BuyCodec.ui(listOf(restored)).single().jsonObject["confirmationDate"])
    }
    @Test fun sellPersistsIndependentConfirmationTimeAndNeverInfersItFromSyncTime() {
        val created=Instant.parse("2026-09-28T00:00:00Z")
        val t=SellTransaction("sell",SellInput(fund,BigDecimal("10"),BigDecimal.ZERO,day,CutoffType.BEFORE_1500),baseline,day,createdAt=created,updatedAt=created,calendarSource="test")
        assertNull(t.navConfirmedAt)
        val actual=Instant.parse("2026-10-08T03:00:00Z")
        val ready=SellCalculation.confirm(t,OfficialBuyNav(day,BigDecimal("4.5700")),actual)
        val synced=ready.copy(status=SellStatus.SYNCED,updatedAt=actual.plusSeconds(3600),syncedAt=actual.plusSeconds(3600))
        val restored=SellCodec.decode(SellCodec.encode(listOf(synced))).single()
        assertEquals(actual,restored.navConfirmedAt);assertEquals(day,restored.confirmedNavDate)
        assertEquals("2026-10-08",SellCodec.ui(listOf(restored)).single().jsonObject["confirmationDate"]!!.jsonPrimitive.content)
        val legacy=JsonArray(SellCodec.encode(listOf(synced)).map {JsonObject(it.jsonObject.filterKeys {key->key!="navConfirmedAt"})})
        assertNull(SellCodec.decode(legacy).single().navConfirmedAt)
    }
}
