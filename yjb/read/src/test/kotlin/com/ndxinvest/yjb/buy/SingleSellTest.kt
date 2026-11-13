package com.ndxinvest.yjb.buy

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Synthetic gateway/state tests; never real SELL acceptance. */
class SingleSellTest {
    private fun d(s:String)=BigDecimal(s)
    private val fund=BuyFund("1","17","000017","测试基金")
    private val time=Instant.parse("2026-10-03T00:00:00Z")
    private fun input(shares:String="25",rate:String="0.015")=SellInput(fund,d(shares),d(rate),LocalDate.parse("2026-09-28"),CutoffType.BEFORE_1500)
    private class Store:SellStorage {
        var rows=emptyList<SellTransaction>();var fail=false
        override suspend fun read()=rows
        override suspend fun write(rows:List<SellTransaction>){if(fail)throw BuyFailure(BuyProblem.STORAGE);this.rows=SellCodec.decode(SellCodec.encode(rows))}
    }
    private class Gateway:BuyGateway {
        var holding=BuyBaseline(BigDecimal("100"),BigDecimal("1.2"));var nav:BigDecimal?=BigDecimal("2")
        var posts=0;var gets=0;var available=true;var delivery="ok";var problem:BuyProblem?=null
        var verifyFails=false;var wrongTarget=false;var roundShares=false;var roundCost=false
        override suspend fun current(fund:BuyFund):BuyBaseline {gets++;problem?.let {throw BuyFailure(it)};return holding}
        override suspend fun officialNav(fund:BuyFund,targetDate:LocalDate)=nav
        override fun writeAvailable()=available
        override fun normalizeTarget(target:BuyBaseline)=target.copy(shares=if(roundShares)target.shares.setScale(2,RoundingMode.HALF_UP)else target.shares,unitCost=if(roundCost)target.unitCost.setScale(2,RoundingMode.HALF_UP)else target.unitCost)
        override fun matches(actual:BuyBaseline,target:BuyBaseline)=actual.equivalent(target)
        override suspend fun postFinal(fund:BuyFund,target:BuyBaseline){posts++;if(delivery!="undelivered")holding=if(wrongTarget)target.copy(shares=target.shares+BigDecimal.ONE)else target;if(verifyFails)problem=BuyProblem.NETWORK;if(delivery!="ok")throw BuyFailure(BuyProblem.NETWORK)}
    }
    private fun repo(s:Store,g:Gateway)=SingleSellRepository(s,g,calendar={code->BundledBuyCalendar.forCode(code)?:BuyCalendar(code,LocalDate.parse("2026-01-01"),LocalDate.parse("2026-12-31"),(0..364).map {LocalDate.parse("2026-01-01").plusDays(it.toLong())}.filter {!BuyNavDates.weekend(it)}.toSet(),"TEST_ONLY_WEEKDAYS")},now={time})
    @Test fun partialSellKeepsUnitCostAndDeductsFeeFromProceedsOnly()=runTest {
        val s=Store();val g=Gateway();val r=repo(s,g);val t=r.create(input());val ready=r.confirmNav(t.id)
        assertEquals(d("50"),ready.grossAmount);assertEquals(d("0.750"),ready.feeAmount);assertEquals(d("49.250"),ready.netAmount)
        assertEquals(d("30.0"),ready.soldCost);assertEquals(d("19.250"),ready.calculatedProfit)
        assertEquals(d("75"),ready.target!!.shares);assertEquals(d("1.2"),ready.target.unitCost);assertEquals(d("90.0"),ready.target.totalCost)
        assertEquals(SellStatus.SYNCED,r.sync(t.id).status);assertEquals(1,g.posts);assertEquals(ready.target,g.holding)
        r.sync(t.id);assertEquals(1,g.posts)
    }
    @Test fun fullClearRetainsCostMetadataAndSellHistory()=runTest {
        val s=Store();val g=Gateway();val r=repo(s,g);val t=r.create(input("100","0"));r.confirmNav(t.id)
        val synced=r.sync(t.id);assertEquals(SellStatus.SYNCED,synced.status);assertEquals(0,g.holding.shares.signum())
        assertEquals(d("1.2"),g.holding.unitCost);assertEquals(1,s.rows.size);assertEquals(synced,repo(s,g).restore().single())
        val ui=SellCodec.ui(s.rows).single().jsonObject;assertEquals("SELL",ui["kind"]!!.jsonPrimitive.content)
        assertFalse(ui["cashReceiptConfirmed"]!!.jsonPrimitive.boolean);assertEquals("-100",ui["shareDelta"]!!.jsonPrimitive.content)
    }
    @Test fun zeroFeeLargeNumbersAndDecimalProductsStayExact()=runTest {
        val s=Store();val g=Gateway();g.holding=BuyBaseline(d("999999999999999999999999.999999"),d("1.123456789"));g.nav=d("2.123456789")
        val r=repo(s,g);val t=r.create(input("12345678901234567890.123456789","0"));val ready=r.confirmNav(t.id)
        assertEquals(t.input.shares.multiply(g.nav),ready.netAmount);assertEquals(0,ready.feeAmount!!.signum())
        assertEquals(t.baseline.unitCost,ready.target!!.unitCost);assertEquals(ready,SellCodec.decode(SellCodec.encode(listOf(ready))).single())
    }
    @Test fun zeroNegativeTooManySharesAndInvalidFeeCannotCreate()=runTest {
        val s=Store();val g=Gateway();val r=repo(s,g)
        for(shares in listOf("0","-1","100.0001"))assertFailsWith<BuyFailure>{r.create(input(shares))}
        for(rate in listOf("-0.1","1","1.5"))assertFailsWith<BuyFailure>{r.create(input(rate=rate))}
        g.holding=g.holding.copy(shares=d("0"));assertFailsWith<BuyFailure>{r.create(input("1"))}
        assertTrue(s.rows.isEmpty());assertEquals(0,g.posts)
    }
    @Test fun waitingAndReadyRestoreWithoutReconfirmationOrWrites()=runTest {
        val s=Store();val g=Gateway();g.nav=null;val r=repo(s,g);val t=r.create(input())
        assertEquals(SellStatus.WAITING_NAV,r.confirmNav(t.id).status);assertEquals(t.status,repo(s,g).restore().single().status)
        g.nav=d("2.1234");val ready=r.confirmNav(t.id);assertEquals(t.expectedNavDate,ready.confirmedNavDate)
        assertEquals(ready,repo(s,g).restore().single());g.nav=d("99");assertEquals(ready,r.confirmNav(t.id));assertEquals(0,g.posts)
    }
    @Test fun externalChangeNeverPostsOrSilentlyRebases()=runTest {
        val s=Store();val g=Gateway();val r=repo(s,g);val t=r.create(input());r.confirmNav(t.id)
        g.holding=g.holding.copy(shares=d("99"));val changed=r.sync(t.id)
        assertEquals(SellStatus.EXTERNAL_CHANGED,changed.status);assertEquals(g.holding,changed.external);assertEquals(0,g.posts)
        assertEquals(SellStatus.CANCELLED,r.cancel(t.id).status)
    }
    @Test fun successfulPostNeedsMatchingGetAndWrongGetBlocksRepeat()=runTest {
        val s=Store();val g=Gateway();g.wrongTarget=true;val r=repo(s,g);val t=r.create(input());r.confirmNav(t.id)
        assertEquals(SellStatus.SYNCING,r.sync(t.id).status);assertEquals(SellStatus.SYNCING,r.sync(t.id).status)
        assertEquals(1,g.posts);assertFailsWith<BuyFailure>{r.cancel(t.id)}
    }
    @Test fun timeoutActuallyDeliveredGetsReconciledWithoutAnotherPost()=runTest {
        val s=Store();val g=Gateway();g.delivery="timeout";val r=repo(s,g);val t=r.create(input());r.confirmNav(t.id)
        assertEquals(SellStatus.SYNCED,r.sync(t.id).status);assertEquals(SellStatus.SYNCED,repo(s,g).sync(t.id).status);assertEquals(1,g.posts)
    }
    @Test fun undeliveredPostCanOnlyReadBackEvenAfterRestart()=runTest {
        val s=Store();val g=Gateway();g.delivery="undelivered";val r=repo(s,g);val t=r.create(input());r.confirmNav(t.id)
        assertEquals(SellStatus.SYNCING,r.sync(t.id).status);val gets=g.gets;g.delivery="ok"
        assertEquals(SellStatus.SYNCING,r.sync(t.id).status);assertTrue(g.gets>gets);assertEquals(1,g.posts);g.holding=s.rows.single().submittedTarget!!;assertEquals(SellStatus.SYNCED,repo(s,g).sync(t.id).status);assertEquals(1,g.posts)
    }
    @Test fun verifyNetworkFailureAndRestartReconcileAlreadyDeliveredTarget()=runTest {
        val s=Store();val g=Gateway();g.verifyFails=true;val r=repo(s,g);val t=r.create(input());r.confirmNav(t.id)
        assertEquals(SellStatus.SYNCING,r.sync(t.id).status);assertTrue(s.rows.single().postMayHaveBeenSent)
        g.problem=null;val restarted=repo(s,g);restarted.restore();assertEquals(SellStatus.SYNCED,restarted.sync(t.id).status);assertEquals(1,g.posts)
    }
    @Test fun sessionExpiresAfterDeliveredPostAndReloginReconcilesWithoutDuplicateSell()=runTest {
        val s=Store();val g=Gateway()
        val expiring=object:BuyGateway by g {
            override suspend fun postFinalGuarded(fund:BuyFund,target:BuyBaseline,beforeSend:suspend()->Unit){beforeSend();postFinal(fund,target)}
            override suspend fun postFinal(fund:BuyFund,target:BuyBaseline){g.postFinal(fund,target);g.problem=BuyProblem.AUTH_EXPIRED}
        }
        val r=SingleSellRepository(s,expiring,calendar={code->BundledBuyCalendar.forCode("270042")},now={time});val t=r.create(input());r.confirmNav(t.id)
        val failed=r.sync(t.id)
        assertEquals(SellStatus.SYNCING,failed.status);assertEquals(BuyProblem.AUTH_EXPIRED,failed.problem)
        assertTrue(failed.postMayHaveBeenSent);assertNotNull(failed.submittedTarget);assertEquals(1,g.posts)
        assertFailsWith<BuyFailure>{r.cancel(t.id)}
        g.problem=null;val restarted=repo(s,g);restarted.restore()
        assertEquals(SellStatus.SYNCED,restarted.sync(t.id).status);assertEquals(1,g.posts)
    }
    @Test fun interruptedSyncMarkerRestoresAndDoesNotRepeatPost()=runTest {
        val s=Store();val g=Gateway();val r=repo(s,g);val t=r.create(input());val ready=r.confirmNav(t.id)
        s.rows=listOf(ready.copy(status=SellStatus.SYNCING,submittedTarget=ready.target,postMayHaveBeenSent=true));g.holding=ready.target!!
        val restarted=repo(s,g);assertEquals(SellStatus.SYNCING,restarted.restore().single().status)
        assertEquals(SellStatus.SYNCED,restarted.sync(t.id).status);assertEquals(0,g.posts)
    }
    @Test fun unverifiedWriterExpiredTokenOrPersistenceFailureNeverPosts()=runTest {
        val s=Store();val g=Gateway();val r=repo(s,g);val t=r.create(input());r.confirmNav(t.id);g.available=false
        assertEquals(BuyProblem.WRITE_UNVERIFIED,r.sync(t.id).problem);g.available=true;g.problem=BuyProblem.AUTH_EXPIRED
        assertEquals(BuyProblem.AUTH_EXPIRED,r.sync(t.id).problem);g.problem=null;s.fail=true
        assertFailsWith<BuyFailure>{r.sync(t.id)};assertEquals(0,g.posts)
    }
    @Test fun pendingEditsCancelAndDuplicateCreateAreLocalAndIdempotent()=runTest {
        val s=Store();val g=Gateway();val r=repo(s,g);val id="11111111-1111-4111-8111-111111111111";val t=r.create(input(),id)
        assertEquals(t,r.create(input(),id));assertEquals(1,s.rows.size)
        assertFailsWith<BuyFailure>{r.create(input("26"),id)}
        val edited=r.edit(id,d("50"),d("0"),t.input.tradeDate,CutoffType.AFTER_1500,"备注")
        assertEquals(LocalDate.parse("2026-09-29"),edited.expectedNavDate);assertNull(edited.confirmedNav)
        assertEquals(SellStatus.CANCELLED,r.cancel(id).status);r.sync(id);assertEquals(0,g.posts)
    }
    @Test fun confirmedNavCannotBeEdited()=runTest {
        val s=Store();val g=Gateway();val r=repo(s,g);val t=r.create(input());r.confirmNav(t.id)
        assertFailsWith<BuyFailure>{r.edit(t.id,d("1"),d("0"),t.input.tradeDate,CutoffType.BEFORE_1500,"")}
    }
    @Test fun tinyUnrepresentableSaleAndRoundedAccidentalClearAreBlocked()=runTest {
        for(shares in listOf("0.001","99.999")) {
            val s=Store();val g=Gateway();g.roundShares=true;val r=repo(s,g);val t=r.create(input(shares));r.confirmNav(t.id)
            val blocked=r.sync(t.id);assertEquals(SellStatus.READY_TO_SYNC,blocked.status);assertEquals(BuyProblem.INPUT,blocked.problem)
            assertFalse(blocked.postMayHaveBeenSent);assertEquals(0,g.posts)
        }
    }
    @Test fun roundingCannotSilentlyChangeBaselineUnitCost()=runTest {
        val s=Store();val g=Gateway();g.holding=g.holding.copy(unitCost=d("1.2345"));g.roundCost=true;val r=repo(s,g)
        val t=r.create(input());r.confirmNav(t.id);assertEquals(BuyProblem.INPUT,r.sync(t.id).problem);assertEquals(0,g.posts)
    }
    @Test fun normalizedTargetAndActualDeltaRemainSeparateFromRequestedShares()=runTest {
        val s=Store();val g=Gateway();g.roundShares=true;val r=repo(s,g);val t=r.create(input("25.1234"));val ready=r.confirmNav(t.id)
        val synced=r.sync(t.id);assertEquals(SellStatus.SYNCED,synced.status);assertEquals(d("74.8766"),synced.target!!.shares)
        assertEquals(d("74.88"),synced.submittedTarget!!.shares);assertEquals(ready.netAmount,synced.netAmount)
        val ui=SellCodec.ui(listOf(synced)).single().jsonObject;assertEquals("25.1234",ui["confirmedShares"]!!.jsonPrimitive.content)
        assertEquals("-25.12",ui["shareDelta"]!!.jsonPrimitive.content);assertFalse(ui["cashReceiptConfirmed"]!!.jsonPrimitive.boolean)
    }
    @Test fun malformedCodecAndDiagnosticsNeverExposeFinancialSecrets()=runTest {
        val s=Store();val g=Gateway();val t=repo(s,g).create(input());assertFalse(t.toString().contains(fund.fundName))
        assertFailsWith<BuyFailure>{SellCodec.decode(JsonArray(listOf(buildJsonObject {put("type","BUY")})))}
        val ui=SellCodec.ui(listOf(t)).single().jsonObject;assertEquals(JsonNull,ui["confirmedNav"]);assertEquals(JsonNull,ui["confirmedAmount"])
        assertFalse(ui.toString().contains("accountId"));assertEquals("KEEP_UNIT_COST",ui["costPolicy"]!!.jsonPrimitive.content)
    }
    @Test fun dateResolverCannotSkipUnknownWeekdayOrConfirmFutureNav()=runTest {
        for(date in listOf("2026-09-29","2026-10-12")) {
            val s=Store();val g=Gateway();val dishonest=object:BuyGateway by g {
                override suspend fun resolveOfficialNav(fund:BuyFund,startDate:LocalDate,knownClosed:(LocalDate)->Boolean)=OfficialBuyNav(LocalDate.parse(date),d("99"))
            }
            g.nav=null
            val r=SingleSellRepository(s,dishonest,calendar={BundledBuyCalendar.forCode("270042")},now={time});val t=r.create(input());val pending=r.confirmNav(t.id)
            assertEquals(SellStatus.WAITING_NAV,pending.status);assertNull(pending.confirmedNav)
            assertNull(pending.problem);assertEquals(0,g.posts)
        }
    }
    @Test fun afterCutoffAndWeekendForecastNeedNoCalendar()=runTest {
        val s=Store();val g=Gateway();val r=repo(s,g)
        val t=r.create(input().copy(tradeDate=LocalDate.parse("2026-09-25"),cutoffType=CutoffType.AFTER_1500))
        assertEquals(LocalDate.parse("2026-09-28"),t.expectedNavDate);assertEquals("shared-cn-us-2026",t.calendarSource)
        assertEquals(LocalDate.parse("2026-09-28"),r.confirmNav(t.id).confirmedNavDate);assertEquals(0,g.posts)
    }
}

