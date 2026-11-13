package com.ndxinvest.network

import java.net.URI
import java.time.Instant

@JvmInline
public value class CredentialReference public constructor(public val value: String) {
    init {
        require(value.isNotBlank()) { "Credential reference must not be blank" }
    }
}

public enum class CredentialLocation {
    HEADER,
    QUERY,
    JSON_BODY,
}

public enum class ProviderHttpMethod {
    GET,
    POST_JSON,
}

public sealed interface ProviderJsonValue {
    public data class Text public constructor(public val value: String) : ProviderJsonValue {
        init {
            require(isSafeParameterValue(value)) { "Provider JSON text must not contain control characters" }
        }
    }

    public data class TextArray public constructor(public val values: List<String>) : ProviderJsonValue {
        init {
            require(values.all(::isSafeParameterValue)) {
                "Provider JSON text arrays must not contain control characters"
            }
        }
    }
}

public data class CredentialBinding public constructor(
    public val reference: CredentialReference,
    public val location: CredentialLocation,
    public val parameterName: String,
) {
    init {
        val valid = when (location) {
            CredentialLocation.HEADER -> HTTP_HEADER_NAME.matches(parameterName)
            CredentialLocation.QUERY,
            CredentialLocation.JSON_BODY,
            -> PARAMETER_NAME.matches(parameterName)
        }
        require(valid) { "Credential parameter name is invalid for its location" }
    }
}

public data class ProviderRequest public constructor(
    public val endpoint: String,
    public val query: Map<String, String> = emptyMap(),
    public val headers: Map<String, String> = emptyMap(),
    public val method: ProviderHttpMethod = ProviderHttpMethod.GET,
    public val jsonBody: Map<String, ProviderJsonValue> = emptyMap(),
    public val credential: CredentialBinding? = null,
) {
    init {
        val uri = runCatching { URI(endpoint) }.getOrNull()
        require(
            uri?.let { parsed ->
                parsed.scheme.equals("https", ignoreCase = true) &&
                    parsed.host != null &&
                    parsed.rawUserInfo == null &&
                    parsed.rawQuery == null &&
                    parsed.rawFragment == null
            } == true,
        ) { "Provider endpoint must be an absolute HTTPS URL without user info, query, or fragment" }
        require(query.keys.all(PARAMETER_NAME::matches)) { "Provider query keys must be valid parameter names" }
        require(query.values.all(::isSafeParameterValue)) { "Provider query values must not contain control characters" }
        require(query.keys.none(::looksLikeCredentialKey)) {
            "Credential values must be resolved from CredentialReference, not stored in query configuration"
        }
        require(headers.keys.all(HTTP_HEADER_NAME::matches)) { "Provider header names must be valid HTTP names" }
        require(headers.values.all(::isSafeHeaderValue)) {
            "Provider header values must contain printable ASCII characters only"
        }
        require(headers.keys.map(String::lowercase).distinct().size == headers.size) {
            "Provider header names must be unique ignoring case"
        }
        require(headers.keys.none(::looksLikeCredentialKey)) {
            "Credential values must be resolved from CredentialReference, not stored in header configuration"
        }
        require(jsonBody.keys.all(PARAMETER_NAME::matches)) { "Provider JSON keys must be valid parameter names" }
        require(jsonBody.keys.none(::looksLikeCredentialKey)) {
            "Credential values must be resolved from CredentialReference, not stored in JSON configuration"
        }
        require(method == ProviderHttpMethod.POST_JSON || jsonBody.isEmpty()) {
            "A Provider JSON body requires POST_JSON"
        }
        require(
            credential?.location != CredentialLocation.QUERY || credential.parameterName !in query.keys,
        ) { "Credential query binding must not duplicate a configured query value" }
        require(
            credential?.location != CredentialLocation.JSON_BODY ||
                (method == ProviderHttpMethod.POST_JSON && credential.parameterName !in jsonBody.keys),
        ) { "Credential JSON binding requires POST_JSON and must not duplicate a configured body value" }
        require(
            credential?.location != CredentialLocation.HEADER ||
                headers.keys.none { it.equals(credential.parameterName, ignoreCase = true) },
        ) { "Credential header binding must not duplicate a configured header value" }
    }
}

public sealed interface TransportResult {
    public data class Response public constructor(
        public val statusCode: Int,
        public val body: String,
        public val receivedAt: Instant,
    ) : TransportResult {
        init {
            require(statusCode in 100..599) { "HTTP status code is invalid" }
        }
    }

    public data class Timeout public constructor(
        public val safeDetail: String = "Provider request timed out",
    ) : TransportResult {
        init {
            require(safeDetail.isNotBlank()) { "Timeout detail must not be blank" }
        }
    }

    public data class NetworkError public constructor(
        public val safeDetail: String = "Provider network request failed",
    ) : TransportResult {
        init {
            require(safeDetail.isNotBlank()) { "Network error detail must not be blank" }
        }
    }

    public data object CredentialUnavailable : TransportResult

    public data object ResponseTooLarge : TransportResult
}

public fun interface ProviderTransport {
    public suspend fun execute(request: ProviderRequest): TransportResult
}

public fun interface CredentialResolver {
    public suspend fun resolve(reference: CredentialReference): ResolvedCredential?
}

public class ResolvedCredential private constructor(
    private val characters: CharArray,
) : AutoCloseable {
    private var closed: Boolean = false

    internal fun reveal(): String {
        check(!closed) { "Credential has already been cleared" }
        return characters.concatToString()
    }

    /** Scoped access for protocol signing; the temporary copy is wiped on every exit. */
    public fun <T> withCharacters(block: (CharArray) -> T): T {
        check(!closed) { "Credential has already been cleared" }
        val copy = characters.copyOf()
        return try { block(copy) } finally { copy.fill('\u0000') }
    }

    internal fun forEachCharacter(action: (Char) -> Unit) {
        check(!closed) { "Credential has already been cleared" }
        characters.forEach(action)
    }

    override fun close() {
        characters.fill('\u0000')
        closed = true
    }

    override fun toString(): String = "[REDACTED]"

    public companion object {
        public fun from(characters: CharArray): ResolvedCredential {
            require(characters.isNotEmpty()) { "Resolved credential must not be empty" }
            require(characters.none { Character.isISOControl(it.code) }) {
                "Resolved credential must not contain control characters"
            }
            require(characters.hasWellFormedUtf16()) { "Resolved credential must contain valid Unicode" }
            return ResolvedCredential(characters.copyOf())
        }
    }
}

private val HTTP_HEADER_NAME: Regex = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
private val PARAMETER_NAME: Regex = Regex("[A-Za-z0-9._~-]+")

private fun isSafeParameterValue(value: String): Boolean = value.none { Character.isISOControl(it.code) }

private fun isSafeHeaderValue(value: String): Boolean = value.all { it.code in 0x20..0x7e }

private fun looksLikeCredentialKey(key: String): Boolean {
    val normalized = key.lowercase().replace("-", "").replace("_", "")
    return normalized.contains("apikey") ||
        normalized.contains("token") ||
        normalized.contains("secret") ||
        normalized.contains("authorization") ||
        normalized.contains("cookie")
}

private fun CharArray.hasWellFormedUtf16(): Boolean {
    var index = 0
    while (index < size) {
        val character = this[index]
        when {
            Character.isHighSurrogate(character) -> {
                if (index + 1 >= size || !Character.isLowSurrogate(this[index + 1])) return false
                index += 2
            }

            Character.isLowSurrogate(character) -> return false
            else -> index += 1
        }
    }
    return true
}
