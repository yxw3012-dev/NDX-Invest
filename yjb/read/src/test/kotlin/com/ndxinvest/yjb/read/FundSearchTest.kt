package com.ndxinvest.yjb.read

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class FundSearchTest {
    @Test fun searchUsesOnlyReadEndpointAndProjectsPublicFields()=runTest {
        val calls=mutableListOf<ReadRequest>()
        val api=YjbReadApi(YjbWire {request->calls+=request;ReadResponse(200,"""{"code":200,"data":{"funds":[{"fund_id":10869,"code":"000834","name":"大成纳斯达克100ETF联接(QDII)A","privateField":"excluded"}]}}""")},{"test-key".toCharArray()})
        val rows=api.searchFunds(" 大成 ","test-session".toCharArray())
        assertEquals(1,rows.size)
        assertEquals(setOf("fundId","code","name"),rows[0].jsonObject.keys)
        assertEquals("000834",rows[0].jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("/content/v1/search/fund",calls.single().path)
        assertEquals("POST",calls.single().method)
        assertEquals("大成",Json.parseToJsonElement(calls.single().body!!).jsonObject["keyword"]!!.jsonPrimitive.content)
        assertFalse(calls.single().toString().contains("test-session"))
    }
    @Test fun invalidInputNeverRequestsAndEmptyResultsAreSupported()=runTest {
        var requests=0
        val api=YjbReadApi(YjbWire {requests++;ReadResponse(200,"""{"code":200,"data":{"funds":[]}}""")},{"test-key".toCharArray()})
        for(query in listOf(" ","a".repeat(65),"a\nb"))assertFailsWith<YjbFailure>{api.searchFunds(query,charArrayOf('x'))}
        assertEquals(0,requests)
        assertTrue(api.searchFunds("大成",charArrayOf('x')).isEmpty())
    }
    @Test fun authFailureAndMalformedResultsNeverBecomeFunds()=runTest {
        for(body in listOf("""{"code":1000,"message":"private-server-error"}""","""{"code":200,"data":{"funds":[{"fund_id":"bad","code":"000834","name":"大成"}]}}""")) {
            val api=YjbReadApi(YjbWire {ReadResponse(200,body)},{"test-key".toCharArray()})
            val failure=assertFailsWith<YjbFailure>{api.searchFunds("大成",charArrayOf('x'))}
            assertFalse(failure.toString().contains("private-server-error"))
        }
        assertFalse(allowedReadPath("POST","/fund_hold"))
        assertFalse(allowedReadPath("GET","/content/v1/search/fund"))
    }
}
