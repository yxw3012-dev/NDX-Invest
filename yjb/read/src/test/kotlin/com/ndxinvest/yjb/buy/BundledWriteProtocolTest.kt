package com.ndxinvest.yjb.buy

import com.ndxinvest.yjb.read.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Synthetic transport verifies release wiring; never evidence about additional real accounts. */
class BundledWriteProtocolTest {
    @Test fun releaseUsesDocumentedObservedScalesWithoutAccountOrCredentials() {
        val p=BundledYjbWriteProtocol.precision
        assertEquals(2,p.shareScale);assertEquals(8,p.unitCostScale)
        assertTrue(p.evidence.contains(BundledYjbWriteProtocol.SOURCE))
        assertFalse(p.evidence.contains("accountId"));assertFalse(p.evidence.contains("Authorization"))
        val writer=YjbFinalPositionWriter(p,{charArrayOf('s')},{charArrayOf('k')})
        val input=BuyBaseline("101.12345678".toBigDecimal(),"2.123456789012".toBigDecimal())
        val wire=writer.normalize(input)
        assertEquals("101.12",wire.shares.toPlainString());assertEquals("2.12345679",wire.unitCost.toPlainString())
        assertEquals("101.12345678",input.shares.toPlainString())
    }
    @Test fun newOwnedAccountUsesSharedProfileButStillNeedsBaselinePostAndGetVerification()=runTest {
        val fund=BuyFund("99","8","040046","synthetic")
        val baseline=BuyBaseline("10.00".toBigDecimal(),"3.00000000".toBigDecimal())
        var actual=baseline;var posts=0;var reads=0;var marked=false
        val wire=YjbWire {request->
            val data=when(request.path){
                "/users/v1/user-account"->"""{"list":[{"id":99}]}"""
                "/position/v1/static/fund-accounts/99/funds"->{reads++;"""{"list":[{"fund_id":8,"hold_share":"${actual.shares}","hold_cost":"${actual.unitCost}"}]}"""}
                "/fund_hold"->{
                    assertTrue(marked);posts++
                    val body=Json.parseToJsonElement(request.body!!).jsonObject
                    assertEquals("99",body["account_id"]!!.jsonPrimitive.content)
                    val item=body["items"]!!.jsonArray.single().jsonObject
                    actual=BuyBaseline(item["hold_share"]!!.jsonPrimitive.content.toBigDecimal(),item["hold_cost"]!!.jsonPrimitive.content.toBigDecimal())
                    "{}"
                }
                else->error("Unexpected fake route")
            }
            ReadResponse(200,"""{"code":200,"data":$data}""")
        }
        val api=YjbReadApi(wire,{charArrayOf('k')})
        val writer=YjbFinalPositionWriter(BundledYjbWriteProtocol.precision,{charArrayOf('s')},{charArrayOf('k')},wire)
        val gateway=YjbBuyGateway(api,{charArrayOf('s')},writer)
        val target=BuyBaseline("11.12345678".toBigDecimal(),"3.123456789".toBigDecimal())
        val result=FinalPositionSync.run(fund,baseline,target,null,false,gateway,{true},{marked=true})
        assertEquals(BuyStatus.SYNCED,result.status);assertEquals(1,posts);assertEquals(2,reads)
        assertEquals("11.12",actual.shares.toPlainString());assertEquals("3.12345679",actual.unitCost.toPlainString())
        assertEquals(BuyProblem.ACCOUNT_MISMATCH,assertFailsWith<BuyFailure>{
            gateway.current(fund.copy(accountId="1"))
        }.problem)
        assertEquals(1,posts)
        val reconciled=FinalPositionSync.run(fund,baseline,target,writer.normalize(target),true,gateway,{true},{error("must not resend")})
        assertEquals(BuyStatus.SYNCED,reconciled.status);assertEquals(1,posts)
    }
}
