package com.ndxinvest.app.market

import com.ndxinvest.market.model.*
import com.ndxinvest.providers.contract.*
import java.math.BigDecimal
import java.time.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class MarketReadTest {
    private val now=Instant.parse("2026-10-03T10:00:00Z")
    private val clock=Clock.fixed(now,ZoneOffset.UTC)
    private fun quote(time:Instant=now)=NdxQuote(BigDecimal("30807.935"),BigDecimal("306.375"),BigDecimal("0.01004"),time,MarketSessionState.CLOSED,MarketSourceId("tencent"))
    private fun fx(time:Instant=now)=UsdCnyQuote(BigDecimal("6.7050123456789"),BigDecimal("-0.0002"),time,MarketSourceId("tencent"))
    private fun history(start:LocalDate=LocalDate.of(1996,6,6)):List<NdxDaily> {
        val end=LocalDate.of(2026,10,2)
        return generateSequence(start){it.plusDays(1)}.takeWhile {it<=end}.mapIndexed {i,d->
            val p=BigDecimal(1000+i)
            NdxDaily(d,p,p,p,p,MarketSourceId("nasdaq"))
        }.toList()
    }
    private class Cache(var json:JsonObject?=null):MarketCache {
        var fail=false
        override suspend fun read()=json
        override suspend fun write(value:JsonObject):Boolean {if(fail)return false;json=value;return true}
    }
    private class Provider<T>(var result:ProviderResult<T>):DataProvider<T> {
        override val id=ProviderId("test")
        var calls=0
        override suspend fun fetch():ProviderResult<T>{calls++;return result}
    }
    private fun <T> ok(value:T)=ProviderResult.Success(value,ProviderId("test"),now)
    private fun fail()=ProviderResult.Failure(listOf(ProviderFailure(ProviderId("test"),ProviderFailureKind.NETWORK,true,"test failure")))
    private fun repo(index:List<DataProvider<NdxQuote>>,fx:List<DataProvider<UsdCnyQuote>>,history:DataProvider<List<NdxDaily>>,cache:Cache,emit:suspend(MarketReadState)->Unit={}):MarketReadRepository=
        MarketReadRepository(index,fx,{history},cache,clock,emit)

    @Test fun realFactsMappedAndRoundedWithoutChangingPrecision()=runTest {
        var state=MarketReadState()
        repo(listOf(Provider(ok(quote()))),listOf(Provider(ok(fx()))),Provider(fail()),Cache()){state=it}.refresh()
        val json=MarketUiMapper().map(state,now)["nd100"]!!.jsonObject
        assertEquals("30,807.94",json["indexText"]!!.jsonPrimitive.content)
        assertEquals("+306.38",json["changeText"]!!.jsonPrimitive.content)
        assertEquals("+1.00%",json["changePercentText"]!!.jsonPrimitive.content)
        assertEquals("6.7050",json["fxText"]!!.jsonPrimitive.content)
        assertEquals("6.7050123456789",state.fx!!.value.rate.toPlainString())
        assertContains(json["marketStatus"]!!.jsonPrimitive.content,"已休市")
    }
    @Test fun primaryFailureFallsBackIndependently()=runTest {
        val bad=Provider<NdxQuote>(fail());val good=Provider(ok(quote()));val currency=Provider<UsdCnyQuote>(fail())
        var state=MarketReadState()
        repo(listOf(bad,good),listOf(currency),Provider(fail()),Cache()){state=it}.refresh()
        assertEquals(1,bad.calls);assertEquals(1,good.calls);assertNotNull(state.index);assertNull(state.fx)
    }
    @Test fun futureQuoteIsRejectedAndFallbackUsed()=runTest {
        val invalid=Provider(ok(quote(now.plusSeconds(400))))
        var state=MarketReadState()
        repo(listOf(invalid,Provider(ok(quote()))),listOf(Provider(ok(fx()))),Provider(fail()),Cache()){state=it}.refresh()
        assertEquals(now,state.index!!.value.quoteTime)
    }
    @Test fun successfulSnapshotSurvivesRestartAndOfflineRefresh()=runTest {
        val cache=Cache();var state=MarketReadState()
        repo(listOf(Provider(ok(quote()))),listOf(Provider(ok(fx()))),Provider(fail()),cache).refresh()
        repo(listOf(Provider(fail())),listOf(Provider(fail())),Provider(fail()),cache){state=it}.refresh()
        assertEquals(quote(),state.index!!.value);assertTrue(state.index!!.cached);assertTrue(state.fx!!.cached)
        val mapped=MarketUiMapper().map(state,now)
        assertContains(mapped["dataSourceState"]!!.jsonObject["indexStatus"]!!.jsonPrimitive.content,"缓存")
        assertContains(mapped["nd100"]!!.jsonObject["updatedText"]!!.jsonPrimitive.content,"2026-10-03")
    }
    @Test fun refreshDoesNotClearVisibleValues()=runTest {
        val index=Provider(ok(quote()));val currency=Provider(ok(fx()));val seen=mutableListOf<MarketReadState>()
        val r=repo(listOf(index),listOf(currency),Provider(fail()),Cache()){seen+=it}
        r.refresh();index.result=fail();currency.result=fail();seen.clear();r.refresh()
        assertTrue(seen.all {it.index!=null&&it.fx!=null})
        assertTrue(seen.first().loading);assertFalse(seen.last().loading)
    }
    @Test fun coldFailureHasUnknownFieldsInsteadOfMock()=runTest {
        var s=MarketReadState()
        repo(listOf(Provider(fail())),listOf(Provider(fail())),Provider(fail()),Cache()){s=it}.refresh()
        val mapped=MarketUiMapper().map(s,now);val m=mapped["nd100"]!!.jsonObject
        for(field in listOf("indexText","changeText","fxText","drawdownText"))assertEquals("--",m[field]!!.jsonPrimitive.content)
        assertFalse(mapped.toString().contains("Mock"))
        assertTrue(m["performance"]!!.jsonArray.all {it.jsonArray.all {v->v.jsonPrimitive.content=="--"}})
    }
    @Test fun corruptedCacheDoesNotBlockNetwork()=runTest {
        val cache=Cache(buildJsonObject {put("version",1);put("index","malformed")});var s=MarketReadState()
        repo(listOf(Provider(ok(quote()))),listOf(Provider(ok(fx()))),Provider(fail()),cache){s=it}.refresh()
        assertNotNull(s.index);assertFalse(s.index!!.cached);assertNotNull(cache.json)
    }
    @Test fun oneCorruptChannelDoesNotLoseOtherCache() {
        val data=MarketReadRepository.encode(MarketReadState(MarketValue(quote(),now),MarketValue(fx(),now)))
        val broken=JsonObject(data+mapOf("index" to JsonPrimitive("bad")))
        val restored=MarketReadRepository.decode(broken,now)
        assertNull(restored.index);assertEquals(fx(),restored.fx!!.value)
    }
    @Test fun unknownCacheVersionIsIgnored() {
        assertEquals(MarketReadState(),MarketReadRepository.decode(buildJsonObject {put("version",99)},now))
    }
    @Test fun cacheWriteFailureStillShowsNetworkFacts()=runTest {
        val cache=Cache().apply {fail=true};var s=MarketReadState()
        repo(listOf(Provider(ok(quote()))),listOf(Provider(ok(fx()))),Provider(fail()),cache){s=it}.refresh()
        assertTrue(s.cacheFailed);assertNotNull(s.index)
        assertContains(MarketUiMapper().map(s,now)["dataSourceState"]!!.toString(),"缓存未能保存")
    }
    @Test fun cachedQuoteTimeNotMislabelledAsFreshObservation() {
        val old=now.minus(Duration.ofDays(3))
        val s=MarketReadState(index=MarketValue(quote(old),now),fx=MarketValue(fx(old),now))
        val mapped=MarketUiMapper().map(s,now)
        assertContains(mapped["dataSourceState"]!!.toString(),"最近报价 2026-09-30")
        assertContains(mapped["nd100"]!!.jsonObject["updatedText"]!!.jsonPrimitive.content,"2026-09-30")
    }
    @Test fun homeStatusAndTimestampStayCompactWhileDiagnosticsKeepSources() {
        for(session in MarketSessionState.entries) {
            val state=MarketReadState(index=MarketValue(quote().copy(marketState=session),now),loading=true)
            val mapped=MarketUiMapper().map(state,now)
            val home=mapped["nd100"]!!.jsonObject
            assertFalse(home["marketStatus"]!!.jsonPrimitive.content.contains("腾讯"))
            assertEquals("2026-10-03 18:00",home["updatedText"]!!.jsonPrimitive.content)
            assertContains(mapped["dataSourceState"]!!.jsonObject["indexStatus"]!!.jsonPrimitive.content,"腾讯")
        }
    }
    @Test fun refreshSuccessOnlyFollowsFinishedNetworkQuotesWithUsableHistory() {
        val good=MarketReadState(index=MarketValue(quote(),now),fx=MarketValue(fx(),now),history=MarketValue(history(),now,cached=true))
        fun succeeded(s:MarketReadState)=MarketUiMapper().map(s,now)["nd100"]!!.jsonObject["refreshSucceeded"]!!.jsonPrimitive.boolean
        assertTrue(succeeded(good))
        assertFalse(succeeded(good.copy(loading=true)))
        assertFalse(succeeded(good.copy(index=good.index!!.copy(cached=true))))
        assertFalse(succeeded(good.copy(fx=good.fx!!.copy(cached=true))))
        assertFalse(succeeded(good.copy(history=null)))
    }
    @Test fun availableHistoryAllowsDrawdownWithoutInceptionGate() {
        val rows=history()
        val mapped=MarketUiMapper().map(MarketReadState(index=MarketValue(quote(),now),history=MarketValue(rows,now)),now)
        val m=mapped["nd100"]!!.jsonObject
        assertEquals("0.00%",m["drawdownText"]!!.jsonPrimitive.content)
        assertEquals(0,m["drawdownRate"]!!.jsonPrimitive.content.toBigDecimal().signum())
        assertContains(m["historyText"]!!.jsonPrimitive.content,"1996-06-06—2026-10-02")
        assertContains(m["drawdownBasisText"]!!.jsonPrimitive.content,"1996-06-06 至 2026-10-02")
        assertEquals(4,m["performance"]!!.jsonArray.size)
        assertTrue(m["performance"]!!.jsonArray.all {it.jsonArray.all {v->v.jsonPrimitive.content!="--"}})
    }
    @Test fun drawdownUsesAvailableHistoricalPeak() {
        val rows=history()
        val mapped=MarketUiMapper().map(MarketReadState(index=MarketValue(quote().copy(point=BigDecimal("5000")),now),history=MarketValue(rows,now)),now)
        val m=mapped["nd100"]!!.jsonObject
        val expected=BigDecimal("5000").divide(rows.last().close,java.math.MathContext.DECIMAL128).subtract(BigDecimal.ONE).setScale(8,java.math.RoundingMode.HALF_UP)
        assertEquals(0,expected.compareTo(m["drawdownRate"]!!.jsonPrimitive.content.toBigDecimal()))
    }
    @Test fun unavailablePerformancePeriodStaysUnknown() {
        val rows=history(LocalDate.of(2026,9,1))
        val m=MarketUiMapper().map(MarketReadState(history=MarketValue(rows,now)),now)["nd100"]!!.jsonObject
        assertEquals("--",m["performance"]!!.jsonArray[0].jsonArray[0].jsonPrimitive.content)
        assertEquals(4,m["performance"]!!.jsonArray.size)
        assertTrue(m["performance"]!!.jsonArray.all {it.jsonArray.all {v->v.jsonPrimitive.content=="--"}})
    }
    @Test fun historyReusedFor24HoursWithoutExtraRequests()=runTest {
        val cache=Cache(MarketReadRepository.encode(MarketReadState(history=MarketValue(history(LocalDate.of(2026,9,1)),now))))
        val provider=Provider<List<NdxDaily>>(fail())
        repo(listOf(Provider(ok(quote()))),listOf(Provider(ok(fx()))),provider,cache).refresh()
        assertEquals(0,provider.calls)
    }
    @Test fun cachedAvailableRangeRestoresDrawdownAfterRestart()=runTest {
        val rows=history()
        val cache=Cache(MarketReadRepository.encode(MarketReadState(index=MarketValue(quote().copy(point=BigDecimal("5000")),now),history=MarketValue(rows,now))))
        var state=MarketReadState()
        repo(listOf(Provider(fail())),listOf(Provider(fail())),Provider(fail()),cache){state=it}.refresh()
        val mapped=MarketUiMapper().map(state,now)["nd100"]!!.jsonObject
        assertNotEquals("--",mapped["drawdownText"]!!.jsonPrimitive.content)
        assertContains(mapped["historyText"]!!.jsonPrimitive.content,"缓存")
        assertEquals(4,mapped["performance"]!!.jsonArray.size)
    }
    @Test fun drawdownRequiresBothHistoryAndQuoteButNotACompletePerformancePeriod() {
        val rows=history(LocalDate.of(2026,9,1))
        val mapper=MarketUiMapper()
        val missingQuote=mapper.map(MarketReadState(history=MarketValue(rows,now)),now)["nd100"]!!.jsonObject
        assertEquals(JsonNull,missingQuote["drawdownRate"])
        val available=mapper.map(MarketReadState(index=MarketValue(quote().copy(point=BigDecimal("500")),now),history=MarketValue(rows,now)),now)["nd100"]!!.jsonObject
        assertNotEquals("--",available["drawdownText"]!!.jsonPrimitive.content)
        assertTrue(available["performance"]!!.jsonArray.all {it.jsonArray.all {v->v.jsonPrimitive.content=="--"}})
        val missingHistory=mapper.map(MarketReadState(index=MarketValue(quote(),now)),now)["nd100"]!!.jsonObject
        assertEquals(JsonNull,missingHistory["drawdownRate"])
        assertEquals(4,missingHistory["performance"]!!.jsonArray.size)
    }
    @Test fun oldHistoryFailureFallsBackWithoutWiping()=runTest {
        val rows=history(LocalDate.of(2026,9,1));val cache=Cache(MarketReadRepository.encode(MarketReadState(history=MarketValue(rows,now.minusSeconds(90000)))))
        val provider=Provider<List<NdxDaily>>(fail());var state=MarketReadState()
        repo(listOf(Provider(ok(quote()))),listOf(Provider(ok(fx()))),provider,cache){state=it}.refresh()
        assertEquals(1,provider.calls);assertEquals(rows,state.history!!.value);assertTrue(state.history!!.cached)
    }
    @Test fun duplicateHistoryAndImplausibleFxAreRejected() {
        val rows=history(LocalDate.of(2026,9,1))
        assertFailsWith<IllegalArgumentException>{MarketReadRepository.validateHistory(MarketValue(rows+rows.first(),now),now)}
        assertFailsWith<IllegalArgumentException>{MarketReadRepository.validateFx(MarketValue(fx().copy(rate=BigDecimal("1000")),now),now)}
    }
    @Test fun concurrentRefreshOnlyFetchesOnce()=runTest {
        val blocker=CompletableDeferred<Unit>();var calls=0
        val p=object:DataProvider<NdxQuote>{override val id=ProviderId("test");override suspend fun fetch():ProviderResult<NdxQuote>{calls++;blocker.await();return ok(quote())}}
        val r=repo(listOf(p),listOf(Provider(ok(fx()))),Provider(fail()),Cache())
        val first=launch {r.refresh()};yield();r.refresh();blocker.complete(Unit);first.join()
        assertEquals(1,calls)
    }
    @Test fun cancellationIsNotConvertedIntoCachedSuccess()=runTest {
        val p=object:DataProvider<NdxQuote>{override val id=ProviderId("test");override suspend fun fetch():ProviderResult<NdxQuote>{throw CancellationException()}}
        assertFailsWith<CancellationException>{repo(listOf(p),listOf(Provider(ok(fx()))),Provider(fail()),Cache()).refresh()}
    }
}
