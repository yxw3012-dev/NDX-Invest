package com.ndxinvest.yjb.buy

import com.ndxinvest.yjb.read.*
import com.sun.net.httpserver.HttpServer
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.test.*

/** Real loopback HTTP only. These synthetic profiles are not YJB protocol evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
class YjbRateLimitFakeServerTest {
    private val start=Instant.parse("2026-10-05T00:00:00Z")
    private suspend fun TestScope.flow(acceptPost:Boolean,postWait:String="2",navLimited:Boolean=false) {
        var position=BuyBaseline(BigDecimal("100"),BigDecimal("4"));var posts=0;var navReads=0;var holdingReads=0
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/"){e->
            val path=e.requestURI.path
            var status=200
            val data=when(path){
                "/users/v1/user-account"->"""{"list":[{"id":1}]}"""
                "/position/v1/static/fund-accounts/1/funds"->{holdingReads++;"""{"list":[{"fund_id":17,"hold_share":"${position.shares}","hold_cost":"${position.unitCost}"}]}"""}
                "/market/v1/fund-nav/fund-history-nav"->{
                    navReads++
                    if(navLimited&&navReads==1){status=429;e.responseHeaders.add("Retry-After","2")}
                    """{"list":[{"day":"2026-09-28","unit_net":"4.5700"}]}"""
                }
                "/fund_hold"->{
                    posts++
                    val item=Json.parseToJsonElement(e.requestBody.bufferedReader().readText()).jsonObject["items"]!!.jsonArray.single().jsonObject
                    if(acceptPost)position=BuyBaseline(item["hold_share"]!!.jsonPrimitive.content.toBigDecimal(),item["hold_cost"]!!.jsonPrimitive.content.toBigDecimal())
                    status=429;e.responseHeaders.add("Retry-After",postWait);"{}"
                }
                else->error("Unexpected loopback route")
            }
            val body=if(status==429)"private error; must not leak" else """{"code":200,"data":$data}"""
            val bytes=body.toByteArray();e.sendResponseHeaders(status,bytes.size.toLong());e.responseBody.use {it.write(bytes)};e.close()
        }
        server.start()
        val http=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).build()
        val wire=YjbWire {r->withContext(Dispatchers.IO){
            val request=Request.Builder().url("http://127.0.0.1:${server.address.port}${r.path}")
            r.headers.forEach {(k,v)->request.header(k,v)}
            if(r.method=="POST")request.post(requireNotNull(r.body).toRequestBody("application/json".toMediaType()))
            http.newCall(request.build()).execute().use {rateLimitedResponse(it)?:ReadResponse(it.code,it.body.string())}
        }}
        val gate=YjbRateLimit({testScheduler.currentTime},{start.plusMillis(testScheduler.currentTime)},{delay(it)},{0})
        val now={start.plusMillis(testScheduler.currentTime)}
        val api=YjbReadApi(wire,{"synthetic-key".toCharArray()},now,gate)
        val writer=YjbFinalPositionWriter(VerifiedWritePrecision("SYNTHETIC LOOPBACK ONLY",2,4),{"synthetic-token".toCharArray()},{"synthetic-key".toCharArray()},wire,now,gate)
        val gateway=YjbBuyGateway(api,{"synthetic-token".toCharArray()},writer)
        val storage=object:BuyStorage {
            var rows=emptyList<BuyTransaction>()
            override suspend fun read()=rows
            override suspend fun write(rows:List<BuyTransaction>){this.rows=BuyCodec.decode(BuyCodec.encode(rows))}
        }
        val repo=SingleBuyRepository(storage,gateway,BundledBuyCalendar::forCode,now)
        try {
            val created=repo.create(BuyInput(BuyFund("1","17","270042","Synthetic"),BigDecimal("100"),BigDecimal.ZERO,LocalDate.parse("2026-09-28"),CutoffType.BEFORE_1500))
            assertEquals(BuyStatus.READY_TO_SYNC,repo.confirmNav(created.id).status)
            assertEquals(if(navLimited)2 else 1,navReads)
            val result=repo.sync(created.id)
            assertEquals(1,posts,"Rate-limit handling must never automatically resend holding POST")
            if(postWait=="60") {
                assertEquals(BuyStatus.SYNCING,result.status);assertEquals(BuyProblem.RATE_LIMITED,result.problem)
                assertEquals(2,holdingReads,"Long Retry-After must prevent an early verification GET")
                val restart=SingleBuyRepository(storage,gateway,BundledBuyCalendar::forCode,now)
                restart.restore();advanceTimeBy(60_000)
                assertEquals(BuyStatus.SYNCED,restart.reconcileOnly(result.id).status);assertEquals(1,posts)
            }else if(acceptPost){
                assertEquals(BuyStatus.SYNCED,result.status);assertTrue(writer.matches(position,result.submittedTarget!!))
                assertEquals(1,posts);assertEquals(3,holdingReads)
            }else {
                assertEquals(BuyStatus.SYNCING,result.status);assertEquals(BuyProblem.WRITE_PENDING,result.problem)
                assertTrue(result.postMayHaveBeenSent);assertEquals(BuyProblem.WRITE_PENDING,BuyCodec.decode(BuyCodec.encode(storage.rows)).single().problem)
                assertTrue(BuyCodec.ui(storage.rows).single().jsonObject["syncError"]!!.jsonPrimitive.content.contains("不会重复"))
                repo.reconcileOnly(result.id);assertEquals(1,posts);assertTrue(position.equivalent(created.baseline))
            }
        }finally{server.stop(0);http.dispatcher.executorService.shutdown();http.connectionPool.evictAll()}
    }

    @Test fun navGet429RetriesAndPost429ButActuallyAcceptedUsesGetVerification()=runTest {flow(true,navLimited=true)}
    @Test fun rejectedHoldingPostIsNotRepeatedAndRateLimitIsRetainedInJournal()=runTest {flow(false)}
    @Test fun longWriteCooldownBlocksReadbackUntilLaterGetOnlyReconciliation()=runTest {flow(true,postWait="60")}
    @Test fun historicalNavRateLimitIsNotMisclassifiedAsInvalidNavOrAuthentication()=runTest {
        var calls=0
        val gate=YjbRateLimit({testScheduler.currentTime},{start},{delay(it)},{0})
        val api=YjbReadApi(YjbWire {calls++;ReadResponse(429,"private","60")},{"synthetic-key".toCharArray()},{start},gate)
        val gateway=YjbBuyGateway(api,{"synthetic-token".toCharArray()})
        val failure=assertFailsWith<BuyFailure>{gateway.officialHistory(BuyFund("1","17","270042","Synthetic"))}
        assertEquals(BuyProblem.RATE_LIMITED,failure.problem);assertEquals(1,calls);assertFalse(failure.toString().contains("private"))
    }
}
