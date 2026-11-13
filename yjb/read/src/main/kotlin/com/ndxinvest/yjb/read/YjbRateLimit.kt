package com.ndxinvest.yjb.read

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/** One process-wide queue/cooldown for YJB reads, login and holding writes. No credentials retained. */
class YjbRateLimit(
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val now: () -> Instant = Instant::now,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val jitterMillis: () -> Long = { Random.nextLong(100, 501) },
) {
    companion object {
        val shared = YjbRateLimit()
        private const val WAIT_BUDGET_MS = 10_000L
        private const val MAX_READ_ATTEMPTS = 3
        private const val MAX_DELAY_MS = Long.MAX_VALUE / 4
    }

    private val requests = Mutex()
    private var blockedUntil = Long.MIN_VALUE
    private var consecutiveLimits = 0

    /** Only GET and the explicitly read-only quote batch may be retried. POST side effects never are. */
    suspend fun exchange(retryRead: Boolean, send: suspend () -> ReadResponse): ReadResponse = requests.withLock {
        var waited = 0L
        var attempts = 0
        while (true) {
            val remaining = if (blockedUntil == Long.MIN_VALUE) 0L else
                (blockedUntil - monotonicMillis()).coerceAtLeast(0)
            if (remaining > WAIT_BUDGET_MS - waited) throw YjbFailure(ReadError.RATE_LIMITED)
            if (remaining > 0) {
                pause(remaining) // Cancellable coroutine delay; never block the UI thread.
                waited += remaining
                continue
            }
            // Generate signatures inside send(), after waiting and anew for every permitted attempt.
            val response = send()
            attempts++
            if (!isLimited(response)) {
                consecutiveLimits = 0
                return@withLock response
            }
            consecutiveLimits = (consecutiveLimits + 1).coerceAtMost(6)
            val fallback = (2_000L shl (consecutiveLimits - 1)).coerceAtMost(60_000) +
                jitterMillis().coerceIn(0, 500)
            val wait = retryAfterMillis(response.retryAfter, now()) ?: fallback
            blockedUntil = maxOf(blockedUntil, monotonicMillis() + wait)
            if (!retryRead || attempts >= MAX_READ_ATTEMPTS) throw YjbFailure(ReadError.RATE_LIMITED)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private fun isLimited(response: ReadResponse): Boolean {
        if (response.status == 429) return true
        if (response.status != 200) return false
        // Recognize an explicit 429 envelope only; don't infer limits from private error messages.
        return try { Json.parseToJsonElement(response.body).jsonObject["code"]?.jsonPrimitive?.content == "429" }
        catch (_: Exception) { false }
    }

    internal fun retryAfterMillis(value: String?, time: Instant): Long? {
        val header = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 } ?: return null
        if (header.all { it in '0'..'9' }) {
            val seconds = header.toLongOrNull() ?: return MAX_DELAY_MS
            return seconds.coerceAtMost(MAX_DELAY_MS / 1_000) * 1_000
        }
        return try {
            Duration.between(time, ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
                .toMillis().coerceIn(0, MAX_DELAY_MS)
        } catch (_: Exception) { null }
    }
}

/** 429 metadata is sufficient; do not read or expose a possibly private/oversized error body. */
internal fun rateLimitedResponse(response: okhttp3.Response): ReadResponse? =
    if (response.code == 429) ReadResponse(429, "", response.header("Retry-After")) else null
