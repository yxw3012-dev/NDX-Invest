package com.ndxinvest.yjb.statistics

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.*

class PersonalXirrTest {
    private val start=LocalDate.parse("2025-01-01")
    private fun flow(days:Long,amount:String)=PersonalCashFlow(start.plusDays(days),BigDecimal(amount))
    @Test fun ninetyDaysIsWholePeriodGateNotPerContributionFilter() {
        assertEquals("UNDER_90_DAYS",PersonalXirr.calculate(listOf(flow(0,"-1000"),flow(89,"1100"))).reason)
        assertNotNull(PersonalXirr.calculate(listOf(flow(0,"-1000"),flow(89,"-100"),flow(90,"1200"))).rate)
    }
    @Test fun exactAnnualExamplesAndNegativeReturn() {
        assertTrue(PersonalXirr.calculate(listOf(flow(0,"-1000"),flow(365,"1100"))).rate!!.subtract(BigDecimal("0.1")).abs()<BigDecimal("0.000000001"))
        assertTrue(PersonalXirr.calculate(listOf(flow(0,"-1000"),flow(365,"800"))).rate!!.subtract(BigDecimal("-0.2")).abs()<BigDecimal("0.000000001"))
    }
    @Test fun zeroTerminalValueDoesNotEraseTheEvaluationDate() {
        assertNotNull(PersonalXirr.calculate(listOf(flow(0,"-1000"),flow(89,"1100"),flow(90,"0"))).rate)
        assertNotNull(PersonalXirr.calculate(listOf(flow(0,"-1000"),flow(89,"1100"),flow(90,"-200"),flow(90,"200"))).rate)
    }
    @Test fun multipleRootsAreNotArbitrarilyChosen() {
        assertEquals("MULTIPLE_ROOTS",PersonalXirr.calculate(listOf(flow(0,"-100"),flow(365,"230"),flow(730,"-132"))).reason)
    }
    @Test fun mixedCashFlowsWithOneSupportedRootCanStillCalculate() {
        val result=PersonalXirr.calculate(listOf(flow(0,"-1000"),flow(150,"200"),flow(200,"-300"),flow(365,"1210")))
        assertNotNull(result.rate);assertNull(result.reason)
    }
    @Test fun tangentRootIsFoundByDerivativeIsolation() {
        val result=PersonalXirr.calculate(listOf(flow(0,"-100"),flow(365,"220"),flow(730,"-121")))
        assertNotNull(result.rate);assertTrue(result.rate!!.subtract(BigDecimal("0.1")).abs()<BigDecimal("0.00001"))
    }
    @Test fun sameDayNettingAndUnknownRoot() {
        assertNotNull(PersonalXirr.calculate(listOf(flow(0,"-400"),flow(0,"-600"),flow(365,"1100"))).rate)
        assertEquals("NO_RELIABLE_ROOT",PersonalXirr.calculate(listOf(flow(0,"-1000"),flow(365,"-1"))).reason)
    }
    @Test fun thousandsOfUnevenContributionsRemainBoundedAndAllParticipate() {
        val flows=(0L..9999L).map {flow(it,"-0.01")}+flow(10000,"120")
        assertNotNull(PersonalXirr.calculate(flows).rate)
    }
    @Test fun hugeAmountsAreNormalizedOnlyForRateSolving() {
        val amount=BigDecimal("100000000000000000000000000000000.12345678")
        val result=PersonalXirr.calculate(listOf(PersonalCashFlow(start,-amount),PersonalCashFlow(start.plusDays(365),amount.multiply(BigDecimal("1.1")))))
        assertTrue(result.rate!!.subtract(BigDecimal("0.1")).abs()<BigDecimal("0.000000001"));assertEquals("100000000000000000000000000000000.12345678",amount.toPlainString())
    }
    @Test fun tooManyMixedTermsGiveExplicitComplexityReason() {
        assertEquals("COMPLEX_CASH_FLOWS",PersonalXirr.calculate((0L..100L).map {flow(it*10,if(it%2==0L)"-10" else "20")}).reason)
    }
}
