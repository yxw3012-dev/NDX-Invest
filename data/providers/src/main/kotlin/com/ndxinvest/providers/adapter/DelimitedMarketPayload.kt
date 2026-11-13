package com.ndxinvest.providers.adapter

import com.ndxinvest.providers.contract.ProviderFailureKind
import com.ndxinvest.providers.json.fail
import java.math.BigDecimal

internal object DelimitedMarketPayload {
    fun requiredVariable(body: String, name: String): String {
        val assignment = Regex(
            "(?m)^[ \\t]*(?:var[ \\t]+)?${Regex.escape(name)}[ \\t]*=[ \\t]*\"([^\"\\r\\n]*)\";[ \\t]*\\r?$",
        )
        val matches = assignment.findAll(body).toList()
        if (matches.isEmpty()) fail(ProviderFailureKind.MISSING_FIELD, "Provider variable is missing")
        if (matches.size > 1) {
            fail(ProviderFailureKind.CONFLICTING_DUPLICATE, "Provider variable is duplicated")
        }
        return matches.single().groupValues[1]
    }

    fun requiredField(fields: List<String>, index: Int): String {
        val value = fields.getOrNull(index)
            ?: fail(ProviderFailureKind.MISSING_FIELD, "Provider field is missing")
        if (value.isBlank()) fail(ProviderFailureKind.MISSING_FIELD, "Provider field is blank")
        return value
    }

    fun requiredDecimal(fields: List<String>, index: Int): BigDecimal {
        val value = requiredField(fields, index)
        return try {
            BigDecimal(value)
        } catch (_: NumberFormatException) {
            fail(ProviderFailureKind.INVALID_VALUE, "Provider field is not an exact decimal")
        }
    }

    fun percentToFraction(value: BigDecimal): BigDecimal = value.movePointLeft(2)

    fun requireIdentity(actual: String, expected: String) {
        if (actual != expected) fail(ProviderFailureKind.INVALID_VALUE, "Provider instrument identity is invalid")
    }

    fun requireExactChange(current: BigDecimal, previous: BigDecimal, change: BigDecimal) {
        if (current.subtract(previous).compareTo(change) != 0) {
            fail(ProviderFailureKind.INVALID_VALUE, "Provider change fields are inconsistent")
        }
    }
}
