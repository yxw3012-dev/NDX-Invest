package com.ndxinvest.yjb.read

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class YjbRateLimitTest {
    private val start = Instant.parse("2026-10-05T00:00:00Z")
    private fun TestScope.limiter() = YjbRateLimit(
        monotonicMillis = { testScheduler.currentTime }, now = { start.plusMillis(testScheduler.currentTime) },
        pause = { delay(it) }, jitterMillis = { 0 },
    )
    private fun TestScope.api(wire: YjbWire, limiter: YjbRateLimit) = YjbReadApi(
        wire, { "synthetic-key".toCharArray() }, { start.plusMillis(testScheduler.currentTime) }, limiter,
    )
    private fun ok() = ReadResponse(200, """{"code":200,"data":{}}""")

    @Test fun deltaSecondsWaitAndEveryRetryGetsAFreshSignature() = runTest {
        val calls = mutableListOf<ReadRequest>(); val keys = mutableListOf<CharArray>()
        val client = YjbReadApi(YjbWire { r -> calls += r; if (calls.size == 1) ReadResponse(429, "private", "2") else ok() },
            { "synthetic-key".toCharArray().also(keys::add) }, { start.plusMillis(testScheduler.currentTime) }, limiter())
        client.call("/users/v1/account", "synthetic-token".toCharArray())
        assertEquals(2_000, testScheduler.currentTime)
        assertEquals(2, calls.size)
        assertNotEquals(calls[0].headers["Request-Time"], calls[1].headers["Request-Time"])
        assertNotEquals(calls[0].headers["Request-Sign"], calls[1].headers["Request-Sign"])
        assertTrue(keys.all { k -> k.all { it == '\u0000' } })
    }

    @Test fun httpDateRetryAfterIsHonoured() = runTest {
        var calls = 0
        val header = DateTimeFormatter.RFC_1123_DATE_TIME.format(start.plusSeconds(3).atZone(ZoneOffset.UTC))
        api(YjbWire { if (++calls == 1) ReadResponse(429, "", header) else ok() }, limiter())
            .call("/users/v1/account", charArrayOf('x'))
        assertEquals(3_000, testScheduler.currentTime); assertEquals(2, calls)
    }

    @Test fun missingOrMalformedRetryAfterUsesExponentialBackoffAndCapsAttempts() = runTest {
        for (header in listOf(null, "not-a-date", "-1")) {
            val timestamps = mutableListOf<Long>(); val base = testScheduler.currentTime
            val error = assertFailsWith<YjbFailure> {
                api(YjbWire { timestamps += testScheduler.currentTime - base; ReadResponse(429, "private", header) }, limiter())
                    .call("/users/v1/account", charArrayOf('x'))
            }
            assertEquals(ReadError.RATE_LIMITED, error.kind)
            assertEquals(listOf(0L, 2_000L, 6_000L), timestamps)
            assertFalse(error.toString().contains("private"))
        }
    }

    @Test fun cumulativeWaitBudgetStopsMultipleShortServerWaits() = runTest {
        var calls = 0
        val error = assertFailsWith<YjbFailure> {
            api(YjbWire { calls++; ReadResponse(429, "", "6") }, limiter()).call("/users/v1/account", charArrayOf('x'))
        }
        assertEquals(ReadError.RATE_LIMITED, error.kind)
        assertEquals(2, calls); assertEquals(6_000, testScheduler.currentTime)
    }

    @Test fun longCooldownIsSharedAcrossClientInstancesWithoutEarlyRequests() = runTest {
        val gate = limiter(); var blockedCalls = 0; var otherCalls = 0
        val first = api(YjbWire { blockedCalls++; ReadResponse(429, "", "60") }, gate)
        assertEquals(ReadError.RATE_LIMITED, assertFailsWith<YjbFailure> { first.call("/users/v1/account", charArrayOf('x')) }.kind)
        val other = api(YjbWire { otherCalls++; ok() }, gate)
        assertFailsWith<YjbFailure> { other.call("/users/v1/user-account", charArrayOf('y')) }
        assertEquals(1, blockedCalls); assertEquals(0, otherCalls); assertEquals(0, testScheduler.currentTime)
        advanceTimeBy(60_000)
        other.call("/users/v1/user-account", charArrayOf('y')); assertEquals(1, otherCalls)
    }

    @Test fun readOnlyQuoteBatchMayRetryButLoginAndSendCodeNeverDo() = runTest {
        var quotes = 0
        api(YjbWire { if (++quotes == 1) ReadResponse(429, "", "1") else ReadResponse(200, """{"code":200,"data":[]}""") }, limiter())
            .call("/market/v1/fund/batch", charArrayOf('x'), buildJsonObject { put("funds", JsonArray(emptyList())) })
        assertEquals(2, quotes)
        for (path in listOf("/login", "/send_code")) {
            var calls = 0
            val error = assertFailsWith<YjbFailure> {
                api(YjbWire { calls++; ReadResponse(429, "", "1") }, limiter()).call(path, charArrayOf('x'), buildJsonObject {})
            }
            assertEquals(ReadError.RATE_LIMITED, error.kind); assertEquals(1, calls)
        }
    }

    @Test fun authenticationAndOtherErrorsAreNotRetriedAsRateLimits() = runTest {
        for ((response, kind) in listOf(ReadResponse(401, "") to ReadError.AUTH_EXPIRED,
            ReadResponse(200, """{"code":1000}""") to ReadError.AUTH_EXPIRED,
            ReadResponse(500, "private") to ReadError.NETWORK,
            ReadResponse(200, "malformed") to ReadError.PROTOCOL)) {
            var calls = 0; val before = testScheduler.currentTime
            val error = assertFailsWith<YjbFailure> {
                api(YjbWire { calls++; response }, limiter()).call("/users/v1/account", charArrayOf('x'))
            }
            assertEquals(kind, error.kind); assertEquals(1, calls); assertEquals(before, testScheduler.currentTime)
        }
    }

    @Test fun explicit429EnvelopeUsesSameBoundedPolicy() = runTest {
        var calls = 0
        api(YjbWire { if (++calls == 1) ReadResponse(200, """{"code":429,"message":"private"}""") else ok() }, limiter())
            .call("/users/v1/account", charArrayOf('x'))
        assertEquals(2, calls); assertEquals(2_000, testScheduler.currentTime)
    }

    @Test fun cancelledWaitSendsNothingMoreAndReleasesQueue() = runTest {
        var calls = 0; val gate = limiter()
        val client = api(YjbWire { if (++calls == 1) ReadResponse(429, "", "5") else ok() }, gate)
        val pending = launch { client.call("/users/v1/account", charArrayOf('x')) }
        runCurrent(); assertEquals(1, calls)
        pending.cancelAndJoin(); advanceTimeBy(5_000)
        client.call("/users/v1/account", charArrayOf('x')); assertEquals(2, calls)
    }

    @Test fun concurrentClientsAreQueuedInsteadOfBurstingRequests() = runTest {
        val gate = limiter(); var active = 0; var maximum = 0; var calls = 0
        val wire = YjbWire { active++; maximum = maxOf(maximum, active); calls++; delay(100); active--; ok() }
        (1..8).map { async { api(wire, gate).call("/users/v1/account", charArrayOf('x')) } }.awaitAll()
        assertEquals(8, calls); assertEquals(1, maximum)
    }

    @Test fun retryAfterParserHandlesZeroPastDatesAndOverflowWithoutFastRetry() = runTest {
        val gate = limiter()
        assertEquals(0, gate.retryAfterMillis("0", start))
        assertEquals(0, gate.retryAfterMillis("Sun, 04 Oct 2026 00:00:00 GMT", start))
        assertNull(gate.retryAfterMillis("-5", start))
        assertNull(gate.retryAfterMillis("bad", start))
        assertTrue(gate.retryAfterMillis("999999999999999999999999", start)!! > 10_000)
        var calls = 0
        assertFailsWith<YjbFailure> { api(YjbWire { calls++; ReadResponse(429, "", "0") }, gate).call("/users/v1/account", charArrayOf('x')) }
        assertEquals(3, calls)
    }

    @Test fun transportKeepsRetryAfterButDoesNotReadPrivate429Body() {
        val response = okhttp3.Response.Builder().request(okhttp3.Request.Builder().url("http://127.0.0.1/").build())
            .protocol(okhttp3.Protocol.HTTP_1_1).code(429).message("Limited").header("Retry-After", "60")
            .body(object : okhttp3.ResponseBody() {
                override fun contentType(): okhttp3.MediaType? = null
                override fun contentLength() = 1_000_000L
                override fun source(): okio.BufferedSource = error("429 body must not be read")
            }).build()
        val limited = rateLimitedResponse(response)!!
        assertEquals(429, limited.status); assertEquals("60", limited.retryAfter); assertEquals("", limited.body)
        assertEquals("YjbReadResponse[REDACTED]", limited.toString())
    }
}
