package com.ndxinvest.yjb.buy

import com.ndxinvest.yjb.read.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.test.*

/** Loopback fake protocol only. Neither these tests nor the writer fixture prove a real SELL. */
class SellFakeServerTest {
    private suspend fun flow(full:Boolean,uncertain:Boolean) {
        var holding=BuyBaseline(BigDecimal("100"),BigDecimal("1.2"));var published=false;var posts=0
        val calls=java.util.Collections.synchronizedList(mutableListOf<String>())
        val states=mutableListOf<SellStatus>()
        val storage=object:SellStorage {var rows=emptyList<SellTransaction>();override suspend fun read()=rows;override suspend fun write(rows:List<SellTransaction>){this.rows=SellCodec.decode(SellCodec.encode(rows))}}
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/"){exchange->
            val path=exchange.requestURI.toString();calls+=exchange.requestMethod+" "+path
            val data=when {
                path=="/users/v1/user-account"->"""{"list":[{"id":1}]}"""
                path=="/position/v1/static/fund-accounts/1/funds"->"""{"list":[{"fund_id":17,"hold_share":"${holding.shares.toPlainString()}","hold_cost":"${holding.unitCost.toPlainString()}"}]}"""
                path.startsWith("/market/v1/fund-nav/")->if(published)"""{"list":[{"day":"2026-09-28","unit_net":"2.123456789"}]}"""else """{"list":[]}"""
                path=="/fund_hold"->{
                    assertEquals("POST",exchange.requestMethod);assertEquals(SellStatus.SYNCING,storage.rows.single().status);assertTrue(storage.rows.single().postMayHaveBeenSent)
                    posts++;val body=Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                    assertEquals(1,body["items"]!!.jsonArray.size);val item=body["items"]!!.jsonArray.single().jsonObject
                    assertFalse(item["hold_share"]!!.jsonPrimitive.isString)
                    holding=BuyBaseline(item["hold_share"]!!.jsonPrimitive.content.toBigDecimal(),item["hold_cost"]!!.jsonPrimitive.content.toBigDecimal());"{}"
                }
                else->error("Unexpected route")
            }
            val failed=uncertain&&path=="/fund_hold"
            val response=if(failed)"{\"code\":500}"else "{\"code\":200,\"data\":$data}"
            val bytes=response.toByteArray();exchange.sendResponseHeaders(if(failed)500 else 200,bytes.size.toLong());exchange.responseBody.use {it.write(bytes)};exchange.close()
        };server.start()
        val client=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).build()
        val wire=YjbWire {r->withContext(Dispatchers.IO){
            val b=Request.Builder().url("http://127.0.0.1:${server.address.port}"+r.path);r.headers.forEach {(k,v)->b.header(k,v)}
            if(r.method=="POST")b.post(r.body!!.toRequestBody("application/json".toMediaType()))
            client.newCall(b.build()).execute().use {ReadResponse(it.code,it.body.string())}
        }}
        val time=Instant.parse("2026-10-03T00:00:00Z")
        val writer=YjbFinalPositionWriter(VerifiedWritePrecision("FAKE SERVER ONLY",2,8),{charArrayOf('s')},{charArrayOf('k')},wire,{time})
        val gateway=YjbBuyGateway(YjbReadApi(wire,{charArrayOf('k')},{time}),{charArrayOf('s')},writer)
        val r=SingleSellRepository(storage,gateway,calendar={code->BundledBuyCalendar.forCode(code)?:BuyCalendar(code,LocalDate.parse("2026-01-01"),LocalDate.parse("2026-12-31"),(0..364).map {LocalDate.parse("2026-01-01").plusDays(it.toLong())}.filter {!BuyNavDates.weekend(it)}.toSet(),"TEST_ONLY_WEEKDAYS")},now={time},emit={states+=it.single().status})
        try {
            val t=r.create(SellInput(BuyFund("1","17","000017","synthetic"),BigDecimal(if(full)"100"else "25"),BigDecimal("0.015"),LocalDate.parse("2026-09-28"),CutoffType.BEFORE_1500))
            assertEquals(SellStatus.WAITING_NAV,r.confirmNav(t.id).status);published=true
            val ready=r.confirmNav(t.id);assertEquals(SellStatus.READY_TO_SYNC,ready.status)
            assertEquals(SellStatus.SYNCED,r.sync(t.id).status);assertEquals(1,posts)
            assertEquals(0,holding.unitCost.compareTo(BigDecimal("1.2")))
            assertEquals(0,holding.shares.compareTo(BigDecimal(if(full)"0"else "75")))
            val post=calls.indexOf("POST /fund_hold")
            assertEquals("GET /position/v1/static/fund-accounts/1/funds",calls[post-1])
            assertEquals("GET /users/v1/user-account",calls[post+1]);assertEquals("GET /position/v1/static/fund-accounts/1/funds",calls[post+2])
            r.sync(t.id);assertEquals(1,posts)
            assertTrue(states.containsAll(listOf(SellStatus.WAITING_NAV,SellStatus.READY_TO_SYNC,SellStatus.SYNCING,SellStatus.SYNCED)))
            assertEquals(1,storage.rows.size);assertEquals(ready.netAmount,storage.rows.single().netAmount)
        }finally {server.stop(0);client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
    }
    @Test fun partialSellPostAndGetVerifyCompleteOverFakeHttp()=runTest {flow(false,false)}
    @Test fun fullClearRetainsFundAndCostInFakeReadback()=runTest {flow(true,false)}
    @Test fun deliveredButFailedPostReconcilesWithoutRepeat()=runTest {flow(false,true)}
}
