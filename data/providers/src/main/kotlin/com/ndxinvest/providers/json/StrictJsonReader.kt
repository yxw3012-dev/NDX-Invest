package com.ndxinvest.providers.json

import com.ndxinvest.providers.contract.ProviderFailureKind
import com.ndxinvest.providers.contract.ProviderPayloadException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal class StrictJsonReader private constructor(
    private val root: JsonElement,
) {
    fun requiredArray(path: String): JsonArray = when (val value = required(path)) {
        is JsonArray -> value
        else -> fail(ProviderFailureKind.WRONG_TYPE, "Field '$path' must be an array")
    }

    fun requiredString(path: String): String {
        val primitive = requiredPrimitive(path)
        if (!primitive.isString) fail(ProviderFailureKind.WRONG_TYPE, "Field '$path' must be a string")
        return primitive.content
    }

    fun optionalString(path: String): String? {
        val value = optional(path) ?: return null
        if (value !is JsonPrimitive || !value.isString) {
            fail(ProviderFailureKind.WRONG_TYPE, "Field '$path' must be a string when present")
        }
        return value.content
    }

    fun requiredDecimal(path: String): BigDecimal {
        val primitive = requiredPrimitive(path)
        if (primitive.booleanOrNull != null) {
            fail(ProviderFailureKind.WRONG_TYPE, "Field '$path' must be a decimal")
        }
        return try {
            BigDecimal(primitive.content)
        } catch (_: NumberFormatException) {
            fail(ProviderFailureKind.INVALID_VALUE, "Field '$path' is not an exact decimal")
        }
    }

    fun optionalDecimal(path: String): BigDecimal? {
        val value = optional(path) ?: return null
        if (value !is JsonPrimitive || value.booleanOrNull != null) {
            fail(ProviderFailureKind.WRONG_TYPE, "Field '$path' must be a decimal when present")
        }
        return try {
            BigDecimal(value.content)
        } catch (_: NumberFormatException) {
            fail(ProviderFailureKind.INVALID_VALUE, "Field '$path' is not an exact decimal")
        }
    }

    fun optionalLong(path: String): Long? {
        val decimal = optionalDecimal(path) ?: return null
        return try {
            decimal.longValueExact()
        } catch (_: ArithmeticException) {
            fail(ProviderFailureKind.INVALID_VALUE, "Field '$path' must be an exact integer")
        }
    }

    fun requiredLong(path: String): Long {
        val decimal = requiredDecimal(path)
        return try {
            decimal.longValueExact()
        } catch (_: ArithmeticException) {
            fail(ProviderFailureKind.INVALID_VALUE, "Field '$path' must be an exact integer")
        }
    }

    fun requiredDate(path: String, pattern: String): LocalDate {
        val text = requiredString(path)
        val formatter = try {
            DateTimeFormatter.ofPattern(pattern)
        } catch (_: IllegalArgumentException) {
            fail(ProviderFailureKind.INVALID_VALUE, "Date pattern for '$path' is invalid")
        }
        return try {
            LocalDate.parse(text, formatter)
        } catch (_: DateTimeParseException) {
            fail(ProviderFailureKind.INVALID_VALUE, "Field '$path' is not a valid date")
        }
    }

    fun optionalDate(path: String, pattern: String): LocalDate? {
        val text = optionalString(path) ?: return null
        val formatter = try {
            DateTimeFormatter.ofPattern(pattern)
        } catch (_: IllegalArgumentException) {
            fail(ProviderFailureKind.INVALID_VALUE, "Date pattern for '$path' is invalid")
        }
        return try {
            LocalDate.parse(text, formatter)
        } catch (_: DateTimeParseException) {
            fail(ProviderFailureKind.INVALID_VALUE, "Field '$path' is not a valid date")
        }
    }

    fun requiredOffsetDate(path: String): LocalDate {
        val text = requiredString(path)
        return try {
            OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDate()
        } catch (_: DateTimeParseException) {
            fail(ProviderFailureKind.INVALID_VALUE, "Field '$path' is not a valid offset timestamp")
        }
    }

    fun requiredTime(path: String, pattern: String): LocalTime {
        val text = requiredString(path)
        val formatter = try {
            DateTimeFormatter.ofPattern(pattern)
        } catch (_: IllegalArgumentException) {
            fail(ProviderFailureKind.INVALID_VALUE, "Time pattern for '$path' is invalid")
        }
        return try {
            LocalTime.parse(text, formatter)
        } catch (_: DateTimeParseException) {
            fail(ProviderFailureKind.INVALID_VALUE, "Field '$path' is not a valid time")
        }
    }

    fun requiredInstant(path: String, encoding: InstantEncoding): Instant {
        val primitive = requiredPrimitive(path)
        return try {
            when (encoding) {
                InstantEncoding.ISO_8601 -> {
                    if (!primitive.isString) {
                        fail(ProviderFailureKind.WRONG_TYPE, "Field '$path' must be an ISO timestamp string")
                    }
                    Instant.parse(primitive.content)
                }

                InstantEncoding.EPOCH_SECONDS -> Instant.ofEpochSecond(primitive.content.toLong())
                InstantEncoding.EPOCH_MILLIS -> Instant.ofEpochMilli(primitive.content.toLong())
            }
        } catch (_: DateTimeParseException) {
            fail(ProviderFailureKind.INVALID_VALUE, "Field '$path' is not a valid timestamp")
        } catch (_: NumberFormatException) {
            fail(ProviderFailureKind.INVALID_VALUE, "Field '$path' is not a valid epoch timestamp")
        }
    }

    fun child(element: JsonElement): StrictJsonReader {
        if (element !is JsonObject) fail(ProviderFailureKind.WRONG_TYPE, "Array record must be an object")
        return StrictJsonReader(element)
    }

    private fun requiredPrimitive(path: String): JsonPrimitive = when (val value = required(path)) {
        is JsonPrimitive -> value
        else -> fail(ProviderFailureKind.WRONG_TYPE, "Field '$path' must be a scalar value")
    }

    private fun required(path: String): JsonElement {
        val value = locate(path, missingAllowed = false)
            ?: fail(ProviderFailureKind.MISSING_FIELD, "Field '$path' is missing")
        if (value is JsonNull) fail(ProviderFailureKind.NULL_FIELD, "Field '$path' must not be null")
        return value
    }

    private fun optional(path: String): JsonElement? {
        val value = locate(path, missingAllowed = true) ?: return null
        return value.takeUnless { it is JsonNull }
    }

    private fun locate(path: String, missingAllowed: Boolean): JsonElement? {
        require(path.isNotBlank()) { "JSON field path must not be blank" }
        var current = root
        path.segments().forEach { segment ->
            current = when (current) {
                is JsonObject -> {
                    val objectValue = current
                    if (segment !in objectValue) {
                        if (missingAllowed) return null
                        fail(ProviderFailureKind.MISSING_FIELD, "Field '$path' is missing")
                    }
                    objectValue.getValue(segment)
                }

                is JsonArray -> {
                    val index = segment.toIntOrNull()
                        ?: fail(ProviderFailureKind.WRONG_TYPE, "Path '$path' needs an array index")
                    val arrayValue = current
                    if (index !in arrayValue.indices) {
                        if (missingAllowed) return null
                        fail(ProviderFailureKind.MISSING_FIELD, "Array index in '$path' is missing")
                    }
                    arrayValue[index]
                }

                else -> fail(ProviderFailureKind.WRONG_TYPE, "Path '$path' traverses a scalar value")
            }
        }
        return current
    }

    internal companion object {
        fun parse(body: String): StrictJsonReader = try {
            StrictJsonReader(JSON.parseToJsonElement(body))
        } catch (_: SerializationException) {
            fail(ProviderFailureKind.MALFORMED_JSON, "Provider response is not valid JSON")
        }

        fun from(element: JsonElement): StrictJsonReader = StrictJsonReader(element)

        private val JSON: Json = Json { ignoreUnknownKeys = true }
    }
}

private fun String.segments(): List<String> {
    require(isNotBlank()) { "JSON field path must not be blank" }
    return if (startsWith('/')) {
        drop(1).split('/').map(::decodeJsonPointerSegment)
    } else {
        split('.')
    }
}

private fun decodeJsonPointerSegment(segment: String): String = buildString {
    var index = 0
    while (index < segment.length) {
        val character = segment[index]
        if (character != '~') {
            append(character)
            index += 1
        } else {
            require(index + 1 < segment.length) { "JSON Pointer escape is incomplete" }
            append(
                when (segment[index + 1]) {
                    '0' -> '~'
                    '1' -> '/'
                    else -> throw IllegalArgumentException("JSON Pointer escape is invalid")
                },
            )
            index += 2
        }
    }
}

public enum class InstantEncoding {
    ISO_8601,
    EPOCH_SECONDS,
    EPOCH_MILLIS,
}

internal fun fail(kind: ProviderFailureKind, detail: String): Nothing =
    throw ProviderPayloadException(kind, detail)
