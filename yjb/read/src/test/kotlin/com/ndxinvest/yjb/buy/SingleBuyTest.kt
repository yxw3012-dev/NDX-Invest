package com.ndxinvest.yjb.buy

import kotlin.test.*
import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.test.runTest

class SingleBuyTest {
    private fun d(v:String)=BigDecimal(v)
    private val fund=BuyFund("1","2","270042","测试基金")
    private val time=Instant.parse("2026-10-03T00:00:00Z")
    private fun input(fee:String="0.0015")=BuyInput(fund,d("1000"),d(fee),LocalDate.parse("2026-09-28"),CutoffType.BEFORE_1500)
    private class Store:BuyStorage {var rows=emptyList<BuyTransaction>();var fail=false;override suspend fun read()=rows;override suspend fun write(rows:List<BuyTransaction>){if(fail)throw BuyFailure(BuyProblem.STORAGE);this.rows=BuyCodec.decode(BuyCodec.encode(rows))}}
    private class Gateway:BuyGateway {
        var holding=BuyBaseline(BigDecimal("100"),BigDecimal("1.2"));var nav:BigDecimal?=BigDecimal("2");var posted=0;var available=true;var delivery="ok";var getFailure:BuyProblem?=null;var failVerify=false;var wrongTarget=false;var coarsePrecision=false
        override suspend fun current(fund:BuyFund):BuyBaseline {getFailure?.let {throw BuyFailure(it)};return holding}
        override suspend fun officialNav(fund:BuyFund,targetDate:LocalDate)=nav
        override fun writeAvailable()=available
        override fun normalizeTarget(target:BuyBaseline)=if(coarsePrecision)BuyBaseline(target.shares.setScale(2,java.math.RoundingMode.HALF_UP),target.unitCost.setScale(2,java.math.RoundingMode.HALF_UP))else target
        override fun matches(actual:BuyBaseline,target:BuyBaseline)=actual.equivalent(target)
        override suspend fun postFinal(fund:BuyFund,target:BuyBaseline){posted++;if(delivery!="undelivered")holding=if(wrongTarget)target.copy(shares=target.shares+BigDecimal.ONE)else target;if(failVerify)getFailure=BuyProblem.NETWORK;if(delivery!="ok")throw BuyFailure(BuyProblem.NETWORK)}
    }
    private fun repo(s:Store,g:Gateway)=SingleBuyRepository(s,g,{if(it=="270042")BundledBuyCalendar.forCode("270042")!!else null},{time})
    @Test fun normalBuyIncludesFeeInCostAndVerifiesBeforeSynced()=runTest {val s=Store();val g=Gateway();val r=repo(s,g);val t=r.create(input());assertEquals(BuyStatus.WAITING_NAV,t.status);val ready=r.confirmNav(t.id);assertEquals(d("1.50"),ready.feeAmount);assertEquals(0,d("499.25").compareTo(ready.confirmedShares));assertEquals(0,d("1120").compareTo(ready.target!!.totalCost.round(java.math.MathContext(20))));assertEquals(BuyStatus.SYNCED,r.sync(t.id).status);assertEquals(1,g.posted);r.sync(t.id);assertEquals(1,g.posted)}
    @Test fun zeroFeeAndLargeDecimalAreExact()=runTest {val s=Store();val g=Gateway();val r=repo(s,g);val t=r.create(input("0").copy(amount=d("999999999999999999999999.123456789")));val result=r.confirmNav(t.id);assertEquals(0,result.feeAmount!!.signum());assertEquals(0,result.confirmedShares!!.compareTo(d("499999999999999999999999.56")))}
    @Test fun externalChargeAndSettlementRoundingAreIndependentOfUi()=runTest {
        val s=Store();val g=Gateway();val r=repo(s,g)
        val t=r.create(input("0.015").copy(amount=d("100000")))
        val ready=r.confirmNav(t.id)
        assertEquals(d("1477.83"),ready.feeAmount)
        assertEquals(d("49261.09"),ready.confirmedShares)
        assertEquals(t.input.amount,s.read().single().input.amount)
        assertEquals(1,BuyCodec.decode(BuyCodec.encode(listOf(ready))).single().calculationVersion)
    }
    @Test fun halfCentAndHalfShareRoundUp()=runTest {
        val s=Store();val g=Gateway();val r=repo(s,g)
        val t=r.create(input("0").copy(amount=d("1.01")))
        assertEquals(d("0.51"),r.confirmNav(t.id).confirmedShares)
    }
    @Test fun legacyWaitingUpgradesButAlreadyConfirmedJournalKeepsFrozenResult()=runTest {
        val s=Store();val g=Gateway();val r=repo(s,g)
        val t=r.create(input());s.rows=listOf(t.copy(calculationVersion=0))
        assertEquals(1,r.confirmNav(t.id).calculationVersion)
        val old=s.rows.single().copy(calculationVersion=0,feeAmount=d("1.5000"),confirmedShares=d("499.25"),status=BuyStatus.SYNCED)
        s.rows=BuyCodec.decode(BuyCodec.encode(listOf(old)))
        r.restore();assertEquals(old,r.confirmNav(old.id));assertEquals(0,g.posted)
    }
    @Test fun waitingThenExactNavAppears()=runTest {val s=Store();val g=Gateway();g.nav=null;val r=repo(s,g);val t=r.create(input());assertEquals(BuyStatus.WAITING_NAV,r.confirmNav(t.id).status);g.nav=d("2.123456789");val ready=r.confirmNav(t.id);assertEquals(t.expectedNavDate,ready.confirmedNavDate);assertEquals(g.nav,ready.confirmedNav)}
    @Test fun externalChangeNeverPosts()=runTest {val s=Store();val g=Gateway();val r=repo(s,g);val t=r.create(input());r.confirmNav(t.id);g.holding=BuyBaseline(d("101"),d("1.2"));assertEquals(BuyStatus.EXTERNAL_CHANGED,r.sync(t.id).status);assertEquals(0,g.posted);assertEquals(BuyStatus.CANCELLED,r.cancel(t.id).status)}
    @Test fun timeoutActuallyDeliveredReconcilesWithoutSecondPost()=runTest {val s=Store();val g=Gateway();g.delivery="timeout";val r=repo(s,g);val t=r.create(input());r.confirmNav(t.id);assertEquals(BuyStatus.SYNCED,r.sync(t.id).status);r.sync(t.id);assertEquals(1,g.posted)}
    @Test fun timeoutNotDeliveredStaysPendingAndNeverRepeatsPost()=runTest {val s=Store();val g=Gateway();g.delivery="undelivered";val r=repo(s,g);val t=r.create(input());r.confirmNav(t.id);assertEquals(BuyStatus.SYNCING,r.sync(t.id).status);g.delivery="ok";assertEquals(BuyStatus.SYNCING,r.sync(t.id).status);assertEquals(1,g.posted);g.holding=s.rows.single().submittedTarget!!;assertEquals(BuyStatus.SYNCED,repo(s,g).sync(t.id).status);assertEquals(1,g.posted)}
    @Test fun failedVerifyReconcilesDeliveredTargetAfterRestart()=runTest {val s=Store();val g=Gateway();g.failVerify=true;val r=repo(s,g);val t=r.create(input());r.confirmNav(t.id);assertEquals(BuyStatus.SYNCING,r.sync(t.id).status);g.getFailure=null;val restarted=repo(s,g);restarted.restore();assertEquals(BuyStatus.SYNCED,restarted.sync(t.id).status);assertEquals(1,g.posted)}
    @Test fun sessionExpiresAfterDeliveredPostAndReloginReconcilesWithoutDuplicateBuy()=runTest {
        val s=Store();val g=Gateway()
        val expiring=object:BuyGateway by g {
            override suspend fun postFinalGuarded(fund:BuyFund,target:BuyBaseline,beforeSend:suspend()->Unit){beforeSend();postFinal(fund,target)}
            override suspend fun postFinal(fund:BuyFund,target:BuyBaseline){g.postFinal(fund,target);g.getFailure=BuyProblem.AUTH_EXPIRED}
        }
        val r=SingleBuyRepository(s,expiring,calendar=BundledBuyCalendar::forCode,now={time});val t=r.create(input());r.confirmNav(t.id)
        val failed=r.sync(t.id)
        assertEquals(BuyStatus.SYNCING,failed.status);assertEquals(BuyProblem.AUTH_EXPIRED,failed.problem)
        assertTrue(failed.postMayHaveBeenSent);assertNotNull(failed.submittedTarget);assertEquals(1,g.posted)
        assertFailsWith<BuyFailure>{r.cancel(t.id)}
        g.getFailure=null;val restarted=repo(s,g);restarted.restore()
        assertEquals(BuyStatus.SYNCED,restarted.sync(t.id).status);assertEquals(1,g.posted)
    }
    @Test fun crashDuringSyncUsesWriteAheadMarker()=runTest {val s=Store();val g=Gateway();val r=repo(s,g);val t=r.create(input());val ready=r.confirmNav(t.id);s.rows=listOf(ready.copy(status=BuyStatus.SYNCING,submittedTarget=ready.target,postMayHaveBeenSent=true));g.holding=ready.target!!;val restarted=repo(s,g);assertEquals(BuyStatus.SYNCING,restarted.restore().single().status);assertEquals(BuyStatus.SYNCED,restarted.sync(t.id).status);assertEquals(0,g.posted)}
    @Test fun waitingAndReadyRestoreWithoutInventedConfirmation()=runTest {val s=Store();val g=Gateway();val r=repo(s,g);val t=r.create(input());assertEquals(BuyStatus.WAITING_NAV,repo(s,g).restore().single().status);r.confirmNav(t.id);assertEquals(BuyStatus.READY_TO_SYNC,repo(s,g).restore().single().status)}
    @Test fun unverifiedWriteStaysReadyAndNeverPosts()=runTest {val s=Store();val g=Gateway();g.available=false;val r=repo(s,g);val t=r.create(input());r.confirmNav(t.id);val result=r.sync(t.id);assertEquals(BuyStatus.READY_TO_SYNC,result.status);assertEquals(BuyProblem.WRITE_UNVERIFIED,result.problem);assertEquals(0,g.posted)}
    @Test fun expiredSessionAndStorageFailureCannotWrite()=runTest {val s=Store();val g=Gateway();val r=repo(s,g);val t=r.create(input());r.confirmNav(t.id);g.getFailure=BuyProblem.AUTH_EXPIRED;assertEquals(BuyProblem.AUTH_EXPIRED,r.sync(t.id).problem);g.getFailure=null;s.fail=true;assertFailsWith<BuyFailure>{r.sync(t.id)};assertEquals(0,g.posted)}
    @Test fun duplicateCreateRequestIsIdempotentAndCancelNeverSyncs()=runTest {val s=Store();val g=Gateway();val r=repo(s,g);val request="11111111-1111-4111-8111-111111111111";val t=r.create(input(),request);assertEquals(t,r.create(input(),request));assertEquals(1,s.rows.size);assertEquals(BuyStatus.CANCELLED,r.cancel(t.id).status);r.sync(t.id);assertEquals(0,g.posted)}
    @Test fun editsOnlyWaitingAndNeverModifyConfirmedNav()=runTest {val s=Store();val g=Gateway();val r=repo(s,g);val t=r.create(input());val edited=r.edit(t.id,d("200"),d("0"),t.input.tradeDate,CutoffType.AFTER_1500,"备注");assertEquals(LocalDate.parse("2026-09-29"),edited.expectedNavDate);r.confirmNav(t.id);assertFailsWith<BuyFailure>{r.edit(t.id,d("1"),d("0"),t.input.tradeDate,CutoffType.BEFORE_1500,"")}}
    @Test fun calendarForecastIsOptionalAndUnknownFundCanCreate()=runTest {val c=BundledBuyCalendar.forCode("270042")!!;assertEquals(LocalDate.parse("2026-10-08"),c.expected(LocalDate.parse("2026-10-01"),CutoffType.BEFORE_1500));assertEquals(LocalDate.parse("2026-09-28"),c.expected(LocalDate.parse("2026-09-26"),CutoffType.BEFORE_1500));val s=Store();val g=Gateway();val t=repo(s,g).create(input().copy(fund=fund.copy(fundCode="000001")));assertEquals(BuyStatus.WAITING_NAV,t.status);assertEquals(BuyNavDates.PROVISIONAL_SOURCE,t.calendarSource);assertEquals(BuyProblem.CALENDAR_UNAVAILABLE,repo(s,g).confirmNav(t.id).problem);assertEquals(0,g.posted)}
    @Test fun invalidInputsAndOtherNavDateCannotConfirm()=runTest {val s=Store();val g=Gateway();val r=repo(s,g);for(value in listOf("0","-1")){assertFailsWith<BuyFailure>{r.create(input().copy(amount=d(value)))}};assertFailsWith<BuyFailure>{r.create(input("1"))};val t=r.create(input());assertFailsWith<IllegalArgumentException>{BuyCalculation.confirm(t,t.expectedNavDate.plusDays(1),d("2"),time)};assertFalse(t.toString().contains(fund.fundName))}
    @Test fun wrongGetRemainsPendingAndNeverOverwritesThirdState()=runTest {val s=Store();val g=Gateway();g.wrongTarget=true;val r=repo(s,g);val t=r.create(input());r.confirmNav(t.id);assertEquals(BuyStatus.SYNCING,r.sync(t.id).status);assertEquals(BuyStatus.SYNCING,r.sync(t.id).status);assertEquals(1,g.posted);assertFailsWith<BuyFailure>{r.cancel(t.id)}}
    @Test fun unrepresentableTinyBuyCannotSilentlyPostUnchangedPosition()=runTest {val s=Store();val g=Gateway();g.coarsePrecision=true;val r=repo(s,g);val t=r.create(input("0").copy(amount=d("0.00001")));val original=r.confirmNav(t.id);val result=r.sync(t.id);assertEquals(BuyProblem.INPUT,result.problem);assertEquals(BuyStatus.WAITING_NAV,result.status);assertEquals(original.target,result.target);assertFalse(result.postMayHaveBeenSent);assertEquals(0,g.posted)}
    @Test fun uncertainRowBlocksOtherBuyEvenIfTheFundHasBeenRenamed()=runTest {
        val s=Store();val g=Gateway();g.delivery="undelivered";val r=repo(s,g)
        val first=r.create(input());r.confirmNav(first.id);r.sync(first.id)
        val second=r.create(input().copy(fund=fund.copy(fundName="renamed")));r.confirmNav(second.id)
        assertEquals(BuyProblem.PENDING_TRANSACTION,r.sync(second.id).problem);assertEquals(1,g.posted)
        val cross=SingleBuyRepository(s,g,BundledBuyCalendar::forCode,pendingOther={true})
        assertEquals(BuyProblem.PENDING_TRANSACTION,cross.sync(second.id).problem);assertEquals(1,g.posted)
    }
    @Test fun missingSubmittedTargetInLegacySentMarkerFailsClosed()=runTest {
        val s=Store();val g=Gateway();val r=repo(s,g);val t=r.create(input());val ready=r.confirmNav(t.id)
        s.rows=listOf(ready.copy(status=BuyStatus.SYNC_FAILED,postMayHaveBeenSent=true,submittedTarget=null))
        val restarted=repo(s,g);assertEquals(BuyStatus.SYNCING,restarted.restore().single().status)
        assertEquals(BuyProblem.STORAGE,restarted.sync(t.id).problem)
        assertFailsWith<BuyFailure>{restarted.reconcileOnly(t.id)};assertEquals(0,g.posted)
    }
}

