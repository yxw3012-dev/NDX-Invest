package com.ndxinvest.yjb.buy

import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class OrdinaryTradeAutomationTest {
    private class Fixture {
        val fund=BuyFund("1","17","270042","synthetic")
        val clock=Instant.parse("2026-10-08T01:00:00Z")
        var nav:BigDecimal?=null;var position=BuyBaseline(BigDecimal("100"),BigDecimal("2"));var posts=0;var failure:BuyProblem?=null;var failVerify=false;var navFailure:BuyProblem?=null;var navReads=0
        val bs=object:BuyStorage {var rows=emptyList<BuyTransaction>();override suspend fun read()=rows;override suspend fun write(rows:List<BuyTransaction>){this.rows=BuyCodec.decode(BuyCodec.encode(rows))}}
        val ss=object:SellStorage {var rows=emptyList<SellTransaction>();override suspend fun read()=rows;override suspend fun write(rows:List<SellTransaction>){this.rows=SellCodec.decode(SellCodec.encode(rows))}}
        val gateway=object:BuyGateway {
            override suspend fun current(fund:BuyFund):BuyBaseline {failure?.let {throw BuyFailure(it)};return position}
            override suspend fun officialNav(fund:BuyFund,targetDate:LocalDate):BigDecimal? {navReads++;navFailure?.let {throw BuyFailure(it)};return nav}
            override fun writeAvailable()=true
            override fun normalizeTarget(target:BuyBaseline)=target
            override fun matches(actual:BuyBaseline,target:BuyBaseline)=actual.equivalent(target)
            override suspend fun postFinal(fund:BuyFund,target:BuyBaseline){posts++;position=target;if(failVerify)failure=BuyProblem.NETWORK}
        }
        fun buys()=SingleBuyRepository(bs,gateway,BundledBuyCalendar::forCode,now={clock})
        fun sells()=SingleSellRepository(ss,gateway,now={clock})
        fun engine()=OrdinaryTradeAutomation(bs,ss,buys(),sells())
        suspend fun buy(auto:Boolean=true)=buys().create(BuyInput(fund,BigDecimal("100"),BigDecimal.ZERO,LocalDate.parse("2026-09-30"),CutoffType.BEFORE_1500),autoSync=auto)
        suspend fun sell()=sells().create(SellInput(fund,BigDecimal("10"),BigDecimal.ZERO,LocalDate.parse("2026-09-30"),CutoffType.BEFORE_1500),autoSync=true)
    }
    @Test fun delayedNavAndRestartProgressNewBuyOnceButNeverTakeOverLegacyRows()=runTest {
        val f=Fixture();val t=f.buy();assertEquals(0,f.engine().run(setOf("1")).synced);assertEquals(0,f.posts)
        assertEquals(t.expectedNavDate,f.bs.read().single().expectedNavDate)
        f.nav=BigDecimal("2.123456789");f.buys().restore()
        assertEquals(1,f.engine().run(setOf("1")).synced);assertEquals(1,f.posts);assertEquals(0,f.engine().run(setOf("1")).synced)
        val old=f.buy(false);assertEquals(0,f.engine().run(setOf("1")).synced);assertEquals(BuyStatus.WAITING_NAV,f.bs.read().single {it.id==old.id}.status)
        val legacy=BuyCodec.encode(f.bs.read()).map {kotlinx.serialization.json.JsonObject(it.jsonObject.filterKeys {k->k!="autoSync"})}
        assertTrue(BuyCodec.decode(kotlinx.serialization.json.JsonArray(legacy)).none {it.autoSync})
    }
    @Test fun firstPlaceholderBuyWaitsThenAutomaticallyCreatesPositionOnceAfterRestart()=runTest {
        val f=Fixture();f.position=BuyBaseline(BigDecimal.ZERO,BigDecimal.ZERO,false)
        val t=f.buy();assertTrue(t.autoSync);assertFalse(t.baseline.exists)
        assertEquals(0,f.engine().run(setOf("1")).synced);assertEquals(0,f.posts)
        assertEquals(BuyStatus.WAITING_NAV,f.bs.read().single().status)
        f.buys().restore();f.nav=BigDecimal("2")
        assertEquals(1,f.engine().run(setOf("1")).synced)
        assertEquals(BigDecimal("50"),f.position.shares.stripTrailingZeros().setScale(0))
        assertEquals(0,BigDecimal("2").compareTo(f.position.unitCost));assertTrue(f.position.exists)
        assertEquals(BuyStatus.SYNCED,f.bs.read().single().status)
        f.buys().restore();assertEquals(0,f.engine().run(setOf("1")).synced);assertEquals(1,f.posts)
    }
    @Test fun firstPlaceholderBuyRejectsExternallyCreatedZeroPositionAndExpiredSession()=runTest {
        val f=Fixture();f.position=BuyBaseline(BigDecimal.ZERO,BigDecimal.ZERO,false);f.buy()
        f.nav=BigDecimal("2");f.position=f.position.copy(exists=true)
        assertEquals(setOf(BuyProblem.EXTERNAL_CHANGED),f.engine().run(setOf("1")).problems);assertEquals(0,f.posts)
        val g=Fixture();g.position=BuyBaseline(BigDecimal.ZERO,BigDecimal.ZERO,false);g.buy()
        g.nav=BigDecimal("2");g.failure=BuyProblem.AUTH_EXPIRED
        assertEquals(setOf(BuyProblem.AUTH_EXPIRED),g.engine().run(setOf("1")).problems);assertEquals(0,g.posts)
    }
    @Test fun firstPlaceholderBuyUncertainWriteReconcilesWithoutSecondPost()=runTest {
        val f=Fixture();f.position=BuyBaseline(BigDecimal.ZERO,BigDecimal.ZERO,false);f.buy()
        f.nav=BigDecimal("2");f.failVerify=true
        assertEquals(emptySet(),f.engine().run(setOf("1")).problems);assertEquals(1,f.posts)
        f.failure=null;f.buys().restore()
        assertEquals(1,f.engine().run(setOf("1")).synced);assertEquals(1,f.posts)
    }
    @Test fun automaticSellUsesSameVerifiedGetFlowAndNeverChangesUnitCost()=runTest {
        val f=Fixture();f.sell();f.nav=BigDecimal("3")
        assertEquals(1,f.engine().run(setOf("1")).synced);assertEquals(BigDecimal("90"),f.position.shares);assertEquals(BigDecimal("2"),f.position.unitCost);assertEquals(1,f.posts)
    }
    @Test fun externalConflictAndTokenFailureNeverWrite()=runTest {
        val f=Fixture();f.buy();f.nav=BigDecimal("2");f.position=f.position.copy(shares=BigDecimal("99"))
        assertEquals(setOf(BuyProblem.EXTERNAL_CHANGED),f.engine().run(setOf("1")).problems);assertEquals(0,f.posts)
        val g=Fixture();g.buy();g.nav=BigDecimal("2");g.failure=BuyProblem.AUTH_EXPIRED
        assertEquals(setOf(BuyProblem.AUTH_EXPIRED),g.engine().run(setOf("1")).problems);assertEquals(0,g.posts)
    }
    @Test fun uncertainPostIsReconciledByGetOnlyAfterRestart()=runTest {
        val f=Fixture();f.buy();f.nav=BigDecimal("2");f.failVerify=true
        assertEquals(emptySet(),f.engine().run(setOf("1")).problems);assertEquals(1,f.posts)
        f.failure=null;f.buys().restore();assertEquals(1,f.engine().run(setOf("1")).synced);assertEquals(1,f.posts)
        val t=f.bs.read().single();f.bs.write(listOf(t.copy(status=BuyStatus.SYNC_FAILED,problem=BuyProblem.NETWORK)));f.position=t.baseline
        assertEquals(0,f.engine().run(setOf("1")).synced);assertEquals(1,f.posts,"GET finds baseline; automatic and manual reconciliation both remain GET-only")
    }
    @Test fun rateLimitStopsOrdinaryBatchAndLeavesLaterFundWaitingUntilRecovery()=runTest {
        val f=Fixture();val first=f.buy()
        val second=f.buys().create(first.input.copy(fund=f.fund.copy(fundId="18")),autoSync=true)
        f.navFailure=BuyProblem.RATE_LIMITED
        assertEquals(setOf(BuyProblem.RATE_LIMITED),f.engine().run(setOf("1")).problems)
        assertEquals(1,f.navReads);assertEquals(0,f.posts);assertTrue(f.bs.read().all {it.status==BuyStatus.WAITING_NAV})
        val untouched=f.bs.read().single {it.problem==null};assertTrue(untouched.id in setOf(first.id,second.id))
        f.navFailure=null;f.nav=BigDecimal("2")
        // This fixture shares one synthetic position; only test that read processing resumes.
        f.engine().run(setOf("1"));assertTrue(f.navReads>1)
    }
}
