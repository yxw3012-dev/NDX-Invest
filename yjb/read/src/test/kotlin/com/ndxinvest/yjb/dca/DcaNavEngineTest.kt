package com.ndxinvest.yjb.dca

import com.ndxinvest.yjb.buy.*
import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DcaNavEngineTest {
    private class Fixture {
        val fund=BuyFund("1","17","040046","synthetic")
        val id="885e825b-6aa0-4a30-9786-c65a2c41141e"
        var time=Instant.parse("2026-10-03T01:00:00Z")
        var history=listOf<OfficialBuyNav>();var gets=0;var posts=0;var historyReads=0
        var calendarProvider:(String)->BuyCalendar?=BundledBuyCalendar::forCode
        var historyError:BuyProblem?=null;var auth=false;var sellPending=false
        var position=BuyBaseline(BigDecimal("100"),BigDecimal("2"))
        var postMode="success";var failVerify=false;var failReadyWrite=false;var available=true;var boundedWirePrecision=false
        val store=object:DcaStorage {
            var state=DcaState()
            override suspend fun read()=DcaCodec.decode(DcaCodec.encode(state))
            override suspend fun write(state:DcaState){this.state=DcaCodec.decode(DcaCodec.encode(state))}
        }
        val journal=object:BuyStorage {
            var rows=emptyList<BuyTransaction>()
            override suspend fun read()=BuyCodec.decode(BuyCodec.encode(rows))
            override suspend fun write(rows:List<BuyTransaction>){if(failReadyWrite&&rows.any {it.status==BuyStatus.READY_TO_SYNC}){failReadyWrite=false;throw BuyFailure(BuyProblem.STORAGE)};this.rows=BuyCodec.decode(BuyCodec.encode(rows))}
        }
        val gateway=object:BuyGateway {
            override suspend fun current(fund:BuyFund):BuyBaseline {gets++;if(auth)throw BuyFailure(BuyProblem.AUTH_EXPIRED);if(failVerify&&posts>0)throw BuyFailure(BuyProblem.NETWORK);return position}
            override suspend fun officialHistory(fund:BuyFund):List<OfficialBuyNav> {historyReads++;historyError?.let {throw BuyFailure(it)};return history}
            override suspend fun officialNav(fund:BuyFund,targetDate:LocalDate):BigDecimal?=error("automatic DCA must use formal history")
            override fun writeAvailable()=available
            override fun normalizeTarget(target:BuyBaseline)=if(boundedWirePrecision)BuyBaseline(target.shares.setScale(2,java.math.RoundingMode.HALF_UP),target.unitCost.setScale(4,java.math.RoundingMode.HALF_UP))else target
            override fun matches(actual:BuyBaseline,target:BuyBaseline)=actual.equivalent(target)
            override suspend fun postFinal(fund:BuyFund,target:BuyBaseline){posts++;if(postMode!="undelivered")position=target;if(postMode!="success")throw BuyFailure(BuyProblem.NETWORK)}
        }
        suspend fun approvePending(){
            val review=store.read().catchupReviews.single {it.decision==CatchupDecision.PENDING}
            val p=store.read().plans.single {it.id==review.slots.first().planId}
            repo().resolveCatchupReview(p.id,p.revision,review.id,true,position)
        }
        fun buys()=SingleBuyRepository(journal,gateway,{error("automatic DCA must not read calendars")},now={time})
        fun repo(b:SingleBuyRepository=buys())=DcaRepository(store,journal,b,now={time})
        fun config(f:DcaFrequency=DcaFrequency.DAILY,w:Int=1,m:Int=1)=DcaConfig(BigDecimal("5"),BigDecimal.ZERO,f,w,m)
        suspend fun create(c:DcaConfig=config(),planId:String=id)=repo().create(fund,c,true,planId)
        fun at(date:String){time=LocalDate.parse(date).atTime(12,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant()}
        fun nav(vararg dates:String){history=dates.map {OfficialBuyNav(LocalDate.parse(it),BigDecimal("2.123456789"))}}
        suspend fun run(accounts:Set<String> = setOf("1")):DcaNavEngine.Report {
            val b=buys();b.restore();val r=repo(b)
            return DcaNavEngine(store,journal,r,b,gateway,{sellPending},calendar=calendarProvider,now={time}).run(accounts)
        }
    }
    @Test fun holidayGapCreatesOnlyActualNavDatesAndNeverUsesCalendar()=runTest {
        val f=Fixture();f.create();f.at("2026-10-09");f.nav("2026-09-30","2026-10-08")
        assertEquals(1,f.run().synced);val t=f.journal.read().single()
        assertEquals(LocalDate.parse("2026-10-08"),t.input.tradeDate);assertEquals(t.input.tradeDate,t.confirmedNavDate)
        assertEquals(f.time,t.createdAt);assertEquals(BuyStatus.SYNCED,t.status);assertEquals(BigDecimal("5"),t.input.amount)
        assertEquals(LocalDate.parse("2026-10-09"),f.store.read().plans.single().nextDate)
        assertEquals(0,f.run().synced);assertEquals(1,f.posts);assertEquals(1,f.journal.read().size)
    }
    @Test fun rateLimitStopsBatchBeforeAnotherFundOrNewOccurrenceAndLaterRunRecovers()=runTest {
        val f=Fixture();f.create()
        f.repo().create(f.fund.copy(fundId="18",fundCode="270042"),f.config(),true,"985e825b-6aa0-4a30-9786-c65a2c41141e")
        f.at("2026-10-09");f.nav("2026-10-08");f.historyError=BuyProblem.RATE_LIMITED
        assertEquals(setOf(BuyProblem.RATE_LIMITED),f.run().problems)
        assertEquals(1,f.historyReads);assertEquals(0,f.gets);assertEquals(0,f.posts)
        assertTrue(f.journal.read().isEmpty());assertTrue(f.store.read().occurrences.isEmpty())
        f.historyError=null;assertEquals(2,f.run().synced);assertEquals(2,f.posts)
        assertEquals(0,f.run().synced);assertEquals(2,f.posts)
    }
    @Test fun emptyHistoryWaitsThenLateEarlierNavIsNotLostAfterLaterDate()=runTest {
        val f=Fixture();f.create();f.at("2026-10-13");assertEquals(0,f.run().synced);assertTrue(f.journal.read().isEmpty())
        f.nav("2026-09-30","2026-10-09");assertEquals(0,f.run().synced)
        f.nav("2026-09-30","2026-10-08","2026-10-09");assertEquals(2,f.run().synced)
        assertEquals(setOf("2026-10-08","2026-10-09"),f.journal.read().map {it.scheduledDate.toString()}.toSet());assertEquals(2,f.posts)
    }
    @Test fun offlineCatchupIsSequentialAndExactFeeMathStaysOrdinaryBuy()=runTest {
        val f=Fixture();f.create(f.config().copy(feeRate=BigDecimal("0.0015")));f.at("2026-10-13")
        f.historyError=BuyProblem.NETWORK;assertEquals(setOf(BuyProblem.NETWORK),f.run().problems);assertEquals(0,f.posts)
        f.historyError=null;f.nav("2026-09-30","2026-10-08","2026-10-09","2026-10-12")
        assertEquals(setOf(BuyProblem.CATCHUP_REVIEW),f.run().problems);assertEquals(0,f.posts);f.approvePending();assertEquals(3,f.run().synced);val rows=f.journal.read()
        assertEquals(BigDecimal("0.01"),rows.first().feeAmount)
        assertTrue(rows[1].baseline.equivalent(rows[0].target!!));assertTrue(rows[2].baseline.equivalent(rows[1].target!!));assertEquals(3,f.posts)
        assertEquals(0,f.run().synced)
    }
    @Test fun historicalAmountChangesUseTheirOwnEffectiveConfiguration()=runTest {
        val f=Fixture();val p=f.create();f.at("2026-10-09");f.repo().update(p.id,f.config().copy(amount=BigDecimal("10.123456789")),p.revision)
        f.at("2026-10-13");f.nav("2026-09-30","2026-10-08","2026-10-09")
        assertEquals(setOf(BuyProblem.CATCHUP_REVIEW),f.run().problems);f.approvePending();assertEquals(2,f.run().synced);assertEquals(listOf("5","10.123456789"),f.journal.read().map {it.input.amount.toPlainString()})
    }
    @Test fun pauseResumeAndDeleteNeverCreateDatesDuringPauseOrAfterDeletion()=runTest {
        val f=Fixture();val p=f.create();f.at("2026-10-08");val paused=f.repo().setEnabled(p.id,false,p.revision)
        f.nav("2026-09-30","2026-10-08");assertEquals(0,f.run().synced)
        f.at("2026-10-09");val resumed=f.repo().setEnabled(p.id,true,paused.revision)
        f.at("2026-10-13");f.nav("2026-09-30","2026-10-08","2026-10-09","2026-10-12")
        assertEquals(setOf(BuyProblem.CATCHUP_REVIEW),f.run().problems);f.approvePending();assertEquals(2,f.run().synced);assertFalse(f.journal.read().any {it.scheduledDate.toString()=="2026-10-08"})
        val latest=f.store.read().plans.single();assertTrue(latest.revision>resumed.revision);f.repo().delete(p.id,latest.revision)
        f.nav("2026-09-30","2026-10-13");assertEquals(0,f.run().synced);assertEquals(2,f.journal.read().size)
    }
    @Test fun legacyUpgradeNeverFabricatesEarlierConfigHistory()=runTest {
        val f=Fixture();val p=f.create();f.store.write(DcaState(listOf(p.copy(history=emptyList()))))
        f.at("2026-10-13");f.nav("2026-09-30","2026-10-08","2026-10-12");assertEquals(0,f.run().synced)
        assertEquals(f.time,f.store.read().plans.single().history.single().effectiveAt)
        f.at("2026-10-14");f.nav("2026-09-30","2026-10-13");assertEquals(1,f.run().synced)
    }
    @Test fun planCreatedAfterCutoffNeverClaimsThatDay()=runTest {
        val f=Fixture();f.time=Instant.parse("2026-10-08T08:00:00Z");f.create();f.at("2026-10-12");f.nav("2026-09-30","2026-10-08","2026-10-09")
        assertEquals(1,f.run().synced);assertEquals("2026-10-09",f.journal.read().single().scheduledDate.toString())
    }
    @Test fun weeklyBiweeklyAndMonthlyDatesAreNotShiftedOntoAnotherNav()=runTest {
        for((config,expected) in listOf(
            Fixture().config(DcaFrequency.WEEKLY,w=1) to listOf("2026-10-12","2026-10-19"),
            Fixture().config(DcaFrequency.BIWEEKLY,w=1) to listOf("2026-10-19"),
            Fixture().config(DcaFrequency.MONTHLY,m=9) to listOf("2026-10-09"))) {
            val f=Fixture();f.create(config);f.at("2026-10-20");f.nav("2026-09-30","2026-10-05","2026-10-08","2026-10-09","2026-10-12","2026-10-19")
            assertEquals(setOf(BuyProblem.CATCHUP_REVIEW),f.run().problems);f.approvePending();assertEquals(expected.size,f.run().synced);assertEquals(expected,f.journal.read().map {it.scheduledDate.toString()})
        }
    }
    @Test fun twoPlansOnSameFundSyncOneAtATimeAgainstFreshBaselines()=runTest {
        val f=Fixture();f.create();f.create(planId="885e825b-6aa0-4a30-9786-c65a2c411412");f.at("2026-10-09");f.nav("2026-09-30","2026-10-08")
        assertEquals(2,f.run().synced);val rows=f.journal.read();assertTrue(rows[1].baseline.equivalent(rows[0].target!!));assertEquals(2,f.posts)
    }
    @Test fun cancelledOrRemovedRecordCannotBeRecreatedByRestartOrNavCorrection()=runTest {
        val f=Fixture();val p=f.create();f.at("2026-10-09");f.nav("2026-09-30","2026-10-08")
        f.repo().executeNav(p.id,f.history.last());val t=f.journal.read().single();f.journal.write(listOf(t.copy(status=BuyStatus.CANCELLED)))
        f.history=f.history.map {it.copy(value=BigDecimal("3"))};assertEquals(0,f.run().synced);assertEquals(0,f.posts);assertEquals(1,f.journal.read().size)
        f.journal.write(emptyList());assertFailsWith<DcaFailure>{f.run()};assertEquals(0,f.posts);assertTrue(f.journal.read().isEmpty())
    }
    @Test fun crashBetweenWaitingAndConfirmedRestoresExactFrozenNavOnce()=runTest {
        val f=Fixture();f.create();f.at("2026-10-09");f.nav("2026-09-30","2026-10-08");f.failReadyWrite=true
        assertEquals(setOf(BuyProblem.STORAGE),f.run().problems);assertEquals(BuyStatus.WAITING_NAV,f.journal.read().single().status)
        f.history=emptyList();assertEquals(1,f.run().synced);assertEquals(1,f.posts);assertEquals(f.store.read().occurrences.single().officialNav?.value,f.journal.read().single().confirmedNav)
    }
    @Test fun outsideHistoryWindowStopsInsteadOfFabricatingMissingDates()=runTest {
        val f=Fixture();f.create();f.at("2027-03-02");f.nav("2027-02-01","2027-03-01")
        assertEquals(setOf(BuyProblem.NAV_OUTSIDE_WINDOW),f.run().problems);assertTrue(f.journal.read().isEmpty());assertEquals(0,f.posts)
    }
    @Test fun storageFailureStopsWholeRunBeforeAnotherFundCanPost()=runTest {
        val f=Fixture();f.create();f.repo().create(f.fund.copy(fundId="18",fundCode="000018"),f.config(),true,"885e825b-6aa0-4a30-9786-c65a2c411412")
        f.at("2026-10-09");f.nav("2026-09-30","2026-10-08");f.failReadyWrite=true
        assertEquals(setOf(BuyProblem.STORAGE),f.run().problems);assertEquals(1,f.journal.read().size);assertEquals("17",f.journal.read().single().input.fund.fundId);assertEquals(0,f.posts)
    }
    @Test fun anEmptySuccessfulResponseDoesNotProvePastDateCoverage()=runTest {
        val f=Fixture();f.create();f.at("2027-03-02");assertEquals(0,f.run().synced)
        assertNull(f.store.read().plans.single().lastObservedNavDate)
        f.nav("2027-02-01","2027-03-01");assertEquals(setOf(BuyProblem.NAV_OUTSIDE_WINDOW),f.run().problems);assertEquals(0,f.posts)
    }
    @Test fun renamedFundStillBlocksOnItsUnfinishedManualTransaction()=runTest {
        val f=Fixture();f.create();f.at("2026-10-09");f.nav("2026-09-30","2026-10-08")
        val t=f.repo().executeNav(f.id,f.history.last())!!
        f.journal.write(f.journal.read()+t.copy(id="885e825b-6aa0-4a30-9786-c65a2c411411",input=t.input.copy(fund=f.fund.copy(fundName="renamed")),originPlanId=null,scheduledDate=null))
        assertEquals(setOf(BuyProblem.PENDING_TRANSACTION),f.run().problems);assertEquals(0,f.posts)
    }
    @Test fun monthlyClampedDateOnlyExecutesIfThatExactDateHasFormalNav()=runTest {
        val f=Fixture();f.calendarProvider={code->BuyCalendar(code,LocalDate.parse("2028-01-01"),LocalDate.parse("2028-12-31"),setOf(LocalDate.parse("2028-02-29")),"TEST_ONLY_EXPLICIT_DATES")};f.at("2028-01-03");f.create(f.config(DcaFrequency.MONTHLY,m=31));f.at("2028-03-02")
        f.nav("2028-01-02","2028-02-29","2028-03-01");assertEquals(setOf(BuyProblem.CATCHUP_REVIEW),f.run().problems);f.approvePending();assertEquals(1,f.run().synced);assertEquals("2028-02-29",f.journal.read().single().scheduledDate.toString())
    }
    @Test fun authExpiredAndWrongAccountNeverPost()=runTest {
        val f=Fixture();f.create();f.at("2026-10-09");f.nav("2026-09-30","2026-10-08")
        assertEquals(0,f.run(setOf("2")).synced);assertEquals(0,f.gets)
        f.auth=true;assertEquals(setOf(BuyProblem.AUTH_EXPIRED),f.run().problems);assertEquals(0,f.posts)
        f.auth=false;assertEquals(1,f.run().synced);assertEquals(1,f.journal.read().size)
    }
    @Test fun manualPendingBuyOrSellPreventsAutomaticBaselineInterference()=runTest {
        val f=Fixture();f.create();f.at("2026-10-09");f.nav("2026-09-30","2026-10-08")
        f.sellPending=true;assertEquals(setOf(BuyProblem.PENDING_TRANSACTION),f.run().problems);assertEquals(0,f.posts)
        f.sellPending=false;val ready=f.repo().executeNav(f.id,f.history.last())!!
        f.journal.write(f.journal.read()+ready.copy(id="885e825b-6aa0-4a30-9786-c65a2c411411",originPlanId=null,scheduledDate=null))
        assertEquals(setOf(BuyProblem.PENDING_TRANSACTION),f.run().problems);assertEquals(0,f.posts)
    }
    @Test fun legacyManualOccurrenceIsNeverTakenOverOrAutomaticallySynced()=runTest {
        val f=Fixture();val p=f.create();f.at("2026-10-04");f.repo(SingleBuyRepository(f.journal,f.gateway,{null},now={f.time})).execute(p.id,p.nextDate,p.revision)
        f.at("2026-10-09");f.nav("2026-09-30","2026-10-08")
        assertEquals(setOf(BuyProblem.PENDING_TRANSACTION),f.run().problems);assertEquals(0,f.posts);assertNull(f.store.read().occurrences.single().officialNav)
    }
    @Test fun baselineConflictStopsFundAndNeverGeneratesFollowingPeriod()=runTest {
        val f=Fixture();f.create();f.at("2026-10-13");f.nav("2026-09-30","2026-10-08","2026-10-09")
        f.repo().executeNav(f.id,f.history[1]);f.position=BuyBaseline(BigDecimal("123"),BigDecimal("2"))
        assertEquals(setOf(BuyProblem.EXTERNAL_CHANGED),f.run().problems);assertEquals(BuyStatus.EXTERNAL_CHANGED,f.journal.read().single().status);assertEquals(0,f.posts)
        assertEquals(0,f.run().synced);assertEquals(1,f.journal.read().size)
    }
    @Test fun unverifiedWriterNeverPostsAndKeepsReadyForLaterEvidence()=runTest {
        val f=Fixture();f.create();f.at("2026-10-09");f.nav("2026-09-30","2026-10-08");f.available=false
        assertEquals(setOf(BuyProblem.WRITE_UNVERIFIED),f.run().problems);assertEquals(0,f.posts);assertEquals(BuyStatus.READY_TO_SYNC,f.journal.read().single().status)
        f.available=true;assertEquals(1,f.run().synced);assertEquals(1,f.posts)
    }
    @Test fun deliveredTimeoutVerifiesAndUndeliveredTimeoutNeverResends()=runTest {
        for(mode in listOf("delivered","undelivered")) {
            val f=Fixture();f.create();f.at("2026-10-09");f.nav("2026-09-30","2026-10-08");f.postMode=mode
            val result=f.run();assertEquals(1,f.posts)
            if(mode=="delivered"){assertEquals(1,result.synced);assertEquals(0,f.run().synced);assertEquals(1,f.posts)}
            else {assertEquals(setOf(BuyProblem.WRITE_PENDING),result.problems);f.postMode="success";val gets=f.gets;assertEquals(setOf(BuyProblem.WRITE_PENDING),f.run().problems);assertTrue(f.gets>gets);assertEquals(1,f.posts);f.position=f.journal.read().single().submittedTarget!!;assertEquals(1,f.run().synced);assertEquals(1,f.posts)}
        }
    }
    @Test fun failedGetAfterPostRestartsByReconcilingWithoutRepeatWrite()=runTest {
        val f=Fixture();f.create();f.at("2026-10-09");f.nav("2026-09-30","2026-10-08");f.failVerify=true
        assertEquals(setOf(BuyProblem.WRITE_PENDING),f.run().problems);assertTrue(f.journal.read().single().postMayHaveBeenSent)
        f.failVerify=false;assertEquals(1,f.run().synced);assertEquals(1,f.posts);assertEquals(1,f.journal.read().size)
    }
    @Test fun threePendingPeriodsRequireOneDurableExplicitApproval()=runTest {
        val f=Fixture();f.create();f.at("2026-10-09");f.repo().checked(setOf(f.id),null)
        f.at("2026-10-13");f.nav("2026-09-30","2026-10-08","2026-10-09","2026-10-12")
        assertEquals(setOf(BuyProblem.CATCHUP_REVIEW),f.run().problems);assertEquals(0,f.posts);assertTrue(f.journal.read().isEmpty())
        val first=f.store.read().catchupReviews.single();assertEquals(3,first.slots.size)
        f.run();assertEquals(first.id,f.store.read().catchupReviews.single().id)
        f.approvePending();assertEquals(3,f.run().synced);assertEquals(3,f.posts)
        assertEquals(0,f.run().synced);assertEquals(3,f.posts)
    }
    @Test fun sevenDaysWithoutSuccessfulCheckRequiresReviewEvenForOnePeriod()=runTest {
        val f=Fixture();f.create();f.at("2026-10-10");f.nav("2026-09-30","2026-10-08")
        assertEquals(setOf(BuyProblem.CATCHUP_REVIEW),f.run().problems);assertEquals(1,f.store.read().catchupReviews.single().slots.size);assertEquals(0,f.posts)
        assertTrue(DcaCodec.ui(f.store.read(),setOf("1"),f.time).single().toString().contains("catchupReview"))
    }
    @Test fun skipPersistsAcrossRestartAndCreatesNoTransactions()=runTest {
        val f=Fixture();f.create();f.at("2026-10-13");f.nav("2026-09-30","2026-10-08","2026-10-09","2026-10-12");f.run()
        val review=f.store.read().catchupReviews.single();val p=f.store.read().plans.single()
        f.repo().resolveCatchupReview(p.id,p.revision,review.id,false)
        assertEquals(0,f.run().synced);assertTrue(f.journal.read().isEmpty());assertEquals(0,f.posts)
        assertEquals(CatchupDecision.SKIPPED,f.store.read().catchupReviews.single().decision)
        f.at("2026-10-14");f.nav("2026-09-30","2026-10-08","2026-10-09","2026-10-12","2026-10-13")
        assertEquals(1,f.run().synced);assertEquals("2026-10-13",f.journal.read().single().scheduledDate.toString())
    }
    @Test fun changedBaselineRequiresNewApprovalBeforeAnyCatchupWrite()=runTest {
        val f=Fixture();f.create();f.at("2026-10-13");f.nav("2026-09-30","2026-10-08","2026-10-09","2026-10-12");f.run()
        val review=f.store.read().catchupReviews.single();val p=f.store.read().plans.single()
        f.position=f.position.copy(shares=BigDecimal("101"))
        assertEquals(BuyProblem.EXTERNAL_CHANGED,assertFailsWith<BuyFailure>{f.repo().resolveCatchupReview(p.id,p.revision,review.id,true,f.position)}.problem)
        f.run();assertEquals(0,f.posts);f.approvePending()
        f.position=f.position.copy(shares=BigDecimal("102"))
        assertEquals(setOf(BuyProblem.CATCHUP_REVIEW),f.run().problems);assertEquals(0,f.posts)
        assertEquals(1,f.store.read().catchupReviews.count {it.decision==CatchupDecision.PENDING})
    }
    @Test fun planEditPauseAndDeleteInvalidateOldConfirmation()=runTest {
        for(action in listOf("edit","pause","delete")) {
            val f=Fixture();f.create();f.at("2026-10-13");f.nav("2026-09-30","2026-10-08","2026-10-09","2026-10-12");f.run()
            val review=f.store.read().catchupReviews.single();val p=f.store.read().plans.single()
            when(action){"edit"->f.repo().update(p.id,p.config.copy(amount=BigDecimal("8")),p.revision);"pause"->f.repo().setEnabled(p.id,false,p.revision);else->f.repo().delete(p.id,p.revision)}
            assertEquals(CatchupDecision.SUPERSEDED,f.store.read().catchupReviews.single().decision)
            assertFailsWith<DcaFailure>{f.repo().resolveCatchupReview(p.id,p.revision,review.id,true,f.position)};assertEquals(0,f.posts)
        }
    }
    @Test fun approvalIsNotBlanketConsentForFutureBacklog()=runTest {
        // Bound fake wire values like a verified writer; this is not real YJB precision evidence.
        val f=Fixture();f.boundedWirePrecision=true;f.create();f.at("2026-10-13");f.nav("2026-09-30","2026-10-08","2026-10-09","2026-10-12");f.run();f.approvePending()
        f.at("2026-10-16");f.nav("2026-09-30","2026-10-08","2026-10-09","2026-10-12","2026-10-13","2026-10-14","2026-10-15")
        val result=f.run();assertEquals(3,result.synced);assertEquals(setOf(BuyProblem.CATCHUP_REVIEW),result.problems);assertEquals(3,f.posts)
        assertEquals(listOf("2026-10-13","2026-10-14","2026-10-15"),f.store.read().catchupReviews.single {it.decision==CatchupDecision.PENDING}.slots.map {it.date.toString()})
    }
    @Test fun pendingApprovalDoesNotStopRecoveryOfAnAlreadyCreatedTransaction()=runTest {
        val f=Fixture();f.create();f.at("2026-10-09");f.nav("2026-09-30","2026-10-08");f.failVerify=true;f.run()
        f.failVerify=false;f.at("2026-10-15");f.nav("2026-09-30","2026-10-08","2026-10-09","2026-10-12","2026-10-13")
        val result=f.run();assertEquals(1,result.synced);assertEquals(setOf(BuyProblem.CATCHUP_REVIEW),result.problems);assertEquals(1,f.posts)
        assertEquals(BuyStatus.SYNCED,f.journal.read().single().status)
    }
    @Test fun approvalBatchIsBoundedToFiftySlotsAndTailRequiresSeparateConsent()=runTest {
        val f=Fixture();f.boundedWirePrecision=true;f.create()
        val dates=(1..85).map {LocalDate.parse("2026-10-03").plusDays(it.toLong())}.filter {!BuyNavDates.weekend(it)}.take(53)
        f.calendarProvider={code->BuyCalendar(code,LocalDate.parse("2026-01-01"),LocalDate.parse("2026-12-31"),dates.toSet(),"TEST_ONLY")}
        f.at(dates.last().toString());f.history=dates.map {OfficialBuyNav(it,BigDecimal("2.123456789"))}
        assertEquals(setOf(BuyProblem.CATCHUP_REVIEW),f.run().problems);assertEquals(50,f.store.read().catchupReviews.single().slots.size);f.approvePending()
        val report=f.run();assertEquals(50,report.synced);assertEquals(50,f.posts);assertEquals(setOf(BuyProblem.CATCHUP_REVIEW),report.problems)
        assertEquals(3,f.store.read().catchupReviews.single {it.decision==CatchupDecision.PENDING}.slots.size)
    }
    @Test fun futureOrMalformedHistoryCannotCreateTransactions()=runTest {
        val f=Fixture();f.create();f.at("2026-10-09");f.nav("2026-10-10")
        // The coverage guard is conservative even before future-date validation.
        assertTrue(f.run().problems.isNotEmpty());assertEquals(0,f.posts)
        f.nav("2026-09-30","2026-10-08","2026-10-08");assertEquals(setOf(BuyProblem.NAV_PROTOCOL),f.run().problems);assertEquals(0,f.posts)
    }
}
