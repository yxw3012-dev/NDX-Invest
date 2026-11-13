package com.ndxinvest.providers.contract

import com.ndxinvest.network.ProviderRequest
import com.ndxinvest.network.ProviderTransport
import com.ndxinvest.network.TransportResult
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

@JvmInline
public value class ProviderId public constructor(public val value: String) {
    init {
        require(value.isNotBlank()) { "Provider id must not be blank" }
    }
}

public enum class ProviderFailureKind {
    TIMEOUT,
    NETWORK,
    CREDENTIAL_UNAVAILABLE,
    RESPONSE_TOO_LARGE,
    RATE_LIMITED,
    HTTP_ERROR,
    MALFORMED_JSON,
    MISSING_FIELD,
    NULL_FIELD,
    WRONG_TYPE,
    INVALID_VALUE,
    ESTIMATED_DATA_REJECTED,
    UNPUBLISHED_DATA_REJECTED,
    CONFLICTING_DUPLICATE,
}

public data class ProviderFailure public constructor(
    public val providerId: ProviderId,
    public val kind: ProviderFailureKind,
    public val retryable: Boolean,
    public val safeDetail: String,
    public val statusCode: Int? = null,
) {
    init {
        require(safeDetail.isNotBlank()) { "Provider failure detail must not be blank" }
    }
}

public sealed interface ProviderResult<out T> {
    public data class Success<T> public constructor(
        public val value: T,
        public val providerId: ProviderId,
        public val fetchedAt: Instant,
        public val priorFailures: List<ProviderFailure> = emptyList(),
    ) : ProviderResult<T>

    public data class Failure public constructor(
        public val failures: List<ProviderFailure>,
    ) : ProviderResult<Nothing> {
        init {
            require(failures.isNotEmpty()) { "A failed Provider result needs at least one failure" }
        }
    }
}

public interface DataProvider<T> {
    public val id: ProviderId

    public suspend fun fetch(): ProviderResult<T>
}

public fun interface JsonPayloadParser<T> {
    public fun parse(body: String, fetchedAt: Instant): T
}

public class ProviderPayloadException public constructor(
    public val kind: ProviderFailureKind,
    message: String,
) : IllegalArgumentException(message)

public class HttpJsonProvider<T> public constructor(
    override val id: ProviderId,
    private val request: ProviderRequest,
    private val transport: ProviderTransport,
    private val parser: JsonPayloadParser<T>,
) : DataProvider<T> {
    override suspend fun fetch(): ProviderResult<T> {
        val transportResult = try {
            transport.execute(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ProviderResult.Failure(
                listOf(failure(ProviderFailureKind.NETWORK, retryable = true, "Provider network request failed")),
            )
        }
        return when (transportResult) {
            is TransportResult.Timeout -> ProviderResult.Failure(
                listOf(failure(ProviderFailureKind.TIMEOUT, retryable = true, "Provider request timed out")),
            )

            is TransportResult.NetworkError -> ProviderResult.Failure(
                listOf(failure(ProviderFailureKind.NETWORK, retryable = true, "Provider network request failed")),
            )

            TransportResult.CredentialUnavailable -> ProviderResult.Failure(
                listOf(
                    failure(
                        ProviderFailureKind.CREDENTIAL_UNAVAILABLE,
                        retryable = false,
                        "Provider credential is unavailable",
                    ),
                ),
            )

            TransportResult.ResponseTooLarge -> ProviderResult.Failure(
                listOf(
                    failure(
                        ProviderFailureKind.RESPONSE_TOO_LARGE,
                        retryable = false,
                        "Provider response exceeded the safe size limit",
                    ),
                ),
            )

            is TransportResult.Response -> parseResponse(transportResult)
        }
    }

    private fun parseResponse(response: TransportResult.Response): ProviderResult<T> {
        if (response.statusCode == 429) {
            return ProviderResult.Failure(
                listOf(
                    failure(
                        ProviderFailureKind.RATE_LIMITED,
                        retryable = true,
                        detail = "Provider rate limit reached",
                        statusCode = response.statusCode,
                    ),
                ),
            )
        }
        if (response.statusCode !in 200..299) {
            return ProviderResult.Failure(
                listOf(
                    failure(
                        ProviderFailureKind.HTTP_ERROR,
                        retryable = response.statusCode >= 500,
                        detail = "Provider returned HTTP ${response.statusCode}",
                        statusCode = response.statusCode,
                    ),
                ),
            )
        }
        return try {
            ProviderResult.Success(parser.parse(response.body, response.receivedAt), id, response.receivedAt)
        } catch (error: ProviderPayloadException) {
            ProviderResult.Failure(
                listOf(
                    failure(
                        error.kind,
                        retryable = false,
                        detail = "Provider payload failed validation: ${error.kind.name}",
                    ),
                ),
            )
        } catch (_: IllegalArgumentException) {
            ProviderResult.Failure(
                listOf(failure(ProviderFailureKind.INVALID_VALUE, retryable = false, "Invalid Provider value")),
            )
        }
    }

    private fun failure(
        kind: ProviderFailureKind,
        retryable: Boolean,
        detail: String,
        statusCode: Int? = null,
    ): ProviderFailure = ProviderFailure(id, kind, retryable, detail, statusCode)
}

public object DefaultProviderOrder {
    public val fundNav: List<ProviderId> = listOf(
        ProviderId("tiantian-eastmoney"),
        ProviderId("gffunds-official"),
    )
    public val ndxQuote: List<ProviderId> = listOf(
        ProviderId("tencent"),
        ProviderId("sina"),
        ProviderId("eastmoney"),
    )
    public val ndxHistory: List<ProviderId> = listOf(
        ProviderId("eastmoney"),
        ProviderId("stooq"),
        ProviderId("nasdaq"),
    )
    public val usdCny: List<ProviderId> = listOf(ProviderId("tencent"), ProviderId("eastmoney"))
}
