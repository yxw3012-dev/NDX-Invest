package com.ndxinvest.yjb.simulation

import com.ndxinvest.yjb.buy.*
import com.ndxinvest.yjb.dca.*
import com.ndxinvest.yjb.read.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.math.BigDecimal
import java.nio.file.*
import java.time.*
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.test.*

/** Isolated experiment: production repositories + real loopback HTTP + synthetic file journals.
 * Dates/NAV publication and write precision are fixtures, never live YJB evidence.
 */
class MidAutumnSimulationTest {
    private fun bd(s:String)=BigDecimal(s)
    private class Fixture(val name:String,val code:String="160213",val cached:Boolean=false):AutoCloseable {
        val reports=Paths.get("build/reports/mid-autumn").toAbsolutePath().also {Files.createDirectories(it)}
        val dir=Files.createTempDirectory(reports,"sandbox-$name-")
        val fund=BuyFund("1",code,code,"模拟基金 $code")
        var time=Instant.parse("2026-09-24T04:00:00Z")
        @Volatile var position=BuyBaseline(BigDecimal("100"),BigDecimal("4"))
        @Volatile var navs=JsonArray(emptyList())
        @Volatile var mode="OK"
        @Volatile var readFailure=""
        var failReadyOnce=false
        var posts=0
        val calls=Collections.synchronizedList(mutableListOf<String>())
        val events=mutableListOf<JsonObject>()
        private fun read(file:String)=if(Files.exists(dir.resolve(file)))Json.parseToJsonElement(Files.readString(dir.resolve(file))) else null
        private fun write(file:String,data:JsonElement){val temp=dir.resolve("$file.tmp");Files.writeString(temp,data.toString());Files.move(temp,dir.resolve(file),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)}
        val bs=object:BuyStorage {
            override suspend fun read()=BuyCodec.decode((read("buy.json") as? JsonArray)?:JsonArray(emptyList()))
            override suspend fun write(rows:List<BuyTransaction>){if(failReadyOnce&&rows.any {it.status==BuyStatus.READY_TO_SYNC}){failReadyOnce=false;throw BuyFailure(BuyProblem.STORAGE)};write("buy.json",BuyCodec.encode(rows))}
        }
        val ss=object:SellStorage {
            override suspend fun read()=SellCodec.decode((read("sell.json") as? JsonArray)?:JsonArray(emptyList()))
            override suspend fun write(rows:List<SellTransaction>){write("sell.json",SellCodec.encode(rows))}
        }
        val ds=object:DcaStorage {
            override suspend fun read()=read("plans.json")?.jsonArray?.let(DcaCodec::decode)?:DcaState()
            override suspend fun write(state:DcaState){write("plans.json",DcaCodec.encode(state))}
        }
        val intents=object:CreationRequestStorage {
            override suspend fun read()=(read("intents.json") as? JsonArray)?:JsonArray(emptyList())
            override suspend fun write(rows:JsonArray){write("intents.json",rows)}
        }
        val navStore=object:FormalNavStorage {
            override suspend fun read()=(read("navs.json") as? JsonArray)?:JsonArray(emptyList())
            override suspend fun write(rows:JsonArray){write("navs.json",rows)}
        }
        private val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        private val client=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).build()
        val wire=YjbWire {request->withContext(Dispatchers.IO){
            val b=Request.Builder().url("http://127.0.0.1:${server.address.port}"+request.path)
            request.headers.forEach {(k,v)->b.header(k,v)}
            if(request.method=="POST")b.post(request.body!!.toRequestBody("application/json".toMediaType()))
            client.newCall(b.build()).execute().use {ReadResponse(it.code,it.body.string())}
        }}
        private val writer=YjbFinalPositionWriter(VerifiedWritePrecision("SIMULATION ONLY: not Production evidence",2,4),{charArrayOf('s')},{charArrayOf('k')},wire,{time})
        fun gateway():BuyGateway=YjbBuyGateway(YjbReadApi(wire,{charArrayOf('k')},{time}),{charArrayOf('s')},writer,if(cached)FormalNavCache(navStore,{time})else null)
        fun buys(g:BuyGateway=gateway())=SingleBuyRepository(bs,g,BundledBuyCalendar::forCode,now={time},emit={rows->events+=buildJsonObject {put("at",time.toString());put("buy",BuyCodec.ui(rows));put("position",positionJson())}})
        fun sells()=SingleSellRepository(ss,gateway(),now={time},emit={rows->events+=buildJsonObject {put("at",time.toString());put("sell",SellCodec.ui(rows));put("position",positionJson())}})
        fun plans(b:SingleBuyRepository=buys())=DcaRepository(ds,bs,b,now={time})
        fun engine():DcaNavEngine {val b=buys();return DcaNavEngine(ds,bs,plans(b),b,gateway(),pendingSell={f->ss.read().any {it.input.fund==f&&it.status !in setOf(SellStatus.SYNCED,SellStatus.CANCELLED)}},now={time})}
        fun ordinary()=OrdinaryTradeAutomation(bs,ss,buys(),sells())
        fun at(day:String,hour:Int=12){time=LocalDate.parse(day).atTime(hour,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant()}
        fun publish(vararg days:String){navs=JsonArray(days.map {buildJsonObject {put("day",it);put("unit_net","4.5700")}})}
        fun input(amount:String="100",rate:String="0",cutoff:CutoffType=CutoffType.BEFORE_1500)=BuyInput(fund,BigDecimal(amount),BigDecimal(rate),LocalDate.parse("2026-09-24"),cutoff)
        suspend fun create(auto:Boolean=true)=buys().create(input(),autoSync=auto)
        suspend fun ready():BuyTransaction {val t=create();at("2026-09-28");publish("2026-09-23","2026-09-24");return buys().confirmNav(t.id).also {assertEquals(BuyStatus.READY_TO_SYNC,it.status)}}
        suspend fun plan(amount:String="5",id:String="11111111-1111-4111-8111-111111111111")=plans().create(fund,DcaConfig(BigDecimal(amount),BigDecimal.ZERO,DcaFrequency.DAILY,1,1),true,id)
        private fun positionJson()=buildJsonObject {put("shares",position.shares.toPlainString());put("unitCost",position.unitCost.toPlainString());put("totalCost",position.totalCost.toPlainString())}
        private fun data():JsonObject=YjbMapper.map(buildJsonObject {
            put("displayName","隔离模拟账户");put("maskedId","模拟账户");put("lastSyncAt",time.toString())
            put("groups",buildJsonArray {add(buildJsonObject {put("accountId","1");put("accountTitle","模拟账户");put("funds",buildJsonArray {add(buildJsonObject {
                put("fund_id",fund.fundId);put("code",code);put("short_name",fund.fundName);put("hold_share",position.shares.toPlainString());put("hold_cost",position.unitCost.toPlainString())
                // Fake server market facts, never a Production holding-authority calculation.
                val market=position.shares.multiply(BigDecimal("4.5700"));put("money",market.toPlainString());put("hold_earn",market.subtract(position.totalCost).toPlainString());put("last_net","4.5700");put("net_day","2026-09-24")
            })})})})
        })
        suspend fun checkpoint(label:String){events+=buildJsonObject {put("checkpoint",label);put("at",time.toString());put("position",positionJson());put("payload",buildJsonObject {
            put("data",data());put("transactions",JsonArray(BuyCodec.ui(bs.read())+SellCodec.ui(ss.read())));put("plans",DcaCodec.ui(ds.read(),setOf("1"),time));put("accountStatus","CONNECTED");put("holdingsStatus",if(position.shares.signum()==0)"EMPTY"else "READY")
        })}}
        init {
            server.createContext("/"){e->
                val path=e.requestURI.toString();calls+=e.requestMethod+" "+path
                var status=200
                val result:JsonElement=when {
                    path=="/fund_hold"->{
                        require(e.requestMethod=="POST");posts++
                        val item=Json.parseToJsonElement(e.requestBody.bufferedReader().readText()).jsonObject["items"]!!.jsonArray.single().jsonObject
                        require(item["fund_id"]!!.jsonPrimitive.content==fund.fundId)
                        if(mode!="DROP_UNDELIVERED")position=BuyBaseline(item["hold_share"]!!.jsonPrimitive.content.toBigDecimal(),item["hold_cost"]!!.jsonPrimitive.content.toBigDecimal())
                        when(mode){"WRONG"->position=position.copy(shares=position.shares+BigDecimal("0.10"));"AUTH_AFTER"->readFailure="AUTH";"OFFLINE_AFTER"->readFailure="OFFLINE"}
                        if(mode.startsWith("DROP_")){e.close();return@createContext}
                        JsonObject(emptyMap())
                    }
                    readFailure.isNotEmpty()->{status=if(readFailure=="AUTH")401 else 503;JsonObject(emptyMap())}
                    path=="/users/v1/user-account"->buildJsonObject {put("list",buildJsonArray {add(buildJsonObject {put("id",1)})})}
                    path=="/position/v1/static/fund-accounts/1/funds"->buildJsonObject {put("list",buildJsonArray {add(buildJsonObject {put("fund_id",fund.fundId);put("hold_share",position.shares.toPlainString());put("hold_cost",position.unitCost.toPlainString())})})}
                    path.startsWith("/market/v1/fund-nav/fund-history-nav?")->buildJsonObject {put("list",navs)}
                    else->error("Unexpected loopback route")
                }
                val bytes=buildJsonObject {put("code",status);put("data",result)}.toString().toByteArray()
                e.sendResponseHeaders(status,bytes.size.toLong());e.responseBody.use {it.write(bytes)};e.close()
            };server.start()
        }
        suspend fun finish(pass:Boolean){checkpoint("final");Files.writeString(reports.resolve("$name.json"),buildJsonObject {
            put("name",name);put("passed",pass);put("environment","LOOPBACK_FAKE_ONLY");put("syntheticShareScale",2);put("syntheticUnitCostScale",4);put("postCount",posts)
            put("httpCalls",JsonArray(calls.map(::JsonPrimitive)));put("events",JsonArray(events));put("buyJournal",BuyCodec.encode(bs.read()));put("sellJournal",SellCodec.encode(ss.read()));put("planJournal",DcaCodec.encode(ds.read()))
        }.toString())}
        override fun close(){server.stop(0);client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
    }
    private suspend fun scenario(name:String,code:String="160213",cached:Boolean=false,body:suspend(Fixture)->Unit){Fixture(name,code,cached).use {f->try {body(f);f.finish(true)}catch(e:Throwable){runCatching {f.finish(false)};throw e}}}

    @Test fun buyBeforeHolidayAndSellActualAddedShares()=runTest {scenario("01-buy-sell") {f->
        val b=f.buys();val t=f.create();f.checkpoint("BUY created; no NAV")
        f.at("2026-09-27");assertEquals(BuyStatus.WAITING_NAV,b.confirmNav(t.id).status);assertEquals(0,f.posts)
        f.buys().restore();assertEquals(t.expectedNavDate,f.bs.read().single().expectedNavDate)
        f.at("2026-09-28");f.publish("2026-09-23","2026-09-24");val ready=f.buys().confirmNav(t.id)
        assertEquals(LocalDate.parse("2026-09-24"),ready.confirmedNavDate);assertEquals("2026-09-28",BuyCodec.ui(listOf(ready)).single().jsonObject["confirmationDate"]!!.jsonPrimitive.content)
        assertEquals(0,ready.feeAmount!!.signum());f.checkpoint("BUY NAV confirmed")
        f.buys().restore();val done=f.buys().sync(t.id);assertEquals(BuyStatus.SYNCED,done.status);assertEquals(1,f.posts);f.checkpoint("BUY synced and GET verified")
        val added=f.position.shares.subtract(t.baseline.shares);assertEquals(BigDecimal("21.88"),added);assertEquals(2,done.confirmedShares!!.scale())
        val cost=f.position.unitCost;val s=f.sells().create(SellInput(f.fund,added,BigDecimal.ZERO,LocalDate.parse("2026-09-28"),CutoffType.BEFORE_1500))
        assertEquals(SellStatus.WAITING_NAV,f.sells().confirmNav(s.id).status)
        f.at("2026-09-29");f.publish("2026-09-23","2026-09-24","2026-09-28");f.sells().confirmNav(s.id);assertEquals(SellStatus.SYNCED,f.sells().sync(s.id).status)
        assertEquals(0,f.position.shares.compareTo(BigDecimal("100")));assertEquals(cost,f.position.unitCost);f.checkpoint("SELL synced and GET verified")
        f.buys().sync(t.id);f.sells().sync(s.id);assertEquals(2,f.posts)
    }}
    @Test fun afterCutoffWaitsForFirstOpenDateExactNav()=runTest {scenario("02-after-cutoff") {f->
        f.at("2026-09-24",16);val t=f.buys().create(f.input(cutoff=CutoffType.AFTER_1500));assertEquals(LocalDate.parse("2026-09-28"),t.expectedNavDate)
        f.at("2026-09-28");f.publish("2026-09-23","2026-09-24");assertEquals(BuyStatus.WAITING_NAV,f.buys().confirmNav(t.id).status)
        f.at("2026-09-29");f.publish("2026-09-24","2026-09-28");val r=f.buys().confirmNav(t.id);assertEquals(LocalDate.parse("2026-09-28"),r.confirmedNavDate)
        assertEquals("2026-09-29",BuyCodec.ui(listOf(r)).single().jsonObject["confirmationDate"]!!.jsonPrimitive.content);assertEquals(BuyStatus.SYNCED,f.buys().sync(t.id).status)
    }}
    @Test fun dailyFiveSkipsHolidayAndRestartNeverDuplicates()=runTest {scenario("03-daily-five","040046") {f->
        f.at("2026-09-23");f.plan();for(day in listOf("2026-09-24","2026-09-25","2026-09-26","2026-09-27")){f.at(day);f.publish("2026-09-23");assertEquals(0,f.engine().run(setOf("1")).synced);assertTrue(f.bs.read().isEmpty())}
        f.at("2026-09-28");f.publish("2026-09-23","2026-09-24");assertEquals(1,f.engine().run(setOf("1")).synced);f.checkpoint("9/24 DCA materialized after disclosure")
        f.at("2026-09-29");f.publish("2026-09-23","2026-09-24","2026-09-28");assertEquals(1,f.engine().run(setOf("1")).synced)
        f.buys().restore();f.plans().restore();assertEquals(0,f.engine().run(setOf("1")).synced);assertEquals(2,f.posts)
        assertEquals(listOf("2026-09-24","2026-09-28"),f.bs.read().map {it.scheduledDate.toString()});assertTrue(f.bs.read().all {it.input.amount==BigDecimal("5")})
        assertTrue(BuyCodec.ui(f.bs.read()).all {it.jsonObject["type"]!!.jsonPrimitive.content=="定投"&&it.jsonObject["kind"]!!.jsonPrimitive.content=="BUY"})
    }}
    @Test fun laterNavCannotReplaceEarlierMissingBuyOrDcaNav()=runTest {scenario("04-late-earlier-nav","040046") {f->
        f.at("2026-09-23");f.plan();f.at("2026-09-29");f.publish("2026-09-23","2026-09-28");assertEquals(0,f.engine().run(setOf("1")).synced);assertEquals(0,f.posts)
        f.publish("2026-09-28","2026-09-23","2026-09-24");assertEquals(2,f.engine().run(setOf("1")).synced);assertEquals("2026-09-24",f.bs.read().first().confirmedNavDate.toString())
    };scenario("05-later-nav-not-buy-substitute") {f->val t=f.create();f.at("2026-09-29");f.publish("2026-09-23","2026-09-28");assertEquals(BuyStatus.WAITING_NAV,f.buys().confirmNav(t.id).status);assertNull(f.bs.read().single().confirmedNav);assertEquals(0,f.posts)}}
    @Test fun feeAndTinyPrecisionNeverUseUiRoundedInputs()=runTest {scenario("06-nonzero-fee") {f->
        val t=f.buys().create(f.input(rate="0.0015"));f.at("2026-09-28");f.publish("2026-09-24");val r=f.buys().confirmNav(t.id)
        assertEquals(0,BigDecimal("0.15").compareTo(r.feeAmount));assertEquals(0,BigDecimal("500").compareTo(r.target!!.totalCost.setScale(20,java.math.RoundingMode.HALF_UP)))
        assertEquals(BuyStatus.SYNCED,f.buys().sync(t.id).status)
    };scenario("07-unrepresentable-tiny-buy") {f->
        val t=f.buys().create(f.input(amount="0.00001"));f.at("2026-09-28");f.publish("2026-09-24");f.buys().confirmNav(t.id);assertEquals(BuyProblem.INPUT,f.buys().sync(t.id).problem);assertEquals(0,f.posts)
    }}
    @Test fun malformedDuplicateZeroAndCorrectedNavFailClosed()=runTest {scenario("08-invalid-nav") {f->
        val t=f.create();f.at("2026-09-28")
        for(value in listOf("[{\"day\":\"2026-09-24\",\"unit_net\":0}]","[{\"day\":\"bad\",\"unit_net\":1}]","[{\"day\":\"2026-09-24\",\"unit_net\":4.57},{\"day\":\"2026-09-24\",\"unit_net\":4.57}]")){f.navs=Json.parseToJsonElement(value).jsonArray;assertEquals(BuyProblem.NAV_PROTOCOL,f.buys().confirmNav(t.id).problem);assertEquals(0,f.posts)}
        f.publish("2026-09-24","2026-09-23");assertEquals(BuyStatus.READY_TO_SYNC,f.buys().confirmNav(t.id).status)
    };scenario("09-nav-correction",cached=true) {f->
        val r=f.ready();f.time=f.time.plusSeconds(301);f.navs=Json.parseToJsonElement("[{\"day\":\"2026-09-24\",\"unit_net\":4.58}]").jsonArray
        assertEquals(BuyProblem.NAV_PROTOCOL,assertFailsWith<BuyFailure>{f.gateway().officialHistory(f.fund)}.problem)
        assertEquals(r.confirmedNav,f.buys().restore().single().confirmedNav);assertEquals(0,f.posts)
    }}
    @Test fun configHistoryPauseResumeDeleteAndCreatedTradeRecovery()=runTest {scenario("10-plan-history","040046") {f->
        f.at("2026-09-23");val p=f.plan();f.at("2026-09-25");f.plans().update(p.id,p.config.copy(amount=BigDecimal("10")),p.revision)
        f.at("2026-09-29");f.publish("2026-09-23","2026-09-24","2026-09-28");assertEquals(2,f.engine().run(setOf("1")).synced);assertEquals(listOf("5","10"),f.bs.read().map {it.input.amount.toPlainString()})
        val current=f.ds.read().plans.single();f.plans().setEnabled(current.id,false,current.revision);f.publish("2026-09-23","2026-09-24","2026-09-28","2026-09-29");assertEquals(0,f.engine().run(setOf("1")).synced)
        val paused=f.ds.read().plans.single();f.at("2026-09-29",16);f.plans().setEnabled(paused.id,true,paused.revision);assertEquals(0,f.engine().run(setOf("1")).synced);assertEquals(2,f.posts)
        val resumed=f.ds.read().plans.single();f.plans().delete(resumed.id,resumed.revision);assertEquals(0,f.engine().run(setOf("1")).synced);assertEquals(2,f.bs.read().size)
    };scenario("11-paused-plan-recovers-created-trade","040046") {f->
        f.at("2026-09-23");f.plan();f.at("2026-09-28");f.publish("2026-09-23","2026-09-24");f.mode="OFFLINE_AFTER";assertTrue(f.engine().run(setOf("1")).problems.isNotEmpty());assertEquals(1,f.posts)
        val p=f.ds.read().plans.single();f.plans().setEnabled(p.id,false,p.revision);f.mode="OK";f.readFailure="";assertEquals(1,f.engine().run(setOf("1")).synced);assertEquals(1,f.posts);assertEquals(BuyStatus.SYNCED,f.bs.read().single().status)
        val paused=f.ds.read().plans.single();f.plans().delete(paused.id,paused.revision);assertEquals(1,f.bs.read().size)
    }}
    @Test fun sameFundPlansSerializeAndOrdinaryTradeBlocksPlans()=runTest {scenario("12-two-plans","040046") {f->
        f.at("2026-09-23");f.plan();f.plan("7","22222222-2222-4222-8222-222222222222");f.at("2026-09-28");f.publish("2026-09-23","2026-09-24");assertEquals(2,f.engine().run(setOf("1")).synced)
        val rows=f.bs.read();assertTrue(rows[1].baseline.equivalent(rows[0].submittedTarget!!));assertEquals(0,f.engine().run(setOf("1")).synced);assertEquals(2,f.posts)
    };scenario("13-pending-trade-blocks-dca","040046") {f->
        f.at("2026-09-23");f.plan();f.at("2026-09-24");f.create();f.at("2026-09-28");f.publish("2026-09-23","2026-09-24")
        assertEquals(setOf(BuyProblem.PENDING_TRANSACTION),f.engine().run(setOf("1")).problems);assertEquals(0,f.posts);assertEquals(1,f.ordinary().run(setOf("1")).synced);assertEquals(1,f.engine().run(setOf("1")).synced)
    }}
    @Test fun externalChangeAndPostUncertaintyNeverBlindlyOverwrite()=runTest {scenario("14-external-change") {f->
        val t=f.ready();f.position=f.position.copy(shares=BigDecimal("101"));assertEquals(BuyStatus.EXTERNAL_CHANGED,f.buys().sync(t.id).status);assertEquals(0,f.posts)
    };scenario("15-post-delivered-response-lost") {f->
        val t=f.ready();f.mode="DROP_ACCEPTED";assertEquals(BuyStatus.SYNCED,f.buys().sync(t.id).status);f.buys().restore();f.buys().sync(t.id);assertEquals(1,f.posts)
    };scenario("16-post-not-delivered") {f->
        val t=f.ready();f.mode="DROP_UNDELIVERED";assertEquals(BuyStatus.SYNCING,f.buys().sync(t.id).status);assertEquals(1,f.posts)
        f.buys().restore();assertEquals(0,f.ordinary().run(setOf("1")).synced);assertEquals(1,f.posts,"automatic reconciliation is GET-only")
        f.mode="OK";assertEquals(BuyStatus.SYNCING,f.buys().sync(t.id).status);assertEquals(1,f.posts);assertEquals(0,f.ordinary().run(setOf("1")).synced);assertEquals(1,f.posts)
    }}
    @Test fun failedGetAndWrongGetCannotPretendHttp200WasSuccess()=runTest {scenario("17-wrong-readback") {f->
        val t=f.ready();f.mode="WRONG";assertEquals(BuyStatus.SYNCING,f.buys().sync(t.id).status);f.buys().restore();assertEquals(BuyStatus.SYNCING,f.buys().reconcileOnly(t.id).status);assertEquals(1,f.posts)
    };scenario("18-readback-offline") {f->
        val t=f.ready();f.mode="OFFLINE_AFTER";val failed=f.buys().sync(t.id);assertEquals(BuyStatus.SYNCING,failed.status);assertTrue(failed.postMayHaveBeenSent)
        f.mode="OK";f.readFailure="";f.buys().restore();assertEquals(BuyStatus.SYNCED,f.buys().reconcileOnly(t.id).status);assertEquals(1,f.posts)
    }}
    @Test fun authExpiryBeforeAndAfterPostRequireFreshAuthenticatedReads()=runTest {scenario("19-auth-before-post") {f->
        val t=f.create();f.at("2026-09-28");f.publish("2026-09-24");f.readFailure="AUTH";assertEquals(BuyProblem.AUTH_EXPIRED,f.buys().confirmNav(t.id).problem)
        f.readFailure="";f.buys().confirmNav(t.id);f.readFailure="OFFLINE";assertEquals(BuyProblem.NETWORK,f.buys().sync(t.id).problem);assertEquals(0,f.posts)
        f.readFailure="";assertEquals(BuyStatus.SYNCED,f.buys().sync(t.id).status)
    };scenario("20-auth-after-post") {f->
        val t=f.ready();f.mode="AUTH_AFTER";assertEquals(BuyProblem.AUTH_EXPIRED,f.buys().sync(t.id).problem);f.readFailure="";f.mode="OK";f.buys().restore();assertEquals(BuyStatus.SYNCED,f.buys().reconcileOnly(t.id).status);assertEquals(1,f.posts)
    }}
    @Test fun durableIntentAndInterruptedConfirmationRestoreWithoutDuplicate()=runTest {scenario("21-durable-create-and-confirm") {f->
        val body=buildJsonObject {put("amountInput","100")};val first=CreationRequests(f.intents).prepare("buy-create:",body);val id=first["requestId"]!!.jsonPrimitive.content
        val t=f.buys().create(f.input(),id,autoSync=true);assertEquals(id,CreationRequests(f.intents).prepare("buy-create:",body)["requestId"]!!.jsonPrimitive.content);f.buys().create(f.input(),id,autoSync=true);assertEquals(1,f.bs.read().size)
        CreationRequests(f.intents).complete(id,buildJsonObject {put("requestId",id);put("ok",true)});assertNotNull(CreationRequests(f.intents).pending()!!["result"]);CreationRequests(f.intents).acknowledge(id);assertNull(CreationRequests(f.intents).pending())
        f.at("2026-09-28");f.publish("2026-09-24");f.failReadyOnce=true;assertEquals(BuyStatus.WAITING_NAV,f.buys().confirmNav(t.id).status);f.buys().restore();assertEquals(BuyStatus.READY_TO_SYNC,f.buys().confirmNav(t.id).status);assertEquals(0,f.posts)
    };scenario("22-crash-after-sending-marker") {f->
        val t=f.ready();val normal=f.gateway();val crashing=object:BuyGateway by normal {override suspend fun postFinalGuarded(fund:BuyFund,target:BuyBaseline,beforeSend:suspend()->Unit){beforeSend();postFinal(fund,target)};override suspend fun postFinal(fund:BuyFund,target:BuyBaseline):Unit=throw CancellationException("SIMULATED PROCESS EXIT")}
        assertFailsWith<CancellationException>{f.buys(crashing).sync(t.id)};assertEquals(BuyStatus.SYNCING,f.bs.read().single().status);assertTrue(f.bs.read().single().postMayHaveBeenSent)
        f.buys().restore();assertEquals(BuyStatus.SYNCING,f.buys().reconcileOnly(t.id).status);assertEquals(0,f.posts);assertEquals(BuyStatus.SYNCING,f.buys().sync(t.id).status);assertEquals(0,f.posts)
    }}
    @Test fun exactlyAtCutoffUsesPriorVersionAndDoesNotClaimNewPlanToday()=runTest {scenario("26-cutoff-edit","040046") {f->
        f.at("2026-09-23");val p=f.plan();f.at("2026-09-24",15);f.plans().update(p.id,p.config.copy(amount=BigDecimal("10")),p.revision)
        f.at("2026-09-29");f.publish("2026-09-23","2026-09-24","2026-09-28");assertEquals(2,f.engine().run(setOf("1")).synced)
        assertEquals(listOf("5","10"),f.bs.read().map {it.input.amount.toPlainString()});assertEquals(2,f.posts)
    };scenario("27-cutoff-create","040046") {f->
        f.at("2026-09-24",15);f.plan();f.at("2026-09-29");f.publish("2026-09-23","2026-09-24","2026-09-28")
        assertEquals(1,f.engine().run(setOf("1")).synced);assertEquals("2026-09-28",f.bs.read().single().scheduledDate.toString());assertEquals(1,f.posts)
    }}
    @Test fun historyWindowAndZeroSharesAndTinyRemainderStayHonest()=runTest {scenario("23-history-outside-window") {f->
        val t=f.create();f.at("2026-09-29");f.publish("2026-09-28");assertEquals(BuyProblem.NAV_OUTSIDE_WINDOW,f.buys().confirmNav(t.id).problem);assertEquals(0,f.posts)
    };scenario("24-full-clear-preserves-fund") {f->
        val s=f.sells().create(SellInput(f.fund,BigDecimal("100"),BigDecimal.ZERO,LocalDate.parse("2026-09-24"),CutoffType.BEFORE_1500));f.at("2026-09-28");f.publish("2026-09-24");f.sells().confirmNav(s.id);assertEquals(SellStatus.SYNCED,f.sells().sync(s.id).status)
        assertEquals(0,f.position.shares.signum());f.checkpoint("closed fund remains in catalogue, not holdings");assertEquals(0,f.position.unitCost.compareTo(BigDecimal("4")))
    };scenario("25-no-accidental-clear") {f->
        f.position=BuyBaseline(BigDecimal("100.004"),BigDecimal("4"));val s=f.sells().create(SellInput(f.fund,BigDecimal("100.003"),BigDecimal.ZERO,LocalDate.parse("2026-09-24"),CutoffType.BEFORE_1500));f.at("2026-09-28");f.publish("2026-09-24");f.sells().confirmNav(s.id);assertEquals(BuyProblem.INPUT,f.sells().sync(s.id).problem);assertEquals(0,f.posts)
    }}
}
