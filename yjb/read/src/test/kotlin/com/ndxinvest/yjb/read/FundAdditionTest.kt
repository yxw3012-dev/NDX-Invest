package com.ndxinvest.yjb.read

import com.ndxinvest.yjb.buy.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class FundAdditionTest {
    @Test fun missingHoldingRequiresExplicitLocalAuthorizationAndLiveOwnedAccount()=runTest {
        var owns=true;var funds="[]"
        val wire=YjbWire {r->val list=if(r.path=="/users/v1/user-account")if(owns)"[{\"id\":1}]" else "[]" else funds;ReadResponse(200,"{\"code\":200,\"data\":{\"list\":$list}}")}
        val api=YjbReadApi(wire,{"fake-key".toCharArray()})
        assertFailsWith<BuyFailure>{YjbBuyGateway(api,{"fake-session".toCharArray()}).current(fund)}
        val gateway=YjbBuyGateway(api,{"fake-session".toCharArray()},allowAbsent={it==fund})
        assertFalse(gateway.current(fund).exists)
        funds="[{\"fund_id\":10869,\"hold_share\":\"0\",\"hold_cost\":\"0\"}]"
        assertTrue(gateway.current(fund).exists)
        owns=false;assertEquals(BuyProblem.ACCOUNT_MISMATCH,assertFailsWith<BuyFailure>{gateway.current(fund)}.problem)
    }
    @Test fun completedFirstBuyDoesNotGenerateADuplicateInitialImport() {
        val raw=buildJsonObject {put("groups",buildJsonArray {add(buildJsonObject {put("accountId","1");put("funds",buildJsonArray {add(buildJsonObject {put("fund_id","10869");put("hold_share","50");put("hold_cost","2")})})})});put("quotes",JsonArray(emptyList()));put("lastSyncAt","2026-10-07T00:00:00Z")}
        assertTrue(InitialPositionStore.import(JsonArray(emptyList()),raw,setOf(fund.uiId)).isEmpty())
        val imported=InitialPositionStore.import(JsonArray(emptyList()),raw)
        assertEquals(1,imported.size);assertEquals(imported,InitialPositionStore.import(imported,raw))
    }
    private val fund=BuyFund("1","10869","000834","测试基金")
    private class Memory:LocalFundStorage {
        var rows=JsonArray(emptyList());var fail=false
        override suspend fun read()=rows
        override suspend fun write(rows:JsonArray):Boolean {if(fail)return false;this.rows=rows;return true}
    }
    private class Gateway:BuyGateway {
        var position=BuyBaseline(BigDecimal.ZERO,BigDecimal.ZERO,false);var posts=0;var lost=false;var timeout=false;var available=true
        override suspend fun current(fund:BuyFund)=position
        override suspend fun officialNav(fund:BuyFund,targetDate:LocalDate)=BigDecimal("2")
        override fun writeAvailable()=available
        override fun normalizeTarget(target:BuyBaseline)=target.copy(shares=target.shares.setScale(2,java.math.RoundingMode.HALF_UP),unitCost=target.unitCost.setScale(4,java.math.RoundingMode.HALF_UP))
        override fun matches(actual:BuyBaseline,target:BuyBaseline)=normalizeTarget(actual).equivalent(normalizeTarget(target))
        override suspend fun postFinal(fund:BuyFund,target:BuyBaseline){posts++;if(!lost)position=target;if(timeout)throw BuyFailure(BuyProblem.NETWORK)}
    }
    @Test fun zeroPlaceholderIsPersistentAndNeverWritesOrCreatesATransaction()=runTest {
        val store=Memory();val gateway=Gateway();val service=FundAddition(store,gateway)
        assertEquals("PLACEHOLDER",service.add(fund,BigDecimal.ZERO,BigDecimal.ZERO));assertEquals(0,gateway.posts)
        val restarted=FundAddition(store,gateway);assertTrue(restarted.permitsAbsent(fund))
        assertFailsWith<BuyFailure>{restarted.add(fund,BigDecimal.ZERO,BigDecimal.ZERO)}
        assertEquals(1,store.rows.size);assertFalse(store.rows.toString().contains("BUY"))
    }
    @Test fun positiveAdditionWritesOneFinalTargetAndRequiresGetVerification()=runTest {
        val store=Memory();val gateway=Gateway();val service=FundAddition(store,gateway)
        assertEquals("VERIFIED",service.add(fund,BigDecimal("100.001"),BigDecimal("6.4142123")))
        assertEquals(1,gateway.posts);assertEquals(BigDecimal("100.00"),gateway.position.shares);assertEquals(BigDecimal("6.4142"),gateway.position.unitCost)
        assertEquals("IMPORTED",store.rows.single().jsonObject["kind"]!!.jsonPrimitive.content);assertFalse(service.permitsAbsent(fund))
        assertFailsWith<BuyFailure>{service.add(fund,BigDecimal("100.001"),BigDecimal("6.4142123"))};assertEquals(1,gateway.posts)
    }
    @Test fun timeoutActuallyAppliedIsVerifiedWithoutAnotherPost()=runTest {
        val store=Memory();val gateway=Gateway().apply {timeout=true}
        assertEquals("VERIFIED",FundAddition(store,gateway).add(fund,BigDecimal.ONE,BigDecimal.ONE));assertEquals(1,gateway.posts)
    }
    @Test fun uncertainWriteRemainsGetOnlyAcrossRestartAndCannotChangeTarget()=runTest {
        val store=Memory();val gateway=Gateway().apply {lost=true;timeout=true};val s=FundAddition(store,gateway)
        assertEquals(BuyProblem.WRITE_PENDING,assertFailsWith<BuyFailure>{s.add(fund,BigDecimal.ONE,BigDecimal.ONE)}.problem)
        val restart=FundAddition(store,gateway)
        assertFailsWith<BuyFailure>{restart.add(fund,BigDecimal.ONE,BigDecimal.ONE)};assertEquals(1,gateway.posts)
        assertFailsWith<BuyFailure>{restart.add(fund,BigDecimal.TEN,BigDecimal.ONE)};assertEquals(1,gateway.posts)
        gateway.position=BuyBaseline(BigDecimal.ONE,BigDecimal.ONE)
        assertEquals("VERIFIED",restart.add(fund,BigDecimal.ONE,BigDecimal.ONE));assertEquals(1,gateway.posts)
    }
    @Test fun duplicateExternalPositionAndStorageFailureNeverWrite()=runTest {
        val gateway=Gateway().apply {position=BuyBaseline(BigDecimal.TEN,BigDecimal.ONE)}
        assertEquals(BuyProblem.EXTERNAL_CHANGED,assertFailsWith<BuyFailure>{FundAddition(Memory(),gateway).add(fund,BigDecimal.ONE,BigDecimal.ONE)}.problem);assertEquals(0,gateway.posts)
        val fresh=Gateway();assertEquals(BuyProblem.STORAGE,assertFailsWith<BuyFailure>{FundAddition(Memory().apply {fail=true},fresh).add(fund,BigDecimal.ONE,BigDecimal.ONE)}.problem);assertEquals(0,fresh.posts)
    }
    @Test fun invalidCombinationsAndSubPrecisionSharesAreRejected()=runTest {
        val gateway=Gateway();val s=FundAddition(Memory(),gateway)
        assertFailsWith<IllegalArgumentException>{s.add(fund,BigDecimal.ZERO,BigDecimal.ONE)}
        assertFailsWith<IllegalArgumentException>{s.add(fund,BigDecimal("-1"),BigDecimal.ONE)}
        assertFailsWith<BuyFailure>{s.add(fund,BigDecimal("0.001"),BigDecimal.ONE)};assertEquals(0,gateway.posts)
        assertEquals("VERIFIED",FundAddition(Memory(),gateway).add(fund,BigDecimal.ONE,BigDecimal.ZERO))
    }
    @Test fun placeholderNavSurvivesRestartAndPlatformPositionReplacesSameCard()=runTest {
        val store=Memory();val service=FundAddition(store,Gateway());service.add(fund,BigDecimal.ZERO,BigDecimal.ZERO)
        service.rememberNav(fund.fundId,OfficialBuyNav(LocalDate.parse("2026-09-30"),BigDecimal("6.4259")))
        val raw=buildJsonObject {put("fundCatalog",JsonArray(emptyList()));put("holdings",JsonArray(emptyList()));put("details",JsonArray(emptyList()));put("overview",buildJsonObject {put("amount","--");put("metrics",JsonArray(List(4){JsonPrimitive("--")}))})}
        val merged=LocalFundProjection.merge(raw,store.rows,setOf("1"),emptyMap())
        assertEquals(1,merged["holdings"]!!.jsonArray.size);assertEquals("--",merged["holdings"]!!.jsonArray[0].jsonObject["amount"]!!.jsonPrimitive.content)
        assertEquals("6.4259",merged["details"]!!.jsonArray[0].jsonObject["nav"]!!.jsonPrimitive.content);assertEquals(raw["overview"],merged["overview"])
        assertTrue(LocalFundProjection.merge(raw,store.rows,setOf("2"),emptyMap())["holdings"]!!.jsonArray.isEmpty())
        val actual=JsonObject(raw+mapOf("fundCatalog" to merged["fundCatalog"]!!,"holdings" to merged["holdings"]!!,"details" to merged["details"]!!))
        assertEquals(1,LocalFundProjection.merge(actual,store.rows,setOf("1"),emptyMap())["holdings"]!!.jsonArray.size)
        service.reconcile(actual);assertEquals("RESOLVED",store.rows[0].jsonObject["kind"]!!.jsonPrimitive.content);assertFalse(service.permitsAbsent(fund))
    }
    @Test fun removeIsLocalOnlyAndRejectsPendingTransactions()=runTest {
        val store=Memory();val gateway=Gateway();val service=FundAddition(store,gateway);service.add(fund,BigDecimal.ZERO,BigDecimal.ZERO)
        assertFailsWith<IllegalArgumentException>{service.remove(fund.uiId,true)};assertEquals(1,store.rows.size)
        service.remove(fund.uiId,false);assertTrue(store.rows.isEmpty());assertEquals(0,gateway.posts)
    }
    @Test fun firstBuyRetainsMissingBaselineAndDetectsExternallyAddedZeroPosition()=runTest {
        val store=object:BuyStorage {var rows=emptyList<BuyTransaction>();override suspend fun read()=rows;override suspend fun write(rows:List<BuyTransaction>){this.rows=rows}}
        val gateway=Gateway();val repo=SingleBuyRepository(store,gateway,{null},now={Instant.parse("2026-10-07T00:00:00Z")})
        val t=repo.create(BuyInput(fund,BigDecimal("100"),BigDecimal.ZERO,LocalDate.parse("2026-09-28"),CutoffType.BEFORE_1500))
        assertFalse(t.baseline.exists);assertFalse(BuyCodec.decode(BuyCodec.encode(listOf(t))).single().baseline.exists)
        val ready=BuyCalculation.confirm(t,t.expectedNavDate,BigDecimal("2"),t.updatedAt);store.write(listOf(ready))
        gateway.position=BuyBaseline(BigDecimal.ZERO,BigDecimal.ZERO,true)
        assertEquals(BuyStatus.EXTERNAL_CHANGED,repo.sync(t.id).status);assertEquals(0,gateway.posts)
    }
    @Test fun firstBuyFromAbsentFundUsesExistingCalculationAndGetVerifiedFlow()=runTest {
        val store=object:BuyStorage {var rows=emptyList<BuyTransaction>();override suspend fun read()=rows;override suspend fun write(rows:List<BuyTransaction>){this.rows=rows}}
        val gateway=Gateway();val repo=SingleBuyRepository(store,gateway,{null},now={Instant.parse("2026-10-07T00:00:00Z")})
        val t=repo.create(BuyInput(fund,BigDecimal("100"),BigDecimal.ZERO,LocalDate.parse("2026-09-28"),CutoffType.BEFORE_1500))
        val ready=BuyCalculation.confirm(t,t.expectedNavDate,BigDecimal("2"),t.updatedAt);store.write(listOf(ready))
        assertEquals(BuyStatus.SYNCED,repo.sync(t.id).status);assertEquals(BigDecimal("50.00"),gateway.position.shares);assertEquals(1,gateway.posts)
        assertEquals(BuyStatus.SYNCED,repo.sync(t.id).status);assertEquals(1,gateway.posts)
    }
}
