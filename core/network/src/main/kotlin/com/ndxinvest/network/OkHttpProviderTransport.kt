package com.ndxinvest.network

import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

public fun interface NetworkClock {
    public fun now(): Instant
}

public class OkHttpProviderTransport public constructor(
    client: OkHttpClient,
    private val credentialResolver: CredentialResolver,
    private val clock: NetworkClock = NetworkClock(Instant::now),
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) : ProviderTransport {
    private val client: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    init {
        require(maxResponseBytes > 0) { "Maximum Provider response size must be positive" }
    }

    override suspend fun execute(request: ProviderRequest): TransportResult {
        val credential = resolveCredential(request.credential) ?: if (request.credential != null) {
            return TransportResult.CredentialUnavailable
        } else {
            null
        }
        return try {
            val httpRequest = buildRequest(request, credential)
            client.newCall(httpRequest).await().use { response -> response.toTransportResult() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            TransportResult.Timeout()
        } catch (_: IOException) {
            TransportResult.NetworkError()
        } catch (_: Exception) {
            TransportResult.NetworkError()
        } finally {
            credential?.close()
        }
    }

    private suspend fun resolveCredential(binding: CredentialBinding?): ResolvedCredential? {
        if (binding == null) return null
        return try {
            credentialResolver.resolve(binding.reference)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun buildRequest(request: ProviderRequest, credential: ResolvedCredential?): Request {
        val binding = request.credential
        val urlBuilder = request.endpoint.toHttpUrl().newBuilder()
        request.query.toSortedMap().forEach { (name, value) -> urlBuilder.addQueryParameter(name, value) }
        if (binding?.location == CredentialLocation.QUERY) {
            urlBuilder.addQueryParameter(binding.parameterName, requireNotNull(credential).reveal())
        }
        return Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
            .apply {
                request.headers.entries
                    .sortedBy { it.key.lowercase() }
                    .forEach { (name, value) -> header(name, value) }
                if (binding?.location == CredentialLocation.HEADER) {
                    header(binding.parameterName, requireNotNull(credential).reveal())
                }
                when (request.method) {
                    ProviderHttpMethod.GET -> get()
                    ProviderHttpMethod.POST_JSON -> post(
                        StructuredJsonRequestBody(
                            values = request.jsonBody,
                            credentialName = binding
                                ?.takeIf { it.location == CredentialLocation.JSON_BODY }
                                ?.parameterName,
                            credential = credential,
                        ),
                    )
                }
            }
            .build()
    }

    private fun Response.toTransportResult(): TransportResult {
        val responseBody = body
        if (responseBody.contentLength() > maxResponseBytes) return TransportResult.ResponseTooLarge
        val source = responseBody.source()
        source.request(maxResponseBytes + 1)
        if (source.buffer.size > maxResponseBytes) return TransportResult.ResponseTooLarge
        val charset: Charset = responseBody.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return TransportResult.Response(code, source.readString(charset), clock.now())
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, cancelledResponse, _ -> cancelledResponse.close() }
                }
            },
        )
    }

    private companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 2L * 1024L * 1024L
    }
}

private class StructuredJsonRequestBody(
    private val values: Map<String, ProviderJsonValue>,
    private val credentialName: String?,
    private val credential: ResolvedCredential?,
) : RequestBody() {
    override fun contentType(): MediaType = JSON_MEDIA_TYPE

    override fun writeTo(sink: BufferedSink) {
        val names = buildSet {
            addAll(values.keys)
            credentialName?.let(::add)
        }.sorted()
        sink.writeByte('{'.code)
        names.forEachIndexed { index, name ->
            if (index > 0) sink.writeByte(','.code)
            sink.writeJsonText(name)
            sink.writeByte(':'.code)
            if (name == credentialName) {
                sink.writeCredential(requireNotNull(credential))
            } else {
                sink.writeJsonValue(requireNotNull(values[name]))
            }
        }
        sink.writeByte('}'.code)
    }

    override fun toString(): String = "StructuredJsonRequestBody([REDACTED])"

    private companion object {
        val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
    }
}

private fun BufferedSink.writeJsonValue(value: ProviderJsonValue) {
    when (value) {
        is ProviderJsonValue.Text -> writeJsonText(value.value)
        is ProviderJsonValue.TextArray -> {
            writeByte('['.code)
            value.values.forEachIndexed { index, item ->
                if (index > 0) writeByte(','.code)
                writeJsonText(item)
            }
            writeByte(']'.code)
        }
    }
}

private fun BufferedSink.writeJsonText(value: String) {
    writeByte('"'.code)
    var segmentStart = 0
    value.forEachIndexed { index, character ->
        if (character == '"' || character == '\\') {
            if (segmentStart < index) writeUtf8(value, segmentStart, index)
            writeByte('\\'.code)
            writeByte(character.code)
            segmentStart = index + 1
        }
    }
    if (segmentStart < value.length) writeUtf8(value, segmentStart, value.length)
    writeByte('"'.code)
}

private fun BufferedSink.writeCredential(credential: ResolvedCredential) {
    writeByte('"'.code)
    var pendingHighSurrogate: Char? = null
    credential.forEachCharacter { character ->
        val high = pendingHighSurrogate
        if (high != null) {
            writeUtf8CodePoint(Character.toCodePoint(high, character))
            pendingHighSurrogate = null
        } else {
            when {
                Character.isHighSurrogate(character) -> pendingHighSurrogate = character
                character == '"' || character == '\\' -> {
                    writeByte('\\'.code)
                    writeByte(character.code)
                }

                else -> writeUtf8CodePoint(character.code)
            }
        }
    }
    check(pendingHighSurrogate == null) { "Credential contains invalid Unicode" }
    writeByte('"'.code)
}
