package com.ndxinvest.yjb.buy

import com.ndxinvest.yjb.read.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
import java.math.BigDecimal
import java.time.*

/** Documented request protocol + synthetic server results; never a live write validation. */
class BuyProtocolTest {
    private val fund=BuyFund("1","17","270042","测试基金")
    private val now=Instant.parse("2026-10-03T00:00:00Z")
    private fun ok(s:String)=ReadResponse(200,"{\"code\":200,\"data\":$s}")
    @Test fun exactTargetDateOnlyNeverLatestOrOutsideReturnedWindow()=runTest {
        val rows="""{"list":[{"day":"2026-09-30","unit_net":2.009619},{"day":"2026-09-28","unit_net":"2.1234567890123456789"}],"total":100}"""
        val requests=mutableListOf<ReadRequest>();val sessions=mutableListOf<CharArray>()
        val api=YjbReadApi(YjbWire{requests+=it;ok(rows)},{"synthetic-key".toCharArray()},{now})
        val g=YjbBuyGateway(api,{"synthetic-session".toCharArray().also {sessions+=it}})
        assertEquals(BigDecimal("2.1234567890123456789"),g.officialNav(fund,LocalDate.parse("2026-09-28")))
        assertNull(g.officialNav(fund,LocalDate.parse("2026-09-29")))
        assertNull(g.officialNav(fund,LocalDate.parse("2026-10-01")))
        assertEquals(BuyProblem.NAV_OUTSIDE_WINDOW,assertFailsWith<BuyFailure>{g.officialNav(fund,LocalDate.parse("2026-09-01"))}.problem)
        assertTrue(requests.all {it.method=="GET"&&it.path.endsWith("?fund_id=17")});assertTrue(sessions.all {it.all {c->c=='\u0000'}})
    }
    @Test fun duplicateMalformedZeroNavRejected()=runTest {
        for(rows in listOf("""[{"day":"2026-09-28","unit_net":0}]""","""[{"day":"2026-09-28","unit_net":"NaN"}]""","""[{"day":"2026-09-28","unit_net":1},{"day":"2026-09-28","unit_net":2}]""","""[{"day":"28/09/2026","unit_net":2}]""")){
            val g=YjbBuyGateway(YjbReadApi(YjbWire{ok("{\"list\":$rows}")},{charArrayOf('k')},{now}),{charArrayOf('s')})
            assertEquals(BuyProblem.NAV_PROTOCOL,assertFailsWith<BuyFailure>{g.officialNav(fund,LocalDate.parse("2026-09-28"))}.problem)
        }
    }
    @Test fun freshOwnedAccountBaselineUsesRawDecimalsAndRejectsDifferentAccount()=runTest {
        val calls=mutableListOf<String>();val g=YjbBuyGateway(YjbReadApi(YjbWire{r->calls+=r.path;ok(if(r.path=="/users/v1/user-account")"""{"list":[{"id":1}]}""" else """{"list":[{"fund_id":17,"hold_share":"999.123456789","hold_cost":"1.123456789"}]}""")},{charArrayOf('k')},{now}),{charArrayOf('s')})
        val b=g.current(fund);assertEquals(BigDecimal("999.123456789"),b.shares);assertEquals(BigDecimal("1.123456789"),b.unitCost)
        assertEquals(listOf("/users/v1/user-account","/position/v1/static/fund-accounts/1/funds"),calls)
        assertEquals(BuyProblem.ACCOUNT_MISMATCH,assertFailsWith<BuyFailure>{g.current(fund.copy(accountId="2"))}.problem)
        assertFalse(g.writeAvailable());assertFailsWith<BuyFailure>{g.postFinal(fund,b)}
    }
    @Test fun candidateWriterExactNumericPayloadAndSecretBuffersCleared()=runTest {
        var captured:ReadRequest?=null;val session="synthetic-session".toCharArray();val key="synthetic-key".toCharArray()
        // TEST ONLY: these scales are synthetic, not evidence about YJB's stored precision.
        val w=YjbFinalPositionWriter(VerifiedWritePrecision("TEST ONLY overwrite fake",8,10),{session},{key},YjbWire {captured=it;ok("{}")} ,{now})
        val target=BuyBaseline(BigDecimal("999999999999999999.123456789"),BigDecimal("2.123456789012"));w.post(fund,target)
        val request=captured!!;assertEquals("POST",request.method);assertEquals("/fund_hold",request.path)
        val body=Json.parseToJsonElement(request.body!!).jsonObject;assertEquals("1",body["account_id"]!!.jsonPrimitive.content)
        val item=body["items"]!!.jsonArray.single().jsonObject
        assertEquals("999999999999999999.12345679",item["hold_share"]!!.jsonPrimitive.content);assertFalse(item["hold_share"]!!.jsonPrimitive.isString)
        assertEquals("2.1234567890",item["hold_cost"]!!.jsonPrimitive.content)
        assertTrue(session.all {it=='\u0000'});assertTrue(key.all {it=='\u0000'});assertFalse(request.toString().contains("synthetic"));assertFalse(request.toString().contains("999999"))
        assertTrue(w.matches(w.normalize(target),target));assertFalse(w.matches(target.copy(shares=target.shares+BigDecimal.ONE),target))
    }
    @Test fun candidateWriterAuthAndErrorsAreSanitizedIncludingKeyFailure()=runTest {
        for(response in listOf(ReadResponse(401,"private-body"),ReadResponse(200,"{\"code\":1000,\"message\":\"private-body\"}"),ReadResponse(500,"private-body"))){
            val session="synthetic-session".toCharArray();val w=YjbFinalPositionWriter(VerifiedWritePrecision("TEST ONLY",2,4),{session},{charArrayOf('k')},YjbWire{response},{now})
            val e=assertFailsWith<BuyFailure>{w.post(fund,BuyBaseline(BigDecimal.ONE,BigDecimal.ONE))};assertFalse(e.toString().contains("private"));assertTrue(session.all {it=='\u0000'})
        }
        val session="synthetic-session".toCharArray();val w=YjbFinalPositionWriter(VerifiedWritePrecision("TEST ONLY",2,4),{session},{throw IllegalStateException("private-key")},YjbWire{error("must not POST")},{now})
        assertFailsWith<BuyFailure>{w.post(fund,BuyBaseline(BigDecimal.ONE,BigDecimal.ONE))};assertTrue(session.all {it=='\u0000'})
    }
}
