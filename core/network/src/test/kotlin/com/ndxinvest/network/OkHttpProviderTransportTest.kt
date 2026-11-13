package com.ndxinvest.network

import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OkHttpProviderTransportTest {
    @Test
    fun `transport encodes sorted query injects a header credential and timestamps the response`() = runTest {
        val captured = AtomicReference<Request>()
        val resolved = AtomicReference<ResolvedCredential>()
        val credentialText = listOf("credential", "value").joinToString("-")
        val transport = transport(
            handler = { request ->
                captured.set(request)
                response(request, body = "{\"ok\":true}")
            },
            resolver = CredentialResolver { reference ->
                assertEquals(CredentialReference("secure-store:provider:sample"), reference)
                ResolvedCredential.from(credentialText.toCharArray()).also(resolved::set)
            },
        )

        val result = assertIs<TransportResult.Response>(
            transport.execute(
                ProviderRequest(
                    endpoint = "https://example.invalid/data",
                    query = mapOf("z" to "last value", "a" to ".NDX"),
                    headers = mapOf("Referer" to "https://example.invalid/finance/"),
                    credential = CredentialBinding(
                        CredentialReference("secure-store:provider:sample"),
                        CredentialLocation.HEADER,
                        "Authorization",
                    ),
                ),
            ),
        )

        val request = captured.get()
        assertTrue(request.header("Authorization") == credentialText)
        assertEquals("application/json", request.header("Accept"))
        assertEquals("https://example.invalid/finance/", request.header("Referer"))
        assertEquals("a=.NDX&z=last%20value", request.url.encodedQuery)
        assertEquals(200, result.statusCode)
        assertEquals("{\"ok\":true}", result.body)
        assertEquals(NOW, result.receivedAt)
        assertFailsWith<IllegalStateException> { resolved.get().reveal() }
    }

    @Test
    fun `transport preserves the Sina dollar symbol in its verified endpoint path`() = runTest {
        val captured = AtomicReference<Request>()
        val transport = transport(handler = { request -> captured.set(request); response(request) })

        assertIs<TransportResult.Response>(
            transport.execute(
                ProviderRequest(
                    endpoint = "https://hq.sinajs.cn/list=gb_\$ndx",
                    headers = mapOf("Referer" to "https://stock.finance.sina.com.cn/"),
                ),
            ),
        )

        assertEquals("/list=gb_\$ndx", captured.get().url.encodedPath)
        assertEquals(null, captured.get().url.encodedQuery)
    }

    @Test
    fun `transport decodes a Provider declared GB18030 response before parsing`() = runTest {
        val expected = "v_usNDX=\"200~纳斯达克100~.NDX\";"
        val transport = transport(
            handler = { request ->
                response(request).newBuilder()
                    .body(
                        expected.toByteArray(Charset.forName("GB18030"))
                            .toResponseBody("text/plain; charset=GB18030".toMediaType()),
                    )
                    .build()
            },
        )

        val result = assertIs<TransportResult.Response>(
            transport.execute(ProviderRequest("https://example.invalid/data")),
        )

        assertEquals(expected, result.body)
    }

    @Test
    fun `query credentials are injected only after resolution`() = runTest {
        val captured = AtomicReference<Request>()
        val credentialText = listOf("query", "credential").joinToString("-")
        val transport = transport(
            handler = { request ->
                captured.set(request)
                response(request)
            },
            resolver = CredentialResolver { ResolvedCredential.from(credentialText.toCharArray()) },
        )

        assertIs<TransportResult.Response>(
            transport.execute(
                ProviderRequest(
                    endpoint = "https://example.invalid/data",
                    credential = CredentialBinding(
                        CredentialReference("secure-store:provider:sample"),
                        CredentialLocation.QUERY,
                        "token",
                    ),
                ),
            ),
        )

        assertTrue(captured.get().url.queryParameter("token") == credentialText)
    }

    @Test
    fun `JSON POST injects and escapes a body credential only while executing`() = runTest {
        val capturedBody = AtomicReference<String>()
        val capturedContentType = AtomicReference<String>()
        val capturedMethod = AtomicReference<String>()
        val resolved = AtomicReference<ResolvedCredential>()
        val credentialText = "user-value\\\"with\\\\escapes"
        val transport = transport(
            handler = { request ->
                capturedMethod.set(request.method)
                capturedContentType.set(request.body?.contentType()?.toString())
                capturedBody.set(Buffer().also { request.body?.writeTo(it) }.readUtf8())
                response(request)
            },
            resolver = CredentialResolver {
                ResolvedCredential.from(credentialText.toCharArray()).also(resolved::set)
            },
        )

        assertIs<TransportResult.Response>(
            transport.execute(
                ProviderRequest(
                    endpoint = "https://api.example.invalid/market/snapshot",
                    method = ProviderHttpMethod.POST_JSON,
                    jsonBody = mapOf(
                        "symbols" to ProviderJsonValue.TextArray(listOf(".NDX")),
                        "fields" to ProviderJsonValue.TextArray(
                            listOf("close", "changeRate"),
                        ),
                        "date" to ProviderJsonValue.Text("2026-09-11"),
                    ),
                    credential = CredentialBinding(
                        CredentialReference("secure-store:provider:sample"),
                        CredentialLocation.JSON_BODY,
                        "token",
                    ),
                ),
            ),
        )

        assertEquals("POST", capturedMethod.get())
        assertEquals("application/json; charset=utf-8", capturedContentType.get())
        assertEquals(
            "{\"date\":\"2026-09-11\",\"fields\":[\"close\",\"changeRate\"]," +
                "\"symbols\":[\".NDX\"],\"token\":\"user-value\\\\\\\"with\\\\\\\\escapes\"}",
            capturedBody.get(),
        )
        assertFailsWith<IllegalStateException> { resolved.get().reveal() }
    }

    @Test
    fun `missing or failed credential resolution does not start an HTTP call`() = runTest {
        val calls = AtomicInteger()
        val request = ProviderRequest(
            endpoint = "https://example.invalid/data",
            credential = CredentialBinding(
                CredentialReference("secure-store:missing"),
                CredentialLocation.HEADER,
                "Authorization",
            ),
        )
        val missing = transport(
            handler = { seen -> calls.incrementAndGet(); response(seen) },
            resolver = CredentialResolver { null },
        ).execute(request)
        val failed = transport(
            handler = { seen -> calls.incrementAndGet(); response(seen) },
            resolver = CredentialResolver { error("sensitive resolver detail") },
        ).execute(request)

        assertIs<TransportResult.CredentialUnavailable>(missing)
        assertIs<TransportResult.CredentialUnavailable>(failed)
        assertEquals(0, calls.get())
    }

    @Test
    fun `timeout and IO failures are normalized without leaking exception messages`() = runTest {
        val timeout = transport(handler = { throw SocketTimeoutException("sensitive timeout detail") })
            .execute(ProviderRequest("https://example.invalid/data"))
        val network = transport(handler = { throw IOException("sensitive network detail") })
            .execute(ProviderRequest("https://example.invalid/data"))

        val timeoutResult = assertIs<TransportResult.Timeout>(timeout)
        val networkResult = assertIs<TransportResult.NetworkError>(network)
        assertFalse(timeoutResult.safeDetail.contains("sensitive"))
        assertFalse(networkResult.safeDetail.contains("sensitive"))
    }

    @Test
    fun `oversized responses are rejected before becoming Provider payloads`() = runTest {
        val transport = transport(
            handler = { request -> response(request, body = "123456789") },
            maxResponseBytes = 8,
        )

        assertIs<TransportResult.ResponseTooLarge>(
            transport.execute(ProviderRequest("https://example.invalid/data")),
        )
    }

    private fun transport(
        handler: (Request) -> Response,
        resolver: CredentialResolver = CredentialResolver { null },
        maxResponseBytes: Long = 1024,
    ): OkHttpProviderTransport {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()
        return OkHttpProviderTransport(
            client = client,
            credentialResolver = resolver,
            clock = NetworkClock { NOW },
            maxResponseBytes = maxResponseBytes,
        )
    }

    private fun response(request: Request, body: String = "{}"): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
        .build()

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-12T12:00:00Z")
    }
}
