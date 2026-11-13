package com.ndxinvest.yjb.buy

import com.ndxinvest.yjb.read.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.*
import kotlin.test.*

/** Synthetic official-history responses; no live account, inferred closure or YJB POST. */
class BuyNavResolutionTest {
    private val fund=BuyFund("1","17","000017","未内置日历的基金")
    private val time=Instant.parse("2026-10-10T00:00:00Z")
    private val calls=mutableListOf<ReadRequest>()
    private var history="""{"list":[]}"""
    private class Store:BuyStorage {
        var rows=emptyList<BuyTransaction>()
        override suspend fun read()=rows
        override suspend fun write(rows:List<BuyTransaction>){this.rows=BuyCodec.decode(BuyCodec.encode(rows))}
    }
    private val store=Store()
    private val gateway=YjbBuyGateway(YjbReadApi(YjbWire {request->
        calls+=request
        val data=when(request.path) {
            "/users/v1/user-account"->"""{"list":[{"id":1}]}"""
            "/position/v1/static/fund-accounts/1/funds"->"""{"list":[{"fund_id":17,"hold_share":"222.53","hold_cost":"4.49412345"}]}"""
            "/market/v1/fund-nav/fund-history-nav?fund_id=17"->history
            else->error("Unexpected route; no writes in date resolution")
        }
        ReadResponse(200,"{\"code\":200,\"data\":$data}")
    },{charArrayOf('k')},{time}),{charArrayOf('s')})
    private fun repo(calendar:(String)->BuyCalendar?={code->BundledBuyCalendar.forCode(code)?:BuyCalendar(code,LocalDate.parse("2026-01-01"),LocalDate.parse("2026-12-31"),(0..364).map {LocalDate.parse("2026-01-01").plusDays(it.toLong())}.filter {!BuyNavDates.weekend(it)}.toSet(),"TEST_ONLY_WEEKDAYS")})=SingleBuyRepository(store,gateway,calendar,{time})
    private fun input(date:String="2026-09-28",cutoff:CutoffType=CutoffType.BEFORE_1500,code:String=fund.fundCode)=
        BuyInput(fund.copy(fundCode=code),BigDecimal("100"),BigDecimal.ZERO,LocalDate.parse(date),cutoff)
    private fun history(vararg entries:Pair<String,String>) {
        history=buildJsonObject {put("list",JsonArray(entries.map {(date,nav)->buildJsonObject {put("day",date);put("unit_net",nav)}}))}.toString()
    }
    private fun noPost(){assertTrue(calls.all {it.method=="GET"})}

    @Test fun explicitCalendarUsesExactOfficialDateAndDecimalMath()=runTest {
        history("2026-09-29" to "99.99","2026-09-28" to "4.5700")
        val r=repo();val created=r.create(input());assertEquals(BuyStatus.WAITING_NAV,created.status)
        val t=r.confirmNav(created.id)
        assertEquals(BuyStatus.READY_TO_SYNC,t.status);assertEquals(LocalDate.parse("2026-09-28"),t.confirmedNavDate)
        assertEquals(BigDecimal("4.5700"),t.confirmedNav);assertEquals(0,t.feeAmount!!.signum())
        assertEquals(BigDecimal("21.88"),t.confirmedShares)
        noPost()
    }
    @Test fun afterCutoffNeverUsesSameDayNav()=runTest {
        history("2026-09-28" to "4.57")
        val r=repo();val t=r.create(input(cutoff=CutoffType.AFTER_1500))
        assertEquals(LocalDate.parse("2026-09-29"),t.expectedNavDate)
        assertEquals(BuyStatus.WAITING_NAV,r.confirmNav(t.id).status)
        history("2026-09-29" to "4.61","2026-09-28" to "4.57")
        assertEquals(LocalDate.parse("2026-09-29"),r.confirmNav(t.id).confirmedNavDate);noPost()
    }
    @Test fun weekendAndFridayAfterCutoffResolveMondayWithEvidence()=runTest {
        history("2026-09-28" to "4.57","2026-09-24" to "4.60")
        val r=repo()
        for(value in listOf(input("2026-09-26"),input("2026-09-27"),input("2026-09-25",CutoffType.AFTER_1500))) {
            val t=r.create(value);assertEquals(LocalDate.parse("2026-09-28"),r.confirmNav(t.id).confirmedNavDate)
        };noPost()
    }
    @Test fun missingWeekdayIsNotSkippedEvenWhenLaterNavExistsOrResponseClaimsComplete()=runTest {
        history="""{"list":[{"day":"2026-09-30","unit_net":"4.65"},{"day":"2026-09-28","unit_net":"4.57"}],"total":2,"complete":true,"has_more":false}"""
        val r=repo();val t=r.create(input("2026-09-29"));val pending=r.confirmNav(t.id)
        assertEquals(BuyStatus.WAITING_NAV,pending.status);assertNull(pending.problem)
        assertNull(pending.confirmedNavDate);assertNull(pending.confirmedShares)
        assertNull(pending.confirmedNav);noPost()
    }
    @Test fun delayedMissingDateCanArriveAndRestoreConfirmExactDate()=runTest {
        history("2026-09-30" to "4.65","2026-09-28" to "4.57")
        val t=repo().create(input("2026-09-29"));repo().confirmNav(t.id)
        val restored=repo();assertEquals(BuyStatus.WAITING_NAV,restored.restore().single().status)
        history("2026-09-30" to "4.65","2026-09-29" to "4.61","2026-09-28" to "4.57")
        val confirmed=restored.confirmNav(t.id);assertEquals(BuyStatus.READY_TO_SYNC,confirmed.status)
        assertEquals(LocalDate.parse("2026-09-29"),confirmed.confirmedNavDate);assertNull(confirmed.problem);noPost()
    }
    @Test fun absentCalendarYearStillCreatesAndEditsLocally()=runTest {
        val r=repo();val t=r.create(input("2025-12-31",code="270042"))
        assertEquals(BuyNavDates.PROVISIONAL_SOURCE,t.calendarSource)
        val edited=r.edit(t.id,BigDecimal("200"),BigDecimal.ZERO,LocalDate.parse("2026-09-29"),CutoffType.AFTER_1500,"编辑")
        assertEquals(BuyStatus.WAITING_NAV,edited.status);assertEquals(LocalDate.parse("2026-09-30"),edited.expectedNavDate)
        assertNull(edited.confirmedNav);noPost()
    }
    @Test fun documentedClosureCanSkipButPredictionRemainsSeparateFromConfirmation()=runTest {
        val start=LocalDate.parse("2026-09-28");val actual=start.plusDays(1);val predicted=start.plusDays(2)
        val evidence=BuyCalendar(fund.fundCode,start,predicted,setOf(predicted),"TEST_ONLY_EXPLICIT_CLOSURE")
        val r=repo {evidence};val t=r.create(input());assertEquals(predicted,t.expectedNavDate)
        history(actual.toString() to "4.61",predicted.toString() to "99")
        val ready=r.confirmNav(t.id)
        assertEquals(predicted,ready.confirmedNavDate);assertEquals(predicted,ready.expectedNavDate)
        assertEquals(BigDecimal("99"),ready.confirmedNav);assertEquals(ready,BuyCodec.decode(BuyCodec.encode(listOf(ready))).single());noPost()
    }
    @Test fun existingVerifiedHolidayEvidenceSkipsToPublishedNavOnly()=runTest {
        history("2026-09-28" to "4.57","2026-09-24" to "4.60")
        val r=repo();val t=r.create(input("2026-09-25",code="270042"))
        assertEquals(LocalDate.parse("2026-09-28"),t.expectedNavDate)
        val ready=r.confirmNav(t.id);assertEquals(BuyStatus.READY_TO_SYNC,ready.status)
        assertEquals(LocalDate.parse("2026-09-28"),ready.confirmedNavDate);noPost()
    }
    @Test fun verifiedGuotaiProfileCoversTheYear()=runTest {
        history("2026-09-29" to "4.61","2026-09-28" to "4.57")
        val r=repo();val t=r.create(input(cutoff=CutoffType.AFTER_1500,code="160213"))
        assertEquals("shared-cn-us-2026",t.calendarSource)
        assertEquals(LocalDate.parse("2026-09-29"),r.confirmNav(t.id).confirmedNavDate);noPost()
    }
    @Test fun outsideHistoryWindowCannotUseEarliestReturnedNav()=runTest {
        history("2026-09-30" to "4.65","2026-09-29" to "4.61")
        val r=repo();val t=r.create(input());val pending=r.confirmNav(t.id)
        assertEquals(BuyStatus.WAITING_NAV,pending.status);assertEquals(BuyProblem.NAV_OUTSIDE_WINDOW,pending.problem);assertNull(pending.confirmedNav);noPost()
    }
    @Test fun malformedDuplicateOrZeroHistoricalRowsFailClosed()=runTest {
        val t=repo().create(input())
        for(value in listOf("""[{"day":"2026-09-28","unit_net":0}]""","""[{"day":"2026-09-28","unit_net":1},{"day":"2026-09-28","unit_net":1}]""","""[{"day":"bad","unit_net":1}]""")) {
            history="{\"list\":$value}";val pending=repo().confirmNav(t.id)
            assertEquals(BuyStatus.WAITING_NAV,pending.status);assertEquals(BuyProblem.NAV_PROTOCOL,pending.problem)
        };noPost()
    }
    @Test fun futureReportedNavCannotConfirmTransaction()=runTest {
        history("2026-10-12" to "4.99")
        val r=repo();val t=r.create(input("2026-10-09",CutoffType.AFTER_1500))
        val pending=r.confirmNav(t.id);assertEquals(BuyStatus.WAITING_NAV,pending.status);assertEquals(BuyProblem.NAV_PROTOCOL,pending.problem);noPost()
    }
    @Test fun readyRestoresAndConfirmationCannotChangeAfterNavFinalization()=runTest {
        history("2026-09-28" to "4.57")
        val r=repo();val created=r.create(input());val ready=r.confirmNav(created.id)
        assertEquals(ready,repo().restore().single())
        history("2026-09-29" to "99");assertEquals(ready,repo().confirmNav(ready.id));noPost()
    }
    @Test fun repositoryRejectsLaterDateSubstitutionFromAnAdapter()=runTest {
        val bad=object:BuyGateway by gateway {
            override suspend fun resolveOfficialNav(fund:BuyFund,startDate:LocalDate,knownClosed:(LocalDate)->Boolean)=
                OfficialBuyNav(startDate.plusDays(1),BigDecimal("99"))
        }
        val r=SingleBuyRepository(store,bad,{code->BundledBuyCalendar.forCode(code)?:BuyCalendar(code,LocalDate.parse("2026-01-01"),LocalDate.parse("2026-12-31"),(0..364).map {LocalDate.parse("2026-01-01").plusDays(it.toLong())}.filter {!BuyNavDates.weekend(it)}.toSet(),"TEST_ONLY_WEEKDAYS")},{time});val t=r.create(input())
        val pending=r.confirmNav(t.id);assertEquals(BuyStatus.WAITING_NAV,pending.status)
        assertNull(pending.problem);assertNull(pending.confirmedNav);noPost()
    }
}

