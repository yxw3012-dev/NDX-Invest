package com.ndxinvest.yjb.dca

import com.ndxinvest.yjb.buy.*
import java.math.BigDecimal
import java.time.*
import kotlin.test.*

class DcaDateDisplayTest {
    private val time=Instant.parse("2026-10-03T01:00:00Z")
    private fun plan(code:String="040046"):DcaPlan {
        val first=LocalDate.parse("2026-10-04");val config=DcaConfig(BigDecimal("5"),BigDecimal.ZERO,DcaFrequency.DAILY,1,1)
        return DcaPlan("plan",BuyFund("1","17",code,"test"),config,first,first,true,createdAt=time,updatedAt=time,history=listOf(DcaVersion(time,config,true,first,1)))
    }
    @Test fun holidayCandidateProjectsNextEligibleDayWithoutMutatingStoredCursor() {
        val p=plan();val s=DcaState(listOf(p))
        assertEquals("2026-10-08" to "待执行",DcaDateDisplay.next(p,s,LocalDate.parse("2026-10-03")))
        assertEquals(LocalDate.parse("2026-10-04"),p.nextDate);assertTrue(s.occurrences.isEmpty())
        assertEquals("2026-10-08" to "待执行",DcaDateDisplay.next(p,s,LocalDate.parse("2026-10-09")))
        assertEquals("2026-10-08" to "待处理",DcaDateDisplay.next(p.copy(automationProblem=BuyProblem.NETWORK),s,LocalDate.parse("2026-10-09")))
    }
    @Test fun newFundUsesSharedCalendarWhileUncoveredYearRemainsUnknown() {
        val p=plan("000001");assertEquals("2026-10-08" to "待执行",DcaDateDisplay.next(p,DcaState(listOf(p)),LocalDate.parse("2026-10-03")))
        val known=plan();assertEquals("--" to "已暂停",DcaDateDisplay.next(known.copy(enabled=false),DcaState(),LocalDate.parse("2026-10-03")))
        val next=known.copy(firstDate=LocalDate.parse("2027-01-01"),nextDate=LocalDate.parse("2027-01-01"),history=known.history.map {it.copy(firstDate=LocalDate.parse("2027-01-01"))})
        assertEquals("--" to "日期待核验",DcaDateDisplay.next(next,DcaState(listOf(next)),LocalDate.parse("2026-12-31")))
    }
    @Test fun skippedCatchupDatesDoNotReappearAsNextExecution() {
        val p=plan().copy(nextDate=LocalDate.parse("2026-10-12"))
        val slots=listOf("2026-10-08","2026-10-09").map {DcaCatchupSlot(p.id,LocalDate.parse(it),BigDecimal("5"),BigDecimal.ZERO,BigDecimal("2"),1)}
        val review=DcaCatchupReview("review",p.fund,slots,BuyBaseline(BigDecimal("100"),BigDecimal("2")),time,CatchupDecision.SKIPPED,time)
        assertEquals("2026-10-12" to "待执行",DcaDateDisplay.next(p,DcaState(plans=listOf(p),catchupReviews=listOf(review)),LocalDate.parse("2026-10-09")))
    }
}
