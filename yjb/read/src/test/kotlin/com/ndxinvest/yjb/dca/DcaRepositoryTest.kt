package com.ndxinvest.yjb.dca

import com.ndxinvest.yjb.buy.*
import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DcaRepositoryTest {
    private val fund=BuyFund("1","17","000017","synthetic")
    private val planId="885e825b-6aa0-4a30-9786-c65a2c41141e"
    private fun config(f:DcaFrequency=DcaFrequency.DAILY,w:Int=1,m:Int=1)=DcaConfig(BigDecimal("100.123456789"),BigDecimal("0.0015"),f,w,m,"plan note")
    private class Fixture {
        var time=Instant.parse("2026-10-03T01:00:00Z")
        var gets=0;var posts=0;var failGet=false;var failDcaWriteAt=0;var writes=0
        val planStore=object:DcaStorage {
            var state=DcaState()
            override suspend fun read()=DcaCodec.decode(DcaCodec.encode(state))
            override suspend fun write(state:DcaState){writes++;if(writes==failDcaWriteAt)throw DcaFailure(DcaProblem.STORAGE);this.state=DcaCodec.decode(DcaCodec.encode(state))}
        }
        val buyStore=object:BuyStorage {
            var rows=emptyList<BuyTransaction>()
            override suspend fun read()=BuyCodec.decode(BuyCodec.encode(rows))
            override suspend fun write(rows:List<BuyTransaction>){this.rows=BuyCodec.decode(BuyCodec.encode(rows))}
        }
        val gateway=object:BuyGateway {
            var current=BuyBaseline(BigDecimal("100"),BigDecimal("2"))
            override suspend fun current(fund:BuyFund):BuyBaseline {gets++;if(failGet)throw BuyFailure(BuyProblem.AUTH_EXPIRED);return current}
            override suspend fun officialNav(fund:BuyFund,targetDate:LocalDate)=BigDecimal("2.123456789")
            override fun writeAvailable()=true
            override fun normalizeTarget(target:BuyBaseline)=target
            override fun matches(actual:BuyBaseline,target:BuyBaseline)=actual.equivalent(target)
            override suspend fun postFinal(fund:BuyFund,target:BuyBaseline){posts++;current=target}
        }
        val buys=SingleBuyRepository(buyStore,gateway,{BundledBuyCalendar.forCode("270042")},now={time})
        fun repo()=DcaRepository(planStore,buyStore,buys,now={time})
        fun due(){time=Instant.parse("2026-10-04T08:00:00Z")}
    }
    @Test fun creationAndRestartNeverReadHoldingsCreateBuyOrPost()=runTest {
        val f=Fixture();val p=f.repo().create(fund,config(),true,planId)
        assertEquals(LocalDate.parse("2026-10-04"),p.nextDate)
        assertEquals(p,f.repo().restore().plans.single());assertEquals(0,f.gets);assertEquals(0,f.posts);assertTrue(f.buyStore.read().isEmpty())
        assertEquals(p,f.repo().create(fund,config(),true,planId));assertEquals(1,f.planStore.read().plans.size)
    }
    @Test fun editPauseResumeAndDeletePreserveHistoryAndOtherPlans()=runTest {
        val f=Fixture();val r=f.repo();val p=r.create(fund,config(),true,planId)
        val other=r.create(fund.copy(fundId="18",fundCode="000018"),config(),true,"885e825b-6aa0-4a30-9786-c65a2c411412")
        val edited=r.update(p.id,config(DcaFrequency.MONTHLY,m=31).copy(amount=BigDecimal("1234.123456789")),p.revision)
        assertEquals(LocalDate.parse("2026-10-31"),edited.nextDate)
        val paused=r.setEnabled(p.id,false,edited.revision);assertFalse(paused.enabled)
        val resumed=r.setEnabled(p.id,true,paused.revision);assertTrue(resumed.enabled)
        r.delete(p.id,resumed.revision);assertTrue(f.planStore.read().plans.single {it.id==p.id}.deleted)
        assertEquals(other,f.planStore.read().plans.single {it.id==other.id});assertTrue(f.buyStore.read().isEmpty());assertEquals(0,f.gets)
    }
    @Test fun manualExecutionUsesClickDateAndCutoffCreatesOrdinaryBuyWithoutPost()=runTest {
        val f=Fixture();val r=f.repo();val p=r.create(fund,config(),true,planId)
        // Execute late: period date stays Oct 4, actual trade is Oct 6 after 15:00 Shanghai.
        f.time=Instant.parse("2026-10-06T08:00:00Z")
        val t=r.execute(p.id,p.nextDate,p.revision)
        assertEquals(BuyStatus.WAITING_NAV,t.status);assertEquals(p.id,t.originPlanId);assertEquals(p.nextDate,t.scheduledDate)
        assertEquals(LocalDate.parse("2026-10-06"),t.input.tradeDate);assertEquals(CutoffType.AFTER_1500,t.input.cutoffType)
        assertEquals(p.config.amount,t.input.amount);assertEquals(p.config.feeRate,t.input.feeRate);assertEquals(0,f.posts)
        f.time=Instant.parse("2026-10-08T01:00:00Z") // next-date formal NAV can only confirm after that date arrives
        val ready=f.buys.confirmNav(t.id);assertEquals(BuyStatus.READY_TO_SYNC,ready.status);assertEquals(0,f.posts)
        // Fake gateway only: actual ordinary BUY sync keeps its original safeguards/formulas.
        assertEquals(BuyStatus.SYNCED,f.buys.sync(t.id).status);assertEquals(1,f.posts)
    }
    @Test fun samePeriodRepeatedAfterRestartReturnsExactlyOneBuy()=runTest {
        val f=Fixture();val r=f.repo();val p=r.create(fund,config(),true,planId);f.due()
        val t=r.execute(p.id,p.nextDate,p.revision)
        assertEquals(t,f.repo().execute(p.id,p.nextDate,p.revision))
        assertEquals(1,f.buyStore.read().size);assertEquals(1,f.planStore.read().occurrences.size);assertEquals(1,f.gets);assertEquals(0,f.posts)
        assertEquals(LocalDate.parse("2026-10-05"),f.repo().restore().plans.single().nextDate)
    }
    @Test fun crashAfterBuySaveRestoresReservationWithoutSecondBuyOrNetwork()=runTest {
        val f=Fixture();val r=f.repo();val p=r.create(fund,config(),true,planId);f.due()
        f.failDcaWriteAt=3 // create plan, reserve period, fail final plan advance after durable BUY
        assertFailsWith<DcaFailure>{r.execute(p.id,p.nextDate,p.revision)}
        assertEquals(1,f.buyStore.read().size);assertEquals(OccurrenceStatus.RESERVED,f.planStore.read().occurrences.single().status)
        val gets=f.gets;f.failDcaWriteAt=0;val recovered=f.repo().restore()
        assertEquals(OccurrenceStatus.CREATED,recovered.occurrences.single().status);assertEquals(gets,f.gets)
        assertEquals(1,f.buyStore.read().size);assertEquals(0,f.posts)
    }
    @Test fun networkFailureKeepsFrozenReservationForExplicitRetry()=runTest {
        val f=Fixture();val r=f.repo();val p=r.create(fund,config(),true,planId);f.due();f.failGet=true
        assertFailsWith<BuyFailure>{r.execute(p.id,p.nextDate,p.revision)}
        val reserved=f.planStore.read().occurrences.single();f.repo().restore();assertTrue(f.buyStore.read().isEmpty())
        assertFailsWith<DcaFailure>{r.update(p.id,config().copy(amount=BigDecimal("20")),p.revision)}
        f.failGet=false;f.time=Instant.parse("2026-10-06T01:00:00Z")
        val t=f.repo().execute(p.id,p.nextDate,p.revision)
        assertEquals(reserved.transactionId,t.id);assertEquals(reserved.input,t.input);assertEquals(0,f.posts)
    }
    @Test fun reservationFailureBlocksBuyCreationBeforeAnyGet()=runTest {
        val f=Fixture();val r=f.repo();val p=r.create(fund,config(),true,planId);f.due();f.failDcaWriteAt=2
        assertFailsWith<DcaFailure>{r.execute(p.id,p.nextDate,p.revision)}
        assertEquals(0,f.gets);assertTrue(f.buyStore.read().isEmpty());assertTrue(f.planStore.read().occurrences.isEmpty())
    }
    @Test fun notDuePausedStaleAndInvalidInputsNeverCreateBuy()=runTest {
        val f=Fixture();val r=f.repo();val p=r.create(fund,config(),false,planId)
        assertEquals(DcaProblem.PAUSED,assertFailsWith<DcaFailure>{r.execute(p.id,p.nextDate,p.revision)}.problem)
        val active=r.setEnabled(p.id,true,p.revision)
        assertEquals(DcaProblem.NOT_DUE,assertFailsWith<DcaFailure>{r.execute(p.id,p.nextDate,active.revision)}.problem)
        assertEquals(DcaProblem.STALE,assertFailsWith<DcaFailure>{r.update(p.id,config(),p.revision)}.problem)
        for(c in listOf(config().copy(amount=BigDecimal.ZERO),config().copy(feeRate=BigDecimal.ONE),config(DcaFrequency.WEEKLY,w=8),config(DcaFrequency.MONTHLY,m=0)))assertFailsWith<DcaFailure>{r.update(p.id,c,active.revision)}
        assertEquals(0,f.gets);assertEquals(0,f.posts)
    }
    @Test fun deleteReservedOrExecutedPlanNeverDeletesBuyOrCreatesAnother()=runTest {
        val f=Fixture();val r=f.repo();val p=r.create(fund,config(),true,planId);f.due()
        val t=r.execute(p.id,p.nextDate,p.revision);val latest=f.planStore.read().plans.single()
        r.delete(p.id,latest.revision);assertEquals(t,f.buyStore.read().single())
        assertFailsWith<DcaFailure>{r.execute(p.id,p.nextDate,p.revision)}
        assertEquals(1,f.repo().restore().occurrences.size);assertEquals(0,f.posts)
    }
    @Test fun cancelledOrdinaryBuyDoesNotAllowRepeatingConsumedPeriod()=runTest {
        val f=Fixture();val r=f.repo();val p=r.create(fund,config(),true,planId);f.due()
        val t=r.execute(p.id,p.nextDate,p.revision);f.buys.cancel(t.id)
        assertEquals(BuyStatus.CANCELLED,f.repo().execute(p.id,p.nextDate,p.revision).status)
        assertEquals(1,f.buyStore.read().size);assertEquals(0,f.posts)
    }
    @Test fun calendarCadenceCoversLeapMonthEndsWeeklyBiweeklyAndDaily() {
        val c=config(DcaFrequency.MONTHLY,m=31)
        assertEquals(LocalDate.parse("2028-02-29"),DcaSchedule.after(LocalDate.parse("2028-01-31"),c))
        assertEquals(LocalDate.parse("2028-03-31"),DcaSchedule.after(LocalDate.parse("2028-02-29"),c))
        assertEquals(LocalDate.parse("2026-10-05"),DcaSchedule.firstAfter(LocalDate.parse("2026-10-03"),config(DcaFrequency.WEEKLY,w=1)))
        assertEquals(LocalDate.parse("2026-10-19"),DcaSchedule.after(LocalDate.parse("2026-10-05"),config(DcaFrequency.BIWEEKLY,w=1)))
        assertEquals(LocalDate.parse("2027-01-01"),DcaSchedule.after(LocalDate.parse("2026-12-31"),config()))
    }
    @Test fun uiScopesPlansToCurrentAccountAndTombstonesNeverReappear()=runTest {
        val f=Fixture();val r=f.repo();val p=r.create(fund,config(),true,planId)
        assertEquals(1,DcaCodec.ui(f.planStore.read(),setOf("1"),f.time).size)
        assertTrue(DcaCodec.ui(f.planStore.read(),setOf("2"),f.time).isEmpty())
        r.delete(p.id,p.revision);assertTrue(DcaCodec.ui(f.planStore.read(),setOf("1"),f.time).isEmpty())
    }
    @Test fun oldBuyJournalCompatibleAndPlanMetadataRoundTrips()=runTest {
        val f=Fixture();val r=f.repo();val p=r.create(fund,config(),true,planId);f.due()
        val t=r.execute(p.id,p.nextDate,p.revision)
        assertEquals(t,BuyCodec.decode(BuyCodec.encode(listOf(t))).single())
        val ordinary=t.copy(originPlanId=null,scheduledDate=null)
        assertEquals(ordinary,BuyCodec.decode(BuyCodec.encode(listOf(ordinary))).single())
        assertFalse(BuyCodec.encode(listOf(ordinary)).toString().contains("originPlanId"))
    }
}
