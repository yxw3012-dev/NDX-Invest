package com.ndxinvest.yjb.buy

import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PositionReadbackTest {
    private val fund=BuyFund("1","17","270042","synthetic")
    private val target=BuyBaseline(BigDecimal("120"),BigDecimal("2.5"))
    private class Gateway(val reads:MutableList<Any>):BuyGateway {
        var gets=0;var posts=0
        override suspend fun current(fund:BuyFund):BuyBaseline {gets++;val next=reads.removeAt(0);if(next is BuyProblem)throw BuyFailure(next);return next as BuyBaseline}
        override suspend fun officialNav(fund:BuyFund,targetDate:LocalDate):BigDecimal?=null
        override fun writeAvailable()=true
        override fun normalizeTarget(target:BuyBaseline)=target
        override fun matches(actual:BuyBaseline,target:BuyBaseline)=actual.equivalent(target)
        override suspend fun postFinal(fund:BuyFund,target:BuyBaseline){posts++}
    }
    @Test fun delayedReplicaConvergesOnThirdGetWithoutAnotherPost()=runTest {
        val waits=mutableListOf<Long>()
        val g=Gateway(mutableListOf(target.copy(shares=BigDecimal("100")),target.copy(shares=BigDecimal("119")),target))
        val result=PositionReadback {waits+=it}.verify(fund,target,g)
        assertEquals(BuyStatus.SYNCED,result.status);assertEquals(listOf(1000L,3000L),waits);assertEquals(3,g.gets);assertEquals(0,g.posts)
    }
    @Test fun staleThirdValueIsPendingNotAnExternalChange()=runTest {
        val stale=target.copy(shares=BigDecimal("999"));val g=Gateway(MutableList(3){stale})
        val r=PositionReadback {}.verify(fund,target,g)
        assertEquals(BuyStatus.SYNCING,r.status);assertEquals(BuyProblem.WRITE_PENDING,r.problem);assertEquals(stale,r.external);assertEquals(3,g.gets)
    }
    @Test fun authAndRateLimitStopImmediatelyAndNeverPost()=runTest {
        for(problem in listOf(BuyProblem.AUTH_EXPIRED,BuyProblem.RATE_LIMITED,BuyProblem.STORAGE)) {
            val g=Gateway(mutableListOf(problem));val waits=mutableListOf<Long>()
            val r=PositionReadback {waits+=it}.verify(fund,target,g)
            assertEquals(BuyStatus.SYNCING,r.status);assertEquals(problem,r.problem);assertEquals(1,g.gets);assertEquals(0,g.posts);assertTrue(waits.isEmpty())
        }
    }
    @Test fun transientNetworkFailureCanRecoverInSameBoundedReadback()=runTest {
        val g=Gateway(mutableListOf(BuyProblem.NETWORK,target))
        assertEquals(BuyStatus.SYNCED,PositionReadback {}.verify(fund,target,g).status);assertEquals(2,g.gets)
    }
    @Test fun cancellationPreservesCallerControl()=runTest {
        val g=Gateway(mutableListOf(target.copy(shares=BigDecimal.ONE)))
        assertFailsWith<CancellationException>{PositionReadback {throw CancellationException()}.verify(fund,target,g)}
        assertEquals(1,g.gets);assertEquals(0,g.posts)
    }
}
