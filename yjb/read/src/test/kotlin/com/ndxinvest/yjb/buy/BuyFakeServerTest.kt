package com.ndxinvest.yjb.buy

import com.ndxinvest.yjb.read.*
import com.ndxinvest.yjb.dca.*
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

/** End-to-end HTTP loopback with synthetic holdings/secrets. No real YJB POST. */
class BuyFakeServerTest {
    private suspend fun flow(ambiguousPost:Boolean,fundCode:String="270042",manualDca:Boolean=false,autoDca:Boolean=false) {
        var holding=BuyBaseline(BigDecimal("100"),BigDecimal("1.2"));var published=false;var posts=0
        val calls=java.util.Collections.synchronizedList(mutableListOf<String>())
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/"){exchange->
            val path=exchange.requestURI.toString();calls+=exchange.requestMethod+" "+path
            val data=when {
                path=="/users/v1/user-account"->"""{"list":[{"id":1}]}"""
                path.contains("fund-accounts/1/funds")->"""{"list":[{"fund_id":17,"hold_share":"${holding.shares.toPlainString()}","hold_cost":"${holding.unitCost.toPlainString()}"}]}"""
                path.startsWith("/market/v1/fund-nav/")->if(published)"""{"list":[{"day":"2026-09-28","unit_net":"2.123456789"}]}"""else """{"list":[]}"""
                path=="/fund_hold"->{
                    posts++;val body=Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject;val item=body["items"]!!.jsonArray.single().jsonObject
                    holding=BuyBaseline(item["hold_share"]!!.jsonPrimitive.content.toBigDecimal(),item["hold_cost"]!!.jsonPrimitive.content.toBigDecimal());"{}"
                }
                else->error("Unexpected test route")
            }
            val failure=ambiguousPost&&path=="/fund_hold"
            val response=if(failure)"{\"code\":500}"else "{\"code\":200,\"data\":$data}"
            val bytes=response.toByteArray();exchange.sendResponseHeaders(if(failure)500 else 200,bytes.size.toLong());exchange.responseBody.use {it.write(bytes)};exchange.close()
        }
        server.start()
        val client=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).build()
        val wire=YjbWire {r->withContext(Dispatchers.IO){
            val b=Request.Builder().url("http://127.0.0.1:${server.address.port}"+r.path);r.headers.forEach {(k,v)->b.header(k,v)}
            if(r.method=="POST")b.post(r.body!!.toRequestBody("application/json".toMediaType()))
            client.newCall(b.build()).execute().use {ReadResponse(it.code,it.body.string())}
        }}
        var time=Instant.parse(if(manualDca||autoDca)"2026-09-27T01:00:00Z"else "2026-10-03T00:00:00Z");val api=YjbReadApi(wire,{"synthetic-signing-only".toCharArray()},{time})
        val writer=YjbFinalPositionWriter(VerifiedWritePrecision("SYNTHETIC SERVER ONLY",8,10),{"synthetic-session".toCharArray()},{"synthetic-signing-only".toCharArray()},wire,{time})
        val gateway=YjbBuyGateway(api,{"synthetic-session".toCharArray()},writer)
        val storage=object:BuyStorage {var rows=emptyList<BuyTransaction>();override suspend fun read()=rows;override suspend fun write(rows:List<BuyTransaction>){this.rows=BuyCodec.decode(BuyCodec.encode(rows))}}
        val states=mutableListOf<BuyStatus>();val repo=SingleBuyRepository(storage,gateway,{code->BundledBuyCalendar.forCode(code)?:BuyCalendar(code,LocalDate.parse("2026-01-01"),LocalDate.parse("2026-12-31"),(0..364).map {LocalDate.parse("2026-01-01").plusDays(it.toLong())}.filter {!BuyNavDates.weekend(it)}.toSet(),"TEST_ONLY_WEEKDAYS")},{time},emit={states+=it.single().status})
        try {
            val input=BuyInput(BuyFund("1","17",fundCode,"synthetic"),BigDecimal("1000"),BigDecimal("0.0015"),LocalDate.parse("2026-09-28"),CutoffType.BEFORE_1500)
            if(autoDca){
                val planStore=object:DcaStorage {var state=DcaState();override suspend fun read()=state;override suspend fun write(state:DcaState){this.state=DcaCodec.decode(DcaCodec.encode(state))}}
                val plans=DcaRepository(planStore,storage,repo,now={time})
                plans.create(input.fund,DcaConfig(input.amount,input.feeRate,DcaFrequency.DAILY,1,1),true,"885e825b-6aa0-4a30-9786-c65a2c41141e")
                val engine=DcaNavEngine(planStore,storage,plans,repo,gateway,now={time})
                time=Instant.parse("2026-09-29T01:00:00Z")
                assertEquals(0,engine.run(setOf("1")).synced);assertTrue(storage.rows.isEmpty());assertEquals(0,posts)
                published=true;assertEquals(1,engine.run(setOf("1")).synced)
                val result=storage.read().single();assertEquals(BuyStatus.SYNCED,result.status)
                assertEquals(LocalDate.parse("2026-09-28"),result.confirmedNavDate);assertEquals(time,result.createdAt)
                assertEquals(listOf(BuyStatus.WAITING_NAV,BuyStatus.READY_TO_SYNC,BuyStatus.SYNCING,BuyStatus.SYNCING,BuyStatus.SYNCED),states)
                val post=calls.indexOf("POST /fund_hold");assertEquals("GET /position/v1/static/fund-accounts/1/funds",calls[post-1]);assertEquals("GET /position/v1/static/fund-accounts/1/funds",calls[post+2])
                assertEquals(0,engine.run(setOf("1")).synced);assertEquals(1,posts);return
            }
            val t=if(manualDca){
                val planStore=object:DcaStorage {var state=DcaState();override suspend fun read()=state;override suspend fun write(state:DcaState){this.state=DcaCodec.decode(DcaCodec.encode(state))}}
                val plans=DcaRepository(planStore,storage,repo,now={time})
                val plan=plans.create(input.fund,DcaConfig(input.amount,input.feeRate,DcaFrequency.DAILY,1,1),true,"885e825b-6aa0-4a30-9786-c65a2c41141e")
                plans.restore();assertTrue(calls.isEmpty(),"plan create/restore must not contact YJB")
                time=Instant.parse("2026-09-28T01:00:00Z")
                val created=plans.execute(plan.id,plan.nextDate,plan.revision)
                assertEquals(0,posts);assertEquals(created,plans.execute(plan.id,plan.nextDate,plan.revision));assertEquals(1,storage.rows.size)
                created
            }else repo.create(input)
            assertEquals(BuyStatus.WAITING_NAV,repo.confirmNav(t.id).status);published=true;assertEquals(BuyStatus.READY_TO_SYNC,repo.confirmNav(t.id).status)
            val result=repo.sync(t.id);assertEquals(BuyStatus.SYNCED,result.status);assertEquals(1,posts);assertEquals(listOf(BuyStatus.WAITING_NAV,BuyStatus.WAITING_NAV,BuyStatus.READY_TO_SYNC,BuyStatus.SYNCING,BuyStatus.SYNCING,BuyStatus.SYNCED),states)
            val post=calls.indexOf("POST /fund_hold");assertEquals("GET /position/v1/static/fund-accounts/1/funds",calls[post-1]);assertEquals("GET /users/v1/user-account",calls[post+1]);assertEquals("GET /position/v1/static/fund-accounts/1/funds",calls[post+2])
            assertTrue(writer.matches(holding,result.submittedTarget!!));assertNotEquals(result.target,result.submittedTarget,"Original full-precision target is retained alongside wire target")
            repo.sync(t.id);assertEquals(1,posts)
        }finally{server.stop(0);client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
    }
    @Test fun completeCreateNavPostVerifyFlowOverFakeHttp()=runTest {flow(false)}
    @Test fun ambiguousAcceptedPostErrorIsReconciledByGetOverFakeHttp()=runTest {flow(true)}
    @Test fun syntheticCalendarEnablesFakeBuyFlowForUnlistedFund()=runTest {flow(false,"000017")}
    @Test fun manualDcaCreatesOneOrdinaryBuyThenExplicitSyncUsesExistingFakeHttpFlow()=runTest {flow(false,"000017",true)}
    @Test fun delayedFormalNavAutomaticallyCreatesAndSyncsOneBuyOverFakeHttp()=runTest {flow(false,"040046",autoDca=true)}
    @Test fun automaticDcaPostErrorActuallyDeliveredReconcilesOverFakeHttp()=runTest {flow(true,"040046",autoDca=true)}
}
