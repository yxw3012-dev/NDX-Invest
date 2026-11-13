package com.ndxinvest.yjb.read

import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

private fun obj(s:String)=Json.parseToJsonElement(s).jsonObject
private val time=Instant.parse("2026-10-02T01:30:00Z")
private fun ok(data:String)=ReadResponse(200,"{\"code\":200,\"data\":$data}")
private class FakeWire:YjbWire {
    val requests=mutableListOf<ReadRequest>()
    var fail:ReadError?=null
    var funds="""[{"fund_id":"17","hold_share":"100","hold_cost":"1.2","money":"150","hold_earn":"30","short_name":"测试基金","code":"000017"}]"""
    var before:suspend ()->Unit={}
    override suspend fun exchange(request:ReadRequest):ReadResponse {
        requests+=request;before();fail?.let {throw YjbFailure(it)}
        return when(request.path){
            "/send_code"->ok("null")
            "/login"->ok("{\"token\":\"fake-session-only\"}")
            "/users/v1/account"->ok("{\"nickname\":\"测试账户\",\"phone\":\"13800000000\",\"token\":\"must-not-survive\"}")
            "/users/v1/user-account"->ok("{\"list\":[{\"id\":1,\"title\":\"账户一\"},{\"id\":2,\"title\":\"账户二\"}]}")
            "/market/v1/fund/batch"->ok("[{\"fund_id\":\"17\",\"code\":\"000017\",\"short_name\":\"测试基金\",\"last_net\":\"1.5\",\"net_day\":\"2026-10-01\"}]")
            else->ok("{\"list\":$funds}")
        }
    }
}
private fun api(wire:YjbWire)=YjbReadApi(wire,{"fake-protocol-only".toCharArray()},{time})
private class MemoryStorage:YjbReadStorage {
    var session:CharArray?=null;var cache:JsonObject?=null;var saveWorks=true
    override suspend fun token()=session?.copyOf()
    override suspend fun saveToken(value:CharArray):Boolean {session=value.copyOf();value.fill('\u0000');return saveWorks}
    override suspend fun snapshot()=cache
    override suspend fun saveSnapshot(value:JsonObject):Boolean {if(saveWorks)cache=value;return saveWorks}
    override suspend fun clear(){session?.fill('\u0000');session=null;cache=null}
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReadIntegrationTest {
    @Test fun loginAndSigningUseTheDocumentedProtocolWithoutSecretDiagnostics()=runTest {
        val wire=FakeWire();val api=api(wire);val device="fake-device".toCharArray()
        api.sendCode("13800000000",device)
        val token=api.login("13800000000","123456",device)
        assertEquals("fake-session-only",token.concatToString())
        val r=wire.requests.first();assertEquals("POST",r.method)
        assertEquals("ios:fake-device",r.headers["Authorization"])
        val expected=MessageDigest.getInstance("MD5").digest((YjbReadApi.BASE+"/send_code"+"fake-device"+"fake-protocol-only"+time.epochSecond).toByteArray()).joinToString(""){"%02x".format(it)}
        assertEquals(expected,r.headers["Request-Sign"]);assertEquals(time.epochSecond.toString(),r.headers["Request-Time"])
        assertEquals("YjbReadRequest[REDACTED]",r.toString());assertEquals("YjbReadResponse[REDACTED]",ok("{}").toString())
        assertFalse(YjbFailure(ReadError.AUTH_EXPIRED).toString().contains("fake-session"))
        token.fill('\u0000')
    }
    @Test fun invalidLoginAndWritePathsAreRejectedBeforeAnyRequest()=runTest {
        val w=FakeWire();val a=api(w)
        assertFailsWith<YjbFailure>{a.login("invalid","123456",charArrayOf('x'))}
        for(path in listOf("/fund_hold","/position/v1/fund_hold","/fund_search","/users/v1/account?token=x","/position/v1/static/fund-accounts/../funds")) {
            assertFailsWith<YjbFailure>{a.call(path,charArrayOf('x'),obj("{}"))}
            assertFailsWith<YjbFailure>{a.call(path,charArrayOf('x'))}
        }
        assertTrue(w.requests.isEmpty())
    }
    @Test fun http401AndExpiredEnvelopeAreRecognizedAndMalformedDataIsSanitized()=runTest {
        for(response in listOf(ReadResponse(401,"secret-server-error"),ReadResponse(200,"{\"code\":1000,\"message\":\"secret-server-error\"}"))) {
            val failure=assertFailsWith<YjbFailure>{api(YjbWire{response}).call("/users/v1/account",charArrayOf('x'))}
            assertEquals(ReadError.AUTH_EXPIRED,failure.kind);assertFalse(failure.toString().contains("secret-server-error"))
        }
        for(response in listOf(ReadResponse(200,"private-invalid-json"),ReadResponse(500,"private-error"),ok("null"))) {
            val failure=assertFailsWith<YjbFailure>{api(YjbWire{response}).call("/users/v1/account",charArrayOf('x'))}
            assertFalse(failure.toString().contains("private"))
        }
    }
    @Test fun multipleAccountsAreReadAndMappedWithoutRebuildingPlatformValues()=runTest {
        val w=FakeWire();val raw=api(w).snapshot(charArrayOf('x'));val model=YjbMapper.map(raw)
        assertEquals(2,model["holdings"]!!.jsonArray.size)
        val holding=model["holdings"]!!.jsonArray.first().jsonObject
        assertEquals("yjb:1:17",holding.text("id"));assertEquals("¥150",holding.text("amount"));assertEquals("+¥30",holding.text("profit"))
        val detail=model["details"]!!.jsonArray.first().jsonObject
        assertEquals("¥120",detail.text("cost"));assertEquals("1.2",detail.text("avgNav"));assertEquals("+25%",detail.text("profitRate"));assertEquals("1.5",detail.text("officialNav"))
        assertEquals(JsonNull,detail["estimatedNav"]);assertEquals("¥300",model["overview"]!!.jsonObject.text("amount"))
        assertEquals("138****0000",model.text("maskedId"));assertFalse(raw.toString().contains("must-not-survive"));assertFalse(raw.toString().contains("13800000000"))
        assertEquals(listOf("GET","GET","GET","GET","POST"),w.requests.map{it.method})
        assertEquals(1,Json.parseToJsonElement(w.requests.last().body!!).jsonObject["funds"]!!.jsonArray.size)
    }
    @Test fun zeroSharesAreNotCurrentHoldingsAndRawSnapshotKeepsTheClosedFund()=runTest {
        val w=FakeWire();w.funds="""[{"fund_id":"17","hold_share":"0","money":"999","hold_earn":"999"}]"""
        val raw=api(w).snapshot(charArrayOf('x'));val mapped=YjbMapper.map(raw)
        assertTrue(mapped["holdings"]!!.jsonArray.isEmpty());assertEquals(1,raw["groups"]!!.jsonArray.first().jsonObject["funds"]!!.jsonArray.size)
        assertEquals(raw["groups"]!!.jsonArray.size,mapped["fundCatalog"]!!.jsonArray.size)
        assertTrue(mapped["fundCatalog"]!!.jsonArray.all {it.jsonObject["availableSharesText"]!!.jsonPrimitive.content=="0"})
        assertEquals("--",mapped["overview"]!!.jsonObject["metrics"]!!.jsonArray[2].jsonPrimitive.content)
    }
    @Test fun missingFieldsShowUnknownAndInconsistentValuesNeverGetOverwritten()=runTest {
        val w=FakeWire();w.funds="""[{"fund_id":"17","hold_share":"100","hold_cost":"2","money":"9999","hold_earn":"-77"},{"fund_id":"18"}]"""
        val mapped=YjbMapper.map(api(w).snapshot(charArrayOf('x')));val d=mapped["details"]!!.jsonArray
        assertEquals("¥9999",d[0].jsonObject.text("amount"));assertEquals("-¥77",d[0].jsonObject.text("profit"))
        assertEquals("--",d[1].jsonObject.text("amount"));assertEquals("--",d[1].jsonObject.text("shares"));assertEquals("--",mapped["overview"]!!.jsonObject.text("amount"))
    }
    @Test fun hugeDecimalsRetainPrecisionAndInvalidNumbersFailClosed()=runTest {
        val w=FakeWire();w.funds="""[{"fund_id":"17","hold_share":"99999999999999999999.123456","hold_cost":"1.1","money":"12345678901234567890.123456","hold_earn":"-0.123456"}]"""
        val mapped=YjbMapper.map(api(w).snapshot(charArrayOf('x')))
        assertEquals("¥12345678901234567890.123456",mapped["holdings"]!!.jsonArray[0].jsonObject.text("amount"))
        for(number in listOf("NaN","1e99999","-1")) {
            w.funds="""[{"fund_id":"17","hold_share":"$number"}]"""
            assertFailsWith<YjbFailure>{YjbMapper.map(api(w).snapshot(charArrayOf('x')))}
        }
    }
    @Test fun loginFailureDoesNotPersistSessionAndSuccessfulLoginPublishesReady()=runTest {
        val w=FakeWire();val storage=MemoryStorage();val states=mutableListOf<JsonObject>();val repo=YjbReadRepository(api(w),storage,states::add)
        repo.connecting();repo.loginFailed();assertEquals("LOGIN_FAILED",states.last().text("accountStatus"));assertNull(storage.session)
        val token="fake-session".toCharArray();repo.acceptLogin(token)
        assertTrue(token.all{it=='\u0000'});assertEquals("READY",states.last().text("holdingsStatus"));assertNotNull(storage.cache)
    }
    @Test fun refreshCacheFallbackRestartAndRecoveryPreserveLastSuccess()=runTest {
        val w=FakeWire();val storage=MemoryStorage();val states=mutableListOf<JsonObject>();val repo=YjbReadRepository(api(w),storage,states::add)
        repo.acceptLogin("fake-session".toCharArray());val data=states.last()["data"]
        w.fail=ReadError.NETWORK;repo.refresh()
        assertEquals("REFRESHING",states[states.lastIndex-1].text("holdingsStatus"));assertEquals("OFFLINE_CACHE",states.last().text("holdingsStatus"));assertEquals(data,states.last()["data"])
        val restart=YjbReadRepository(api(w),storage,states::add);restart.restore();assertEquals("OFFLINE_CACHE",states.last().text("holdingsStatus"));assertEquals(data,states.last()["data"])
        w.fail=null;restart.refresh();assertEquals("READY",states.last().text("holdingsStatus"))
    }
    @Test fun noCacheErrorEmptyAuthExpiredAndDisconnectMapExactly()=runTest {
        val w=FakeWire();val storage=MemoryStorage();val states=mutableListOf<JsonObject>();val repo=YjbReadRepository(api(w),storage,states::add)
        repo.restore();assertEquals("NOT_CONNECTED",states.last().text("holdingsStatus"))
        storage.session="fake-session".toCharArray();w.fail=ReadError.NETWORK;repo.restore();assertEquals("ERROR",states.last().text("holdingsStatus"))
        w.fail=null;w.funds="[]";repo.refresh();assertEquals("EMPTY",states.last().text("holdingsStatus"))
        w.fail=ReadError.AUTH_EXPIRED;repo.refresh();assertEquals("AUTH_EXPIRED",states.last().text("accountStatus"));assertEquals("AUTH_EXPIRED",states.last().text("holdingsStatus"))
        repo.disconnect();assertNull(storage.session);assertNull(storage.cache);assertEquals("NOT_CONNECTED",states.last().text("accountStatus"))
    }
    @Test fun staleRefreshCannotRepopulateCacheAfterDisconnect()=runTest {
        val w=FakeWire();val storage=MemoryStorage();val states=mutableListOf<JsonObject>();val repo=YjbReadRepository(api(w),storage,states::add)
        repo.acceptLogin("fake-session".toCharArray())
        val gate=CompletableDeferred<Unit>();w.before={gate.await()}
        val refresh=launch{repo.refresh()};runCurrent()
        val disconnect=launch{repo.disconnect()};runCurrent();gate.complete(Unit);refresh.join();disconnect.join()
        assertNull(storage.cache);assertNull(storage.session);assertEquals("NOT_CONNECTED",states.last().text("holdingsStatus"))
    }
    @Test fun newLoginCannotLeakPreviousAccountCacheOnFailure()=runTest {
        val w=FakeWire();val storage=MemoryStorage();val states=mutableListOf<JsonObject>();val repo=YjbReadRepository(api(w),storage,states::add)
        repo.acceptLogin("first-session".toCharArray());w.fail=ReadError.NETWORK
        repo.connecting();repo.acceptLogin("second-session".toCharArray())
        assertEquals("ERROR",states.last().text("holdingsStatus"));assertTrue(states.last()["data"]!!.jsonObject["holdings"]!!.jsonArray.isEmpty());assertNull(storage.cache)
    }
    @Test fun rateLimitPreservesCachedHoldingsAndRecoversAfterCooldown()=runTest {
        val fake=FakeWire();var limited=false;var limitedCalls=0
        val wire=YjbWire {r->if(limited){limitedCalls++;ReadResponse(429,"private","60")}else fake.exchange(r)}
        val gate=YjbRateLimit({testScheduler.currentTime},{time.plusMillis(testScheduler.currentTime)},{delay(it)},{0})
        val a=YjbReadApi(wire,{"synthetic-key".toCharArray()},{time.plusMillis(testScheduler.currentTime)},gate)
        val storage=MemoryStorage();val states=mutableListOf<JsonObject>();val repo=YjbReadRepository(a,storage,states::add)
        repo.acceptLogin("synthetic-token".toCharArray());val data=states.last()["data"];val snapshot=storage.cache
        limited=true;repo.refresh();repo.refresh()
        assertEquals(1,limitedCalls);assertEquals("CONNECTED",states.last().text("accountStatus"))
        assertEquals("OFFLINE_CACHE",states.last().text("holdingsStatus"));assertEquals(data,states.last()["data"]);assertEquals(snapshot,storage.cache)
        assertTrue(states.last().text("errorMessage")!!.contains("请求频繁"));assertFalse(states.last().toString().contains("private"))
        limited=false;advanceTimeBy(60_000);repo.refresh();assertEquals("READY",states.last().text("holdingsStatus"))
    }
    @Test fun rateLimitWithoutCacheShowsErrorWithoutExpiringSession()=runTest {
        val storage=MemoryStorage().also {it.session="synthetic-token".toCharArray()};val states=mutableListOf<JsonObject>()
        val gate=YjbRateLimit({testScheduler.currentTime},{time},{delay(it)},{0})
        val a=YjbReadApi(YjbWire {ReadResponse(429,"private","3600")},{"synthetic-key".toCharArray()},{time},gate)
        YjbReadRepository(a,storage,states::add).restore()
        assertEquals("ERROR",states.last().text("holdingsStatus"));assertEquals("CONNECTED",states.last().text("accountStatus"))
        assertNotNull(storage.session);assertNull(storage.cache);assertTrue(states.last().text("errorMessage")!!.contains("请求频繁"))
    }
}
