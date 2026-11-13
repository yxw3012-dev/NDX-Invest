package com.ndxinvest.yjb.dca

import com.ndxinvest.yjb.buy.*
import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CalendarQueueTest {
    private class F {
        val fund=BuyFund("1","17","040046","synthetic")
        var time=Instant.parse("2026-10-07T01:00:00Z")
        var position=BuyBaseline(BigDecimal("100"),BigDecimal("2"))
        var posts=0;var failVerify=false;var auth=false;val navs=mutableMapOf<LocalDate,BigDecimal>()
        val bs=object:BuyStorage{var rows=emptyList<BuyTransaction>();override suspend fun read()=BuyCodec.decode(BuyCodec.encode(rows));override suspend fun write(rows:List<BuyTransaction>){this.rows=BuyCodec.decode(BuyCodec.encode(rows))}}
        val ss=object:SellStorage{var rows=emptyList<SellTransaction>();override suspend fun read()=SellCodec.decode(SellCodec.encode(rows));override suspend fun write(rows:List<SellTransaction>){this.rows=SellCodec.decode(SellCodec.encode(rows))}}
        val ds=object:DcaStorage{var state=DcaState();override suspend fun read()=DcaCodec.decode(DcaCodec.encode(state));override suspend fun write(state:DcaState){this.state=DcaCodec.decode(DcaCodec.encode(state))}}
        val gateway=object:BuyGateway {
            override suspend fun current(f:BuyFund):BuyBaseline {if(auth)throw BuyFailure(BuyProblem.AUTH_EXPIRED);if(failVerify&&posts>0)throw BuyFailure(BuyProblem.NETWORK);return position}
            override suspend fun officialNav(f:BuyFund,d:LocalDate)=navs[d]
            override suspend fun officialHistory(f:BuyFund)=navs.map {OfficialBuyNav(it.key,it.value)}
            override fun writeAvailable()=true
            override fun normalizeTarget(t:BuyBaseline)=t
            override fun matches(a:BuyBaseline,b:BuyBaseline)=a.equivalent(b)
            override suspend fun postFinal(f:BuyFund,t:BuyBaseline){posts++;position=t}
        }
        fun buyRepo()=SingleBuyRepository(bs,gateway,BundledBuyCalendar::forCode,now={time})
        fun sellRepo()=SingleSellRepository(ss,gateway,now={time})
        fun plans()=DcaRepository(ds,bs,buyRepo(),now={time})
        fun engine()=DcaNavEngine(ds,bs,plans(),buyRepo(),gateway,now={time})
        fun queue()=OrdinaryTradeAutomation(bs,ss,buyRepo(),sellRepo(),engine()::reservedBefore)
        suspend fun plan()=plans().create(fund,DcaConfig(BigDecimal("5"),BigDecimal.ZERO,DcaFrequency.DAILY,1,1),true,"885e825b-6aa0-4a30-9786-c65a2c41141e")
        fun at(day:String,hour:Int=16){time=LocalDate.parse(day).atTime(hour,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant()}
        suspend fun buy(date:String,amount:String="100")=buyRepo().create(BuyInput(fund,BigDecimal(amount),BigDecimal.ZERO,LocalDate.parse(date),CutoffType.BEFORE_1500),autoSync=true)
        fun nav(date:String){navs[LocalDate.parse(date)]=BigDecimal("2")}
    }
    @Test fun calendarCreatesWaitingRecordAtCutoffAndAdvancesNextDateWithoutNav()=runTest {
        val f=F();f.plan();f.at("2026-10-08",14);assertTrue(f.engine().materializeDue(setOf("1")).problems.isEmpty());assertTrue(f.bs.read().isEmpty())
        f.at("2026-10-08");f.engine().materializeDue(setOf("1"));val t=f.bs.read().single()
        assertEquals(BuyStatus.WAITING_NAV,t.status);assertTrue(t.autoSync);assertEquals(LocalDate.parse("2026-10-08"),t.expectedNavDate)
        assertEquals(LocalDate.parse("2026-10-09"),f.ds.read().plans.single().nextDate)
        assertEquals(0,f.posts);assertTrue(f.queue().run(setOf("1")).problems.isEmpty())
        f.plans().restore();f.engine().materializeDue(setOf("1"));assertEquals(1,f.bs.read().size)
        f.at("2026-10-09",10);f.nav("2026-10-08");assertEquals(1,f.queue().run(setOf("1")).synced);assertEquals(1,f.posts)
    }
    @Test fun holidaySkippedAndFridayNextDisplaySkipsWeekend()=runTest {
        val f=F();f.time=Instant.parse("2026-09-29T01:00:00Z");f.plan();f.at("2026-09-30");f.engine().materializeDue(setOf("1"));f.at("2026-10-07")
        f.nav("2026-09-30");f.engine().materializeDue(setOf("1"));assertEquals(listOf(LocalDate.parse("2026-09-30")),f.bs.read().map {it.scheduledDate})
        assertEquals("2026-10-08",DcaDateDisplay.next(f.ds.read().plans.single(),f.ds.read(),LocalDate.parse("2026-10-07")).first)
        f.at("2026-10-09");f.engine().materializeDue(setOf("1"));assertEquals("2026-10-12",DcaDateDisplay.next(f.ds.read().plans.single(),f.ds.read(),LocalDate.parse("2026-10-09")).first)
        assertEquals(3,f.bs.read().size);assertEquals(0,f.posts)
    }
    @Test fun ordinaryBuyThenDcaWaitNormallyAndHandoffUsesVerifiedChainAfterRestart()=runTest {
        val f=F();f.plan();f.at("2026-10-08",10);val first=f.buy("2026-10-08")
        f.at("2026-10-08");f.engine().materializeDue(setOf("1"));val original=f.bs.read().single {it.originPlanId!=null}.baseline
        assertTrue(f.queue().run(setOf("1")).problems.isEmpty());assertEquals(0,f.posts)
        f.at("2026-10-09",10);f.nav("2026-10-08");f.buyRepo().restore();assertEquals(2,f.queue().run(setOf("1")).synced)
        val dca=f.bs.read().single {it.originPlanId!=null};assertEquals(original,dca.baseline);assertEquals(listOf(first.id),dca.queuePredecessors)
        assertTrue(dca.executionBaseline!!.equivalent(f.bs.read().single {it.id==first.id}.submittedTarget!!))
        assertEquals(0,BigDecimal("152.50").compareTo(f.position.shares));assertEquals(2,f.posts)
        f.buyRepo().restore();f.queue().run(setOf("1"));assertEquals(2,f.posts)
    }
    @Test fun externalEditFailsClosedAndCannotUseArbitraryHistoricalTarget()=runTest {
        val f=F();f.at("2026-10-08",10);f.buy("2026-10-08");f.at("2026-10-08",11);f.buy("2026-10-08","5")
        f.position=BuyBaseline(BigDecimal("120"),BigDecimal("2"));f.nav("2026-10-08")
        assertEquals(setOf(BuyProblem.EXTERNAL_CHANGED),f.queue().run(setOf("1")).problems);assertEquals(0,f.posts)
        assertTrue(f.bs.read().all {it.executionBaseline==null})
    }
    @Test fun buySellOrderUsesRemainingSharesAndKeepsOriginalSellSnapshot()=runTest {
        val f=F();f.at("2026-10-08",10);val b=f.buy("2026-10-08")
        f.at("2026-10-08",11);val sell=f.sellRepo().create(SellInput(f.fund,BigDecimal("90"),BigDecimal.ZERO,LocalDate.parse("2026-10-08"),CutoffType.BEFORE_1500),autoSync=true)
        f.nav("2026-10-08");assertEquals(2,f.queue().run(setOf("1")).synced)
        val done=f.ss.read().single();assertEquals(sell.baseline,done.baseline);assertEquals(listOf(b.id),done.queuePredecessors);assertEquals(0,BigDecimal("60").compareTo(f.position.shares))
    }
    @Test fun uncertainPostBlocksQueueAndRestartOnlyReadsFrozenTarget()=runTest {
        val f=F();f.at("2026-10-08",10);f.buy("2026-10-08");f.at("2026-10-08",11);f.buy("2026-10-08","5");f.nav("2026-10-08");f.failVerify=true
        assertTrue(f.queue().run(setOf("1")).problems.isEmpty());assertEquals(1,f.posts);assertEquals(BuyStatus.WAITING_NAV,f.bs.read().last().status)
        f.failVerify=false;f.buyRepo().restore();assertEquals(2,f.queue().run(setOf("1")).synced);assertEquals(2,f.posts)
    }
    @Test fun catchupStillNeedsApprovalAndUnmaterializedPeriodsBlockLaterTrade()=runTest {
        val f=F();f.plan();f.at("2026-10-14");f.nav("2026-10-08");f.nav("2026-10-09");f.nav("2026-10-12");f.nav("2026-10-13");f.nav("2026-10-14")
        assertEquals(setOf(BuyProblem.CATCHUP_REVIEW),f.engine().materializeDue(setOf("1")).problems)
        assertTrue(f.bs.read().isEmpty());f.buy("2026-10-14")
        assertEquals(setOf(BuyProblem.CATCHUP_REVIEW),f.queue().run(setOf("1")).problems);assertEquals(0,f.posts)
    }
    @Test fun secondSellCannotOversellAfterVerifiedPredecessor()=runTest {
        val f=F();f.at("2026-10-08",10)
        f.sellRepo().create(SellInput(f.fund,BigDecimal("80"),BigDecimal.ZERO,LocalDate.parse("2026-10-08"),CutoffType.BEFORE_1500),autoSync=true)
        f.at("2026-10-08",11)
        f.sellRepo().create(SellInput(f.fund,BigDecimal("50"),BigDecimal.ZERO,LocalDate.parse("2026-10-08"),CutoffType.BEFORE_1500),autoSync=true)
        f.nav("2026-10-08");val report=f.queue().run(setOf("1"))
        assertEquals(1,report.synced);assertEquals(setOf(BuyProblem.INPUT),report.problems);assertEquals(1,f.posts)
        assertEquals(BuyProblem.INPUT,f.ss.read().last().problem);assertEquals(0,BigDecimal("20").compareTo(f.position.shares))
        f.queue().run(setOf("1"));assertEquals(1,f.posts)
    }
    @Test fun authFailureStopsWithoutAnyWrite()=runTest {
        val f=F();f.at("2026-10-08");f.buy("2026-10-08");f.auth=true;f.nav("2026-10-08")
        assertEquals(setOf(BuyProblem.AUTH_EXPIRED),f.queue().run(setOf("1")).problems);assertEquals(0,f.posts)
    }
    @Test fun createdOccurrenceIsNotRegeneratedAfterCancelAndPause()=runTest {
        val f=F();val plan=f.plan();f.at("2026-10-08");f.engine().materializeDue(setOf("1"))
        val t=f.bs.read().single();f.buyRepo().cancel(t.id);f.engine().materializeDue(setOf("1"))
        assertEquals(1,f.bs.read().size);assertEquals(BuyStatus.CANCELLED,f.bs.read().single().status)
        val state=f.ds.read();f.ds.write(state.copy(plans=state.plans.map {it.copy(enabled=false)}))
        f.at("2026-10-09");f.engine().materializeDue(setOf("1"));assertEquals(1,f.bs.read().size);assertEquals(0,f.posts)
    }
    @Test fun sentWriteReconcilesBeforeNewCatchupReservation()=runTest {
        val f=F();f.plan();f.at("2026-10-08",10);f.buy("2026-10-08");f.nav("2026-10-08");f.failVerify=true
        f.queue().run(setOf("1"));assertEquals(1,f.posts)
        f.failVerify=false;f.at("2026-10-14");f.nav("2026-10-09");f.nav("2026-10-12");f.nav("2026-10-13");f.nav("2026-10-14")
        assertEquals(setOf(BuyProblem.CATCHUP_REVIEW),f.engine().materializeDue(setOf("1")).problems)
        assertEquals(1,f.queue().run(setOf("1")).synced);assertEquals(BuyStatus.SYNCED,f.bs.read().single().status);assertEquals(1,f.posts)
    }
    @Test fun readyBuyHandoffKeepsItsConfirmedSettlementAmounts()=runTest {
        val f=F();f.at("2026-10-08",10);f.buy("2026-10-08")
        f.at("2026-10-08",11);val second=f.buy("2026-10-08","5");f.nav("2026-10-08")
        val ready=f.buyRepo().confirmNav(second.id)
        f.bs.write(f.bs.read().map {if(it.id==second.id)it.copy(calculationVersion=0)else it})
        assertEquals(2,f.queue().run(setOf("1")).synced)
        val done=f.bs.read().single {it.id==second.id}
        assertEquals(0,done.calculationVersion);assertEquals(ready.confirmedShares,done.confirmedShares)
        assertEquals(ready.confirmedNav,done.confirmedNav);assertEquals(ready.feeAmount,done.feeAmount);assertNotNull(done.executionBaseline)
    }
    @Test fun laterAvailableNavCannotBypassEarlierMissingNav()=runTest {
        val f=F();f.at("2026-10-08",10);f.buy("2026-10-08")
        f.at("2026-10-09",10);f.buy("2026-10-09");f.nav("2026-10-09")
        assertTrue(f.queue().run(setOf("1")).problems.isEmpty());assertEquals(0,f.posts)
        assertTrue(f.bs.read().all {it.status==BuyStatus.WAITING_NAV})
        f.nav("2026-10-08");assertEquals(2,f.queue().run(setOf("1")).synced);assertEquals(2,f.posts)
    }
}
